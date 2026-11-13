package com.mazharansari.axion.voice

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Axion's ONE voice path (v0.3). Read-aloud under a message, the voice test on the Voice screen and the Live call
 * in "same voice" mode all speak through here, so they always sound identical.
 *
 * The active voice is one id from [VoiceCatalog] (prefs [VoicePrefs.KEY_VOICE]):
 *  - Kodex voices → the phone's Google TTS engine with that exact voice name (Kodex's ReadAloudPlayer behaviour:
 *    resolve by name at speak time, sentence chunks, Kodex pace).
 *  - Natural voices → Microsoft Edge neural voices ([EdgeTts]), MP3 per chunk, the next chunk fetched while the
 *    current one plays. Any failure → the Kodex default voice for this message, and a notice saying why.
 */
object ReadAloudPlayer {

    val playing = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private var _playing by mutableStateOf<String?>(null)
    var playingMessageId: String?
        get() = _playing
        private set(v) { _playing = v; playing.value = v }

    var lastNotice by mutableStateOf<String?>(null)
        private set
    var noticeSeq by mutableStateOf(0)
        private set

    /** P1-11: which provider actually produced the voice playing right now ("Edge · Ava" / "Gemini · Kore" / "Phone · Rounded") — shown above the read-aloud pill. Null when nothing is playing. */
    var provenance by mutableStateOf<String?>(null)
        private set

    private fun provenanceFor(voice: AxVoice): String = when (voice.family) {
        VoiceFamily.KODEX -> "Phone · ${voice.name}"
        VoiceFamily.NATURAL -> "Edge · ${voice.name}"
        VoiceFamily.GEMINI -> "Gemini · ${voice.name}"
    }

    private fun routeLabel(voice: AxVoice): String = when (voice.family) {
        VoiceFamily.KODEX -> "phone voice"
        VoiceFamily.NATURAL -> "Edge"
        VoiceFamily.GEMINI -> "Gemini"
    }

