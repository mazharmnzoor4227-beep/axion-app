package com.mazharansari.axion.build

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mazharansari.axion.data.DeployTarget
import com.mazharansari.axion.data.KeyVault
import com.mazharansari.axion.data.Project
import com.mazharansari.axion.linux.CloudSandbox
import com.mazharansari.axion.linux.LinuxRuntime
import com.mazharansari.axion.linux.DevTools
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** Deploy tokens, encrypted with the Android Keystore key (same vault as API keys). */
class DeployTokens(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _saved = MutableStateFlow(load())
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()   // provider id → encrypted blob (never the token)
    private fun load(): Map<String, String> = runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), file.readText()) }.getOrDefault(emptyMap())
    fun has(p: DeployProvider) = _saved.value.containsKey(p.id)
    fun token(p: DeployProvider): String? = _saved.value[p.id]?.let { KeyVault.decrypt(it) }
    fun set(p: DeployProvider, token: String) { _saved.value = _saved.value + (p.id to KeyVault.encrypt(token.trim())); save() }
    fun remove(p: DeployProvider) { _saved.value = _saved.value - p.id; save() }
    private fun save() = runCatching { file.writeText(json.encodeToString(MapSerializer(String.serializer(), String.serializer()), _saved.value)) }
}

sealed interface JobState {
    data object Idle : JobState
    data class Running(val title: String, val lines: List<String>) : JobState
    data class Done(val title: String, val result: String, val file: String? = null) : JobState
    data class Failed(val title: String, val message: String, val lines: List<String> = emptyList()) : JobState
}

/**
 * Everything Build needs besides the chat: the projects, previews (in-app static server or a dev server in Linux),
 * deploys, APK builds and the installer intent. One per app (Graph.build).
 */
class BuildHub(private val ctx: Context, private val linux: LinuxRuntime, private val devtools: DevTools, private val cloud: CloudSandbox? = null) {
    val store = ProjectStore(ctx.filesDir)
    val tokens = DeployTokens(File(ctx.filesDir, "deploy_tokens.json"))
    val deployer = Deployer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _jobs = MutableStateFlow<Map<String, JobState>>(emptyMap())   // "<projectId>:deploy" / ":apk" / ":preview"
    val jobs: StateFlow<Map<String, JobState>> = _jobs.asStateFlow()
    private fun set(key: String, s: JobState) { _jobs.value = _jobs.value + (key to s) }
    private fun log(key: String, title: String, lineIn: String) {
        val line = com.mazharansari.axion.security.SecretScanner.scrub(lineIn)
        val cur = (_jobs.value[key] as? JobState.Running)?.lines.orEmpty()
        set(key, JobState.Running(title, (cur + line).takeLast(80)))
    }

    fun folder(p: Project) = store.folder(p)
    fun guest(p: Project) = store.guestPath(p)

    fun create(name: String, kind: String): Project {
        val p = store.create(name, kind)
        if (kind == "android") runCatching { AndroidTemplate.write(store.folder(p), p.name, p.slug) }
        return p
    }

    fun verifier() = ProjectVerifier(linux, { devtools.isInstalled(it) }, { store.guestPath(it) })

    // ── preview ─────────────────────────────────────────────────────────────────────────────────────────────────────
    private val servers = HashMap<String, StaticServer>()
    private val devServers = HashMap<String, Process>()
    private val _previewUrl = MutableStateFlow<Map<String, String>>(emptyMap())
    val previewUrl: StateFlow<Map<String, String>> = _previewUrl.asStateFlow()

