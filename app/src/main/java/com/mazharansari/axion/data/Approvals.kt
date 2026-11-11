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

/**
 * One remembered approval (v0.12): "this kind of command may run without asking".
 * [pattern] is the command kind the ApprovalGate derives (program + subcommand,
 * e.g. "git status", "npm run", "python3"); [example] is one real command the
 * user approved, kept only so Settings → Approvals can show what the rule means.
 */
@Serializable
data class ApprovalRule(
    val pattern: String,
    val example: String = "",
    val createdAt: Long = 0,
)

/**
 * v0.12 (Hermes-style remembered approvals): the persistent list of command kinds
 * the user has chosen to always allow. Same JSON-file pattern as [MemoryStore].
 * The bank/payment + destructive denylist is NOT stored here and can never be
 * added here — the gate checks it first and always asks (see ApprovalGate).
 */
class ApprovalsStore(ctx: Context) {
    private val file = File(ctx.filesDir, "approvals.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _rules = MutableStateFlow(runCatching { json.decodeFromString<List<ApprovalRule>>(file.readText()) }.getOrDefault(emptyList()))
    val rules: StateFlow<List<ApprovalRule>> = _rules.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun mutate(f: (List<ApprovalRule>) -> List<ApprovalRule>) {
        _rules.value = f(_rules.value)
        val snap = _rules.value
        io.launch { synchronized(file) { runCatching { file.writeText(json.encodeToString(snap)) } } }
    }

    /** True when a remembered rule covers this exact command kind. */
    fun matches(pattern: String): Boolean =
        pattern.isNotBlank() && _rules.value.any { it.pattern == pattern }

    fun add(pattern: String, example: String) {
        if (pattern.isBlank()) return
        if (matches(pattern)) return
        mutate { it + ApprovalRule(pattern, example.take(200), System.currentTimeMillis()) }
    }

    fun remove(pattern: String) = mutate { list -> list.filterNot { it.pattern == pattern } }

    fun clear() = mutate { emptyList() }
}
