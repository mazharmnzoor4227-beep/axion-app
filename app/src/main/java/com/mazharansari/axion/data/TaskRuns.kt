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
 * v0.12: the one long-task run currently in flight (or the last one). Persisted
 * so that if Android kills the app mid-task, the next start can see "a long task
 * was interrupted" instead of the work vanishing silently — the chat itself
 * holds the steps done so far, and the user can open it and say "continue".
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
)

class TaskRunStore(ctx: Context) {
    private val file = File(ctx.filesDir, "task_run.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _current = MutableStateFlow(runCatching { json.decodeFromString<TaskRunRecord>(file.readText()) }.getOrNull())
    val current: StateFlow<TaskRunRecord?> = _current.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun save(rec: TaskRunRecord?) {
        _current.value = rec
        io.launch {
            synchronized(file) {
                runCatching { if (rec == null) file.delete() else file.writeText(json.encodeToString(rec)) }
            }
        }
    }

    fun start(goal: String, conversationId: String) {
        val now = System.currentTimeMillis()
        save(TaskRunRecord(goal.take(200), conversationId, now, now))
    }

    fun progress(stepsDone: Int) {
        val cur = _current.value ?: return
        if (cur.status != "running") return
        save(cur.copy(stepsDone = stepsDone, updatedAt = System.currentTimeMillis()))
    }

    fun finish(status: String) {
        val cur = _current.value ?: return
        save(cur.copy(status = status, updatedAt = System.currentTimeMillis()))
    }

    fun clear() = save(null)
}