    /** Static sites: in-app server (no toolchain needed). Node projects with a dev script: `npm run dev` inside Linux. */
    fun startPreview(p: Project): String? {
        stopPreview(p.id)
        val dir = folder(p)
        val pkg = File(dir, "package.json")
        val hasDev = pkg.isFile && Regex("\"dev\"\\s*:").containsMatchIn(pkg.readText())
        val site = SiteChecker.siteDir(dir)
        if (site != null && (!hasDev || site != dir)) {
            val s = StaticServer(site).start(); servers[p.id] = s
            _previewUrl.value = _previewUrl.value + (p.id to s.url); return s.url
        }
        if (hasDev && linux.isInstalled() && devtools.isInstalled("web")) {
            val port = 5173 + (p.slug.hashCode() and 0x3ff)
            val key = "${p.id}:preview"
            set(key, JobState.Running("Starting dev server", emptyList()))
            val cmd = (if (!File(dir, "node_modules").isDirectory) "npm install --no-fund --no-audit && " else "") + "npm run dev -- --host 127.0.0.1 --port $port"
            val proc = linux.spawn(listOf("/bin/bash", "-lc", cmd), guest(p))
            devServers[p.id] = proc
            val url = "http://127.0.0.1:$port/"
            _previewUrl.value = _previewUrl.value + (p.id to url)
            scope.launch { proc.waitFor(); if (devServers[p.id] === proc) { devServers.remove(p.id); set(key, JobState.Idle) } }
            return url
        }
        return null
    }

    fun stopPreview(id: String) {
        servers.remove(id)?.stop(); devServers.remove(id)?.destroyForcibly()
        _previewUrl.value = _previewUrl.value - id
    }

