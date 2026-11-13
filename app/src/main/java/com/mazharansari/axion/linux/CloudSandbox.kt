package com.mazharansari.axion.linux

import android.content.Context
import com.mazharansari.axion.data.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * P1-3 'OWN COMPUTER': opt-in cloud sandbox ("Turbo (server)").
 *
 * The on-device PRoot Linux stays the default computer (free, offline, private). When the user
 * switches Turbo on for a task AND has set up their own server in Settings, shell work for that
 * task runs on their own server over SSH instead, and the results sync back to the phone
 * workspace. The tool surface is the same: run_command goes through [CloudSandbox.runInWorkspace],
 * the persistent shell through [TurboShellSession], builds through [com.mazharansari.axion.build.BuildHub]
 * (sync project up, Gradle on the server, sync the APK back), and read/write/list keep working on
 * the local workspace copy, which the sync-back step keeps current.
 *
 * Security rules, all deliberate:
 *  - OFF by default: [TurboSettings.enabled] is false until the user turns it on, and the
 *    per-task composer toggle defaults off too ([com.mazharansari.axion.data.AppSettings.turbo]).
 *  - Credentials live ONLY as KeyVault-encrypted blobs in turbo.json (same vault as API keys);
 *    they are never written in plain text, never logged, and never leave the phone except inside
 *    the SSH handshake itself.
 *  - The server's host key is PINNED on first use: the first connection shows the SHA-256
 *    fingerprint in Settings and only connects once the user taps "Trust this server". A changed
 *    host key afterwards refuses to connect (no promiscuous verifier anywhere).
 *  - This file never connects anywhere on its own: every SSH call starts from a user action
 *    (a Turbo task, a build, or the Test button in Settings).
 */
enum class TurboAuth { PASSWORD, KEY }

@Serializable
data class TurboSettings(
    /** Master switch in Settings. The per-task toggle is separate (AppSettings.turbo). */
    val enabled: Boolean = false,
    /** Host name or IP only — no scheme, no path. */
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val auth: TurboAuth = TurboAuth.PASSWORD,
    /** Folder on the server that mirrors the phone workspace. */
    val remoteWorkspace: String = "~/axion-workspace",
    /** Pinned server host key, "SHA256:<base64>" — set when the user trusts the server. */
    val hostKeyFingerprint: String? = null,
    /** KeyVault-encrypted ("gcm:…") blobs. Never plain text, never shown. */
    val passwordBlob: String? = null,
    val keyBlob: String? = null,
    val passphraseBlob: String? = null,
) {
    /** Pure: every problem with these settings, in plain English for the Settings screen. */
    fun validate(): List<String> = buildList {
        if (host.isBlank()) add("Enter the server address.")
        else if (host.contains("://") || host.any { it.isWhitespace() } || host.contains('/'))
            add("Enter just the host name or IP address, without http:// or a path.")
        if (port !in 1..65535) add("Port must be between 1 and 65535.")
        if (username.isBlank()) add("Enter the SSH username.")
        if (remoteWorkspace.isBlank()) add("Enter the folder on the server to use.")
        when (auth) {
            TurboAuth.PASSWORD -> if (passwordBlob.isNullOrBlank()) add("Enter the server password (it is stored encrypted on this phone).")
            TurboAuth.KEY -> if (keyBlob.isNullOrBlank()) add("Paste the private key (it is stored encrypted on this phone).")
        }
    }

    /** "user@host:port" for display. Contains no secret. */
    fun hostLabel(): String = if (username.isBlank() || host.isBlank()) host.ifBlank { "No server set up" } else "$username@$host:$port"
}

/** Pure shell helpers for the remote side (unit-tested, no SSH needed). */
object TurboCommands {
    /** Single-quote a string for sh: 'it'\''s' — safe for paths and passwords in a command line. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** `cd` into [cwd] (even if the cd fails the marker lines below still run) then run [command]. */
    fun cdCommand(cwd: String, command: String): String = "cd ${shellQuote(cwd)}\n$command"

    /** OpenSSH-style SHA-256 fingerprint of a host key ("SHA256:<base64, no padding>"). */
    fun fingerprint(key: PublicKey): String {
        val sha = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha)
    }
}

