package com.mazharansari.axion.data

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

/** States of a persisted background job. "interrupted" = the app was force-stopped / the phone rebooted while it ran. */
object JobState {
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val INTERRUPTED = "interrupted"
}

/** One background job, persisted so it outlives the app process (P2-3). The log file stays in the app cache. */
@Serializable
data class JobRecord(
    val id: Int,
    val command: String,
    val logPath: String,
    val state: String = JobState.RUNNING,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val exitCode: Int? = null,
)

/** Reads the tail of a job log file (works after a restart — the file is just a file). */
fun readLogTail(logPath: String, maxChars: Int = 8000): String = runCatching {
    val t = File(logPath).readText()
    if (t.length <= maxChars) t else "…[beginning cut]\n" + t.takeLast(maxChars)
}.getOrDefault("")

/**
 * P2-3 (ME-16): background jobs survive process death. Every job started with
 * run_background gets a record here (command, log path, state); the in-memory
 * process is gone after a force-stop or reboot, but the record — and its log —
 * is not. On the next app start [markAllRunningInterrupted] turns every record
 * still marked "running" into "interrupted" (the process died with the app),
 * and the Terminal screen + job_output offer to re-run it.
 */
class JobRecordStore(dir: File) {
    private val file = File(dir, "background_jobs.json").apply { parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<JobRecord>> = _records.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maxKept = 100

    private fun load(): List<JobRecord> =
        runCatching { json.decodeFromString<List<JobRecord>>(file.readText()) }.getOrDefault(emptyList())

    private fun save(list: List<JobRecord>) {
        _records.value = list
        val snap = list
        io.launch { synchronized(file) { runCatching { file.writeText(json.encodeToString(snap)) } } }
    }

    fun get(id: Int): JobRecord? = _records.value.firstOrNull { it.id == id }

    fun recordStart(id: Int, command: String, logPath: String) {
        val now = System.currentTimeMillis()
        val all = _records.value + JobRecord(id, command.take(2000), logPath, JobState.RUNNING, now)
        // Cap at [maxKept]: drop the oldest finished records first, never a running one.
        val kept = if (all.size <= maxKept) all else {
            val running = all.filter { it.state == JobState.RUNNING }
            val done = all.filter { it.state != JobState.RUNNING }
            running + done.takeLast((maxKept - running.size).coerceAtLeast(0))
        }
        save(kept)
    }

    /** A job that ended: exit code 0 = done, anything else (or unknown) = failed. */
    fun recordFinish(id: Int, exitCode: Int?) {
        val cur = get(id) ?: return
        if (cur.state != JobState.RUNNING) return
        save(_records.value.map {
            if (it.id == id) it.copy(state = if (exitCode == 0) JobState.DONE else JobState.FAILED, finishedAt = System.currentTimeMillis(), exitCode = exitCode) else it
        })
    }

    fun markInterrupted(id: Int) {
        val cur = get(id) ?: return
        if (cur.state != JobState.RUNNING) return
        save(_records.value.map { if (it.id == id) it.copy(state = JobState.INTERRUPTED, finishedAt = System.currentTimeMillis()) else it })
    }

    /**
     * App start (after a force-stop / reboot): every record still "running" belongs to a
     * process that is gone for good. Returns the records that were marked interrupted.
     */
    fun markAllRunningInterrupted(): List<JobRecord> {
        val changed = _records.value.filter { it.state == JobState.RUNNING }
        if (changed.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val ids = changed.map { it.id }.toSet()
        save(_records.value.map { if (it.id in ids) it.copy(state = JobState.INTERRUPTED, finishedAt = now) else it })
        return changed.map { it.copy(state = JobState.INTERRUPTED, finishedAt = now) }
    }
}
