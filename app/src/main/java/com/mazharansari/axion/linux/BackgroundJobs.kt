package com.mazharansari.axion.linux

import android.app.Application
import com.mazharansari.axion.data.JobRecord
import com.mazharansari.axion.data.JobRecordStore
import com.mazharansari.axion.data.JobState
import com.mazharansari.axion.data.readLogTail
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Long-running Linux commands that must NOT hold up a chat turn: builds, downloads, servers, conversions.
 * Each job is one `bash -lc` under PRoot writing to its own log file; the agent starts it with run_background,
 * checks it with job_output and stops it with job_kill. Jobs live in memory (they end with the app process,
 * like a terminal you closed) — their log files stay in the cache so the tail can still be read afterwards.
 *
 * P2-3 (ME-16): every job also gets a persisted [JobRecord] (command, log path, state) in [records], so a
 * force-stop or reboot does not erase it: on the next start [reconcile] marks the jobs that were still
 * "running" as interrupted (their process died with the app), and [reRun] starts the same command again.
 */
class BackgroundJobs(private val app: Application, private val linux: LinuxRuntime, private val records: JobRecordStore? = null) {

    class Job(val id: Int, val command: String, val startedAt: Long, val outFile: File, val proc: NativeSpawnProcess) {
        fun alive(): Boolean = runCatching { proc.isAlive }.getOrDefault(false)
        fun exitCode(): Int? = if (alive()) null else runCatching { proc.exitValue() }.getOrNull()
        fun tail(maxChars: Int = 8000): String = runCatching {
            val t = outFile.readText()
            if (t.length <= maxChars) t else "…[beginning cut]\n" + t.takeLast(maxChars)
        }.getOrDefault("")
    }

    private val jobs = ConcurrentHashMap<Int, Job>()
    private val ids = AtomicInteger(0)

    fun start(command: String): Job {
        check(linux.isInstalled()) { "Linux is not installed yet — the user can install it in Settings → Developer tools." }
        val id = ids.incrementAndGet()
        val dir = File(app.cacheDir, "axion-jobs").apply { mkdirs() }
        val out = File(dir, "job-$id.log")
        val proc = linux.spawn(listOf("/bin/bash", "-lc", command), guestCwd = "/workspace", outputFile = out)
        records?.recordStart(id, command, out.absolutePath)
        return Job(id, command, System.currentTimeMillis(), out, proc).also { jobs[id] = it }
    }

    fun get(id: Int): Job? {
        val j = jobs[id] ?: return null
        refreshRecord(j)   // P2-3: a dead job's record becomes done/failed the moment anyone looks
        return j
    }

    /** The persisted record for a job (also for jobs from a previous app life — see [reconcile]). */
    fun record(id: Int): JobRecord? = records?.get(id)

    /** All persisted records, newest last, with the live jobs' states refreshed first. */
    fun allRecords(): List<JobRecord> {
        jobs.values.forEach { refreshRecord(it) }
        return records?.records?.value.orEmpty()
    }

    /** The tail of a persisted job's log file — readable even after the app was killed (P2-3). */
    fun logTail(record: JobRecord, maxChars: Int = 8000): String = readLogTail(record.logPath, maxChars)

    /**
     * App start (P2-3): the in-memory map is empty, so every record still "running" belongs to a
     * process that died with the last app life. Returns the records marked interrupted.
     */
    fun reconcile(): List<JobRecord> = records?.markAllRunningInterrupted().orEmpty()

    /** Run a finished, failed or interrupted job again with the same command (a new job number). */
    fun reRun(recordId: Int): Job? {
        val rec = records?.get(recordId) ?: return null
        if (rec.state == JobState.RUNNING) return null   // already running — check it with job_output instead
        return start(rec.command)
    }

    fun list(): List<Job> = jobs.values.sortedBy { it.id }
    fun kill(job: Job) {
        runCatching { job.proc.destroyForcibly() }
        records?.markInterrupted(job.id)   // P2-3: a stopped job reads as interrupted, not as still running
    }

    private fun refreshRecord(j: Job) {
        if (j.alive()) return
        records?.recordFinish(j.id, j.exitCode())
    }
}
