package com.mazharansari.axion.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Live call in "same voice" mode (v0.3): listen → the agent answers (any connected provider, fast mode) → the
 * ACTIVE voice speaks it through [ReadAloudPlayer] → listen again. Because it speaks through the same player as
 * read-aloud, the call voice is exactly the voice picked in Voice Library (Rounded, Ava, Swara…).
 * Tap the orb while it talks to cut in. Turn-based, not full-duplex: Gemini Live is the real-time mode.
 */
class SameVoiceCall(
    context: Context,
    private val language: () -> String,
    private val reply: suspend (history: List<Pair<String, String>>, userText: String, onDelta: (String) -> Unit) -> String,
    private val onTurn: (userText: String, assistantText: String) -> Unit,
) {
    enum class Phase { LISTENING, THINKING, SPEAKING, ERROR, ENDED }
    data class State(val phase: Phase = Phase.LISTENING, val userText: String = "", val replyText: String = "", val status: String? = null, val muted: Boolean = false)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val history = ArrayList<Pair<String, String>>()
    private var rec: SpeechRecognizer? = null
    private var thinkJob: Job? = null
    private var stopped = false
    private var errors = 0

    @Volatile var micLevel = 0f; private set

    fun start() { listen() }

    fun setMuted(m: Boolean) {
        _state.update { it.copy(muted = m) }
        if (m) { destroyRec(); micLevel = 0f } else if (_state.value.phase == Phase.LISTENING) listen()
    }

    /** Orb tap: while Axion talks or thinks, cut in and listen. */
    fun interrupt() {
        when (_state.value.phase) {
            Phase.SPEAKING -> ReadAloudPlayer.stop()
            Phase.THINKING -> { thinkJob?.cancel(); listen() }
            Phase.ERROR -> { errors = 0; listen() }
            else -> Unit
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        thinkJob?.cancel()
        destroyRec()
        if (ReadAloudPlayer.playingMessageId == CALL_ID) ReadAloudPlayer.stop()
        _state.update { it.copy(phase = Phase.ENDED) }
        scope.cancel()
    }

    private fun listen() {
        if (stopped) return
        _state.update { it.copy(phase = Phase.LISTENING, status = null) }
        if (_state.value.muted) return
        destroyRec()
        if (!SpeechRecognizer.isRecognitionAvailable(app)) { fail("Speech recognition isn't available on this phone"); return }
        val r = SpeechRecognizer.createSpeechRecognizer(app); rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { errors = 0 }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { micLevel = ((rmsdB - 1f) / 9f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { micLevel = 0f }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { t -> _state.update { it.copy(userText = t) } }
            }
            override fun onResults(results: Bundle?) {
                micLevel = 0f
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().ifBlank { _state.value.userText }
                if (t.isBlank()) main.postDelayed({ listen() }, 80) else think(t)
            }
            override fun onError(error: Int) {
                micLevel = 0f
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> main.postDelayed({ if (_state.value.phase == Phase.LISTENING) listen() }, 120)
                    else -> if (++errors <= 3) main.postDelayed({ listen() }, 500L * errors) else fail(VoiceRecognizer.humanError(error))
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
        })
    }

    private fun think(userText: String) {
        destroyRec()
        _state.update { it.copy(phase = Phase.THINKING, userText = userText, replyText = "") }
        thinkJob = scope.launch {
            // The reply is spoken sentence by sentence while it is still being written (v0.3: start on the first sentence).
            val buf = StringBuilder(); var spoken = 0; var stream: ReadAloudPlayer.SpeechStream? = null
            fun feed(final: Boolean) {
                val t = buf.toString()
                val end = if (final) t.length else LiveVoiceCall.readyEnd(t, spoken, spoken == 0)
                if (end <= spoken) return
                val s = stream ?: ReadAloudPlayer.stream(app, CALL_ID) { if (!stopped) listen() }.also { stream = it; _state.update { st -> st.copy(phase = Phase.SPEAKING) } }
                s.push(t.substring(spoken, end)); spoken = end
                _state.update { it.copy(replyText = t) }
            }
            val answer = runCatching {
                reply(history.toList(), userText) { delta ->
                    scope.launch { if (delta == com.mazharansari.axion.ui.ChatViewModel.RESET) { if (stream == null) { buf.setLength(0); spoken = 0 } } else { buf.append(delta); feed(false) } }
                }
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) { stream?.let { ReadAloudPlayer.stop() }; throw e }
                fail(e.message?.take(160) ?: "The AI didn't answer"); return@launch
            }.trim()
            kotlinx.coroutines.yield()
            if (stopped) return@launch
            history += userText to answer
            onTurn(userText, answer)
            if (stream == null) {   // nothing streamed (or the provider didn't stream): speak the whole answer
                _state.update { it.copy(phase = Phase.SPEAKING, replyText = answer) }
                ReadAloudPlayer.speak(app, CALL_ID, answer) { if (!stopped) listen() }
            } else {
                if (buf.toString().trim() == answer) feed(true) else if (spoken < answer.length && answer.startsWith(buf.toString().trim().take(spoken))) { buf.setLength(0); buf.append(answer); feed(true) }
                _state.update { it.copy(replyText = answer) }
                stream?.end()
            }
        }
    }

    private fun fail(msg: String) {
        destroyRec()
        _state.update { it.copy(phase = Phase.ERROR, status = msg) }
    }

    private fun destroyRec() { runCatching { rec?.cancel(); rec?.destroy() }; rec = null }

    companion object { const val CALL_ID = "axion-live-call" }
}
