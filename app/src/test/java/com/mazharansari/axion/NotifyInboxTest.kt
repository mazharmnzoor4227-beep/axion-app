package com.mazharansari.axion

import com.mazharansari.axion.assistant.NotifyInbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase F (v0.8.5): the announcement line and the dedupe window are pure — pin them down. */
class NotifyInboxTest {

    @Test fun spokenLineFull() {
        assertEquals(
            "WhatsApp par Ahmed ka message aya hai: kal milte hain",
            NotifyInbox.spokenLine("WhatsApp", "Ahmed", "kal milte hain"),
        )
    }

    @Test fun spokenLineMissingParts() {
        assertEquals("Telegram par Sara ka message aya hai", NotifyInbox.spokenLine("Telegram", "Sara", ""))
        assertEquals("WhatsApp par naya message aya hai: photo", NotifyInbox.spokenLine("WhatsApp", "", "photo"))
        assertEquals("WhatsApp par naya message aya hai", NotifyInbox.spokenLine("WhatsApp", "", ""))
    }

    @Test fun messagingSet() {
        assertTrue(NotifyInbox.isMessaging("com.whatsapp"))
        assertTrue(NotifyInbox.isMessaging("org.telegram.messenger"))
        assertFalse(NotifyInbox.isMessaging("com.android.settings"))
        assertFalse(NotifyInbox.isMessaging("com.mazharansari.axion"))
    }

    @Test fun dedupeWindow() {
        val t0 = 1_000_000L
        assertTrue(NotifyInbox.offer("com.whatsapp", "WhatsApp", "A", "hello", t0))
        assertFalse(NotifyInbox.offer("com.whatsapp", "WhatsApp", "A", "hello", t0 + 5_000))
        assertTrue(NotifyInbox.offer("com.whatsapp", "WhatsApp", "A", "hello", t0 + 20_000))
        assertTrue(NotifyInbox.offer("com.whatsapp", "WhatsApp", "A", "different", t0 + 21_000))
    }
}