    private fun postNotice(context: Context, text: String) {
        lastNotice = text; noticeSeq++
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session = 0
    private var playJob: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: (() -> Unit)? = null
    private var mediaPlayer: MediaPlayer? = null
    private var onDoneForSession: Pair<Int, () -> Unit>? = null

    const val GOOGLE_TTS = "com.google.android.tts"

    /** Gemini keys from Connectors (set by the app); Gemini voices need one. */
    var geminiKeys: () -> List<String> = { emptyList() }
    private var geminiPlayer: com.mazharansari.axion.livecall.LiveAudioPlayer? = null
    private val missingNoticeShown = HashSet<String>()

    fun toggle(context: Context, messageId: String, rawText: String) {
        if (playingMessageId == messageId) stop() else speak(context, messageId, rawText)
    }

    /** Speaks [rawText]; [onDone] runs once when it finishes or is stopped (the Live call listens again then). */
    fun speak(context: Context, messageId: String, rawText: String, voiceOverride: AxVoice? = null, onDone: (() -> Unit)? = null) {
        stop()
        val my = ++session
        if (onDone != null) onDoneForSession = my to onDone
        val plain = stripMarkdownForSpeech(rawText)
        if (plain.isBlank()) { finish(my); return }
        playingMessageId = messageId
        val app = context.applicationContext
        lastNotice = null
        speed = VoicePrefs.speed(app)
        val chosen = voiceOverride ?: VoicePrefs.activeVoice(app)
        playJob = scope.launch {
            var voice = chosen
            if (voice.family == VoiceFamily.GEMINI && geminiKeys().isEmpty()) {
                postNotice(app, "${voice.name} is a Gemini voice — add a Gemini key in Connectors. Using Rounded this time.")
                voice = VoiceCatalog.kodex.first()
            }
            // v0.4: the picked voice reads the answer; only an English phone voice + Urdu/Hindi script gets a same-gender stand-in.
            VoiceRouting.plan(voice, plain).let { pl ->
                if (pl.note != null) { lastNotice = pl.note; noticeSeq++; if (missingNoticeShown.add("script:" + voice.id)) postNotice(app, pl.note) }
                voice = pl.voice
            }
            // P1-11: Edge stays primary; a failed Edge route falls back to the same voice's Gemini counterpart
            // (when a Gemini key is available), and everything ends on the phone's Rounded voice.
            val chain = VoiceCatalog.voiceFallbackChain(voice, geminiKeys().isNotEmpty())
            var ok = false
            for ((idx, candidate) in chain.withIndex()) {
                if (idx > 0) postNotice(app, "${chain[idx - 1].name} (${routeLabel(chain[idx - 1])}) didn't answer — reading in ${candidate.name} (${routeLabel(candidate)}) instead.")
                ok = runTransport(app, candidate, plain, my)
                if (ok || session != my) break
            }
            if (!ok && session == my) postNotice(app, "${chain.last().name} didn't answer either.")
            finish(my)
        }
    }

    // ------------------------------------------------------------------ v0.3.1 transport (Grok's read-aloud pill)
    //
    // The reply is cut into segments and played one after another by whichever engine the voice uses. The pill's
    // controls act on that timeline: pause/play, ±15 s (inside a downloaded/generated clip it is exact; across segments,
    // and on the phone's own engine, it lands on a segment start), speed 0.75×–3× (applied to the audio while it plays),
    // elapsed time and a progress line.

    private enum class Eng { DEVICE, MEDIA, PCM }
    private enum class SegResult { DONE, FAILED, INTERRUPTED }

    private class PcmClip {
        val chunks = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
        @Volatile var done = false
        @Volatile var failed: Throwable? = null
        @Volatile var bytes = 0L
        var job: Job? = null
    }

    private class Seg(val text: String) {
        @Volatile var durMs: Long = -1L          // content time at 1× once known (clip length / measured)
        var file: Deferred<Result<File>>? = null
        var pcm: PcmClip? = null
    }

    private class Tx(val my: Int, val eng: Eng, val voice: AxVoice, val segs: List<Seg>, val cps: Float) {
        @Volatile var idx = 0
        @Volatile var jump: Pair<Int, Long>? = null
        var segJob: Deferred<SegResult>? = null
        @Volatile var pcmPosInSeg = 0L
        @Volatile var deviceSounding = false
        val files = java.util.Collections.synchronizedList(ArrayList<File>())
        fun est(i: Int) = segs[i].durMs.takeIf { it >= 0 } ?: (segs[i].text.length * 1000f / cps).toLong()
        fun startOf(i: Int): Long { var t = 0L; for (k in 0 until i.coerceAtMost(segs.size)) t += est(k); return t }
        fun total(): Long = startOf(segs.size)
    }

    private var tx: Tx? = null

    var paused by mutableStateOf(false); private set
    var elapsedMs by mutableLongStateOf(0L); private set
    var totalMs by mutableLongStateOf(1L); private set
    var speed by mutableFloatStateOf(1f); private set

    /** 0..1 for the pill's progress line. */
    val progress: Float get() = (elapsedMs.toFloat() / totalMs.coerceAtLeast(1L)).coerceIn(0f, 1f)

    /** Test/preview hook: show the pill in a given state without audio. */
    internal fun debugShow(messageId: String?, elapsed: Long = 0L, total: Long = 60_000L, isPaused: Boolean = false, rate: Float = 1f) {
        playingMessageId = messageId; elapsedMs = elapsed; totalMs = total; paused = isPaused; speed = rate
    }

    private suspend fun runTransport(app: Context, voice: AxVoice, plain: String, my: Int): Boolean {
        val eng = when (voice.family) { VoiceFamily.KODEX -> Eng.DEVICE; VoiceFamily.GEMINI -> Eng.PCM; else -> Eng.MEDIA }
        val size = when (eng) { Eng.DEVICE -> 240; Eng.PCM -> 520; Eng.MEDIA -> 700 }
        val pace = VoicePrefs.pace(app)
        val t = Tx(my, eng, voice, chunkText(plain, size).map { Seg(it) }, 14.5f * pace)
        if (t.segs.isEmpty()) return true
        tx = t
        provenance = provenanceFor(voice)   // P1-11: the pill shows which provider is actually speaking
        totalMs = t.total(); elapsedMs = 0L
        val ticker = scope.launch {
            var last = System.currentTimeMillis()
            while (session == my) {
                kotlinx.coroutines.delay(200)
                val now = System.currentTimeMillis(); val dt = now - last; last = now
                if (paused) continue
                val base = t.startOf(t.idx)
                when (t.eng) {
                    Eng.MEDIA -> mediaPlayer?.let { mp -> runCatching { if (mp.isPlaying) elapsedMs = base + mp.currentPosition } }
                    Eng.PCM -> elapsedMs = base + t.pcmPosInSeg
                    Eng.DEVICE -> if (t.deviceSounding) elapsedMs = (elapsedMs + (dt * speed).toLong()).coerceAtMost(base + t.est(t.idx))
                }
                totalMs = t.total().coerceAtLeast(elapsedMs + 1)
            }
        }
        var i = 0; var offset = 0L; var anySound = false
        try {
            while (i < t.segs.size && session == my) {
                while (paused && session == my) kotlinx.coroutines.delay(50)
                if (session != my) break
                t.idx = i; t.pcmPosInSeg = offset
                elapsedMs = t.startOf(i) + offset
                val job = scope.async {
                    when (t.eng) {
                        Eng.DEVICE -> deviceSeg(app, t, i)
                        Eng.MEDIA -> mediaSeg(app, t, i, offset)
                        Eng.PCM -> pcmSeg(t, i, offset)
                    }
                }
                t.segJob = job
                val res = try { job.await() } catch (e: kotlinx.coroutines.CancellationException) { if (session != my) throw e; SegResult.INTERRUPTED }
                if (session != my) break
                val j = t.jump
                if (j != null) { t.jump = null; i = j.first; offset = j.second; continue }
                if (res == SegResult.FAILED) {
                    // P1-11: no notice here — speak() walks the fallback chain and says which voice is next.
                    if (!anySound) return false
                    break
                }
                anySound = true
                i++; offset = 0L
            }
        } finally {
            ticker.cancel()
            t.segs.forEach { s -> s.pcm?.job?.cancel(); s.file?.cancel() }
            t.files.forEach { runCatching { it.delete() } }
            if (tx === t) tx = null
            geminiPlayer?.let { g -> geminiPlayer = null; runCatching { g.stop() } }
        }
        if (session == my) elapsedMs = totalMs
        return true
    }

    private suspend fun deviceSeg(app: Context, t: Tx, i: Int): SegResult {
        val engine = readyEngine(app) ?: return SegResult.FAILED
        val text = t.segs[i].text
        val resolved = resolveDeviceVoice(engine, t.voice.engineVoice, VoiceCatalog.detect(text))
        runCatching {
            if (resolved != null && engine.setVoice(resolved) != TextToSpeech.SUCCESS) {
                // v0.4: the "-network" voice can be refused offline — its "-local" twin is the same voice.
                engine.voices?.firstOrNull { it.name == resolved.name.replace("-network", "-local") }?.let { engine.setVoice(it) }
            }
            engine.setSpeechRate(VoicePrefs.pace(app) * speed); engine.setPitch(1.0f)
        }
        if (resolved == null && !engine.voices.isNullOrEmpty()) { lastNotice = "${t.voice.name} isn't installed on this phone — the phone's default voice read this."; noticeSeq++ }
        if (resolved == null && !engine.voices.isNullOrEmpty() && missingNoticeShown.add(t.voice.id)) {
            postNotice(app, "${t.voice.name} isn't installed on this phone yet — install or update \"Speech Services by Google\" from Play Store. Using the phone's default voice now.")
        }
        val id = "axion-r-${System.nanoTime()}"
        var started = 0L
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { if (utteranceId == id) { started = System.currentTimeMillis(); t.deviceSounding = true } }
                override fun onDone(utteranceId: String?) { if (utteranceId == id) { t.deviceSounding = false; if (cont.isActive) cont.resume(true) } }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id) { t.deviceSounding = false; if (cont.isActive) cont.resume(true) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id) { t.deviceSounding = false; if (cont.isActive) cont.resume(false) } }
            })
            cont.invokeOnCancellation { t.deviceSounding = false; runCatching { engine.stop() } }
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
        }
        if (ok && started > 0 && t.jump == null) t.segs[i].durMs = ((System.currentTimeMillis() - started) * speed).toLong()
        return if (ok) SegResult.DONE else SegResult.FAILED
    }

    private fun fetchSeg(app: Context, t: Tx, i: Int): Deferred<Result<File>> {
        t.segs[i].file?.let { return it }
        val text = t.segs[i].text
        val d = scope.async(Dispatchers.IO) {
            runCatching {
                val rate = ((VoicePrefs.pace(app) - 1f) * 100f).roundToInt()
                val mp3 = EdgeTts.synthesize(text, VoiceCatalog.edgeVoiceFor(t.voice, text, VoicePrefs.smartUrdu(app)), rate)
                val f = File(app.cacheDir, "axion-natural-${System.nanoTime()}.mp3").apply { writeBytes(mp3) }
                t.files += f
                runCatching {
                    val mmr = android.media.MediaMetadataRetriever()
                    mmr.setDataSource(f.absolutePath)
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { t.segs[i].durMs = it }
                    mmr.release()
                }
                f
            }
        }
        t.segs[i].file = d
        return d
    }

    private suspend fun mediaSeg(app: Context, t: Tx, i: Int, offset: Long): SegResult {
        val file = fetchSeg(app, t, i).await().getOrElse { return SegResult.FAILED }
        if (i + 1 < t.segs.size) fetchSeg(app, t, i + 1)
        return if (playFile(file, offset)) SegResult.DONE else SegResult.FAILED
    }

    private fun genPcm(t: Tx, i: Int): PcmClip {
        t.segs[i].pcm?.let { return it }
        val clip = PcmClip()
        t.segs[i].pcm = clip
        val keys = geminiKeys()
        clip.job = scope.launch(Dispatchers.IO) {
            val r = runCatching {
                GeminiVoice.speak(keys, t.voice.engineVoice, t.segs[i].text) { pcm -> clip.chunks.add(pcm); clip.bytes += pcm.size }
            }
            clip.failed = r.exceptionOrNull()
            clip.done = true
            if (r.isSuccess) t.segs[i].durMs = clip.bytes / 48
            if (r.isSuccess && session == t.my && i + 1 < t.segs.size) genPcm(t, i + 1)   // one ahead, so the next segment starts gap-free
        }
        return clip
    }

    private suspend fun pcmSeg(t: Tx, i: Int, offset: Long): SegResult {
        val player = geminiPlayer ?: com.mazharansari.axion.livecall.LiveAudioPlayer(scope, android.media.AudioAttributes.USAGE_MEDIA).also { geminiPlayer = it; it.start(); it.setSpeed(speed) }
        val clip = genPcm(t, i)
        var skip = (offset * 48).let { it - it % 2 }
        var fed = 0; var fedBytes = 0L
        try {
            while (session == t.my) {
                val n = clip.chunks.size
                while (fed < n) {
                    var c = clip.chunks[fed++]
                    if (skip > 0) { if (skip >= c.size) { skip -= c.size; continue }; c = c.copyOfRange(skip.toInt(), c.size); skip = 0 }
                    player.enqueue(c); fedBytes += c.size
                }
                t.pcmPosInSeg = offset + ((fedBytes - player.pending).coerceAtLeast(0) / 48)
                if (clip.done && fed >= clip.chunks.size) break
                kotlinx.coroutines.delay(20)
            }
            if (clip.failed != null && fedBytes == 0L) return SegResult.FAILED
            while (session == t.my && (player.isSounding || paused)) {
                t.pcmPosInSeg = offset + ((fedBytes - player.pending).coerceAtLeast(0) / 48)
                kotlinx.coroutines.delay(40)
            }
            return SegResult.DONE
        } catch (e: kotlinx.coroutines.CancellationException) {
            player.stopAndClear(); if (paused) player.pause()
            throw e
        }
    }

    private fun interruptSegment(t: Tx) {
        when (t.eng) {
            Eng.MEDIA -> { runCatching { mediaPlayer?.stop() }; runCatching { mediaPlayer?.release() }; mediaPlayer = null }
            Eng.DEVICE -> runCatching { tts?.stop() }
            Eng.PCM -> geminiPlayer?.stopAndClear()
        }
        t.segJob?.cancel()
    }

    fun pause() {
        val t = tx ?: return
        if (paused) return
        paused = true
        when (t.eng) {
            Eng.MEDIA -> runCatching { mediaPlayer?.takeIf { it.isPlaying }?.pause() }
            Eng.PCM -> geminiPlayer?.pause()
            Eng.DEVICE -> { t.jump = t.idx to 0L; interruptSegment(t); elapsedMs = t.startOf(t.idx) }
        }
    }

    fun resume() {
        val t = tx ?: return
        if (!paused) return
        paused = false
        when (t.eng) {
            Eng.MEDIA -> mediaPlayer?.let { mp -> runCatching { if (!mp.isPlaying) { applySpeed(mp); mp.start() } } }
            Eng.PCM -> geminiPlayer?.resume()
            Eng.DEVICE -> Unit   // the loop is waiting on `paused`
        }
    }

    fun togglePause() { if (paused) resume() else pause() }

    /** Grok's ⟲15 / 15⟳. */
    fun seekBy(deltaMs: Long) {
        val t = tx ?: return
        val total = t.total()
        val target = elapsedMs + deltaMs
        if (target >= total) { stop(); return }
        val to = target.coerceAtLeast(0L)
        val cur = t.idx; val start = t.startOf(cur)
        val mp = mediaPlayer
        if (t.eng == Eng.MEDIA && mp != null && to >= start && to < start + t.est(cur)) {
            runCatching { mp.seekTo((to - start).toInt()) }; elapsedMs = to; return
        }
        var k = 0
        while (k < t.segs.lastIndex && t.startOf(k + 1) <= to) k++
        val off = when (t.eng) {
            Eng.DEVICE -> 0L
            Eng.MEDIA -> to - t.startOf(k)
            Eng.PCM -> (to - t.startOf(k)).takeIf { t.segs[k].pcm?.done == true } ?: 0L
        }
        t.jump = k to off
        elapsedMs = t.startOf(k) + off
        if (paused && t.eng == Eng.DEVICE) return
        if (paused) { paused = false }
        interruptSegment(t)
    }

    /** Grok's speed list: 0.75× … 3×. Remembered for the next read-aloud too. */
    fun setSpeed(context: Context, value: Float) {
        speed = value
        VoicePrefs.setSpeed(context, value)
        val t = tx ?: return
        when (t.eng) {
            Eng.MEDIA -> mediaPlayer?.let { mp -> runCatching { if (mp.isPlaying) applySpeed(mp) } }
            Eng.PCM -> geminiPlayer?.setSpeed(value)
            Eng.DEVICE -> if (!paused) { t.jump = t.idx to 0L; interruptSegment(t) }
        }
    }

    private fun applySpeed(mp: MediaPlayer) {
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
    }

    fun stop() {
        val ended = session
        session++
        tx?.let { t -> t.segJob?.cancel(); t.segs.forEach { s -> s.pcm?.job?.cancel(); s.file?.cancel() }; t.files.forEach { f -> runCatching { f.delete() } } }
        tx = null
        playJob?.cancel(); playJob = null
        runCatching { tts?.stop() }
        releasePlayer()
        playingMessageId = null
        provenance = null
        paused = false; elapsedMs = 0L
        fireDone(ended)
    }

    private fun finish(my: Int) {
        if (session != my) return
        playingMessageId = null
        provenance = null
        paused = false
        fireDone(my)
    }

    private fun fireDone(my: Int) {
        val d = onDoneForSession ?: return
        if (d.first == my) { onDoneForSession = null; scope.launch { d.second() } }
    }

    private fun releasePlayer() {
        geminiPlayer?.let { g -> geminiPlayer = null; runCatching { g.stop() } }
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    // ------------------------------------------------------------------ on-device (Kodex voices)

    fun hasGoogleTts(context: Context) = runCatching { context.packageManager.getPackageInfo(GOOGLE_TTS, 0); true }.getOrDefault(false)

    private fun speakOnDevice(context: Context, text: String, my: Int, voice: AxVoice) {
        val engine = tts
        if (engine == null || !ttsReady) {
            pendingSpeak = { if (session == my) speakOnDevice(context, text, my, voice) }
            if (engine == null) {
                val listener = TextToSpeech.OnInitListener { status ->
                    val pending = pendingSpeak; pendingSpeak = null
                    if (status == TextToSpeech.SUCCESS) { ttsReady = true; pending?.invoke() }
                    else { tts = null; scope.launch { finish(my) } }
                }
                tts = if (hasGoogleTts(context)) TextToSpeech(context, listener, GOOGLE_TTS) else TextToSpeech(context, listener)
            }
            return
        }
        val script = VoiceCatalog.detect(text)
        val resolved = resolveDeviceVoice(engine, voice.engineVoice, script)
        runCatching {
            if (resolved != null) engine.voice = resolved
            engine.setSpeechRate(VoicePrefs.pace(context))
            engine.setPitch(1.0f)
        }
        if (resolved == null && !engine.voices.isNullOrEmpty() && missingNoticeShown.add(voice.id)) {
            postNotice(context, "${voice.name} isn't installed on this phone yet — install or update \"Speech Services by Google\" from Play Store. Using the phone's default voice now.")
        }
        val chunks = chunkText(text, 1100)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "axion-$my-${chunks.size - 1}") scope.launch { finish(my) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { scope.launch { finish(my) } }
        })
        chunks.forEachIndexed { index, chunk ->
            engine.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "axion-$my-$index")
        }
    }

    /**
     * The Kodex card's exact voice by name; offline the engine lists the same voice as "-local". Urdu or Hindi script
     * can't be read by an English voice at all, so that text goes to the phone's Urdu/Hindi voice instead.
     */
    internal fun resolveDeviceVoice(engine: TextToSpeech, name: String, script: VoiceCatalog.Script): Voice? = runCatching {
        val voices = engine.voices?.toList().orEmpty()
        val lang = when (script) { VoiceCatalog.Script.URDU -> "ur"; VoiceCatalog.Script.DEVANAGARI -> "hi"; else -> null }
        if (lang != null) {
            voices.filter { it.locale?.language == lang && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .maxWithOrNull(compareBy<Voice> { it.quality }.thenBy { if (it.isNetworkConnectionRequired) 1 else 0 })?.let { return@runCatching it }
        }
        voices.firstOrNull { it.name == name }
            ?: voices.firstOrNull { it.name == name.replace("-network", "-local") }
            ?: voices.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }.getOrNull()

    // ------------------------------------------------------------------ natural (Edge neural)

    private suspend fun speakNatural(context: Context, voice: AxVoice, text: String, my: Int): Boolean {
        val chunks = chunkText(text, 1400)
        val smart = VoicePrefs.smartUrdu(context)
        val rate = ((VoicePrefs.pace(context) - 1f) * 100f).roundToInt()
        fun fetch(i: Int): Deferred<Result<File>> = scope.async(Dispatchers.IO) {
            runCatching {
                val chunk = chunks[i]
                val mp3 = EdgeTts.synthesize(chunk, VoiceCatalog.edgeVoiceFor(voice, chunk, smart), rate)
                File(context.cacheDir, "axion-natural-${System.nanoTime()}.mp3").apply { writeBytes(mp3) }
            }
        }
        var next = fetch(0)
        for (i in chunks.indices) {
            val res = next.await()
            if (session != my) { res.getOrNull()?.delete(); return true }
            val file = res.getOrElse { e ->
                if (i == 0) {
                    postNotice(context, "${voice.name} needs internet and didn't answer (${e.message?.take(60) ?: "no connection"}) — using Rounded this time.")
                    return false
                }
                finish(my); return true
            }
            if (i + 1 < chunks.size) next = fetch(i + 1)
            val ok = playFile(file)
            file.delete()
            if (session != my) return true
            if (!ok) { if (i == 0) return false; finish(my); return true }
        }
        finish(my)
        return true
    }

    // ------------------------------------------------------------------ Gemini voices (Live, read-it-exactly)

    private suspend fun speakGemini(context: Context, voice: AxVoice, text: String, my: Int): Boolean {
        val keys = geminiKeys()
        if (keys.isEmpty()) {
            postNotice(context, "${voice.name} is a Gemini voice — add a Gemini key in Connectors. Using Rounded this time.")
            return false
        }
        val player = com.mazharansari.axion.livecall.LiveAudioPlayer(scope, android.media.AudioAttributes.USAGE_MEDIA)
        geminiPlayer = player
        player.start()
        try {
            for ((i, chunk) in chunkText(text, 1200).withIndex()) {
                if (session != my) return true
                val r = runCatching { withContext(Dispatchers.IO) { GeminiVoice.speak(keys, voice.engineVoice, chunk) { pcm -> if (session == my) player.enqueue(pcm) } } }
                if (r.isFailure) {
                    if (session != my) return true
                    if (i == 0 && !player.isSounding) {
                        postNotice(context, "${voice.name} (Gemini) didn't answer (${r.exceptionOrNull()?.message?.take(60) ?: "no connection"}) — using Rounded this time.")
                        return false
                    }
                    break
                }
            }
            while (session == my && player.isSounding) kotlinx.coroutines.delay(60)
        } finally {
            if (geminiPlayer === player) { geminiPlayer = null; player.stop() }
        }
        finish(my)
        return true
    }

    // ------------------------------------------------------------------ streaming (Live call: speak each sentence as it arrives)

    class SpeechStream internal constructor(internal val my: Int) {
        internal val sentences = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        /** Queue one sentence; it starts speaking as soon as the ones before it are done. */
        fun push(sentence: String) { val s = stripMarkdownForSpeech(sentence); if (s.isNotBlank()) sentences.trySend(s) }
        /** No more sentences: onDone fires after the last one is spoken. */
        fun end() { sentences.close() }
    }

    /**
     * A reply spoken sentence by sentence while it is still being written, in the active voice. Natural
     * sentences are fetched the moment they are pushed (in parallel) and played in order, so only the first one's
     * fetch is waited on. [onDone] runs once when the stream ends or is stopped.
     */
    fun stream(context: Context, messageId: String, voiceOverride: AxVoice? = null, onDone: (() -> Unit)? = null): SpeechStream {
        stop()
        val my = ++session
        if (onDone != null) onDoneForSession = my to onDone
        val app = context.applicationContext
        val s = SpeechStream(my)
        playingMessageId = messageId
        val voice = voiceOverride ?: VoicePrefs.activeVoice(app)
        playJob = scope.launch {
            when (voice.family) {
                VoiceFamily.KODEX -> for (sentence in s.sentences) { if (session != my) break; deviceSay(app, sentence, voice) }
                VoiceFamily.GEMINI -> {
                    val keys = geminiKeys()
                    val player = com.mazharansari.axion.livecall.LiveAudioPlayer(scope, android.media.AudioAttributes.USAGE_MEDIA).also { geminiPlayer = it; it.start() }
                    for (sentence in s.sentences) {
                        if (session != my) break
                        val ok = keys.isNotEmpty() && runCatching { withContext(Dispatchers.IO) { GeminiVoice.speak(keys, voice.engineVoice, sentence) { if (session == my) player.enqueue(it) } } }.isSuccess
                        if (!ok) { while (session == my && player.isSounding) kotlinx.coroutines.delay(60); deviceSay(app, sentence, VoiceCatalog.kodex.first()) }
                    }
                    while (session == my && player.isSounding) kotlinx.coroutines.delay(60)
                    if (geminiPlayer === player) { geminiPlayer = null; player.stop() }
                }
                VoiceFamily.NATURAL -> {
                    val smart = VoicePrefs.smartUrdu(app)
                    val rate = ((VoicePrefs.pace(app) - 1f) * 100f).roundToInt()
                    // P1-11: one bad Edge sentence falls back to the same voice's Gemini counterpart, not straight to Rounded.
                    val fb = VoiceCatalog.geminiFallbackFor(voice)
                    val keys = geminiKeys()
                    var fallbackPlayer: com.mazharansari.axion.livecall.LiveAudioPlayer? = null
                    val fetched = kotlinx.coroutines.channels.Channel<Pair<String, Deferred<File?>>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                    launch {
                        for (sentence in s.sentences) {
                            fetched.send(sentence to async(Dispatchers.IO) {
                                runCatching {
                                    val mp3 = EdgeTts.synthesize(sentence, VoiceCatalog.edgeVoiceFor(voice, sentence, smart), rate)
                                    File(app.cacheDir, "axion-stream-${System.nanoTime()}.mp3").apply { writeBytes(mp3) }
                                }.getOrNull()
                            })
                        }
                        fetched.close()
                    }
                    for ((sentence, d) in fetched) {
                        if (session != my) { d.cancel(); continue }
                        val file = d.await()
                        if (session != my) { file?.delete(); continue }
                        val ok = file != null && playFile(file)
                        file?.delete()
                        if (!ok && session == my) {
                            val spokeGemini = if (fb != null && keys.isNotEmpty()) {
                                val p = fallbackPlayer ?: com.mazharansari.axion.livecall.LiveAudioPlayer(scope, android.media.AudioAttributes.USAGE_MEDIA)
                                    .also { fallbackPlayer = it; it.start(); it.setSpeed(speed) }
                                var heard = false
                                runCatching {
                                    withContext(Dispatchers.IO) { GeminiVoice.speak(keys, fb.engineVoice, sentence) { if (session == my) { heard = true; p.enqueue(it) } } }
                                    while (session == my && p.isSounding) kotlinx.coroutines.delay(60)
                                }.isSuccess && heard
                            } else false
                            if (!spokeGemini && session == my) deviceSay(app, sentence, VoiceCatalog.kodex.first())   // still nothing: say it in Rounded, keep going
                        }
                    }
                    fallbackPlayer?.let { runCatching { it.stop() } }
                }
            }
            finish(my)
        }
        return s
    }

    private suspend fun readyEngine(context: Context): TextToSpeech? {
        tts?.let { if (ttsReady) return it; repeat(100) { kotlinx.coroutines.delay(50); if (ttsReady) return tts }; return null }
        return suspendCancellableCoroutine { cont ->
            val listener = TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) ttsReady = true else tts = null
                pendingSpeak?.let { p -> pendingSpeak = null; if (status == TextToSpeech.SUCCESS) p() }
                if (cont.isActive) cont.resume(if (status == TextToSpeech.SUCCESS) tts else null)
            }
            tts = if (hasGoogleTts(context)) TextToSpeech(context, listener, GOOGLE_TTS) else TextToSpeech(context, listener)
        }
    }

    /** One sentence on the phone's engine; returns when it has been spoken. */
    private suspend fun deviceSay(context: Context, text: String, voice: AxVoice) {
        val engine = readyEngine(context) ?: return
        runCatching {
            resolveDeviceVoice(engine, voice.engineVoice, VoiceCatalog.detect(text))?.let { engine.voice = it }
            engine.setSpeechRate(VoicePrefs.pace(context)); engine.setPitch(1.0f)
        }
        val id = "axion-s-${System.nanoTime()}"
        suspendCancellableCoroutine<Unit> { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            })
            cont.invokeOnCancellation { runCatching { engine.stop() } }
            if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
        }
    }

    private suspend fun playFile(file: File, startAtMs: Long = 0L): Boolean = suspendCancellableCoroutine { cont ->
        val player = MediaPlayer()
        mediaPlayer = player
        cont.invokeOnCancellation {
            runCatching { player.stop() }; runCatching { player.release() }
            if (mediaPlayer === player) mediaPlayer = null
        }
        try {
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                runCatching { player.release() }
                if (mediaPlayer === player) mediaPlayer = null
                if (cont.isActive) cont.resume(true)
            }
            player.setOnErrorListener { _, _, _ ->
                runCatching { player.release() }
                if (mediaPlayer === player) mediaPlayer = null
                if (cont.isActive) cont.resume(false)
                true
            }
            player.prepare()
            if (startAtMs > 0) runCatching { player.seekTo(startAtMs.toInt()) }
            if (!paused) {
                if (speed != 1f) applySpeed(player)
                player.start()
            }
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(false)
        }
    }

    // ------------------------------------------------------------------ text

    internal fun stripMarkdownForSpeech(raw: String): String {
        var text = raw.replace(Regex("```[\\s\\S]*?```"), " Code block omitted. ")
        text = text.replace(Regex("`([^`]*)`"), "$1")
        text = text.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("https?://\\S+"), "link")
        text = text.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.contains("-") && trimmed.matches(Regex("^\\|?[\\s:|-]+\\|?$"))) "" else trimmed.trimStart('#', '>', ' ').replace("|", ", ")
        }
        text = text.replace(Regex("[*_~]"), "")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    internal fun chunkText(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val chunks = mutableListOf<String>()
        var rest = text
        while (rest.length > maxLen) {
            var cut = maxOf(rest.lastIndexOf(". ", maxLen), rest.lastIndexOf("۔ ", maxLen), rest.lastIndexOf("? ", maxLen), rest.lastIndexOf("! ", maxLen))
            if (cut < maxLen / 3) cut = rest.lastIndexOf(" ", maxLen)
            if (cut <= 0) cut = maxLen
            chunks += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotBlank()) chunks += rest
        return chunks.filter { it.isNotBlank() }
    }
}


