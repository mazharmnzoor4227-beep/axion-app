package com.mazharansari.axion.linux

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The Developer tools catalog — Kodex's toolchains (Web, Python, Android, C/C++, PHP + Composer, Browser automation).
 * Sizes are what each install measured on the test box (Ubuntu 24.04 under PRoot, 1 Nov 2026); a phone's arm64 build is within ~10 %.
 */
/** diskMb = measured on 1 Nov 2026 in Ubuntu 24.04 under PRoot (x86_64 run; arm64 python/agy matched within 1 %). */
enum class Toolchain(
    val id: String, val title: String, val tech: String, val blurb: String,
    val downloadMb: Int, val diskMb: Int, val needs: List<String> = emptyList(), val hidden: Boolean = false,
) {
    WEB("web", "Web", "JavaScript · TypeScript · Node.js", "Node.js LTS with npm, pnpm and yarn. Websites, React/Vite, scripts.", 33, 330),
    PYTHON("python", "Python", "Python 3 · pip · venv", "Python 3 with pip and venv for scripts, data and automation.", 25, 80),
    ANDROID("android", "Android", "Java · Kotlin", "OpenJDK 17 and the Kotlin compiler. Android SDK + Gradle for APK builds come with Build mode.", 160, 360),
    CPP("cpp", "C/C++", "gcc · g++ · make · cmake", "Compilers and build tools for C and C++.", 60, 165),
    PHP("php", "PHP", "PHP 8.4 · Composer", "PHP with common extensions and Composer.", 12, 25),
    BROWSER("browser", "Browser automation", "Chromium · Puppeteer · Playwright", "Headless Chromium for screenshots, scraping and testing websites. Needs Web.", 190, 330, needs = listOf("web")),
    // v0.5 (addendum 2): APK builds + the optional extras from the Build first-run popup
    ANDROIDSDK("androidsdk", "Android APK builder", "Android SDK 36 · Gradle 8.11", "Builds real APKs inside Build projects. ~210 MB download; Gradle libraries add ~1 GB on the first build, which is slow on a phone.", 210, 1500, needs = listOf("android")),
    GO("go", "Go", "Go toolchain", "The official Go compiler for command-line tools and servers.", 75, 240),
    RUST("rust", "Rust", "rustc · cargo (minimal)", "Rust through rustup's minimal profile. Compiling Rust on a phone is slow.", 90, 450),
    AGY("agy", "Antigravity CLI", "agy 1.1.27", "Google's official Antigravity agent. Installed when you add an Antigravity account.", 53, 200, hidden = true);

    companion object { fun byId(id: String) = entries.firstOrNull { it.id == id } }
}

@Serializable
data class ToolRecord(
    val version: String? = null,
    val sizeKb: Long = 0,
    val latest: String? = null,
    val checkedAt: Long = 0,
    val installedAt: Long = 0,
    val autoUpdate: Boolean = false,
)

sealed interface ToolStatus {
    data object Idle : ToolStatus
    data object Queued : ToolStatus
    data class Working(val action: String, val percent: Int, val message: String) : ToolStatus
    data class Failed(val message: String) : ToolStatus
}

/**
 * v0.5.2 (root cause of mazhar's "Could not install: ca-certificates curl git …"): Antigravity no longer waits on apt.
 * The official agy build is downloaded by the app itself (resumable, SHA-512 checked) and unpacked straight into Ubuntu;
 * it runs in a plain rootfs with nothing from apt (verified 1 Nov 2026: started and reached the Google sign-in link in ~4 s).
 * TLS inside Linux uses Android's system CAs (/opt/axion/cacert.pem, SSL_CERT_FILE) until ca-certificates is installed.
 */
