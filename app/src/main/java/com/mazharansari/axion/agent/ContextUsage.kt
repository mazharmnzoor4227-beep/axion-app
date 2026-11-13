package com.mazharansari.axion.agent

import com.mazharansari.axion.llm.ChatItem
import com.mazharansari.axion.llm.UserItem

/**
 * P2-2 (long-session handling): how much of the model's context window the working
 * history already uses. Measured with the same ~4-chars-per-token estimate as
 * [HistoryCompressor], so the number the user sees is the number the agent acts on.
 * The session auto-compacts BEFORE the window fills instead of dying: when
 * [ContextBudget.shouldCompact] is true the agent re-folds the history with a much
 * tighter budget (the dropped turns become "Session notes" in the system prompt —
 * the summary handoff — and the first request + the user's decisions are never dropped).
 */
data class ContextUsage(
    val usedTokens: Int,
    val budgetTokens: Int,
    val compactedTurns: Int,
) {
    val percent: Int get() = if (budgetTokens <= 0) 0 else (usedTokens * 100 / budgetTokens).coerceIn(0, 100)
    val compacted: Boolean get() = compactedTurns > 0
    /** "Context 62% · 12,400 / 20,000 tokens" (+ " · compacted 18 turns" when folding happened). */
    fun label(): String = "Context $percent% · ${usedTokens.grouped()} / ${budgetTokens.grouped()} tokens" +
        if (compacted) " · compacted $compactedTurns turns" else ""

    private fun Int.grouped(): String = String.format(java.util.Locale.US, "%,d", this)
}

object ContextBudget {
    /** Working budget for cloud models (their real windows are 128k+; 100k leaves headroom for the answer). */
    const val DEFAULT_BUDGET = 100_000
    /** Compact once the working history reaches this much of the window — never wait for the wall. */
    const val COMPACT_AT_PERCENT = 75
    /** The second-pass fold keeps the recent window inside this fraction of the budget. */
    const val TIGHT_FRACTION = 4

    /** Local models use their real context size (LFM3 8192, Qwen/Gemma 4096 — see LocalModel.ctx). */
    fun forLocal(ctx: Int): Int = ctx.coerceIn(2048, DEFAULT_BUDGET)

    /** Usage of one already-folded history plus the new user turn, against [budgetTokens]. */
    fun measure(folded: HistoryCompressor.Result, user: UserItem, budgetTokens: Int): ContextUsage {
        val used = HistoryCompressor.tokens(folded.notes) +
            folded.recent.sumOf { HistoryCompressor.tokens(it) } +
            HistoryCompressor.tokens(user)
        return ContextUsage(used, budgetTokens, folded.compressedTurns)
    }

    fun shouldCompact(usage: ContextUsage): Boolean = usage.percent >= COMPACT_AT_PERCENT

    /** The text a resumed interrupted task starts with: the user taps Send to continue. */
    fun resumeDraft(goal: String): String = "Continue: ${goal.trim().take(200)}"
}