/** Voice settings storage. */
object VoicePrefs {
    const val FILE = "axion_voice_settings"
    const val KEY_VOICE = "active_voice_id"
    const val KEY_PACE = "pace_rate"
    const val KEY_SMART_URDU = "smart_urdu"
    const val KEY_CALL_MODE = "call_mode"          // "same" (default) | "gemini"
    const val KEY_CALL_OWN_VOICE = "call_own_voice" // v0.4: false (default) = native Gemini Live audio; true = Live + your voice (slower)
    const val KEY_CALL_GEMINI_VOICE = "call_gemini_voice"
    const val KEY_SPEED = "read_aloud_speed"          // v0.3.1: Grok's pill speed (0.75–3×), on top of Pace
    val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 2.75f, 3f)

    fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun activeVoice(ctx: Context): AxVoice = VoiceCatalog.byId(prefs(ctx).getString(KEY_VOICE, VoiceCatalog.DEFAULT_ID))
    fun setActive(ctx: Context, id: String) = prefs(ctx).edit().putString(KEY_VOICE, id).apply()
    fun callOwnVoice(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CALL_OWN_VOICE, false)
    fun setCallOwnVoice(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_CALL_OWN_VOICE, v).apply()
    fun pace(ctx: Context): Float = prefs(ctx).getFloat(KEY_PACE, VoiceCatalog.DEFAULT_PACE)
    fun speed(ctx: Context): Float = prefs(ctx).getFloat(KEY_SPEED, 1f)
    fun setSpeed(ctx: Context, v: Float) = prefs(ctx).edit().putFloat(KEY_SPEED, v).apply()
    fun smartUrdu(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SMART_URDU, false)   // v0.4: opt-in (it swapped his picked voice)
    fun secret(ctx: Context, key: String): String? =
        prefs(ctx).getString("enc_$key", null)?.let { com.mazharansari.axion.data.KeyVault.decrypt(it) }?.takeIf { it.isNotBlank() }
    /** v0.5.2: every secret saved here, for the leak scanner. */
    fun savedSecrets(ctx: Context): List<String> = prefs(ctx).all.keys.filter { it.startsWith("enc_") }.mapNotNull { secret(ctx, it.removePrefix("enc_")) }
    fun setSecret(ctx: Context, key: String, value: String?) {
        prefs(ctx).edit().apply { if (value.isNullOrBlank()) remove("enc_$key") else putString("enc_$key", com.mazharansari.axion.data.KeyVault.encrypt(value.trim())) }.apply()
    }

    /** v0.3 migration: the v0.2 voice keys (device voice name / "gemini" source) are dropped; everyone starts on Rounded, Normal. */
    fun migrate(ctx: Context) {
        val p = prefs(ctx)
        if (p.getInt("schema", 0) < 3) {
            p.edit().remove("voice_name").remove("active_voice_source").putFloat(KEY_PACE, VoiceCatalog.DEFAULT_PACE).putInt("schema", 3).apply()
        }
        // v0.4: Smart Urdu was on by default and silently replaced the picked voice on Roman-Urdu/Urdu answers.
        if (p.getInt("schema", 0) < 4) {
            p.edit().putBoolean(KEY_SMART_URDU, false).putInt("schema", 4).apply()
        }
    }
}
