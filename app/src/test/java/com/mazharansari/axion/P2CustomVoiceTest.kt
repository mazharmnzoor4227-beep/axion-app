package com.mazharansari.axion

import com.mazharansari.axion.voice.CustomVoice
import com.mazharansari.axion.voice.CustomVoiceStore
import com.mazharansari.axion.voice.sanitizeVoiceFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-6 (your own voice slot): the store and the file-name guard are pure and
 * tested here. The actual speaking (engine installed / not installed) needs a
 * real phone — the player falls back to the picked voice with an honest notice,
 * and never pretends the clone is speaking.
 */
class P2CustomVoiceTest {

    @Test
    fun `file names are sanitised with no separators`() {
        assertEquals("my_voice__1_.mp3", sanitizeVoiceFileName("my voice (1).mp3"))
        assertEquals("passwd", sanitizeVoiceFileName("../etc/passwd"))
        assertEquals("my-voice", sanitizeVoiceFileName(""))
        assertEquals("a".repeat(80), sanitizeVoiceFileName("a".repeat(120)))
        assertEquals("voice-model.bin", sanitizeVoiceFileName("voice-model.bin"))
    }

    @Test
    fun `store holds a voice and reports usability from the file on disk`() {
        val dir = File("/tmp/p2-voice-test-${System.nanoTime()}").apply { mkdirs() }
        val audio = File(dir, "my-voice.mp3").apply { writeText("fake-audio") }
        val store = CustomVoiceStore(dir)
        assertNull(store.current.value)
        assertFalse(store.isUsable())
        store.set(CustomVoice("my-voice.mp3", audio.absolutePath, "audio", 1000L))
        assertEquals("my-voice.mp3", store.current.value?.name)
        assertTrue(store.isUsable())
        audio.delete()
        assertFalse(store.isUsable())
        store.clear()
        assertNull(store.current.value)
    }

    @Test
    fun `store reads a voice written by an earlier run`() {
        val dir = File("/tmp/p2-voice-test-${System.nanoTime()}").apply { mkdirs() }
        val audio = File(dir, "v.wav").apply { writeText("x") }
        File(dir, "custom-voice.json").writeText(
            """{"name":"v.wav","filePath":"${audio.absolutePath}","kind":"audio","addedAt":1000}""",
        )
        val store = CustomVoiceStore(dir)
        assertEquals("v.wav", store.current.value?.name)
        assertEquals("audio", store.current.value?.kind)
        assertTrue(store.isUsable())
    }

    @Test
    fun `store ignores a corrupt file instead of crashing`() {
        val dir = File("/tmp/p2-voice-test-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "custom-voice.json").writeText("{not json")
        val store = CustomVoiceStore(dir)
        assertNull(store.current.value)
        assertFalse(store.isUsable())
    }
}
