package com.mazharansari.axion.antigravity

import com.mazharansari.axion.linux.LinuxRuntime
import com.mazharansari.axion.linux.NativeSpawnProcess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile

sealed interface AgyLoginState {
    data object Idle : AgyLoginState
    data class Starting(val message: String) : AgyLoginState
    data class AwaitingCode(val url: String) : AgyLoginState
    data class Completing(val message: String) : AgyLoginState
    data class SignedIn(val email: String?) : AgyLoginState
    data class Error(val message: String) : AgyLoginState
}

/**
 * Google sign-in for a NEW Antigravity account — Kodex's AntigravityAuthController.beginLogin()/submitCode() logic, ported:
 * runs agy in a real PTY, answers its terminal handshake, picks "1. Google OAuth", scrapes the Google URL (the user opens it,
 * signs in, and pastes the one-time code back), auto-answers the colour/rendering/privacy (opt-out)/trust screens, reads the
 * signed-in email, then /quit. agy writes its token into a staging folder that becomes the account's own folder.
 * Axion never reads the token.
 */
class AgyLogin(private val linux: LinuxRuntime, private val accounts: AgyAccounts) {
    private val _state = MutableStateFlow<AgyLoginState>(AgyLoginState.Idle)
    val state: StateFlow<AgyLoginState> = _state.asStateFlow()
    @Volatile private var process: NativeSpawnProcess? = null
    private val staging get() = File(accounts.credRoot, "login-staging")

    suspend fun begin() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext
        _state.value = AgyLoginState.Starting("Starting Google sign-in…")
        staging.deleteRecursively(); staging.mkdirs()
        val out = File(linux.home, "agy-login.log").apply { delete() }
        val running = try {
            linux.spawn(
                listOf("/root/.local/bin/agy"), guestCwd = "/workspace", binds = listOf(staging.absolutePath to "/root/.gemini/antigravity-cli"),
                // SSH_CONNECTION makes agy choose its manual "open this URL, paste the code" flow (Kodex).
                env = mapOf("SSH_CONNECTION" to "127.0.0.1 1 127.0.0.1 1", "TERM" to "xterm-256color", "NO_COLOR" to "1"),
                pty = true, outputFile = out,
            )
        } catch (e: Throwable) { _state.value = AgyLoginState.Error(e.message ?: "Could not start Antigravity"); return@withContext }
        process = running
        var offset = 0L; val output = StringBuilder()
        var handshakes = 0; var lastHandshakeAt = 0
        var menu = false; var color = false; var render = false; var privacy = false; var trust = false
        fun send(s: String) { runCatching { running.outputStream.write(s.toByteArray()); running.outputStream.flush() } }
        try {
            val deadline = System.currentTimeMillis() + 15 * 60_000L
            while (running.isAlive || out.length() > offset) {
                ensureActive()
                if (System.currentTimeMillis() > deadline) error("Sign-in timed out. Start again.")
                val available = out.length() - offset
                if (available <= 0) { delay(80); continue }
                val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                val n = RandomAccessFile(out, "r").use { f -> f.seek(offset); f.read(bytes) }
                if (n <= 0) continue
                offset += n; output.append(bytes.decodeToString(0, n))
                val clean = AgyScreens.sanitize(output.toString()).takeLast(40_000)
                if (handshakes < 5 && output.length - lastHandshakeAt > 200 && output.substring(lastHandshakeAt).contains("\u001B[?u")) {
                    send(AgyScreens.HANDSHAKE_REPLY); handshakes++; lastHandshakeAt = output.length
                }
                if (handshakes > 0 && !menu && clean.contains("Select login method", true) && clean.contains("> 1. Google OAuth", true) && AgyScreens.extractGoogleOAuthUrl(clean) == null) {
                    send("\r\n"); menu = true   // Ink runs raw mode: CR LF is Enter
                    _state.value = AgyLoginState.Starting("Google selected — waiting for the sign-in link…")
                }
                val url = AgyScreens.extractGoogleOAuthUrl(clean)
                if (url != null && _state.value is AgyLoginState.Starting) _state.value = AgyLoginState.AwaitingCode(url)
                if (AgyScreens.isSignedInScreen(clean)) {
                    val email = AgyScreens.extractSignedInEmail(clean)
                    send("/quit\r\n")
                    repeat(30) { if (running.isAlive) delay(50) }
                    if (running.isAlive) running.destroy()
                    if (email != null) accounts.recordSignedIn(email, staging)
                    _state.value = AgyLoginState.SignedIn(email)
                    break
                }
                if (!color && clean.contains("Choose your color scheme", true)) { send("\r\n"); color = true }
                if (!render && clean.contains("no flickering (altscreen)", true) && clean.contains("Native Terminal experience (inline)", true)) { send("\u001B[B\r\n"); render = true }
                if (!privacy && clean.contains("Terms of Service & Data Use", true) && clean.contains("help improve Antigravity CLI", true)) {
                    send(if (clean.contains("[x] Yes", true)) " \t\t\r\n" else "\t\t\r\n"); privacy = true   // opt OUT of data collection
                    _state.value = AgyLoginState.Completing("Google connected — finishing setup…")
                }
                if (!trust && clean.contains("Do you trust the contents of this project", true) && clean.contains("Yes, I trust this folder", true)) { send("\r\n"); trust = true }
                if (clean.contains("authentication failed", true) || clean.contains("failed to exchange", true)) error("Google sign-in failed. Start again and paste a fresh code.")
            }
            if (_state.value is AgyLoginState.Starting) error("Antigravity closed before showing the sign-in link (exit ${running.waitFor()}).")
        } catch (e: CancellationException) { _state.value = AgyLoginState.Idle; throw e
        } catch (e: Throwable) {
            if (_state.value !is AgyLoginState.SignedIn) _state.value = AgyLoginState.Error(e.message?.take(240) ?: "Sign-in failed")
        } finally {
            if (running.isAlive) running.destroy()
            runCatching { running.outputStream.close() }
            out.delete()   // the sign-in terminal is ephemeral
            process = null
            if (_state.value !is AgyLoginState.SignedIn) staging.deleteRecursively()
        }
    }

    fun submitCode(code: String) {
        val v = code.trim()
        require(v.isNotBlank()) { "Paste the code from Google" }
        val p = process ?: error("Start sign-in again")
        check(p.isAlive) { "The sign-in expired. Start again." }
        p.outputStream.write((v + "\r\n").toByteArray()); p.outputStream.flush()
        _state.value = AgyLoginState.Completing("Completing Google sign-in…")
    }

    fun cancel() { process?.destroy(); _state.value = AgyLoginState.Idle }
    fun reset() { if (process == null) _state.value = AgyLoginState.Idle }
}