/** One JSON file in the app's private files dir (same pattern as DeployTokens). Blobs inside are KeyVault-encrypted. */
class TurboStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<TurboSettings> = _settings.asStateFlow()

    private fun load(): TurboSettings = runCatching { json.decodeFromString<TurboSettings>(file.readText()) }.getOrDefault(TurboSettings())

    fun save(s: TurboSettings) {
        _settings.value = s
        runCatching { file.writeText(json.encodeToString(TurboSettings.serializer(), s)) }
    }

    fun setPassword(plain: String) = save(_settings.value.copy(passwordBlob = if (plain.isBlank()) null else KeyVault.encrypt(plain)))
    fun password(): String? = _settings.value.passwordBlob?.let { KeyVault.decrypt(it) }
    fun setKey(pem: String) = save(_settings.value.copy(keyBlob = if (pem.isBlank()) null else KeyVault.encrypt(pem.trim())))
    fun keyPem(): String? = _settings.value.keyBlob?.let { KeyVault.decrypt(it) }
    fun setPassphrase(plain: String) = save(_settings.value.copy(passphraseBlob = if (plain.isBlank()) null else KeyVault.encrypt(plain)))
    fun passphrase(): String? = _settings.value.passphraseBlob?.let { KeyVault.decrypt(it) }

    /** Forget the saved credentials (the host/port/username stay). */
    fun clearCredentials() = save(_settings.value.copy(passwordBlob = null, keyBlob = null, passphraseBlob = null))
}

/** What the cloud sandbox can do, behind an interface so tests and the UI never touch sshj directly. */
interface RemoteExecutor {
    suspend fun run(command: String, timeoutMs: Long, onLine: (String) -> Unit = {}): Pair<Int, String>
    suspend fun upload(local: File, remotePath: String)
    suspend fun download(remotePath: String, local: File)
    fun close()
}

sealed interface TurboCredentials {
    data class Password(val password: String) : TurboCredentials
    data class Key(val pem: String, val passphrase: String?) : TurboCredentials
}

/**
 * The sshj implementation. One client per [CloudSandbox] call (opened, used, closed) — no pooled
 * connection is kept across tasks, so a dead server never leaves a stale session behind.
 *
 * Host-key verification is fingerprint pinning, never promiscuous: [pinnedFingerprint] must match
 * the key the server presents; when nothing is pinned yet the connection is refused unless
 * [trustFirstUse] is true (the Settings screen passes true only after showing the fingerprint
 * and getting an explicit tap), in which case [onFirstFingerprint] receives it for storage.
 */
class SshjRemoteExecutor(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val credentials: TurboCredentials,
    private val pinnedFingerprint: String?,
    private val trustFirstUse: Boolean = false,
    private val onFirstFingerprint: ((String) -> Unit)? = null,
    private val cacheDir: File? = null,
) : RemoteExecutor {
    private val lock = Mutex()
    private var ssh: SSHClient? = null

    private fun verifier() = object : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val fp = TurboCommands.fingerprint(key)
            if (pinnedFingerprint != null) return pinnedFingerprint == fp
            if (!trustFirstUse) throw IllegalStateException("Unknown server key $fp — open Settings → Turbo (server) and trust this server first.")
            onFirstFingerprint?.invoke(fp)
            return true
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    private suspend fun ensureConnected() = lock.withLock {
        if (ssh?.isConnected == true && ssh?.isAuthenticated == true) return@withLock
        val client = SSHClient()
        try {
            client.addHostKeyVerifier(verifier())
            withContext(Dispatchers.IO) { client.connect(host, port) }
            when (val c = credentials) {
                is TurboCredentials.Password -> client.authPassword(username, c.password)
                is TurboCredentials.Key -> {
                    // sshj loads keys from a file, so the PEM lives briefly in a private temp
                    // file (mode 600, deleted right after auth) — never in the project or the APK.
                    val tmp = File(cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "."), "turbo-key-${System.nanoTime()}.pem")
                    try {
                        tmp.writeText(c.pem)
                        runCatching { tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true) }
                        val provider = if (c.passphrase.isNullOrEmpty()) client.loadKeys(tmp.absolutePath)
                            else client.loadKeys(tmp.absolutePath, c.passphrase.toCharArray())
                        client.authPublickey(username, provider)
                    } finally {
                        tmp.delete()
                    }
                }
            }
            ssh = client
        } catch (e: Throwable) {
            runCatching { client.disconnect() }
            throw e
        }
    }

    override suspend fun run(command: String, timeoutMs: Long, onLine: (String) -> Unit): Pair<Int, String> = withContext(Dispatchers.IO) {
        ensureConnected()
        val client = ssh ?: error("Not connected")
        val session = client.startSession()
        try {
            val cmd = session.exec(command)
            val out = StringBuilder()
            val err = StringBuilder()
            val readers = listOf(
                async { readCapped(cmd.inputStream, out, onLine) },
                async { readCapped(cmd.errorStream, err, {}) },
            )
            // join() waits up to the timeout; a null exit status afterwards means it timed out.
            runCatching { cmd.join(timeoutMs, TimeUnit.MILLISECONDS) }
            val code = cmd.exitStatus ?: -1
            withTimeoutOrNull(2_000) { readers.forEach { it.await() } }
            val text = (out.toString() + if (err.isNotEmpty()) "\n" + err.toString() else "").take(60_000)
            code to text
        } finally {
            runCatching { session.close() }
        }
    }

    private fun readCapped(stream: java.io.InputStream, into: StringBuilder, onLine: (String) -> Unit) {
        val buf = ByteArray(8192)
        val partial = StringBuilder()
        while (true) {
            val n = runCatching { stream.read(buf) }.getOrNull() ?: -1
            if (n < 0) break
            partial.append(buf.decodeToString(0, n))
            var nl = partial.indexOf("\n")
            while (nl >= 0) {
                val line = partial.substring(0, nl).trimEnd('\r'); partial.delete(0, nl + 1)
                if (into.length < 60_000) { into.append(line).append('\n') }
                onLine(line)
                nl = partial.indexOf("\n")
            }
        }
        if (partial.isNotEmpty()) {
            val line = partial.toString()
            if (into.length < 60_000) into.append(line)
            onLine(line)
        }
    }

    override suspend fun upload(local: File, remotePath: String) = withContext(Dispatchers.IO) {
        ensureConnected()
        ssh!!.newSCPFileTransfer().upload(local.absolutePath, remotePath)
    }

    override suspend fun download(remotePath: String, local: File) = withContext(Dispatchers.IO) {
        ensureConnected()
        local.parentFile?.mkdirs()
        ssh!!.newSCPFileTransfer().download(remotePath, local.absolutePath)
    }

    override fun close() {
        runCatching { ssh?.disconnect() }
        ssh = null
    }
}

