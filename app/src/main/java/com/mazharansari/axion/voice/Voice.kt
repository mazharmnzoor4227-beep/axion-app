package com.mazharansari.axion.voice

import android.content.Context
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * Composer dictation (v0.3): Kodex's [VoiceRecognizer] — restarts across pauses, self-heals a dropped voice service,
 * and exposes the mic level as a plain field the waveform reads once per frame (no state emissions per RMS event,
 * which is what made v0.2's bars stutter). Must be used on the main thread.
 */
class SpeechInput(private val ctx: Context, language: () -> String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val rec = VoiceRecognizer(ctx, language)
    val state = rec.state
    val listening: StateFlow<Boolean> = rec.state.map { it.listening }.stateIn(scope, SharingStarted.Eagerly, false)
    val partial: StateFlow<String> = rec.state.map { it.fullText }.stateIn(scope, SharingStarted.Eagerly, "")
    /** 0..1, read per frame by the waveform. */
    fun level(): Float = rec.level

    fun available() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start() = rec.start()
    /** Stop and hand back everything heard. */
    fun finish(onText: (String) -> Unit) = rec.finish(onText)
    fun cancel() = rec.cancel()
}

/** Read-aloud entry point for the chat: everything goes through [ReadAloudPlayer] (one voice everywhere). */
class Speaker(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _speakingId = MutableStateFlow<String?>(null)
    val speakingId: StateFlow<String?> = _speakingId

    init { scope.launch { ReadAloudPlayer.playing.collect { _speakingId.value = it } } }

    fun speak(id: String, text: String) { scope.launch { ReadAloudPlayer.toggle(ctx, id, text) } }
    fun stop() { scope.launch { ReadAloudPlayer.stop() } }
    fun shutdown() = stop()
}