    // ── deploy ──────────────────────────────────────────────────────────────────────────────────────────────────────
    fun deploy(p: Project, provider: DeployProvider) {
        val key = "${p.id}:deploy"
        if (_jobs.value[key] is JobState.Running) return
        val title = "Publishing to ${provider.title}"
        set(key, JobState.Running(title, emptyList()))
        scope.launch {
            try {
                val token = tokens.token(provider) ?: throw DeployException("Add your ${provider.title} token first.")
                val site = SiteChecker.siteDir(folder(p)) ?: throw DeployException(if (File(folder(p), "package.json").isFile) "Build the site first (there's no dist/ with index.html yet)." else "There's no index.html to publish yet.")
                // v0.5.2: never publish a key. Everything in the folder that goes online is scanned first (patterns + the user's own keys).
                log(key, title, "Checking the files for secret keys")
                com.mazharansari.axion.security.SecretScanner.requireClean(site, "Publishing")
                val prev = p.deploy?.takeIf { it.provider == provider.id }
                val name = prev?.name ?: deployer.siteName(p.slug, "-" + p.id.take(4))
                val r = when (provider) {
                    DeployProvider.NETLIFY -> deployer.netlify(token, site, name, prev?.siteId) { log(key, title, it) }
                    DeployProvider.VERCEL -> deployer.vercel(token, site, name) { log(key, title, it) }
                    DeployProvider.GITHUB -> deployer.github(token, site, prev?.name ?: deployer.siteName(p.slug)) { log(key, title, it) }
                    DeployProvider.CLOUDFLARE -> {
                        if (!linux.isInstalled() || !devtools.isInstalled("web")) throw DeployException("Cloudflare Pages needs Web (Node) in Developer tools.")
                        log(key, title, "Checking the Cloudflare token")
                        val account = deployer.cloudflareAccount(token)
                        log(key, title, "Uploading with wrangler (first time downloads it, ~1 min)")
                        val guestSite = guest(p) + (site.relativeTo(folder(p)).path.let { if (it.isEmpty()) "" else "/$it" })
                        // The token goes to Cloudflare's own tool for this one upload, in that process's environment only —
                        // never written to a file in Linux or the project. Its output is scrubbed before it's shown.
                        val (code, out) = linux.run(deployer.wranglerCommand(guestSite, name), 10 * 60_000L, guest(p),
                            env = mapOf("CLOUDFLARE_API_TOKEN" to token, "CLOUDFLARE_ACCOUNT_ID" to account, "WRANGLER_SEND_METRICS" to "false")) { log(key, title, com.mazharansari.axion.security.SecretScanner.scrub(it, listOf(token)).take(160)) }
                        if (code != 0) throw DeployException("wrangler failed: " + com.mazharansari.axion.security.SecretScanner.scrub(out.lines().lastOrNull { it.isNotBlank() }.orEmpty(), listOf(token)).take(200))
                        DeployResult(Deployer.wranglerUrl(out, name), name, name)
                    }
                }
                store.setDeploy(p.id, DeployTarget(provider.id, r.siteId, r.url, r.name, System.currentTimeMillis()))
                set(key, JobState.Done("Live on ${provider.title}", r.url))
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { set(key, JobState.Failed(title, com.mazharansari.axion.security.SecretScanner.scrub(e.message ?: "Publishing failed"), (_jobs.value[key] as? JobState.Running)?.lines.orEmpty())) }
        }
    }

    // ── APK ─────────────────────────────────────────────────────────────────────────────────────────────────────────
    fun apkReady() = linux.isInstalled() && devtools.isInstalled("android") && devtools.isInstalled("androidsdk")

    fun buildApk(p: Project, turbo: Boolean = false) {
        val key = "${p.id}:apk"
        if (_jobs.value[key] is JobState.Running) return
        val title = "Building APK"
        set(key, JobState.Running(title, listOf("Starting Gradle — the first build downloads libraries and can take 5–15 minutes on a phone.")))
        scope.launch {
            try {
                if (!apkReady()) throw DeployException("Install \"Android APK builder\" in Developer tools first (about 210 MB download, ~1.5 GB after the first build).")
                val dir = folder(p)
                // v0.5.2: an APK can be unpacked by anyone who gets it, so a key in the sources would leak. Scan before building.
                log(key, title, "Checking the project for secret keys")
                com.mazharansari.axion.security.SecretScanner.requireClean(dir, "APK build")
                if (!File(dir, "settings.gradle.kts").isFile && !File(dir, "settings.gradle").isFile) AndroidTemplate.write(dir, p.name, p.slug)
                File(dir, "local.properties").writeText("sdk.dir=/root/android-sdk\n")
                val cmd = if (File(dir, "gradlew").isFile) "chmod +x gradlew && ./gradlew assembleDebug --console=plain" else "gradle assembleDebug --console=plain"
                val (code, out) = if (turbo && cloud?.isConfigured() == true) {
                    // P1-3 Turbo (server): the project syncs up, Gradle runs on the user's own
                    // server (which needs the Android SDK at /root/android-sdk, same path as the
                    // phone), and the built APK syncs back. The debug path below is untouched.
                    val remote = cloud.settings.value.remoteWorkspace.trimEnd('/') + "/projects/${p.slug}"
                    log(key, title, "Turbo: syncing the project to the server")
                    cloud.syncProjectToRemote(dir, remote)
                    log(key, title, "Turbo: building on the server")
                    val (c, o) = cloud.runRaw("cd ${com.mazharansari.axion.linux.TurboCommands.shellQuote(remote)} && $cmd 2>&1", 45 * 60_000L, onLine = { l -> if (l.isNotBlank()) log(key, title, l.take(200)) })
                    log(key, title, "Turbo: syncing the results back to the phone")
                    runCatching { cloud.syncProjectBack(remote, dir) }
                    c to o
                } else {
                    linux.run("cd '${guest(p)}' && $cmd 2>&1", 45 * 60_000L, guest(p)) { l -> if (l.isNotBlank()) log(key, title, l.take(200)) }
                }
                val apk = AndroidTemplate.findApk(dir)
                if (code != 0 || apk == null) throw DeployException(out.lines().filter { it.contains("error", true) || it.contains("FAILED") || it.startsWith("e: ") }.take(6).joinToString("\n").ifBlank { "Gradle failed (exit $code)." })
                val outDir = File(dir, ".axion/out").apply { mkdirs() }
                val dest = File(outDir, "${p.slug}.apk"); apk.copyTo(dest, overwrite = true)
                store.update(p.id) { it.copy(lastApk = dest.absolutePath) }
                set(key, JobState.Done("APK ready", "${dest.name} · ${dest.length() / 1024} KB", dest.absolutePath))
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { set(key, JobState.Failed(title, e.message ?: "Build failed", (_jobs.value[key] as? JobState.Running)?.lines.orEmpty())) }
        }
    }

    // ── release APK (P1-9) ──────────────────────────────────────────────────────────────────────────────────────────
    /**
     * Release build signed with the user's OWN keystore (picked in-app). The debug path
     * ([buildApk]) is untouched. Passwords live only in [req]'s CharArrays for this one build:
     * they are never written to disk, never stored, never logged — the Gradle command that
     * carries them is never logged either, and every output line is scrubbed before it is
     * shown. The staged keystore copy is deleted when the build ends, whatever happens.
     * Axion's own signing keystore is never touched by this path.
     */
    fun buildReleaseApk(p: Project, req: ReleaseSigningRequest) {
        val key = "${p.id}:apk-release"
        if (_jobs.value[key] is JobState.Running) return
        val title = "Building release APK"
        set(key, JobState.Running(title, listOf("Starting Gradle — the first build downloads libraries and can take 5–15 minutes on a phone.")))
        scope.launch {
            var staged: File? = null
            try {
                val problems = req.validate()
                if (problems.isNotEmpty()) throw DeployException(problems.first())
                if (!apkReady()) throw DeployException("Install \"Android APK builder\" in Developer tools first (about 210 MB download, ~1.5 GB after the first build).")
                val dir = folder(p)
                log(key, title, "Checking the project for secret keys")
                com.mazharansari.axion.security.SecretScanner.requireClean(dir, "APK build")
                if (!File(dir, "settings.gradle.kts").isFile && !File(dir, "settings.gradle").isFile) AndroidTemplate.write(dir, p.name, p.slug)
                File(dir, "local.properties").writeText("sdk.dir=/root/android-sdk\n")
                // Stage the keystore outside the project (never in the repo, never in the APK);
                // the file-picker's cache copy is deleted as soon as it is staged.
                staged = ReleaseSigningRequest.stageKeystore(req.keystoreFile, linux.workspaceHost, p.slug)
                runCatching { req.keystoreFile.delete() }
                val injected = ReleaseSigningRequest.injectedArgs(ReleaseSigningRequest.guestKeystorePath(p.slug), req.alias, req.storePassword, req.keyPassword)
                val cmd = ReleaseSigningRequest.gradleCommand(guest(p), File(dir, "gradlew").isFile, injected)
                val secrets = req.secrets()
                val (code, out) = linux.run(cmd, 45 * 60_000L, guest(p)) { l -> if (l.isNotBlank()) log(key, title, ReleaseSigningRequest.scrubbed(l, secrets).take(200)) }
                val apk = ReleaseSigningRequest.findReleaseApk(dir)
                if (code != 0 || apk == null || apk.length() == 0L) throw DeployException(
                    ReleaseSigningRequest.scrubbed(out.lines().filter { it.contains("error", true) || it.contains("FAILED") || it.startsWith("e: ") }.take(6).joinToString("\n").ifBlank { "Gradle failed (exit $code)." }, secrets))
                val outDir = File(dir, ".axion/out").apply { mkdirs() }
                val dest = File(outDir, "${p.slug}-release.apk"); apk.copyTo(dest, overwrite = true)
                store.update(p.id) { it.copy(lastApk = dest.absolutePath) }
                set(key, JobState.Done("Release APK ready", "${dest.name} · ${dest.length() / 1024} KB", dest.absolutePath))
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { set(key, JobState.Failed(title, ReleaseSigningRequest.scrubbed(e.message ?: "Build failed", req.secrets()), (_jobs.value[key] as? JobState.Running)?.lines.orEmpty())) }
            finally { req.clear(); runCatching { staged?.delete() } }
        }
    }

    /** Hands the APK to Android's package installer (the user confirms on screen). */
    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun shareFile(file: File, mime: String) {
        com.mazharansari.axion.security.SecretScanner.leaveCheck(file)?.let { throw IllegalStateException(it) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun clearJob(key: String) { _jobs.value = _jobs.value - key }
}