/**
 * The cloud sandbox itself: settings + the remote execution / sync operations the agent, the
 * shell session and the builder call. On-device Linux is used whenever Turbo is off or not set
 * up, so nothing here can break the normal path.
 */
class CloudSandbox(private val ctx: Context) {
    val store = TurboStore(File(ctx.filesDir, "turbo.json"))
    val settings: StateFlow<TurboSettings> = store.settings

    /** True when Settings has a complete, enabled Turbo setup (credential included). */
    fun isConfigured(): Boolean {
        val s = settings.value
        return s.enabled && s.validate().isEmpty()
    }

    /** The per-task decision: the composer toggle AND a complete setup. */
    fun useTurbo(taskTurbo: Boolean): Boolean = taskTurbo && isConfigured()

    /** Open an executor for [settings]. [trustFirstUse] is true only from the Settings trust flow. */
    fun openExecutor(trustFirstUse: Boolean = false): RemoteExecutor {
        val s = settings.value
        val problems = s.validate()
        check(problems.isEmpty()) { problems.firstOrNull() ?: "Turbo is not set up." }
        val creds: TurboCredentials = when (s.auth) {
            TurboAuth.PASSWORD -> TurboCredentials.Password(store.password() ?: error("No Turbo password saved."))
            TurboAuth.KEY -> TurboCredentials.Key(store.keyPem() ?: error("No Turbo private key saved."), store.passphrase())
        }
        return SshjRemoteExecutor(s.host, s.port, s.username, creds, s.hostKeyFingerprint, trustFirstUse,
            onFirstFingerprint = { fp -> store.save(s.copy(hostKeyFingerprint = fp)) }, cacheDir = ctx.cacheDir)
    }

    /** Run one raw command on the server (no sync). The caller owns the working directory. */
    suspend fun runRaw(command: String, timeoutMs: Long = 120_000, onLine: (String) -> Unit = {}, trustFirstUse: Boolean = false): Pair<Int, String> {
        val ex = openExecutor(trustFirstUse)
        try {
            return ex.run(command, timeoutMs, onLine)
        } finally {
            ex.close()
        }
    }

    /**
     * The run_command path for a Turbo task: mirror the phone workspace up to the server, run the
     * command in the remote workspace, then sync the results back — even when the command fails,
     * so partial results are never stranded on the server. File tools keep reading the local
     * copy, which this keeps current.
     */
    suspend fun runInWorkspace(
        command: String, localWorkspace: File, timeoutMs: Long = 120_000, onLine: (String) -> Unit = {},
    ): Pair<Int, String> {
        val s = settings.value
        val ex = openExecutor()
        try {
            ex.upload(localWorkspace, s.remoteWorkspace)
            val (code, out) = ex.run(TurboCommands.cdCommand(s.remoteWorkspace, command), timeoutMs, onLine)
            return code to out
        } finally {
            runCatching { syncBack(ex, s.remoteWorkspace, localWorkspace) }
            ex.close()
        }
    }

