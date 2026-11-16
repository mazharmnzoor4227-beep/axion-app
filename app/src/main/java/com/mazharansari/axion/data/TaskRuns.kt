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
 * v0.12 (extended Phase 2): every run in flight, persisted — not just the one long task.
 * If Android kills the app mid-run, the next start sees "a run was interrupted" instead of
 * the work vanishing silently — the chat itself holds the steps done so far, and the user
 * can open it and say "continue" (see ChatViewModel.interruptedTask).
 *
 * Honest platform limits: this record makes a run RESUMABLE with one tap — it cannot keep
 * the run itself going without its process. A user/system FORCE-STOP kills everything and
 * no app can survive that; swipe-away and normal process death are covered because the
 * record is on disk before the run starts.
 */
@Serializable
data class TaskRunRecord(
    val goal: String,
    val conversationId: String,
    val startedAt: Long,
    val updatedAt: Long,
    val stepsDone: Int = 0,
    /** running | done | interrupted */
    val status: String = "running",
    /** Phase 2: "long" (v0.12 long task) | "ask" (a chat turn) | "build" (a Build turn). */
    val kind: String = "long",
)

class TaskRunStore(ctx: Context) {
    private val file = File(ctx.filesDir, "task_runs.json")
    private val legacyFile = File(ctx.filesDir, "task_run.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Newest first, at most [MAX_RECORDS] — one per (kind, conversation). */
    private val _records = MutableStateFlow(loadInitial())
    val records: StateFlow<List<TaskRunRecord>> = _records.asStateFlow()

    /** Back-compat: the most recent long-task record (what v0.12 callers read). */
    private val _current = MutableStateFlow(_records.value.firstOrNull { it.kind == "long" })
    val current: StateFlow<TaskRunRecord?> = _current.asStateFlow()

    private fun loadInitial(): List<TaskRunRecord> {
        runCatching { if (file.isFile) return json.decodeFromString<List<TaskRunRecord>>(file.readText()) }.getOrNull()
        // v0.12 single-record file: import it once, then it never comes back.
        runCatching {
            if (legacyFile.isFile) {
                val rec = json.decodeFromString<TaskRunRecord>(legacyFile.readText())
                legacyFile.delete()
                return listOf(rec)
            }
        }
        return emptyList()
    }

    private fun persist() {
        io.launch {
            val snapshot = synchronized(lock) { _records.value.toList() }
            synchronized(file) { runCatching { file.writeText(json.encodeToString(snapshot)) } }
        }
    }

    private fun updateRecords(transform: (List<TaskRunRecord>) -> List<TaskRunRecord>) {
        synchronized(lock) {
            val next = transform(_records.value).take(MAX_RECORDS)
            _records.value = next
            _current.value = next.firstOrNull { it.kind == "long" }
        }
        persist()
    }

    /** v0.12 entry point: start a long task. */
    fun start(goal: String, conversationId: String) = start("long", goal, conversationId)

    fun start(kind: String, goal: String, conversationId: String) {
        val now = System.currentTimeMillis()
        updateRecords { list ->
            val rec = TaskRunRecord(goal.take(200), conversationId, now, now, kind = kind)
            listOf(rec) + list.filterNot { it.kind == kind && it.conversationId == conversationId }
        }
    }

    /** v0.12 entry point: step progress on the long task. */
    fun progress(stepsDone: Int) {
        val cur = _current.value ?: return
        progress(cur.kind, cur.conversationId, stepsDone)
    }

    fun progress(kind: String, conversationId: String, stepsDone: Int) {
        updateRecords { list ->
            list.map {
                if (it.kind == kind && it.conversationId == conversationId && it.status == "running")
                    it.copy(stepsDone = stepsDone, updatedAt = System.currentTimeMillis()) else it
            }
        }
    }

    /** v0.12 entry point: settle the long task. */
    fun finish(status: String) {
        val cur = _current.value ?: return
        finish(cur.kind, cur.conversationId, status)
    }

    fun finish(kind: String, conversationId: String, status: String) {
        updateRecords { list ->
            list.map {
                if (it.kind == kind && it.conversationId == conversationId && it.status == "running")
                    it.copy(status = status, updatedAt = System.currentTimeMillis()) else it
            }
        }
    }

    /**
     * App start: every record still marked running died with the process. Mark them all
     * interrupted (so they are never offered twice) and return them, most recent first.
     */
    fun markAllRunningInterrupted(): List<TaskRunRecord> {
        val now = System.currentTimeMillis()
        var out: List<TaskRunRecord> = emptyList()
        updateRecords { list ->
            out = list.filter { it.status == "running" }.map { it.copy(status = "interrupted", updatedAt = now) }
            out + list.filter { it.status != "running" }
        }
        return out
    }

    /** Forget one interrupted record (the user dismissed its banner). */
    fun remove(record: TaskRunRecord) {
        updateRecords { list -> list.filterNot { it.kind == record.kind && it.conversationId == record.conversationId && it.startedAt == record.startedAt } }
    }

    companion object {
        const val MAX_RECORDS = 4
    }
}
