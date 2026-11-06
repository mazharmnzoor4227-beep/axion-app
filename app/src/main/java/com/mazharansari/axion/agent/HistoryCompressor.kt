package com.mazharansari.axion.agent

import com.mazharansari.axion.llm.*

/**
 * v0.6 (Phase 2.3) HISTORY COMPRESSION. Idea from JetBrains Koog (Apache-2.0) "history compression" (fold old turns into a summary);
 * this is an original, deterministic (no extra model call, no extra tokens) extractive version:
 *  - the last [keepRecent] messages stay verbatim (old long answers clipped);
 *  - everything older becomes ONE "Session notes" block in the system prompt: the first request, every explicit DECISION the user made
 *    (use / call it / colour / never / always … in English, Roman Urdu), what was asked in each earlier turn and a one-line gist of what was answered;
 *  - tool results inside a long run are shrunk (oldest first) once the run passes its token budget — [compressRun].
 * The first user request and the decisions are never dropped (hard-capped list, newest decisions win).
 */
object HistoryCompressor {
    data class Result(val notes: String, val recent: List<ChatItem>, val compressedTurns: Int)

    fun tokens(s: String) = (s.length + 3) / 4
    fun tokens(i: ChatItem): Int = when (i) {
        is SystemItem -> tokens(i.text); is UserItem -> tokens(i.text) + i.media.size * 300
        is AssistantItem -> tokens(i.text) + i.toolCalls.sumOf { tokens(it.arguments) + 12 }; is ToolResultItem -> tokens(i.content)
    }

    private val decision = Regex(
        "\\b(use|using|keep|call (it|the app|this)|name (it|the app|this)|named|colou?r|theme|font|language|always|never|don'?t|do not|must|should be|make (it|the)|prefer|only|without|instead|" +
        "rakho|rakhna|rakh do|use karo|istemal|naam|rang|hamesha|kabhi nahi|mat |nahi chahiye|chahiye|sirf|bina|zaroor|lazmi|" +
        "decided|decision|final hai|final kar|pakka|tay hai|tay kar|faisla|mera faisla|pasand|confirm kar|note kar|yaad rakho|hamesha ke liye|bilkul nahi|kabhi bhi nahi)\\b|[=:]\\s*#?[0-9a-f]{3,8}\\b",
        RegexOption.IGNORE_CASE)

    fun compress(history: List<ChatItem>, budgetTokens: Int = 5000, keepRecent: Int = 8, clipOld: Int = 1800): Result {
        val msgs = history.filter { it is UserItem || it is AssistantItem }
        if (msgs.isEmpty()) return Result("", emptyList(), 0)
        val total = msgs.sumOf { tokens(it) }
        if (total <= budgetTokens && msgs.size <= keepRecent + 4) return Result("", msgs.mapIndexed { i, it -> clip(it, i < msgs.size - 4, clipOld) }, 0)
        val cut = (msgs.size - keepRecent).coerceAtLeast(0)
        var older = msgs.subList(0, cut); var recent = msgs.subList(cut, msgs.size)
        // keep the recent window itself inside the budget: drop from its front into the notes
        while (recent.size > 3 && recent.sumOf { tokens(clip(it, true, clipOld)) } > budgetTokens * 3 / 4) { older = older + recent.first(); recent = recent.drop(1) }
        // a window must not start with an assistant turn
        while (recent.isNotEmpty() && recent.first() is AssistantItem) { older = older + recent.first(); recent = recent.drop(1) }
        return Result(notesFor(msgs.first() as? UserItem, older), recent.mapIndexed { i, it -> clip(it, i < recent.size - 4, clipOld) }, older.size)
    }

    private fun clip(i: ChatItem, old: Boolean, max: Int): ChatItem = when {
        i is AssistantItem && old && i.text.length > max -> AssistantItem(i.text.take(max) + " …", i.toolCalls)
        i is UserItem && i.text.length > 4000 -> UserItem(i.text.take(4000) + " …", i.media)
        else -> i
    }

    fun decisionsIn(text: String): List<String> =
        text.replace(Regex("\\s+"), " ").split(Regex("(?<=[.!?؟۔\\n])\\s+")).map { it.trim() }.filter { it.length in 6..240 && decision.containsMatchIn(it) }

    private fun gist(s: String, n: Int): String = s.replace(Regex("\\s+"), " ").trim().let { if (it.length <= n) it else it.take(n).substringBeforeLast(' ') + "…" }

    fun notesFor(first: UserItem?, older: List<ChatItem>): String {
        val sb = StringBuilder("SESSION NOTES (earlier turns of this conversation, compressed — treat as true, do not ask again):\n")
        first?.let { sb.append("• First request: ").append(gist(it.text, 300)).append('\n') }
        val decisions = LinkedHashSet<String>()
        (listOfNotNull(first) + older).filterIsInstance<UserItem>().forEach { u -> decisions += decisionsIn(u.text) }
        val keep = decisions.toList().takeLast(20)   // v0.6.1: a little more room before the oldest decisions fall out
        if (keep.isNotEmpty()) { sb.append("• Decisions & preferences from the user:\n"); keep.forEach { sb.append("   - ").append(gist(it, 220)).append('\n') } }
        val turns = ArrayList<String>(); var pendingAsk: String? = null
        for (m in older) when (m) {
            is UserItem -> { pendingAsk?.let { turns += "asked: $it" }; pendingAsk = gist(m.text, 110) }
            is AssistantItem -> { turns += "asked: ${pendingAsk ?: "(follow-up)"} → answered: ${gist(m.text, 130)}"; pendingAsk = null }
            else -> {}
        }
        pendingAsk?.let { turns += "asked: $it" }
        val shown = turns.takeLast(12)
        if (turns.size > shown.size) sb.append("• (${turns.size - shown.size} earlier turns not listed)\n")
        shown.forEach { sb.append("• ").append(it).append('\n') }
        return sb.toString().trimEnd()
    }

    /** Inside one long run: once the working list passes [budgetTokens], oldest tool results shrink to a short stub (newest [keepFull] stay whole). */
    fun compressRun(items: List<ChatItem>, budgetTokens: Int = 9000, keepFull: Int = 3, stub: Int = 360): List<ChatItem> {
        if (items.sumOf { tokens(it) } <= budgetTokens) return items
        val resultIdx = items.indices.filter { items[it] is ToolResultItem }
        val shrink = resultIdx.dropLast(keepFull).toSet()
        return items.mapIndexed { i, it ->
            if (i in shrink && it is ToolResultItem && it.content.length > stub) ToolResultItem(it.callId, it.name, it.content.take(stub) + "\n…[earlier result shortened: ${it.content.length - stub} more characters]") else it
        }
    }
}
