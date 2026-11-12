package com.mazharansari.axion.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Which of the two Hermes-style memory files an entry belongs to. */
enum class MemoryTarget { USER, MEMORY }

@Serializable
data class MemoryData(
    /** About the user: preferences, habits, how they like things done. */
    val user: List<String> = emptyList(),
    /** Durable facts, lessons and project notes the agent picked up. */
    val notes: List<String> = emptyList(),
)

/**
 * v0.10 (Hermes learning loop): Axion's persistent, agent-curated memory.
 *
 * Two bounded lists — USER (who the user is, how they like things) and MEMORY (facts, lessons,
 * project notes) — saved as one JSON file in the app's private storage, in the same pattern as
 * [SettingsStore]. The agent writes it with the memory_* tools; the user can see, edit and delete
 * every entry in Settings → Memory. A snapshot is injected into the system prompt of every chat,
 * so what Axion learns in one conversation is there in the next.
 *
 * The budgets are deliberate (Hermes keeps its memory files small on purpose): when a list is
 * full, an add is refused with a message telling the agent to replace or remove something first,
 * which keeps the memory dense instead of letting it rot into a transcript dump.
 */
class MemoryStore(ctx: Context) {
    private val file = File(ctx.filesDir, "memory.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _data = MutableStateFlow(runCatching { json.decodeFromString<MemoryData>(file.readText()) }.getOrDefault(MemoryData()))
    val data: StateFlow<MemoryData> = _data.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val USER_CHAR_BUDGET = 1_500
        const val MEMORY_CHAR_BUDGET = 3_000
        const val ENTRY_MAX_CHARS = 600
        fun budgetOf(t: MemoryTarget) = if (t == MemoryTarget.USER) USER_CHAR_BUDGET else MEMORY_CHAR_BUDGET
    }

    fun entries(t: MemoryTarget): List<String> = if (t == MemoryTarget.USER) _data.value.user else _data.value.notes
    fun usedChars(t: MemoryTarget): Int = entries(t).sumOf { it.length + 1 }

    private fun mutate(f: (MemoryData) -> MemoryData) {
        _data.value = f(_data.value)
        val snap = _data.value
        io.launch { synchronized(file) { runCatching { file.writeText(json.encodeToString(snap)) } } }
    }

    private fun clean(text: String) = text.trim().replace(Regex("\\s*\n\\s*"), " ")

    /**
     * Adds one entry. Returns null on success, or a plain explanation (shown to the agent / user)
     * when the entry is blank, a duplicate, too long, or over the section budget.
     */
    fun add(t: MemoryTarget, raw: String): String? {
        val text = clean(raw)
        if (text.isBlank()) return "The entry is empty."
        if (text.length > ENTRY_MAX_CHARS) return "That entry is ${text.length} characters; the limit is $ENTRY_MAX_CHARS. Save a shorter, denser version."
        val cur = entries(t)
        if (cur.any { it.equals(text, ignoreCase = true) }) return "Already saved: \"$text\""
        if (usedChars(t) + text.length + 1 > budgetOf(t))
            return "The ${t.name.lowercase()} memory is full (${usedChars(t)}/${budgetOf(t)} characters). Replace an outdated entry (memory_replace) or remove one (memory_remove) first, then add."
        mutate { d -> if (t == MemoryTarget.USER) d.copy(user = d.user + text) else d.copy(notes = d.notes + text) }
        return null
    }

    /** Index of the entry that equals or contains [old] (case-insensitive), or -1. Used by agent tools. */
    fun find(t: MemoryTarget, old: String): Int {
        val needle = clean(old).lowercase()
        if (needle.isBlank()) return -1
        val cur = entries(t)
        cur.indexOfFirst { it.equals(needle, ignoreCase = true) }.let { if (it >= 0) return it }
        return cur.indexOfFirst { it.lowercase().contains(needle) }
    }

    /** Replaces the entry matching [old] with [new]. Null on success, else a plain explanation. */
    fun replace(t: MemoryTarget, old: String, newRaw: String): String? {
        val idx = find(t, old)
        if (idx < 0) return "No ${t.name.lowercase()} memory entry matches \"$old\". Use memory_read to see what is saved."
        val text = clean(newRaw)
        if (text.isBlank()) return "The replacement is empty — use memory_remove to delete the entry instead."
        if (text.length > ENTRY_MAX_CHARS) return "That entry is ${text.length} characters; the limit is $ENTRY_MAX_CHARS. Save a shorter, denser version."
        val cur = entries(t)
        val others = cur.filterIndexed { i, _ -> i != idx }
        if (others.sumOf { it.length + 1 } + text.length + 1 > budgetOf(t))
            return "The ${t.name.lowercase()} memory is full (${budgetOf(t)} characters). Shorten the entry or remove something else first."
        mutate { d ->
            if (t == MemoryTarget.USER) d.copy(user = cur.toMutableList().also { it[idx] = text })
            else d.copy(notes = cur.toMutableList().also { it[idx] = text })
        }
        return null
    }

    /** Removes the entry matching [old]. Null on success, else a plain explanation. */
    fun remove(t: MemoryTarget, old: String): String? {
        val idx = find(t, old)
        if (idx < 0) return "No ${t.name.lowercase()} memory entry matches \"$old\". Use memory_read to see what is saved."
        mutate { d -> if (t == MemoryTarget.USER) d.copy(user = d.user.filterIndexed { i, _ -> i != idx }) else d.copy(notes = d.notes.filterIndexed { i, _ -> i != idx }) }
        return null
    }

    // ---- UI helpers (Settings → Memory): index-based, budget rules still apply to text length. ----

    fun removeAt(t: MemoryTarget, index: Int) {
        val cur = entries(t); if (index !in cur.indices) return
        mutate { d -> if (t == MemoryTarget.USER) d.copy(user = cur.filterIndexed { i, _ -> i != index }) else d.copy(notes = cur.filterIndexed { i, _ -> i != index }) }
    }

    fun replaceAt(t: MemoryTarget, index: Int, raw: String): String? {
        val cur = entries(t); if (index !in cur.indices) return "That entry no longer exists."
        val text = clean(raw)
        if (text.isBlank()) return "The entry is empty."
        if (text.length > ENTRY_MAX_CHARS) return "Keep it under $ENTRY_MAX_CHARS characters."
        mutate { d -> if (t == MemoryTarget.USER) d.copy(user = cur.toMutableList().also { it[index] = text }) else d.copy(notes = cur.toMutableList().also { it[index] = text }) }
        return null
    }

    fun clear(t: MemoryTarget) = mutate { d -> if (t == MemoryTarget.USER) d.copy(user = emptyList()) else d.copy(notes = emptyList()) }

    /**
     * The snapshot injected into every system prompt. Empty string when nothing is saved yet,
     * so a fresh install pays no tokens for it.
     */
    fun promptBlock(): String {
        val d = _data.value
        if (d.user.isEmpty() && d.notes.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("WHAT YOU REMEMBER ABOUT THE USER (saved by you in earlier chats with the memory tools — trust it, personalise with it, and update it when it changes):")
            if (d.user.isNotEmpty()) { appendLine("[About the user]"); d.user.forEach { appendLine("- $it") } }
            if (d.notes.isNotEmpty()) { appendLine("[Facts, lessons & project notes]"); d.notes.forEach { appendLine("- $it") } }
        }
    }
}
