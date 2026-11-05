package com.mazharansari.axion.agent

import com.mazharansari.axion.data.StepStatus

/**
 * v0.6 (Phase 1.4) what a Bash card says about a command: running / done / failed (with the exit code) / timed out / stopped.
 * Pure Kotlin (CommandStatusTest). Older saved steps carry no exitCode, so it is also read from the "[exit code N]" /
 * "[timed out after Ns]" line RunCommandTool puts at the top of the output.
 */
object CommandStatusLogic {
    enum class Kind { RUNNING, OK, FAILED, TIMEOUT, STOPPED }
    data class Status(val kind: Kind, val label: String, val exit: Int?)

    private val EXIT = Regex("^\\[exit code (-?\\d+)]\\s*\\n?")
    private val TIMEOUT = Regex("^\\[timed out after (\\d+)s]\\s*\\n?")

    fun status(step: StepStatus, exitCode: Int?, output: String): Status {
        if (step == StepStatus.RUNNING) return Status(Kind.RUNNING, "Running…", null)
        val head = output.trimStart()
        if (head.startsWith("Stopped") && output.length < 40) return Status(Kind.STOPPED, "Stopped", null)
        TIMEOUT.find(head)?.let { return Status(Kind.TIMEOUT, "Timed out after ${it.groupValues[1]} s", -1) }
        val code = exitCode ?: EXIT.find(head)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            code == -1 -> Status(Kind.TIMEOUT, "Timed out", -1)
            code != null && code != 0 -> Status(Kind.FAILED, "Failed · exit $code", code)
            step == StepStatus.ERROR -> Status(Kind.FAILED, "Failed", code)
            else -> Status(Kind.OK, "Done · exit 0", 0)
        }
    }

    /** Output without the bracketed status line (the chip shows it instead). */
    fun body(output: String): String = output.trimStart().let { TIMEOUT.replaceFirst(EXIT.replaceFirst(it, ""), "") }

    /** The last [max] lines (and how many were hidden): long builds stay readable in the chat. */
    fun tail(text: String, max: Int = 200): Pair<String, Int> {
        val lines = text.lines()
        return if (lines.size <= max) text to 0 else lines.takeLast(max).joinToString("\n") to (lines.size - max)
    }
}
