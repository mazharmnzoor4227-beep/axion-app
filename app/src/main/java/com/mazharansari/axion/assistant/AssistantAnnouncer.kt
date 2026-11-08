package com.mazharansari.axion.assistant

import android.content.Context
import com.mazharansari.axion.voice.ReadAloudPlayer

/**
 * Phase F announcements, v0.8.9 (owner's word, 2026-11-08): announcements must speak in the
 * NATURAL voice — the Gemini Live-style voice — not the phone's flat default TTS. So this no
 * longer uses the system TTS engine at all: it speaks through the app's own read-aloud player,
 * which uses the voice the owner picked in the Voice Library (a Gemini voice when his Gemini
 * key is connected — the same natural voices the Live call uses; otherwise the best on-device
 * voice). One shared player also means an announcement never talks over a chat read-aloud.
 */
object AssistantAnnouncer {
    fun speak(ctx: Context, line: String) {
        runCatching { ReadAloudPlayer.speak(ctx.applicationContext, "axion-announce", line) }
    }

    fun stop() {
        runCatching { ReadAloudPlayer.stop() }
    }
}
