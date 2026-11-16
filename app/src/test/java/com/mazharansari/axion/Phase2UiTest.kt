package com.mazharansari.axion

import com.mazharansari.axion.agent.SkillListPrompt
import com.mazharansari.axion.data.BotLogo
import com.mazharansari.axion.data.ConversationDrafts
import com.mazharansari.axion.ui.chat.ChatListMeta
import com.mazharansari.axion.ui.chat.MessageText
import com.mazharansari.axion.ui.chat.PasteAction
import com.mazharansari.axion.ui.chat.PastePolicy
import com.mazharansari.axion.ui.chat.buildChatRows
import com.mazharansari.axion.ui.chat.chatTimeLabel
import com.mazharansari.axion.voice.VoiceInputState
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 2 UI overhaul — pure-logic tests (no device): the URL-decode fix for
 * the garbled message, bot logos, per-conversation drafts, the WhatsApp-style
 * chat list, and the voice finishing flag.
 */
class Phase2UiTest {

    // ── MessageText: the %27%29%0A… garble ──────────────────────────────────

    @Test fun encodedScriptBlobDecodes() {
        val blob = "%27%29%0Apdf.ln%285%29%0Apdf.set_font%28%27Helvetica%27%2C+%27B%27%2C+12%29%0A"
        val out = MessageText.decodeIfEncoded(blob)
        assertEquals("')\npdf.ln(5)\npdf.set_font('Helvetica', 'B', 12)\n", out)
    }

    @Test fun encodedWordsWithPlusDecodes() {
        val blob = "%27This+is+a+short+demo+report+generated+on+the+fly.%27%0A%0A"
        assertTrue(MessageText.decodeIfEncoded(blob).startsWith("'This is a short demo report"))
    }

    @Test fun doubleEncodedDecodes() {
        val once = MessageText.decodeIfEncoded("%2527hello%2520world%2527%2520again%2527")
        assertEquals("'hello world' again'", once)
    }

    @Test fun plainTextUntouched() {
        val plain = "Hello, how are you? Here is a **bold** heading and a list:\n- one\n- two"
        assertEquals(plain, MessageText.decodeIfEncoded(plain))
    }

    @Test fun percentSignUntouched() {
        assertEquals("100% sure, 50% off today", MessageText.decodeIfEncoded("100% sure, 50% off today"))
    }

    @Test fun codeWithPlusUntouched() {
        val code = "Use C++ and a+b for this. See https://example.com/a%20b for details."
        assertEquals(code, MessageText.decodeIfEncoded(code))
    }

    @Test fun markdownHeadingsUntouched() {
        val md = "# Master Prompt\n\n## Section One\n\nDo **this** first."
        assertEquals(md, MessageText.decodeIfEncoded(md))
    }

    @Test fun shortEncodedFragmentUntouched() {
        // Too short / too few %XX sequences to be a blob — leave it alone.
        assertEquals("a%20b", MessageText.decodeIfEncoded("a%20b"))
    }

    // ── BotLogo ─────────────────────────────────────────────────────────────

    @Test fun logoBuiltinParses() {
        val l = BotLogo.parse("builtin:brain")
        assertTrue(l is BotLogo.Builtin)
        assertEquals("brain", (l as BotLogo.Builtin).id)
    }

    @Test fun logoCustomParses() {
        val l = BotLogo.parse("file:/data/logos/ceo.jpg")
        assertTrue(l is BotLogo.Custom)
        assertEquals("/data/logos/ceo.jpg", (l as BotLogo.Custom).path)
    }

    @Test fun logoBlankIsNone() {
        assertEquals(BotLogo.None, BotLogo.parse(""))
        assertEquals(BotLogo.None, BotLogo.parse("   "))
    }

    @Test fun logoBareIdIsBuiltin() {
        assertEquals(BotLogo.Builtin("rocket"), BotLogo.parse("rocket"))
    }

    @Test fun logoRoundTrip() {
        assertEquals("builtin:gem", BotLogo.builtin("gem"))
        assertEquals("file:/x/y.png", BotLogo.custom("/x/y.png"))
        assertEquals(BotLogo.Builtin("gem"), BotLogo.parse(BotLogo.builtin("gem")))
    }

    @Test fun logoBuiltinsKnown() {
        assertTrue(BotLogo.BUILTINS.size >= 8)
        assertTrue(BotLogo.isKnownBuiltin("brain"))
        assertFalse(BotLogo.isKnownBuiltin("nope"))
    }

    // ── ConversationDrafts ──────────────────────────────────────────────────

    @Test fun draftsPerConversation() {
        val d = ConversationDrafts()
        d.save("a", "hello a")
        d.save("b", "hello b")
        assertEquals("hello a", d.restore("a"))
        assertEquals("hello b", d.restore("b"))
        assertEquals("", d.restore("c"))
        assertEquals("", d.restore(null))
        assertEquals(2, d.count())
    }

