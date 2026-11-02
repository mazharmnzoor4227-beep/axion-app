package com.mazharansari.axion.voice

import android.content.Context
import com.mazharansari.axion.livecall.ApiKeyCredential
import com.mazharansari.axion.livecall.LiveCallConfig
import com.mazharansari.axion.livecall.LiveCallSession
import com.mazharansari.axion.livecall.LiveAudioPlayer
import com.mazharansari.axion.livecall.LiveMicCapture
import com.mazharansari.axion.livecall.OkHttpLiveTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Live + your voice" call (v0.3, mazhar 1 Nov: "it must speak in the SAME voice I selected"). Gemini Live does the
 * listening and the thinking — the mic streams from the moment the screen opens, Google's own turn detection ends
 * your turn — and the reply is spoken in YOUR Voice Library voice (Rounded, Ava, Swara…) sentence by sentence as
 * Gemini's transcript streams in, through [ReadAloudPlayer.stream]. Gemini's own audio is not played.
 *
 * Gemini Live has no text-only output (TEXT modality is refused with 1007 on every live model, tested 1 Nov), so
 * the reply text is the live output transcription. Measured from Tab's box: first transcript words ≈0.5–0.8 s
 * after you stop, the first full sentence ≈1.2 s, then the voice's own start (Edge ≈0.5–0.7 s to first audio,
 * the phone's voice ≈0.2–0.4 s) — about 0.7–1.3 s more than native Gemini audio.
 *
 * The mic is paused while your voice speaks (a phone/Edge voice isn't covered by the call echo canceller, so the
 * model would hear itself); tap the orb to cut in. If a reply comes with no transcript at all, its Gemini audio is
 * played instead so you never get silence.
 */