    /** Upload a project folder to the server (builds). Returns the remote project path. */
    suspend fun syncProjectToRemote(localDir: File, remoteDir: String): String {
        val ex = openExecutor()
        try {
            ex.upload(localDir, remoteDir)
            return remoteDir
        } finally {
            ex.close()
        }
    }

    /** Download a remote folder back over the local one (build results, APKs). */
    suspend fun syncProjectBack(remoteDir: String, localDir: File) {
        val ex = openExecutor()
        try {
            syncBack(ex, remoteDir, localDir)
        } finally {
            ex.close()
        }
    }

    /**
     * SCP downloads a directory into the staging folder (sshj may nest it one level deep, as
     * <staging>/<dirname>); [mergeStaged] normalises both shapes into [localDir].
     */
    private suspend fun syncBack(ex: RemoteExecutor, remoteDir: String, localDir: File) = withContext(Dispatchers.IO) {
        val staging = File(localDir.parentFile, ".axion-turbo-staging-${System.nanoTime()}").apply { mkdirs() }
        try {
            ex.download(remoteDir, staging)
            mergeStaged(staging, localDir)
        } finally {
            staging.deleteRecursively()
        }
    }

    internal fun mergeStaged(staging: File, localDir: File) {
        localDir.mkdirs()
        val kids = staging.listFiles().orEmpty()
        val nested = if (kids.size == 1 && kids[0].isDirectory && kids[0].name == localDir.name) kids[0] else staging
        nested.listFiles()?.forEach { src ->
            val dest = File(localDir, src.name)
            if (src.isDirectory) { dest.mkdirs(); src.copyRecursively(dest, overwrite = true) } else src.copyTo(dest, overwrite = true)
        }
    }

    /** Settings → Test button: a real SSH round trip (`echo`) that proves host, auth and host key. */
    suspend fun testConnection(trustFirstUse: Boolean = false): String {
        val (code, out) = runRaw("echo axion-turbo-ok", 30_000, trustFirstUse = trustFirstUse)
        check(code == 0) { "The server answered with exit code $code: ${out.take(300).ifBlank { "no output" }}" }
        check("axion-turbo-ok" in out) { "The server answered, but not as expected: ${out.take(300)}" }
        return "Connected to ${settings.value.hostLabel()}."
    }

    /** The persistent remote shell, used by shell_session when Turbo is on for the task. */
    val turboShell = TurboShellSession(this)
}

/**
 * The remote counterpart of [ShellSession]: one logical shell whose directory survives between
 * calls (kept in [remoteCwd], because every exec is a fresh SSH channel). Same marker protocol
 * as the on-device session: `echo __AXION_TURBO_DONE_<n>_$?` then `pwd`.
 */
class TurboShellSession(private val cloud: CloudSandbox) {
    @Volatile var remoteCwd: String = ""
        private set

    fun reset() {
        remoteCwd = ""
    }

    suspend fun exec(command: String, timeoutMs: Long = 120_000): ShellSession.Result {
        val s = cloud.settings.value
        if (!cloud.isConfigured()) return ShellSession.Result(-2, "", remoteCwd,
            error = "Turbo is not set up yet. Set up your server in Settings → Turbo (server); until then the on-device shell is used.")
        val cwd = remoteCwd.ifBlank { s.remoteWorkspace }
        val marker = "__AXION_TURBO_DONE__"
        val full = "cd ${TurboCommands.shellQuote(cwd)}\n" + command.trimEnd('\n') + "\necho\necho ${marker}_\$?\npwd"
        val (code, out) = try {
            cloud.runRaw(full, timeoutMs)
        } catch (e: Throwable) {
            return ShellSession.Result(-2, "", cwd, error = "Turbo shell error: ${e.message}")
        }
        if (code == -1) return ShellSession.Result(-1, out, cwd, timedOut = true,
            error = "Timed out after ${timeoutMs / 1000} s on the server. Use run_background for commands that run for many minutes.")
        var exitCode = code
        val body = StringBuilder()
        val lines = out.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith(marker + "_")) {
                exitCode = line.removePrefix(marker + "_").trim().toIntOrNull() ?: exitCode
                // The next line is pwd — the shell's new directory.
                val next = lines.getOrNull(i + 1)
                if (next != null && next.startsWith("/")) remoteCwd = next.trim()
                break
            }
            body.append(line).append('\n')
            i++
        }
        return ShellSession.Result(exitCode, body.toString(), remoteCwd.ifBlank { cwd })
    }
}