object AgyCli {
    const val VERSION = "1.1.27"
    const val URL_ARM64 = "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.1.27-5211191891591168/linux-arm/cli_linux_arm64.tar.gz"
    const val SHA512_ARM64 = "ed45f6930785aa4b42f14e07ace1c9d91a94fb76e760f54acbd7d3d3951e1f957fd456a0dae2a3124dd9a3b689bf7afb7c9303a3e4ba95037fc10063424d9bf9"
    const val BYTES_ARM64 = 53_241_342L

    /** Pure, unit-tested: the agy binary inside an unpacked archive (today a single file named "antigravity"). */
    fun findBinary(dir: File): File? {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        return files.firstOrNull { it.name == "agy" || it.name == "antigravity" }
            ?: files.firstOrNull { it.name.startsWith("cli") && it.canExecute() }
            ?: files.filter { it.canExecute() }.maxByOrNull { it.length() }
    }

    /** Puts [binary] where Axion and the Build agent run it: /root/.local/bin/agy (+ /usr/local/bin/agy link). */
    fun installInto(rootfs: File, binary: File) {
        val bin = File(rootfs, "root/.local/bin").apply { mkdirs() }
        val dest = File(bin, "agy"); val tmp = File(bin, "agy.new")
        binary.copyTo(tmp, overwrite = true)
        tmp.setExecutable(true, false); tmp.setReadable(true, false)
        runCatching { android.system.Os.chmod(tmp.absolutePath, "755".toInt(8)) }
        check(tmp.renameTo(dest) || run { dest.delete(); tmp.renameTo(dest) }) { "Could not put Antigravity in place" }
        File(bin, ".axion-agy-version").writeText(VERSION)
        val link = File(rootfs, "usr/local/bin/agy"); link.parentFile?.mkdirs()
        runCatching { if (!link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) android.system.Os.symlink("/root/.local/bin/agy", link.absolutePath) }
        File(rootfs, "root/.gemini/antigravity-cli").mkdirs()
    }

