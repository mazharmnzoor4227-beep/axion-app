package com.mazharansari.axion.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Phase F (v0.8.5): speaks assistant announcements with the phone's own TTS engine (the same
 * Speech Services voices the Voice Library uses). Best-effort by design: if no engine is ready
 * the announcement is simply skipped — a notification must never crash or block on speech.
 */
object AssistantAnnouncer {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pending = ArrayDeque<String>()

    @Synchronized
    fun speak(ctx: Context, line: String) {
        val engine = tts
        if (engine != null) {
            if (ready) engine.speak(line, TextToSpeech.QUEUE_ADD, null, "axion-notify")
            return
        }
        pending.addLast(line)
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                runCatching { tts?.setLanguage(Locale.getDefault()) }
                synchronized(AssistantAnnouncer) {
                    while (pending.isNotEmpty()) tts?.speak(pending.removeFirst(), TextToSpeech.QUEUE_ADD, null, "axion-notify")
                }
            } else {
                synchronized(AssistantAnnouncer) { pending.clear() }
            }
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }
}
