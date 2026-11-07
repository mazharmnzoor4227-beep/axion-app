package com.mazharansari.axion

import com.mazharansari.axion.data.Message
import com.mazharansari.axion.data.MsgStatus
import com.mazharansari.axion.data.Role
import com.mazharansari.axion.livecall.CallContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.8.2: the Live call's memory block — the tail of its own conversation, as a transcript. */
class CallContextTest {

    private fun msg(role: Role, text: String, status: MsgStatus = MsgStatus.DONE) =
        Message(id = "m", role = role, text = text, status = status)

    @Test
    fun emptyConversationSeedsNothing() {
        assertEquals("", CallContext.build(emptyList()))
        assertEquals("", CallContext.build(listOf(msg(Role.USER, "   "))))
    }

    @Test
    fun formatsTurnsAsTranscriptWithHeader() {
        val out = CallContext.build(listOf(
            msg(Role.USER, "meri dukaan ka naam kya rakha tha?"),
            msg(Role.ASSISTANT, "Clawora Beauty."),
            msg(Role.USER, "aur hum ne kya decide kiya tha?"),
        ))
        assertTrue(out.startsWith(CallContext.HEADER))
        assertTrue(out.contains("User: meri dukaan ka naam kya rakha tha?"))
        assertTrue(out.contains("Axion: Clawora Beauty."))
        assertTrue(out.indexOf("naam kya rakha") < out.indexOf("kya decide"))
    }

    @Test
    fun skipsStreamingPlaceholdersAndBlankText() {
        val out = CallContext.build(listOf(
            msg(Role.USER, "pehli baat"),
            msg(Role.ASSISTANT, "aadha jawab", MsgStatus.STREAMING),
            msg(Role.ASSISTANT, ""),
        ))
        assertTrue(out.contains("User: pehli baat"))
        assertFalse(out.contains("aadha jawab"))
    }

    @Test
    fun keepsOnlyTheRecentTail() {
        val many = (1..60).map { msg(if (it % 2 == 0) Role.ASSISTANT else Role.USER, "baat number $it") }
        val out = CallContext.build(many, maxMessages = 10)
        assertFalse(out.contains("baat number 49"))
        assertTrue(out.contains("baat number 60"))
    }

    @Test
    fun charCapDropsOldestFirst() {
        val msgs = listOf(
            msg(Role.USER, "purani ".repeat(50)),
            msg(Role.ASSISTANT, "nayi baat jo lazmi rehni chahiye"),
        )
        val out = CallContext.build(msgs, maxChars = 200)
        assertTrue(out.contains("nayi baat jo lazmi rehni chahiye"))
        assertFalse(out.contains("purani"))
    }
}
