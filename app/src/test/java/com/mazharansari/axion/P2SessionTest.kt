package com.mazharansari.axion.agent

import com.mazharansari.axion.llm.*
import org.junit.Assert.*
import org.junit.Test

/**
 * P2-2: long-session handling — measured context usage, auto-compact at 75%,
 * and the resume draft for an interrupted long task.
 * Pure logic, no model, no network, no Android framework.
 */
class P2SessionTest {

    private fun longHistory(n: Int): List<ChatItem> = buildList {
        add(UserItem("Build me a notes app, blue theme, called Zed."))
        add(AssistantItem("Sure — blue theme, the app is called Zed."))
        for (i in 1..n) {
            add(UserItem("Turn $i: add feature number $i, with a long explanation " + "x".repeat(500)))
            add(AssistantItem("Added feature $i. " + "y".repeat(700)))
        }
    }

    // ── ContextUsage math ────────────────────────────────────────────────────────

    @Test fun percentIsCappedAt100() {
        assertEquals(0, ContextUsage(0, 100_000, 0).percent)
        assertEquals(50, ContextUsage(50_000, 100_000, 0).percent)
        assertEquals(100, ContextUsage(250_000, 100_000, 0).percent)
        assertEquals(0, ContextUsage(10, 0, 0).percent)
    }

    @Test fun labelShowsCountsAndCompaction() {
        val plain = ContextUsage(12_400, 20_000, 0)
        assertEquals("Context 62% · 12,400 / 20,000 tokens", plain.label())
        assertFalse(plain.compacted)
        val folded = ContextUsage(15_000, 20_000, 18)
        assertTrue(folded.compacted)
        assertTrue(folded.label(), folded.label().contains("compacted 18 turns"))
    }

    @Test fun measureCountsNotesPlusRecentPlusUser() {
        val folded = HistoryCompressor.compress(longHistory(4), budgetTokens = 5000)
        val user = UserItem("and now?")
        val usage = ContextBudget.measure(folded, user, 100_000)
        val expected = HistoryCompressor.tokens(folded.notes) +
            folded.recent.sumOf { HistoryCompressor.tokens(it) } + HistoryCompressor.tokens(user)
        assertEquals(expected, usage.usedTokens)
        assertEquals(100_000, usage.budgetTokens)
        assertEquals(folded.compressedTurns, usage.compactedTurns)
    }

    // ── Auto-compact threshold ───────────────────────────────────────────────────

    @Test fun compactsAt75PercentNotBefore() {
        assertFalse(ContextBudget.shouldCompact(ContextUsage(74_999, 100_000, 0)))
        assertTrue(ContextBudget.shouldCompact(ContextUsage(75_000, 100_000, 0)))
        assertTrue(ContextBudget.shouldCompact(ContextUsage(99_000, 100_000, 0)))
    }

    @Test fun tightSecondPassShrinksTheWindow() {
        val history = longHistory(40)
        val first = HistoryCompressor.compress(history)
        val usage = ContextBudget.measure(first, UserItem("continue"), 4_000)
        assertTrue("first pass should already be heavy: ${usage.percent}%", usage.percent >= 75)
        val tight = HistoryCompressor.compress(history, budgetTokens = 4_000 / ContextBudget.TIGHT_FRACTION, keepRecent = 4)
        val after = ContextBudget.measure(tight, UserItem("continue"), 4_000)
        assertTrue("tight pass must shrink usage: ${usage.usedTokens} → ${after.usedTokens}", after.usedTokens < usage.usedTokens)
        assertTrue(after.compactedTurns > 0)
        // The decisions from the first turn survive even the tight fold.
        assertTrue(tight.notes.contains("Zed"))
    }

    @Test fun localBudgetUsesTheModelContextSize() {
        assertEquals(8192, ContextBudget.forLocal(8192))
        assertEquals(4096, ContextBudget.forLocal(4096))
        assertEquals(2048, ContextBudget.forLocal(512))   // never below the floor
        assertEquals(100_000, ContextBudget.forLocal(1_000_000))   // never above the cloud default
    }

    // ── Resume ─────────────────────────────────────────────────────────────────

    @Test fun resumeDraftPrefillsTheContinuePrompt() {
        assertEquals("Continue: Build the notes app", ContextBudget.resumeDraft("Build the notes app"))
        assertEquals("Continue: x", ContextBudget.resumeDraft("  x  "))
        assertTrue(ContextBudget.resumeDraft("g".repeat(500)).length <= "Continue: ".length + 200)
    }
}