/** Kodex's terminal-screen helpers, verbatim (pure, unit-tested). */
object AgyScreens {
    const val HANDSHAKE_REPLY = "\u001B[?2026;1\$y\u001B[?2027;1\$y\u001B[?1u\n"
    private val ANSI = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")
    private val GOOGLE_OAUTH_URL = Regex("https://accounts\\.google\\.com/[^\\s\\\"'<>]*?[?&]state=[A-Za-z0-9._~-]+")
    private val URL_CHARS = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" + "-._~:/?#[]@!$&'()*+,;=%").toSet()

    fun sanitize(text: String): String = text.replace(ANSI, "").filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }

    fun extractGoogleOAuthUrl(output: String): String? {
        val compact = output.replace(Regex("[\\r\\n\\t ]+"), "")
        GOOGLE_OAUTH_URL.find(compact)?.value?.takeIf { "client_id=" in it && "code_challenge=" in it }?.let { return it }
        if (!output.contains("Select login method", true) &&
            (output.contains("browser", true) || output.contains("visit", true) || output.contains("open", true) || output.contains("code", true) || output.contains("paste", true))) {
            val c = Regex("https://[^\\s\"']{20,}").findAll(compact).map { it.value.trimEnd { ch -> ch !in URL_CHARS } }.filter { it.length >= 30 && "." in it }.toList()
            return c.firstOrNull { "google" in it } ?: c.firstOrNull()
        }
        return null
    }

    fun isSignedInScreen(output: String): Boolean = output.contains("for shortcuts", true) || (output.contains("Antigravity CLI", true) && output.contains("Google AI", true))

    fun extractSignedInEmail(output: String): String? =
        Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE).findAll(output).map { it.value }
            .firstOrNull { !it.endsWith(".apps.googleusercontent.com", true) }
}
