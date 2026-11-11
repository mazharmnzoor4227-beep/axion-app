package com.mazharansari.axion

import android.app.Application
import com.mazharansari.axion.agent.Workspace
import com.mazharansari.axion.data.ConnectorRepo
import com.mazharansari.axion.data.ConversationStore
import com.mazharansari.axion.data.SettingsStore
import com.mazharansari.axion.mcp.McpRepo
import com.mazharansari.axion.skills.SkillRepo
import com.mazharansari.axion.voice.SpeechInput
import com.mazharansari.axion.voice.Speaker
import java.io.File

class AxionApp : Application() {
    lateinit var graph: Graph
    override fun onCreate() {
        super.onCreate()
        // v0.4 tap-latency pass: debug builds log any disk/network work on the main thread.
        if (BuildConfig.DEBUG) android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        com.mazharansari.axion.voice.VoicePrefs.migrate(this); graph = Graph(this)
        com.mazharansari.axion.linux.LinuxHub.graph = graph
        com.mazharansari.axion.browser.BrowserHub.graph = graph
        com.mazharansari.axion.local.LocalEngine.libDir = applicationInfo.nativeLibraryDir.orEmpty()   // null under Robolectric
        Thread { runCatching { graph.quota } }.start()   // opens usage.db, restores key cool-downs, hooks rate-limit headers
        // v0.5.2 security: the scanner knows the user's own keys/tokens (so even shapeless tokens are caught), and any
        // Antigravity sign-in files left readable (an older version, a run cut off by a crash) are encrypted again.
        com.mazharansari.axion.security.SecretScanner.known = { graph.knownSecrets() }
        Thread { runCatching { graph.agy.sealAtRest() }; runCatching { graph.knownSecrets() } }.start()
        com.mazharansari.axion.voice.ReadAloudPlayer.geminiKeys = { graph.geminiKeys() }
    }
}

/** App-wide singletons, created lazily so cold start does no disk or network work on the main thread. */
class Graph(val app: Application) {
    val settings by lazy { SettingsStore(app) }
    val chats by lazy { ConversationStore(app) }
    val connectors by lazy { ConnectorRepo(app) }
    val workspace by lazy { Workspace(File(app.filesDir, "workspace")) }
    val skills by lazy { SkillRepo(app, File(app.filesDir, "skills")) }
    /** v0.10: the learning loop's persistent memory (agent-curated; Settings → Memory shows it all). */
    val memory by lazy { com.mazharansari.axion.data.MemoryStore(app) }
    /** v0.12: remembered approvals for gated shell work (Settings → Approvals shows/revokes them). */
    val approvals by lazy { com.mazharansari.axion.data.ApprovalsStore(app) }
    /** v0.12: the in-flight long-task record, so an app kill mid-task is visible on the next start. */
    val taskRuns by lazy { com.mazharansari.axion.data.TaskRunStore(app) }
    val mcp by lazy { McpRepo(app.filesDir) }
    val speaker by lazy { Speaker(app) }
    val imageGen by lazy {
        com.mazharansari.axion.agent.ImageGen({
            connectors.list.value.filter { it.enabled }.groupBy { it.providerId }.mapValues { it.value.first().key() }
        }, File(workspace.outputs, "images"))
    }
    /** Enabled Gemini keys, top to bottom (Gemini voices, Live call). */
    fun geminiKeys(): List<String> = connectors.list.value.filter { it.enabled && it.providerId == "gemini" }.mapNotNull { runCatching { it.key() }.getOrNull() }
    val speech by lazy { SpeechInput(app) { settings.state.value.speechLang } }

    // v0.4 — Linux (PRoot), developer tools, Antigravity accounts, quota.
    val linux by lazy { com.mazharansari.axion.linux.LinuxRuntime(app) }
    val devtools by lazy { com.mazharansari.axion.linux.DevTools(app, linux) }
    // v0.9 — Axion's own browser (one shared WebView for the Browser screen + the agent) and the terminal:
    // one persistent shell session + background jobs, both on the same PRoot Ubuntu.
    val browser by lazy { com.mazharansari.axion.browser.BrowserController(app) }
    val shell by lazy { com.mazharansari.axion.linux.ShellSession(linux) }
    val jobs by lazy { com.mazharansari.axion.linux.BackgroundJobs(app, linux) }
    val agy by lazy { com.mazharansari.axion.antigravity.AgyAccounts(app) }
    val agyLogin by lazy { com.mazharansari.axion.antigravity.AgyLogin(linux, agy) }
    val quota by lazy {
        com.mazharansari.axion.quota.QuotaRepo(app, connectors, agy).also { q ->
            connectors.persistCooldown = { k, until -> runCatching { q.store.putCooldown(k, until) } }
            runCatching { connectors.restoreCooldowns(q.store.cooldowns(System.currentTimeMillis())) }
            connectors.leftToday = { c -> q.leftToday(c) }
            connectors.freeOnly = { settings.state.value.freeMode }
        }
    }
    val agyProbe by lazy { com.mazharansari.axion.antigravity.AgyQuotaProbe(linux, agy, quota) }
    val agyClient by lazy { com.mazharansari.axion.antigravity.AgyClient(linux, agy, quota, agyProbe) }

    // v0.5 — Build projects (preview, deploy, APK) and on-device models.
    val build by lazy { com.mazharansari.axion.build.BuildHub(app, linux, devtools) }
    val updater by lazy { com.mazharansari.axion.update.AppUpdater(app) }
    /** v0.8.0 (Phase C): every chat/Build run in flight; WorkService keeps the process alive while this is non-empty. */
    val runs by lazy {
        com.mazharansari.axion.work.RunRegistry().also { r ->
            r.onFirstRunStarted = { com.mazharansari.axion.work.WorkService.start(app) }
            r.onLastRunFinished = { com.mazharansari.axion.work.WorkService.stop(app) }
        }
    }
    val storage by lazy { com.mazharansari.axion.linux.StorageRepo(app, this) }   // v0.5.3 Settings → Storage
    val localModels by lazy { com.mazharansari.axion.local.LocalModelStore(app).also { com.mazharansari.axion.local.LocalEngine.libDir = app.applicationInfo.nativeLibraryDir.orEmpty() } }

    /** v0.5.2: every saved secret in plain text (decrypts are cached per blob), for the leak scanner. Never shown or stored. */
    @Volatile private var secretsCache: Pair<Long, List<String>>? = null
    fun knownSecrets(): List<String> {
        val now = System.currentTimeMillis()
        secretsCache?.let { (at, l) -> if (now - at < 5_000) return l }
        return computeSecrets().also { secretsCache = now to it }
    }
    private fun computeSecrets(): List<String> {
        val out = ArrayList<String>()
        connectors.list.value.forEach { c -> if (c.providerId != "local") c.key().takeIf { it.length >= 12 }?.let(out::add) }
        runCatching { com.mazharansari.axion.build.DeployProvider.entries.forEach { p -> build.tokens.token(p)?.takeIf { it.length >= 12 }?.let(out::add) } }
        runCatching { mcp.servers.value.forEach { s -> com.mazharansari.axion.data.KeyVault.decrypt(s.tokenBlob)?.takeIf { it.length >= 12 }?.let(out::add) } }
        runCatching { com.mazharansari.axion.voice.VoicePrefs.savedSecrets(app).forEach { if (it.length >= 12) out += it } }
        return out
    }

    /** True when at least one Antigravity account is signed in and Linux + agy are installed. */
    fun agyReady(): Boolean = linux.isInstalled() && devtools.isInstalled("agy") && agy.accounts().any { it.enabled && agy.hasToken(it) }
}