    fun sha512(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-512")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Lines the guest scripts print (assets/devtools/common.sh). Pure for tests. */
object ScriptProtocol {
    sealed interface Line
    data class Progress(val percent: Int, val message: String) : Line
    data class Version(val v: String) : Line
    data class Latest(val v: String) : Line
    data class Size(val kb: Long) : Line
    data class Output(val text: String) : Line
    fun parse(line: String): Line = when {
        line.startsWith("@@P ") -> line.removePrefix("@@P ").split(' ', limit = 2).let { Progress(it[0].toIntOrNull() ?: 0, it.getOrElse(1) { "" }) }
        line.startsWith("@@V ") -> Version(line.removePrefix("@@V ").trim())
        line.startsWith("@@L ") -> Latest(line.removePrefix("@@L ").trim())
        line.startsWith("@@S ") -> Size(line.removePrefix("@@S ").trim().toLongOrNull() ?: 0)
        else -> Output(line)
    }
    /** apt/npm noise → something worth one line in the sheet. */
    fun friendly(line: String): String? {
        val l = line.trim()
        return when {
            l.startsWith("Get:") -> "Downloading " + l.substringAfter("] ").substringBefore(" ").ifBlank { l.take(60) }
            l.startsWith("Unpacking ") -> l.substringBefore(" (")
            l.startsWith("Setting up ") -> l.substringBefore(" (")
            l.startsWith("ERROR:") -> l
            else -> null
        }
    }
}

/**
 * Installs, updates and removes toolchains in the background, one at a time (apt can't run twice), with progress the UI watches.
 * The app stays light: nothing is downloaded until the user taps Install, and the work runs in [DevToolsService] (foreground) so it
 * survives leaving the app.
 */
class DevTools(private val ctx: Context, val linux: LinuxRuntime) {
    private val file = File(ctx.filesDir, "devtools.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _records = MutableStateFlow<Map<String, ToolRecord>>(load())
    val records: StateFlow<Map<String, ToolRecord>> = _records.asStateFlow()
    private val _status = MutableStateFlow<Map<String, ToolStatus>>(emptyMap())
    val status: StateFlow<Map<String, ToolStatus>> = _status.asStateFlow()
    private val _log = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val log: StateFlow<Map<String, List<String>>> = _log.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private data class Job(val tool: String, val action: String)
    private val queue = Channel<Job>(Channel.UNLIMITED)
    private var current: kotlinx.coroutines.Job? = null

    init { scope.launch { for (j in queue) runJob(j) } }

    private fun load(): Map<String, ToolRecord> = runCatching { json.decodeFromString<Map<String, ToolRecord>>(file.readText()) }.getOrDefault(emptyMap())
    private fun save() { runCatching { file.writeText(json.encodeToString(_records.value)) } }
    private fun setStatus(id: String, s: ToolStatus) { _status.value = _status.value + (id to s) }
    /** v0.5.2 live setup panel: the steps of the Antigravity setup and the streaming output (last 200 lines). */
    enum class Step(val title: String) { LINUX("Preparing Linux"), DOWNLOAD("Downloading Antigravity"), VERIFY("Checking and installing"), START("Starting Antigravity") }
    private val _steps = MutableStateFlow<Map<String, List<Step>>>(emptyMap())   // steps this run will go through
    val steps: StateFlow<Map<String, List<Step>>> = _steps.asStateFlow()
    private val _step = MutableStateFlow<Map<String, Step>>(emptyMap())          // the step running (or that failed)
    val step: StateFlow<Map<String, Step>> = _step.asStateFlow()
    private val _live = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val live: StateFlow<Map<String, List<String>>> = _live.asStateFlow()
    private fun setStep(id: String, s: Step) { _step.value = _step.value + (id to s) }
    /** Tests / screenshots: a run in progress at [s] with this output. */
    fun debugProgress(id: String, plan: List<Step>, s: Step, percent: Int, message: String, lines: List<String>) {
        raw[id] = ArrayDeque(); lines.forEach { rawLine(id, it) }; _steps.value = _steps.value + (id to plan); setStep(id, s)
        setStatus(id, ToolStatus.Working("Installing", percent, message))
    }

    // v0.5.2: the FULL output of the last run per tool (apt, proot, curl…), scrubbed of secrets, for "View log" under an error.
    private val raw = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<String>>()
    private val logDir get() = File(ctx.filesDir, "logs").apply { mkdirs() }
    fun logFile(id: String) = File(logDir, "devtools-$id.log")
    private fun rawLine(id: String, line: String) {
        val q = raw.getOrPut(id) { ArrayDeque() }
        val tail = synchronized(q) { q.addLast(com.mazharansari.axion.security.SecretScanner.scrub(line.take(2000))); while (q.size > 2000) q.removeFirst(); q.toList().takeLast(200) }
        synchronized(_live) { _live.value = _live.value + (id to tail) }
    }
    private fun saveRaw(id: String) {
        val q = raw[id] ?: return
        runCatching { logFile(id).writeText(synchronized(q) { q.joinToString("\n") } + "\n") }
    }
    /** Tests / screenshots: a failed install with this output, exactly as a real run would leave it. */
    fun debugFail(id: String, message: String, lines: List<String>) {
        raw[id] = ArrayDeque(); lines.forEach { rawLine(id, it) }; saveRaw(id); setStatus(id, ToolStatus.Failed(message))
    }
    /** Last [lines] lines of the last run (memory first, then the saved file from an earlier app session). */
    fun logText(id: String, lines: Int = 200): String {
        val q = raw[id]
        val all = if (q != null) synchronized(q) { q.toList() } else runCatching { logFile(id).readLines() }.getOrDefault(emptyList())
        return all.takeLast(lines).joinToString("\n")
    }
    private fun appendLog(id: String, line: String) { _log.value = _log.value + (id to ((_log.value[id] ?: emptyList()) + line).takeLast(200)) }

    fun isInstalled(id: String) = _records.value[id]?.version != null && linux.isInstalled()
    fun installedTools(): List<Toolchain> = Toolchain.entries.filter { isInstalled(it.id) }

    fun install(t: Toolchain) = enqueue(t.id, "install")
    fun update(t: Toolchain) = enqueue(t.id, "update")
    fun uninstall(t: Toolchain) = enqueue(t.id, "uninstall")
    fun updateAll() { installedTools().forEach { update(it) } }
    fun setAutoUpdate(t: Toolchain, on: Boolean) { _records.value = _records.value + (t.id to (_records.value[t.id] ?: ToolRecord()).copy(autoUpdate = on)); save() }
    fun cancelCurrent() { current?.cancel() }
    /** v0.5.3 Storage: the tool's files were deleted directly (Antigravity CLI) — mark it not installed. */
    fun forget(id: String) { _records.value = _records.value + (id to (_records.value[id] ?: ToolRecord()).copy(version = null, sizeKb = 0)); save() }

    private fun enqueue(id: String, action: String) {
        if (_status.value[id] is ToolStatus.Working || _status.value[id] == ToolStatus.Queued) return
        // A toolchain that needs another one queues that first (Browser automation → Web).
        if (action == "install") Toolchain.byId(id)?.needs?.forEach { dep -> if (!isInstalled(dep)) enqueue(dep, "install") }
        setStatus(id, ToolStatus.Queued)
        _busy.value = true
        DevToolsService.start(ctx)
        queue.trySend(Job(id, action))
    }

    private suspend fun runJob(j: Job) = coroutineScope {
        val t = Toolchain.byId(j.tool) ?: return@coroutineScope
        current = launch {
            val verb = when (j.action) { "install" -> "Installing"; "update" -> "Updating"; else -> "Removing" }
            raw[t.id] = ArrayDeque(); _live.value = _live.value - t.id; rawLine(t.id, "Axion ${com.mazharansari.axion.BuildConfig.VERSION_NAME} · $verb ${t.title} · ${java.util.Date()} · Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.SUPPORTED_ABIS.firstOrNull()})")
            try {
                _log.value = _log.value + (t.id to emptyList())
                val freshLinux = !linux.isInstalled()
                if (t == Toolchain.AGY) _steps.value = _steps.value + (t.id to (if (freshLinux) listOf(Step.LINUX) else emptyList()) + listOf(Step.DOWNLOAD, Step.VERIFY, Step.START))
                if (freshLinux) {
                    setStep(t.id, Step.LINUX)
                    var lastMsg = ""
                    linux.ensureBase { p -> if (p.message != lastMsg) { lastMsg = p.message; rawLine(t.id, "[linux] ${p.message}") }; setStatus(t.id, ToolStatus.Working(verb, (p.fraction * 30).toInt(), p.message)); DevToolsService.update(ctx, "${t.title}: ${p.message}", (p.fraction * 30).toInt()) }
                }
                if (t == Toolchain.AGY && j.action != "uninstall") {
                    // v0.5.2: Antigravity needs nothing from apt — no base-tools step that can fail on mobile data.
                    installAgyDirect(t.id, verb, if (freshLinux) 30 else 0)
                } else {
                    if (j.action != "uninstall" && t.id != "core" && _records.value["core"]?.version == null) runScript("core", "install", t.id, verb, 30, 40)
                    val (base, span) = if (_records.value["core"]?.version != null || j.action == "uninstall") 0 to 100 else 40 to 60
                    runScript(t.id, j.action, t.id, verb, base.coerceAtLeast(if (linux.isInstalled()) 0 else 30), span)
                }
                setStatus(t.id, ToolStatus.Idle)
                rawLine(t.id, "[axion] done"); saveRaw(t.id)
            } catch (e: CancellationException) { rawLine(t.id, "[axion] stopped"); saveRaw(t.id); setStatus(t.id, ToolStatus.Failed("Stopped")); throw e
            } catch (e: Throwable) {
                rawLine(t.id, "[axion] failed: ${e.javaClass.simpleName}: ${e.message}"); saveRaw(t.id)
                setStatus(t.id, ToolStatus.Failed(e.message?.take(240) ?: "$verb failed"))
            }
        }
        current?.join()
        if (_status.value.values.none { it is ToolStatus.Working || it == ToolStatus.Queued }) { _busy.value = false; DevToolsService.stop(ctx) }
    }

    private suspend fun installAgyDirect(uiId: String, verb: String, base: Int) = withContext(Dispatchers.IO) {
        val span = 100 - base
        fun prog(pct: Int, msg: String) {
            val p = (base + pct * span / 100).coerceIn(0, 99)
            setStatus(uiId, ToolStatus.Working(verb, p, msg)); DevToolsService.update(ctx, "Antigravity: $msg", p)
        }
        check(android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) { "Antigravity has no build for this phone (needs 64-bit ARM)." }
        val dir = File(ctx.cacheDir, "agy-dl").apply { mkdirs() }
        val tgz = File(dir, "agy-${AgyCli.VERSION}.tgz")
        setStep(uiId, Step.DOWNLOAD)
        rawLine(uiId, "[agy] download ${AgyCli.URL_ARM64}")
        prog(2, "Downloading Antigravity CLI ${AgyCli.VERSION}")
        if (tgz.length() > AgyCli.BYTES_ARM64) tgz.delete()
        var lastMb = -1L
        LinuxRuntime.downloadResumable(AgyCli.URL_ARM64, tgz, AgyCli.BYTES_ARM64) { done, total ->
            val mb = done / 1048576
            if (mb != lastMb) { lastMb = mb; prog((done * 70 / total.coerceAtLeast(1)).toInt(), "Downloading Antigravity · $mb of ${total / 1048576} MB") }
        }
        rawLine(uiId, "[agy] downloaded ${tgz.length()} bytes")
        setStep(uiId, Step.VERIFY)
        prog(72, "Checking download")
        val sum = AgyCli.sha512(tgz)
        if (!sum.equals(AgyCli.SHA512_ARM64, true)) { tgz.delete(); rawLine(uiId, "[agy] sha512 mismatch: $sum"); error("The Antigravity download was damaged (checksum). Tap Retry.") }
        prog(80, "Unpacking")
        val x = File(dir, "x").apply { deleteRecursively(); mkdirs() }
        LinuxRuntime.extractTarGz(tgz, x)
        val bin = AgyCli.findBinary(x) ?: error("Antigravity binary not found in the download")
        rawLine(uiId, "[agy] binary ${bin.name} ${bin.length()} bytes")
        prog(90, "Installing")
        AgyCli.installInto(linux.rootfs, bin)
        x.deleteRecursively(); tgz.delete()
        // agy starts? (no apt, no curl — just the binary in Ubuntu)
        setStep(uiId, Step.START)
        prog(95, "Checking Antigravity starts")
        val (code, out) = runCatching { linux.run("/root/.local/bin/agy --version", 90_000) { rawLine(uiId, it) } }.getOrElse { -2 to (it.message ?: "") }
        rawLine(uiId, "[agy --version exit $code]")
        val version = Regex("\\d+\\.\\d+\\.\\d+").find(out)?.value ?: AgyCli.VERSION
        val now = System.currentTimeMillis()
        _records.value = _records.value + ("agy" to (_records.value["agy"] ?: ToolRecord()).copy(version = version, sizeKb = File(linux.rootfs, "root/.local/bin/agy").length() / 1024, installedAt = now, latest = version, checkedAt = now))
        save()
    }

    private suspend fun runScript(tool: String, action: String, uiId: String, verb: String, base: Int, span: Int) {
        var version: String? = null; var size: Long? = null; var lastErr = ""
        val title = Toolchain.byId(uiId)?.title ?: uiId
        rawLine(uiId, "$ run.sh $tool $action")
        val (code, tail) = linux.run("/bin/bash /opt/axion/devtools/run.sh $tool $action", timeoutMs = 45 * 60_000L) { line ->
            rawLine(uiId, line)
            when (val l = ScriptProtocol.parse(line)) {
                is ScriptProtocol.Progress -> {
                    val pct = base + l.percent * span / 100
                    setStatus(uiId, ToolStatus.Working(verb, pct.coerceIn(0, 99), l.message)); appendLog(uiId, l.message)
                    DevToolsService.update(ctx, "$verb $title: ${l.message}", pct)
                }
                is ScriptProtocol.Version -> version = l.v
                is ScriptProtocol.Size -> size = l.kb
                is ScriptProtocol.Latest -> {}
                is ScriptProtocol.Output -> {
                    if (l.text.startsWith("ERROR:")) lastErr = l.text.removePrefix("ERROR:").trim()
                    ScriptProtocol.friendly(l.text)?.let { f -> appendLog(uiId, f); (_status.value[uiId] as? ToolStatus.Working)?.let { setStatus(uiId, it.copy(message = f.take(80))) } }
                }
            }
        }
        rawLine(uiId, "[exit $code]")
        if (code != 0) error(lastErr.ifBlank { if (code == -1) "Timed out" else tail.lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "exit $code" })
        val now = System.currentTimeMillis()
        val old = _records.value[tool] ?: ToolRecord()
        _records.value = _records.value + (tool to if (action == "uninstall") old.copy(version = null, sizeKb = 0)
            else old.copy(version = version, sizeKb = size ?: old.sizeKb, installedAt = now, latest = version, checkedAt = now))
        save()
    }

    /** Asks each installed tool for its newest version (no install). Used by the "Update all" row and the update check. */
    suspend fun checkUpdates(): Map<String, String> {
        if (!linux.isInstalled()) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (t in installedTools()) {
            var latest: String? = null
            runCatching { linux.run("/bin/bash /opt/axion/devtools/run.sh ${t.id} latest", 60_000) { l -> (ScriptProtocol.parse(l) as? ScriptProtocol.Latest)?.let { latest = it.v } } }
            latest?.takeIf { it.isNotBlank() && it != "unknown" }?.let { v ->
                out[t.id] = v
                _records.value = _records.value + (t.id to (_records.value[t.id] ?: ToolRecord()).copy(latest = v, checkedAt = System.currentTimeMillis()))
            }
        }
        save(); return out
    }

    fun hasUpdate(id: String): Boolean {
        val r = _records.value[id] ?: return false
        val v = r.version ?: return false; val l = r.latest ?: return false
        return newer(v, l)
    }

    /** For the agent's system prompt: what it can actually run. */
    fun summaryForAgent(): String? {
        if (!linux.isInstalled()) return null
        val list = installedTools().filter { it != Toolchain.AGY }.joinToString { "${it.title} (${_records.value[it.id]?.version})" }
        return "run_command runs bash inside a private Ubuntu 24.04 Linux (PRoot) on the phone, as root, in /workspace (= the app's workspace folder; uploads/ and outputs/ live there). apt-get works." +
            if (list.isNotBlank()) " Installed developer tools: $list." else " No extra developer tools are installed yet (base: bash, git, curl)."
    }

    companion object {
        /** True when [latest] is a higher version than [current]; tolerant of "Java 17 · Kotlin 2.2" style strings. */
        fun newer(current: String, latest: String): Boolean {
            val a = Regex("\\d+(?:\\.\\d+)+").findAll(current).map { it.value }.lastOrNull() ?: return false
            val b = Regex("\\d+(?:\\.\\d+)+").findAll(latest).map { it.value }.lastOrNull() ?: return false
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) { val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }; if (p != q) return q > p }
            return false
        }
    }
}