class LiveVoiceCall(
    context: Context,
    private val credentials: List<ApiKeyCredential>,
    private val voice: AxVoice,
    private val onTurn: (userText: String, assistantText: String) -> Unit,
) {
    enum class Phase { CONNECTING, LISTENING, THINKING, SPEAKING, ERROR, ENDED }
    data class State(val phase: Phase = Phase.CONNECTING, val userText: String = "", val replyText: String = "", val status: String? = null, val muted: Boolean = false)

    private val app = context.applicationContext
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile private var speaking = false
    @Volatile private var muted = false
    @Volatile private var stopped = false
    private var session: LiveCallSession? = null
    private var stream: ReadAloudPlayer.SpeechStream? = null
    private var spokenUpTo = 0
    private var streamGen = 0
    private var turnAudio = java.io.ByteArrayOutputStream()
    private var fallbackPlayer: LiveAudioPlayer? = null

    private val mic = LiveMicCapture(io, onFrame = { f -> if (!muted && !speaking && !stopped) session?.sendAudio(f) }, onError = { m -> fail(m) })
    val micLevel: Float get() = if (speaking) 0f else mic.level

    private val listener = object : LiveCallSession.Listener {
        override fun onReady() { main.launch { _state.update { if (it.phase == Phase.CONNECTING) it.copy(phase = Phase.LISTENING) else it }; if (!muted) mic.start() } }
        override fun onAudio(bytes: ByteArray) { synchronized(this@LiveVoiceCall) { if (turnAudio.size() < 8_000_000) turnAudio.write(bytes) } }
        override fun onInterrupted() { main.launch { ReadAloudPlayer.stop(); resetTurn() } }
        override fun onTranscript(userText: String, assistantText: String) { main.launch { transcript(userText, assistantText) } }
        override fun onTurn(userText: String?, assistantText: String?) { main.launch { turnDone(userText.orEmpty(), assistantText.orEmpty()) } }
        override fun onKeySwitched(line: String, exhaustedKeyId: String, quota: Boolean, activatedKeyId: String) { main.launch { _state.update { it.copy(status = line) } } }
        override fun onTerminalError(message: String) { fail(message) }
    }

    fun start() {
        val s = LiveCallSession(transportFactory = { OkHttpLiveTransport() }, credentials = credentials, autoSwitch = true,
            config = LiveCallConfig(voiceName = "Kore"), listener = listener, restingKeyIds = emptySet())
        session = s
        s.start()   // connects the moment the call screen opens; the mic starts on setupComplete
    }

    private fun transcript(user: String, reply: String) {
        if (stopped) return
        _state.update { it.copy(userText = user.ifBlank { it.userText }, replyText = reply, phase = if (reply.isBlank() && user.isNotBlank() && !speaking) Phase.THINKING else it.phase) }
        if (reply.length > spokenUpTo) {
            val end = readyEnd(reply, spokenUpTo, first = spokenUpTo == 0)
            if (end > spokenUpTo) { say(reply.substring(spokenUpTo, end)); spokenUpTo = end }
        }
    }

    private fun turnDone(user: String, reply: String) {
        if (stopped) return
        if (reply.length > spokenUpTo) { say(reply.substring(spokenUpTo)); spokenUpTo = reply.length }
        val audio = synchronized(this) { turnAudio.toByteArray().also { turnAudio = java.io.ByteArrayOutputStream() } }
        if (stream == null && reply.isBlank() && audio.isNotEmpty()) playGeminiAudio(audio)   // no transcript: never leave silence
        stream?.end(); stream = null
        if (user.isNotBlank() || reply.isNotBlank()) onTurn(user, reply)
        spokenUpTo = 0
        if (!speaking) _state.update { it.copy(phase = Phase.LISTENING) }
    }

    private fun say(text: String) {
        if (text.isBlank()) return
        val s = stream ?: run { val gen = ++streamGen; ReadAloudPlayer.stream(app, "live-call", voice) {
            if (gen != streamGen) return@stream
            speaking = false
            if (!stopped) _state.update { it.copy(phase = Phase.LISTENING) }
        }.also { stream = it; speaking = true; _state.update { st -> st.copy(phase = Phase.SPEAKING) } } }
        s.push(text)
    }

    private fun playGeminiAudio(pcm: ByteArray) {
        val pl = fallbackPlayer ?: LiveAudioPlayer(io, android.media.AudioAttributes.USAGE_MEDIA).also { fallbackPlayer = it; it.start() }
        speaking = true; _state.update { it.copy(phase = Phase.SPEAKING) }
        pl.enqueue(pcm)
        main.launch { kotlinx.coroutines.delay(300); while (pl.isSounding && !stopped) kotlinx.coroutines.delay(80); speaking = false; if (!stopped) _state.update { it.copy(phase = Phase.LISTENING) } }
    }

    private fun resetTurn() { stream = null; spokenUpTo = 0; speaking = false; synchronized(this) { turnAudio = java.io.ByteArrayOutputStream() } }

    /** Tap the orb while it talks: stop talking, listen. */
    fun interrupt() {
        ReadAloudPlayer.stop(); fallbackPlayer?.stopAndClear()
        speaking = false
        _state.update { it.copy(phase = if (it.phase == Phase.ERROR) Phase.CONNECTING else Phase.LISTENING) }
        if (_state.value.phase == Phase.CONNECTING) { session?.stop(); start() }
    }

    fun setMuted(value: Boolean) {
        muted = value; _state.update { it.copy(muted = value) }
        if (value) { mic.stop(); session?.sendAudioStreamEnd() } else if (_state.value.phase != Phase.CONNECTING) mic.start()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        mic.stop(); session?.stop(); ReadAloudPlayer.stop(); fallbackPlayer?.stop()
        _state.update { if (it.phase == Phase.ERROR) it else it.copy(phase = Phase.ENDED) }
        main.cancel(); io.cancel()
    }

    private fun fail(message: String) {
        main.launch { mic.stop(); _state.update { it.copy(phase = Phase.ERROR, status = message) } }
    }

    companion object {
        private val END = Regex("[.!?۔؟।](?=[\\s\"')\\]]|$)")

        /**
         * How much of [text] (from [from]) is ready to speak while the transcript is still streaming: up to the last
         * sentence end that already has text after it; for the very first piece also a long clause at a comma, so
         * the voice starts sooner.
         */
        fun readyEnd(text: String, from: Int, first: Boolean): Int {
            val pending = text.substring(from)
            var cut = -1
            END.findAll(pending).forEach { m -> if (m.range.last + 1 < pending.length) cut = m.range.last + 1 }
            if (cut < 0 && first && pending.length >= 70) {
                val comma = pending.lastIndexOf(", ", pending.length - 2)
                if (comma >= 35) cut = comma + 1
            }
            return if (cut > 0) from + cut else from
        }
    }
}
