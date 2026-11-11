package com.mazharansari.axion.linux

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile

/**
 * ONE persistent bash inside the PRoot Ubuntu, shared by the agent (shell_session tool) and the Terminal screen.
 * run_command stays one-shot; this session is a real login-less shell that stays alive between commands, so
 * `cd`, exported variables, shell functions and background state survive — like a terminal the user left open.
 *
 * Protocol (pipes, no PTY — output stays clean and parseable): after the user's command we append two lines,
 * `echo __AXION_DONE_<n>_$?` and `pwd`. The marker line gives the real exit code, the next line the new working
 * directory. A command that outlives its timeout kills the session (the only reliable interrupt over pipes);
 * the next command starts a fresh shell in the last known directory — files always survive, shell state does not.
 */
class ShellSession(private val linux: LinuxRuntime) {
    data class Result(val exitCode: Int, val output: String, val cwd: String, val timedOut: Boolean = false, val error: String? = null)

    private val mutex = Mutex()
    @Volatile private var proc: NativeSpawnProcess? = null
    @Volatile private var outFile: File? = null
    private var seq = 0

    private val _cwd = MutableStateFlow("/workspace")
    val cwdFlow: StateFlow<String> = _cwd.asStateFlow()
    val cwd: String get() = _cwd.value
    private val _running = MutableStateFlow(false); val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _busy = MutableStateFlow(false); val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _transcript = MutableStateFlow(""); val transcript: StateFlow<String> = _transcript.asStateFlow()

    val isAlive: Boolean get() = proc?.let { runCatching { it.isAlive }.getOrDefault(false) } == true

    private fun appendTranscript(s: String) {
        if (s.isEmpty()) return
        _transcript.value = (_transcript.value + s).takeLast(80_000)
    }

    private fun writeLocked(s: String) {
        val p = proc ?: return
        runCatching { p.outputStream.write(s.toByteArray()); p.outputStream.flush() }
    }

    private fun startLocked() {
        stopLocked()
        val f = File(linux.home, "shell-session.log").apply { parentFile?.mkdirs(); writeText("") }
        outFile = f
        proc = linux.spawn(listOf("/bin/bash", "--noprofile", "--norc"), guestCwd = cwd, outputFile = f, pty = false)
        _running.value = true
        writeLocked("export AXION_SESSION=1\n")
    }

    private fun stopLocked() {
        runCatching { proc?.destroyForcibly() }
        proc = null
        _running.value = false
    }

    /** The Terminal screen's Stop button: kills whatever is running; the session restarts on the next command. */
    fun stopCurrent() {
        runCatching { proc?.destroyForcibly() }
        proc = null; _running.value = false
    }

    /** A brand-new shell in /workspace (variables and directory forgotten, files kept). */
    suspend fun reset() = mutex.withLock {
        stopLocked()
        _cwd.value = "/workspace"
        _transcript.value = ""
        appendTranscript("[session reset]\n")
    }

    suspend fun exec(command: String, timeoutMs: Long = 120_000): Result = mutex.withLock {
        if (!linux.isInstalled()) return@withLock Result(-2, "", cwd, error = "Linux is not installed yet. The user can install it in Settings → Developer tools; until then there is no shell session (run_command still works with the basic Android shell).")
        _busy.value = true
        try { execLocked(command, timeoutMs) } finally { _busy.value = false }
    }

    private suspend fun execLocked(command: String, timeoutMs: Long): Result {
        if (!isAlive) {
            runCatching { startLocked() }.getOrElse {
                return Result(-2, "", cwd, error = "Could not start the Linux shell: ${it.message}")
            }
        }
        val f = outFile ?: return Result(-2, "", cwd, error = "Session output file missing.")
        val id = ++seq
        val marker = "__AXION_DONE_${id}__"
        appendTranscript("$ $command\n")
        var offset = f.length()
        // the bare `echo` first: it terminates any partial line the command left open, so the marker starts on its own line
        writeLocked(command.trimEnd('\n') + "\necho\necho " + marker + "_\$?\npwd\n")
        val outText = StringBuilder()
        val partial = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        var exitCode: Int? = null
        var markerSeen = false
        var markerAt = 0L
        while (true) {
            val len = f.length()
            if (len > offset) {
                val bytes = RandomAccessFile(f, "r").use { raf ->
                    raf.seek(offset)
                    ByteArray(minOf(len - offset, 65536L).toInt()).also { raf.readFully(it) }
                }
                offset += bytes.size
                partial.append(bytes.decodeToString())
                var nl = partial.indexOf("\n")
                while (nl >= 0) {
                    val line = partial.substring(0, nl).trimEnd('\r'); partial.delete(0, nl + 1)
                    when {
                        line.startsWith(marker + "_") -> { markerSeen = true; markerAt = System.currentTimeMillis(); exitCode = line.removePrefix(marker + "_").trim().toIntOrNull() ?: 0 }
                        markerSeen && exitCode != null -> { if (line.startsWith("/")) _cwd.value = line.trim(); return Result(exitCode ?: 0, outText.toString(), cwd) }
                        else -> if (!markerSeen) { outText.append(line).append('\n'); appendTranscript(line + "\n") }
                    }
                    nl = partial.indexOf("\n")
                }
                continue
            }
            if (markerSeen && System.currentTimeMillis() - markerAt > 600) return Result(exitCode ?: 0, outText.toString(), cwd)   // pwd line lost (rare); the command itself finished
            if (!isAlive) {
                stopLocked()
                return Result(-3, outText.toString(), cwd, error = "The shell ended during the command (the command may have exited the shell, or the process was killed). The session restarts on the next call.")
            }
            if (System.currentTimeMillis() > deadline) {
                stopLocked()
                return Result(-1, outText.toString(), cwd, timedOut = true, error = "Timed out after ${timeoutMs / 1000} s — the session was restarted in $cwd (directory kept, variables reset, files kept). Use run_background for commands that run for many minutes.")
            }
            delay(100)
        }
    }
}