    @Test fun draftBlankClears() {
        val d = ConversationDrafts()
        d.save("a", "text")
        d.save("a", "   ")
        assertEquals("", d.restore("a"))
        assertEquals(0, d.count())
    }

    @Test fun draftClearAfterSend() {
        val d = ConversationDrafts()
        d.save("a", "text")
        d.clear("a")
        assertEquals("", d.restore("a"))
    }

    // ── Chat list rows ──────────────────────────────────────────────────────

    private fun meta(id: String, updated: Long, pinned: Boolean = false, section: String = "ask", botId: String = "", botName: String = "") =
        ChatListMeta(id, "Chat $id", pinned, updated, "last message", section, botId, botName)

    @Test fun rowsExcludeWorkers() {
        val rows = buildChatRows(listOf(meta("a", 100), meta("w", 200, section = "worker")), emptySet())
        assertEquals(listOf("a"), rows.map { it.id })
    }

    @Test fun rowsPinnedFirstThenNewest() {
        val rows = buildChatRows(listOf(meta("a", 300), meta("b", 100, pinned = true), meta("c", 200)), emptySet())
        assertEquals(listOf("b", "a", "c"), rows.map { it.id })
    }

    @Test fun rowsCarryWorkingFlag() {
        val rows = buildChatRows(listOf(meta("a", 100), meta("b", 100)), setOf("b"))
        assertFalse(rows.first { it.id == "a" }.working)
        assertTrue(rows.first { it.id == "b" }.working)
    }

    @Test fun rowsShowBotNameAndLogo() {
        val rows = buildChatRows(
            listOf(meta("a", 100, botId = "user-1", botName = "CEO")),
            emptySet(), mapOf("user-1" to "builtin:brain"),
        )
        val r = rows.single()
        assertTrue(r.isBotChat)
        assertEquals("CEO", r.displayName)
        assertEquals("builtin:brain", r.botLogo)
    }

    @Test fun rowsPlainChatShowsTitle() {
        val r = buildChatRows(listOf(meta("a", 100)), emptySet()).single()
        assertFalse(r.isBotChat)
        assertEquals("Chat a", r.displayName)
    }

    @Test fun timeLabelTodayYesterdayOlder() {
        // 2026-11-16 16:00 PKT (11:00 UTC).
        val now = 1_794_826_800_000L
        assertTrue(chatTimeLabel(now - 3_600_000L, now).contains(":"))
        assertEquals("Yesterday", chatTimeLabel(now - 90_000_000L, now))
        assertEquals("10 Nov", chatTimeLabel(now - 6 * 86_400_000L, now))
    }

    // ── VoiceInputState ─────────────────────────────────────────────────────

    @Test fun voiceFullTextJoinsSegments() {
        val s = VoiceInputState(listening = true, committed = "hello", partial = "world")
        assertEquals("hello world", s.fullText)
        assertFalse(s.finishing)
    }

    @Test fun voiceFinishingFlag() {
        val s = VoiceInputState(listening = true, finishing = true)
        assertTrue(s.finishing)
        assertTrue(s.listening)
    }

    // ── PastePolicy (settings audit: Paste as File is a real setting now) ──

    @Test fun pasteAlwaysConvertsOver2000() {
        assertEquals(PasteAction.CONVERT_TO_FILE, PastePolicy.decide("Always", 2_001))
        assertEquals(PasteAction.SEND_INLINE, PastePolicy.decide("Always", 2_000))
        assertEquals(PasteAction.SEND_INLINE, PastePolicy.decide("Always", 100))
    }

    @Test fun pasteAlwaysAskOnlyForGiant() {
        assertEquals(PasteAction.ASK_USER, PastePolicy.decide("Always ask", 60_001))
        assertEquals(PasteAction.SEND_INLINE, PastePolicy.decide("Always ask", 60_000))
        assertEquals(PasteAction.SEND_INLINE, PastePolicy.decide("Always ask", 5_000))
    }

    @Test fun pasteNeverConverts() {
        assertEquals(PasteAction.SEND_INLINE, PastePolicy.decide("Never", 500_000))
    }

    @Test fun pasteUnknownModeSendsInline() {
        assertEquals(PasteAction.SEND_INLINE, PastePolicy.decide("bogus", 500_000))
    }

    // ── SkillListPrompt (token efficiency: capped per-prompt skill list) ──

    @Test fun skillListShortDescriptions() {
        val out = SkillListPrompt.render(listOf("pdf" to "Reads and writes PDF files with a very long description that goes on and on"))
        assertTrue(out.contains("pdf:"))
        assertTrue(out.length < 80)
    }

    @Test fun skillListKeepsAllNamesUnderCap() {
        val entries = (1..25).map { "skill$it" to "does thing $it" }
        val out = SkillListPrompt.render(entries)
        assertTrue(out.contains("skill1"))
        assertTrue(out.contains("skill20"))
        assertFalse(out.contains("skill21"))
        assertTrue(out.contains("+ 5 more skills"))
    }

    @Test fun skillListEmpty() {
        assertEquals("", SkillListPrompt.render(emptyList()))
    }
}
