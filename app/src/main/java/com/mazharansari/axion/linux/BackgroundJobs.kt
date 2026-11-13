package com.mazharansari.axion.linux

import android.app.Application
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Long-running Linux commands that must NOT hold up a chat turn: builds, downloads, servers, conversions.
 * Each job is one `bash -lc` under PRoot writing to its own log file; the agent starts it with run_background,
 * checks it with job_output and stops it with job_kill. Jobs live in memory (they end with the app process,
 * like a terminal you closed) — their log files stay in the cache so the tail can still be read afterwards.
 */
class BackgroundJobs(private val app: Application, private val linux: LinuxRuntime) {

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
        return Job(id, command, System.currentTimeMillis(), out, proc).also { jobs[id] = it }
    }

    fun get(id: Int): Job? = jobs[id]
    fun list(): List<Job> = jobs.values.sortedBy { it.id }
    fun kill(job: Job) { runCatching { job.proc.destroyForcibly() } }
}
