package com.mazharansari.axion.agent

import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.linux.BackgroundJobs
import com.mazharansari.axion.linux.LinuxHub
import com.mazharansari.axion.linux.ShellSession
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * The terminal tools that complete run_command: a PERSISTENT shell session (directory + variables survive
 * between calls, like an open terminal) and background jobs for commands that run for many minutes.
 * All of them work inside the same PRoot Ubuntu as run_command (linux/ShellSession, linux/BackgroundJobs).
 */

private fun session(): ShellSession =
    LinuxHub.graph?.shell ?: throw IllegalStateException("The shell session is not available in this context.")

private fun jobs(): BackgroundJobs =
    LinuxHub.graph?.jobs ?: throw IllegalStateException("Background jobs are not available in this context.")

class ShellSessionTool : Tool {
    override val spec = ToolSpec(
        "shell_session",
        "Run a command in the PERSISTENT Linux shell session: unlike run_command (fresh shell every time), this shell stays open — cd, exported variables and shell state carry over to the next shell_session call, exactly like a terminal. Working directory starts at /workspace. Use it for multi-step work (cd into a project, activate a venv, then build). Long output is cut; a command that passes its timeout restarts the session.",
        schema { str("command", "The shell command (may be several lines)"); int("timeout_sec", "Timeout in seconds (default 120, max 600)") },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "Shell", args.s("command").orEmpty().lineSequence().firstOrNull().orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val cmd = args.s("command").orEmpty()
        if (cmd.isBlank()) return ToolOutput("Empty command", isError = true)
        val timeout = (args.i("timeout_sec") ?: 120).coerceIn(1, 600).toLong()
        val r = try { session().exec(cmd, timeout * 1000) } catch (e: Throwable) { return ToolOutput("Shell error: ${e.message}", isError = true) }
        return format(r)
    }

    private fun format(r: ShellSession.Result): ToolOutput {
        if (r.error != null && r.exitCode == -2) return ToolOutput(r.error, isError = true)
        val head = buildString {
            when {
                r.timedOut -> append("[timed out] ")
                r.exitCode != 0 -> append("[exit code ${r.exitCode}] ")
            }
            append("[cwd: ${r.cwd}]")
            if (r.error != null) append("\n${r.error}")
            append("\n")
        }
        val body = r.output.ifEmpty { "(no output)" }
        return ToolOutput((head + body).clip(10000), display = (head + body).clip(20000), isError = r.timedOut || r.exitCode == -3, exitCode = r.exitCode)
    }
}

class ShellResetTool : Tool {
    override val spec = ToolSpec(
        "shell_reset",
        "Restart the persistent shell session: a fresh shell in /workspace (directory and variables forgotten, files kept). Use when the session is wedged or polluted.",
        schema { },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "Reset shell", "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = try {
        session().reset()
        ToolOutput("Shell session reset — fresh shell in /workspace.", doneTitle = "Shell reset")
    } catch (e: Throwable) { ToolOutput("Shell error: ${e.message}", isError = true) }
}

class RunBackgroundTool : Tool {
    override val spec = ToolSpec(
        "run_background",
        "Start a long Linux command in the BACKGROUND (builds, big downloads, servers, long conversions) and get a job number at once, without waiting. The command runs in /workspace inside Axion's Ubuntu. Check it with job_output, stop it with job_kill. For anything under a few minutes, run_command or shell_session is simpler.",
        schema { str("command", "The shell command to run in the background") },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "Background", args.s("command").orEmpty().take(80))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val cmd = args.s("command").orEmpty()
        if (cmd.isBlank()) return ToolOutput("Empty command", isError = true)
        return try {
            val job = jobs().start(cmd)
            ToolOutput("Started background job #${job.id}: $cmd\nCheck it with job_output (job_id ${job.id}).", doneTitle = "Job #${job.id} started")
        } catch (e: Throwable) { ToolOutput("Could not start the job: ${e.message}", isError = true) }
    }
}

class JobOutputTool : Tool {
    override val spec = ToolSpec(
        "job_output",
        "Check a background job started with run_background: whether it is still running, its exit code when finished, and the latest output.",
        schema { int("job_id", "The job number from run_background") },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "Job output", args.i("job_id")?.let { "#$it" } ?: "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val id = args.i("job_id") ?: return ToolOutput("Give the job_id from run_background.", isError = true)
        val job = jobs().get(id) ?: return ToolOutput("No background job #$id in this app session.", isError = true)
        val status = if (job.alive()) "RUNNING (started ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(job.startedAt))})"
            else "FINISHED, exit code ${job.exitCode() ?: "?"}"
        val tail = job.tail().ifEmpty { "(no output yet)" }
        return ToolOutput("Job #${job.id} — $status\nCommand: ${job.command}\nOutput:\n$tail", doneTitle = "Job #${job.id} ${if (job.alive()) "running" else "finished"}")
    }
}

class JobKillTool : Tool {
    override val spec = ToolSpec(
        "job_kill",
        "Stop a background job started with run_background.",
        schema { int("job_id", "The job number from run_background") },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "Stop job", args.i("job_id")?.let { "#$it" } ?: "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val id = args.i("job_id") ?: return ToolOutput("Give the job_id from run_background.", isError = true)
        val job = jobs().get(id) ?: return ToolOutput("No background job #$id in this app session.", isError = true)
        jobs().kill(job)
        return ToolOutput("Job #${job.id} stopped.", doneTitle = "Job stopped")
    }
}
