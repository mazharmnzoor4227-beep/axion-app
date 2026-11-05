package com.mazharansari.axion.agent

import com.mazharansari.axion.llm.*
import org.junit.Assert.*
import org.junit.Test

class HistoryCompressionTest {
    private fun longHistory(n: Int): List<ChatItem> = buildList {
        add(UserItem("Please use a blue theme (#1a5eff) and call the app Zed. Never use red anywhere. Build me a notes app."))
        add(AssistantItem("Sure — blue theme, the app is called Zed."))
        for (i in 1..n) { add(UserItem("Turn $i: now add feature number $i to the notes app, with a long explanation " + "x".repeat(500))); add(AssistantItem("Added feature $i. " + "y".repeat(700))) }
    }
    @Test fun shortHistoryIsUntouched() {
        val h = listOf<ChatItem>(UserItem("hi"), AssistantItem("hello"), UserItem("name it Zed"), AssistantItem("ok"))
        val r = HistoryCompressor.compress(h); assertEquals("", r.notes); assertEquals(4, r.recent.size); assertEquals(0, r.compressedTurns)
    }
    @Test fun longHistoryBecomesNotesPlusRecentWindow() {
        val h = longHistory(40); val before = h.sumOf { HistoryCompressor.tokens(it) }
        val r = HistoryCompressor.compress(h, budgetTokens = 5000)
        val after = HistoryCompressor.tokens(r.notes) + r.recent.sumOf { HistoryCompressor.tokens(it) }
        assertTrue("$before → $after", after < before / 4); assertTrue(after < 5000 + 800)
        assertTrue(r.compressedTurns > 50); assertTrue(r.recent.first() is UserItem); assertTrue(r.recent.last() is AssistantItem)
    }
    @Test fun decisionFromTheStartSurvives() {
        val r = HistoryCompressor.compress(longHistory(40), budgetTokens = 4000)
        assertTrue(r.notes, r.notes.contains("blue theme")); assertTrue(r.notes.contains("Zed")); assertTrue(r.notes.contains("Never use red"))
        assertTrue(r.notes.startsWith("SESSION NOTES")); assertTrue(r.notes.contains("First request"))
    }
    @Test fun romanUrduDecisionsAreKept() {
        val h = buildList<ChatItem> { add(UserItem("app ka naam Noor rakho aur rang hara use karo. hamesha Roman Urdu mein jawab do.")); add(AssistantItem("theek hai")); repeat(30) { add(UserItem("kaam $it " + "z".repeat(400))); add(AssistantItem("done $it " + "w".repeat(600))) } }
        val n = HistoryCompressor.compress(h, 3000).notes
        assertTrue(n, n.contains("Noor")); assertTrue(n.contains("hamesha Roman Urdu"))
    }
    @Test fun decisionsAreDeduplicatedAndCapped() {
        val d = HistoryCompressor.decisionsIn("Use blue. Make it fast. The sky is nice. Never use red. Use blue.")
        assertTrue(d.any { it.startsWith("Use blue") }); assertTrue(d.none { it.contains("sky") })
        val many = HistoryCompressor.notesFor(null, (1..60).flatMap { listOf(UserItem("always use option number $it for this"), AssistantItem("ok")) })
        assertTrue(many.lines().count { it.startsWith("   - ") } <= 14)
    }
    @Test fun windowNeverStartsWithAnAssistantTurn() {
        val r = HistoryCompressor.compress(longHistory(30), 3000); assertTrue(r.recent.first() is UserItem)
    }
    @Test fun compressRunShrinksOldToolResultsOnly() {
        val items = buildList<ChatItem> { add(SystemItem("sys")); repeat(8) { add(AssistantItem("call", listOf(ToolCall("c$it", "t", "{}")))); add(ToolResultItem("c$it", "t", "R$it " + "q".repeat(6000))) } }
        val c = HistoryCompressor.compressRun(items, budgetTokens = 6000)
        val res = c.filterIsInstance<ToolResultItem>()
        assertTrue(res.take(5).all { it.content.length < 500 && it.content.contains("shortened") }); assertTrue(res.takeLast(3).all { it.content.length > 5000 })
        assertSame(items, HistoryCompressor.compressRun(items.take(3), 6000).let { items })
    }
}
