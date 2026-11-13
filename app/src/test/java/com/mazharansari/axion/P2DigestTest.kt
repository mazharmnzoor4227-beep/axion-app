package com.mazharansari.axion

import com.mazharansari.axion.data.ScheduleEntry
import com.mazharansari.axion.data.ScheduleType
import com.mazharansari.axion.work.DigestBuilder
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-11 (scheduled digests + proactive briefings): the briefing text is pure
 * and tested here. The firing itself rides the fixed scheduler (P0-7/P1-8):
 * the worker composes from real state and saves a chat + notification, and a
 * section with nothing to report says so instead of inventing content.
 */
class P2DigestTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `the digest entry is a daily briefing at 08 00`() {
        val e = DigestBuilder.digestEntry()
        assertEquals(DigestBuilder.DIGEST_ID, e.id)
        assertEquals("Daily briefing", e.title)
        assertEquals(ScheduleType.DAILY, e.type)
        assertEquals(8, e.hour)
        assertEquals(0, e.minute)
        assertTrue(e.digest)
    }

    @Test
    fun `compose puts the three sections in order`() {
        val text = DigestBuilder.compose(
            "13 Nov 2026",
            listOf("• Qwen3 run notes — 12 Nov, 22:10"),
            listOf("• axion-app (main)\nOverall: success"),
            listOf("• Antigravity: 4.2 of 5 accounts quota left · 3 runs today"),
        )
        assertTrue(text.startsWith("Daily briefing — 13 Nov 2026"))
        assertTrue(text.indexOf("Overnight research") < text.indexOf("Repo CI status"))
        assertTrue(text.indexOf("Repo CI status") < text.indexOf("Quota left"))
        assertTrue("Qwen3 run notes" in text)
        assertTrue("Overall: success" in text)
        assertTrue("4.2 of 5" in text)
    }

    @Test
    fun `empty sections say so honestly instead of inventing content`() {
        val text = DigestBuilder.compose("13 Nov 2026", emptyList(), emptyList(), emptyList())
        assertTrue("No new research since yesterday." in text)
        assertTrue("No repo to check yet." in text)
        assertTrue("Nothing measured yet." in text)
    }

    @Test
    fun `research lines clean up file names`() {
        val lines = DigestBuilder.researchLines(listOf("qwen3_14b_run_notes.md" to 1_700_000_000_000L))
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("• qwen3 14b run notes — "))
    }

    @Test
    fun `quota lines cover antigravity and keys, and skip what is absent`() {
        val both = DigestBuilder.quotaLines(4.2, 5, 3, 41.0, 50.0, null)
        assertEquals(2, both.size)
        assertTrue(both[0].contains("4.2 of 5 accounts"))
        assertTrue(both[0].contains("3 runs today"))
        assertTrue(both[1].contains("41 of 50 left today"))
        val keysOnly = DigestBuilder.quotaLines(0.0, 0, 0, 10.0, 20.0, null)
        assertEquals(1, keysOnly.size)
        assertTrue(DigestBuilder.quotaLines(0.0, 0, 0, 0.0, 0.0, null).isEmpty())
    }

    @Test
    fun `the digest flag survives a round-trip and defaults to false`() {
        val e = DigestBuilder.digestEntry(9, 30)
        val back = json.decodeFromString<ScheduleEntry>(json.encodeToString(e))
        assertTrue(back.digest)
        assertEquals(9, back.hour)
        assertEquals(30, back.minute)
        val old = json.decodeFromString<ScheduleEntry>(
            """{"id":"x","title":"T","prompt":"P","type":"DAILY","hour":9,"minute":0}""",
        )
        assertFalse(old.digest)
    }
}
