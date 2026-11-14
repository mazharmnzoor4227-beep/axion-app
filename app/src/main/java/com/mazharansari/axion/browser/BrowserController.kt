package com.mazharansari.axion.browser

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mazharansari.axion.linux.LinuxHub
import com.mazharansari.axion.linux.LinuxRuntime
import com.mazharansari.axion.llm.Http
import com.mazharansari.axion.mcp.McpServer
import com.mazharansari.axion.mcp.StdioMcpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/** Lets agent tools reach the browser without threading it through every constructor (same pattern as LinuxHub). */
object BrowserHub {
    @Volatile var graph: com.mazharansari.axion.Graph? = null
    val browser: BrowserController? get() = graph?.browser
}

data class BrowserElement(
    val index: Int, val kind: String, val text: String,
    val href: String = "", val type: String = "", val name: String = "", val placeholder: String = "",
)

data class BrowserPage(val url: String, val title: String, val text: String, val elements: List<BrowserElement>)

/**
 * Axion's own browser: ONE WebView PER TAB, owned here, shared by the Browser screen (the user watches and can take over)
 * and by the agent's browser_* tools. Each tab's WebView is created lazily on the main thread with a swappable context —
 * the app context while only the agent uses it, the activity while the Browser screen shows it — so tabs survive
 * navigation between screens and keep their own page, cookies, login state and back/forward history. Switching tabs
 * restores that tab's page. All agent actions are serialised and always act on the active tab.
 *
 * P1-7 (ME-9, ME-10): downloads are REAL — a page's download saves into the public Downloads/Axion folder
 * through DownloadManager, with progress in the Browser screen and an Open action — and <input type=file>
 * opens the system file picker (see [onFileChooserRequested] / [deliverFileChooserResult]).
 *
 * 0.9.6: each tab has an engine ([BrowserEngineKind]): the WebView ([WebViewEngine] below, the original
 * behaviour, unchanged) or Obscura ([ObscuraEngine], the full browser engine the agent uses). The
 * public operations route to the active tab's engine; the WebView machinery here is untouched.
 */
class BrowserController(
    private val app: Application,
    private val linuxProvider: () -> LinuxRuntime? = { LinuxHub.linux },
) {
    private val mutex = Mutex()
    private val wrapper = MutableContextWrapper(app)
    private val tabManager = BrowserTabManager()
    private val views = LinkedHashMap<String, WebView>()
    private val json = Json { ignoreUnknownKeys = true }
    private val webView = WebViewEngine()
    /** The one shared Obscura engine: the MCP server is single-page-per-process, so every Obscura tab uses it. */
    private var obscuraEngine: ObscuraEngine? = null
    private val obscuraReadyMutex = Mutex()
    private val obscuraScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _obscuraShot = MutableStateFlow<File?>(null)
    /** The latest screenshot PNG for the active Obscura tab (the Browser screen shows it). */
    val obscuraShot: StateFlow<File?> = _obscuraShot.asStateFlow()
    private val _obscuraPage = MutableStateFlow<BrowserPage?>(null)
    /** The last page read for the active Obscura tab (the Browser screen shows its text). */
    val obscuraPage: StateFlow<BrowserPage?> = _obscuraPage.asStateFlow()
    private val _installStatus = MutableStateFlow<String?>(null)
    /** While Obscura installs, what it is doing ("Downloading Obscura… 12 of 66 MB"); null otherwise. */
    val installStatus: StateFlow<String?> = _installStatus.asStateFlow()

    private val _url = MutableStateFlow(""); val url: StateFlow<String> = _url.asStateFlow()
    private val _title = MutableStateFlow(""); val title: StateFlow<String> = _title.asStateFlow()
    private val _loading = MutableStateFlow(false); val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _progress = MutableStateFlow(0); val progress: StateFlow<Int> = _progress.asStateFlow()
    private val _canBack = MutableStateFlow(false); val canBack: StateFlow<Boolean> = _canBack.asStateFlow()
    private val _canForward = MutableStateFlow(false); val canForward: StateFlow<Boolean> = _canForward.asStateFlow()
    private val _tabs = MutableStateFlow(tabManager.tabs); val tabs: StateFlow<List<BrowserTabState>> = _tabs.asStateFlow()
    private val _activeTabId = MutableStateFlow(tabManager.activeId); val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()
    private val _downloads = MutableStateFlow<List<DownloadState>>(emptyList()); val downloads: StateFlow<List<DownloadState>> = _downloads.asStateFlow()

    @Volatile private var lastError: String? = null
    @Volatile private var lastBlocked: String? = null
    @Volatile private var lastDownloadName: String? = null
    @Volatile private var lastDownloadError: String? = null
    @Volatile private var loadDone: CompletableDeferred<Unit>? = null

    /**
     * File chooser plumbing (ME-10): the Browser screen sets this to launch the system file picker
     * with the page's accepted MIME types; the picker's result comes back through [deliverFileChooserResult].
     * While no screen is attached (agent-only use) this stays null and file inputs are declined honestly.
     */
    @Volatile var onFileChooserRequested: ((Array<String>) -> Unit)? = null
    @Volatile private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pollJob: Job? = null

    // ---- WebView creation (main thread only) ----

    private fun ensureWv(): WebView {
        val id = tabManager.activeId
        return views[id] ?: createWv(id).also { views[id] = it }
    }

    private fun createWv(tabId: String): WebView {
        val view = WebView(wrapper)
        view.tag = tabId
        view.setBackgroundColor(Color.WHITE)
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            saveFormData = true
            userAgentString = Http.UA   // a normal Chrome-for-Android UA: sites serve their real mobile pages
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(view, true) }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url?.toString().orEmpty()
                val scheme = u.substringBefore(':').lowercase()
                return if (scheme in setOf("http", "https", "about", "blob", "data")) false
                else { lastBlocked = u; true }   // tel:, mailto:, intent:, market: … never leave the app silently
            }
            override fun onPageStarted(v: WebView, u: String?, favicon: Bitmap?) {
                val id = v.tag as? String ?: return
                if (u != null) tabManager.updateTab(id, u, tabManager.tabs.firstOrNull { it.id == id }?.title.orEmpty())
                if (id != tabManager.activeId) { syncTabFlows(); return }
                _loading.value = true; _progress.value = 0
                if (u != null) _url.value = u
                // keep the waiter an operation already created (open/back/…): replacing it here would
                // strand that waiter until its timeout, because onPageFinished completes only the latest one
                if (loadDone?.isCompleted != false) loadDone = CompletableDeferred()
                syncTabFlows()
            }
            override fun onPageFinished(v: WebView, u: String?) {
                val id = v.tag as? String ?: return
                if (u != null) tabManager.updateTab(id, u, v.title.orEmpty())
                syncTabFlows()
                if (id != tabManager.activeId) return
                _loading.value = false; _progress.value = 100
                if (u != null) _url.value = u
                _title.value = v.title.orEmpty()
                _canBack.value = v.canGoBack(); _canForward.value = v.canGoForward()
                loadDone?.complete(Unit)
            }
            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) lastError = "${err.errorCode}: ${err.description}"
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if ((v.tag as? String) != tabManager.activeId) return
                _progress.value = p; if (p == 100) _title.value = v.title.orEmpty()
            }
            override fun onReceivedTitle(v: WebView, t: String?) {
                val id = v.tag as? String ?: return
                tabManager.updateTab(id, tabManager.tabs.firstOrNull { it.id == id }?.url.orEmpty(), t.orEmpty())
                if (id == tabManager.activeId) _title.value = t.orEmpty()
                syncTabFlows()
            }
            override fun onShowFileChooser(v: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                val callback = cb ?: return false
                val launchPicker = onFileChooserRequested
                if (launchPicker == null) { callback.onReceiveValue(null); return false }   // no screen attached: decline, do not hang the page
                val types = params?.acceptTypes?.filter { it.isNotBlank() }?.toTypedArray()?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
                fileChooserCallback?.onReceiveValue(null)   // a previous chooser that never answered must not hang
                fileChooserCallback = callback
                return runCatching { launchPicker(types); true }.getOrElse {
                    fileChooserCallback = null; callback.onReceiveValue(null); false
                }
            }
        }
        view.setDownloadListener { u, _, contentDisposition, mimeType, _ -> startDownload(u, contentDisposition, mimeType) }
        return view
    }

    private fun syncTabFlows() {
        _tabs.value = tabManager.tabs
        _activeTabId.value = tabManager.activeId
    }

    /** Shared flows follow the active tab (its URL/title and its own WebView's back/forward state). */
    private fun syncActiveFlows() {
        val tab = tabManager.active
        _url.value = tab.url
        _title.value = tab.title
        _loading.value = false
        val view = views[tab.id]
        _canBack.value = view?.canGoBack() ?: tab.canGoBack
        _canForward.value = view?.canGoForward() ?: tab.canGoForward
        syncTabFlows()
    }

    /** For the Browser screen: attach the ACTIVE tab's WebView to the visible activity. Main thread (Compose factory). */
    fun displayView(activityContext: Context): View {
        wrapper.baseContext = activityContext
        // An Obscura tab has no WebView — the screen shows its screenshot view instead. This is only
        // a safety net (the screen branches on the tab's engine first); never create a WebView for it.
        if (tabManager.active.engine == BrowserEngineKind.OBSCURA) return View(wrapper)
        val view = ensureWv()
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    /** The Browser screen went away: keep the WebViews (and their pages) alive on the app context. Main thread. */
    fun releaseDisplay() {
        views[tabManager.activeId]?.let { (it.parent as? ViewGroup)?.removeView(it) }
        wrapper.baseContext = app
    }

    /** First open of the Browser screen: start somewhere real if the agent has not already opened a page. */
    suspend fun ensureStarted() { if (tabManager.active.url.isBlank()) open(BrowserTabManager.START_URL) }

    // ---- tabs (P1-7) ----

    /**
     * Opens a new blank tab and makes it active. False when the tab limit is reached.
     * The screen passes the engine the user picked; agent calls pass agent=true and
     * always get an Obscura tab.
     */
    suspend fun newTab(engine: BrowserEngineKind = BrowserEngineKind.WEBVIEW, agent: Boolean = false): Boolean = action {
        if (!tabManager.newTab(if (agent) BrowserEngineKind.OBSCURA else engine)) return@action false
        syncActiveFlows()
        true
    }

    /** Switches to [id] and restores that tab's page. False when there is no such tab. */
    fun switchTab(id: String): Boolean {
        if (!tabManager.switchTo(id)) return false
        loadDone?.complete(Unit)   // a load waiter for the old tab must not hang on the new tab
        syncActiveFlows()
        if (tabManager.active.engine == BrowserEngineKind.OBSCURA) refreshObscuraViewAsync()
        return true
    }

    /** Closes [id] (closing the last tab resets it to a blank tab). False when there is no such tab. */
    suspend fun closeTab(id: String): Boolean = action {
        val single = tabManager.tabs.size == 1
        val tab = tabManager.tabs.firstOrNull { it.id == id }
        val view = views[id]
        if (!tabManager.closeTab(id)) return@action false
        // Only a WebView tab owns a view. An Obscura tab has none; and a last tab
        // reset to blank comes back as a WebView tab (the manager default).
        if (view != null && tab?.engine == BrowserEngineKind.WEBVIEW && !single) {
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            views.remove(id)
        } else if (view != null && tab?.engine == BrowserEngineKind.WEBVIEW) {
            view.loadUrl("about:blank")
        }
        syncActiveFlows()
        if (tabManager.active.engine == BrowserEngineKind.OBSCURA) refreshObscuraViewAsync()
        true
    }

    // ---- file chooser result (P1-7, ME-10) ----

    /** The file picker's answer for the pending <input type=file>: the chosen files, or null/empty when cancelled. */
    fun deliverFileChooserResult(uris: List<Uri>?) {
        val cb = fileChooserCallback
        fileChooserCallback = null
        runCatching { cb?.onReceiveValue(uris?.toTypedArray()) }
    }

    // ---- downloads (P1-7, ME-9) ----

    /** Starts a real download into Downloads/Axion. Called on the main thread by the WebView's download listener. */
    private fun startDownload(url: String, contentDisposition: String?, mimeType: String?) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val name0 = BrowserDownloads.fileNameFor(url, contentDisposition, mimeType)
        if (uri == null || (uri.scheme != "http" && uri.scheme != "https")) {
            _downloads.value = _downloads.value + DownloadState(id = -System.currentTimeMillis(), url = url, fileName = name0, status = DownloadStatus.FAILED)
            lastDownloadError = "Could not download $name0 — that link is not a file download."
            return
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BrowserDownloads.DIR)
        val existing = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
        val name = BrowserDownloads.uniqueFileName(existing, name0)
        val req = DownloadManager.Request(uri)
            .setTitle(name)
            .setDescription("Downloading in Axion")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${BrowserDownloads.DIR}/$name")
            .setAllowedOverMetered(true).setAllowedOverRoaming(true)
        if (!mimeType.isNullOrBlank()) runCatching { req.setMimeType(mimeType) }
        val dm = app.getSystemService(DownloadManager::class.java)
        val dlId = runCatching { dm?.enqueue(req) }.getOrNull()
        if (dlId == null) {
            _downloads.value = _downloads.value + DownloadState(id = -System.currentTimeMillis(), url = url, fileName = name, status = DownloadStatus.FAILED)
            lastDownloadError = "Could not start the download of $name."
            return
        }
        lastDownloadName = name; lastDownloadError = null
        _downloads.value = _downloads.value + DownloadState(id = dlId, url = url, fileName = name)
        ensurePoller()
    }

    private fun ensurePoller() {
        if (pollJob?.isActive == true) return
        pollJob = pollScope.launch {
            while (true) {
                val active = _downloads.value.filter { it.status == DownloadStatus.DOWNLOADING }
                if (active.isEmpty()) break
                active.forEach { pollDownload(it) }
                delay(500)
            }
        }
    }

    private fun pollDownload(d: DownloadState) = runCatching {
        val dm = app.getSystemService(DownloadManager::class.java) ?: return@runCatching
        val cursor = runCatching { dm.query(DownloadManager.Query().setFilterById(d.id)) }.getOrNull() ?: return@runCatching
        cursor.use {
            if (!it.moveToFirst()) { updateDownload(d.id) { copy(status = DownloadStatus.FAILED) }; return@runCatching }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val received = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> updateDownload(d.id) { copy(bytesReceived = received, totalBytes = total, status = DownloadStatus.COMPLETE) }
                DownloadManager.STATUS_FAILED -> updateDownload(d.id) { copy(bytesReceived = received, totalBytes = total, status = DownloadStatus.FAILED) }
                else -> updateDownload(d.id) { copy(bytesReceived = received, totalBytes = total) }
            }
        }
    }

    private fun updateDownload(id: Long, f: DownloadState.() -> DownloadState) {
        _downloads.value = _downloads.value.map { if (it.id == id) it.f() else it }
    }

    /** Opens a finished download with the app that handles its file type. False when it is not finished or cannot be opened. */
    fun openDownload(downloadId: Long): Boolean {
        val state = _downloads.value.firstOrNull { it.id == downloadId } ?: return false
        if (state.status != DownloadStatus.COMPLETE) return false
        val dm = app.getSystemService(DownloadManager::class.java) ?: return false
        val uri = runCatching { dm.getUriForDownloadedFile(downloadId) }.getOrNull() ?: return false
        val ext = state.fileName.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { app.startActivity(intent); true }.getOrDefault(false)
    }

    // ---- action plumbing ----

    private suspend fun <T> action(block: suspend () -> T): T = mutex.withLock { withContext(Dispatchers.Main) { block() } }

    private suspend fun awaitLoad(timeoutMs: Long) {
        val d = loadDone ?: return
        if (!d.isCompleted) withTimeoutOrNull(timeoutMs) { d.await() }
        delay(350)   // a breath for late JS to settle before the next step reads the page
    }

    private suspend fun eval(js: String): JsonObject? {
        val view = ensureWv()
        val raw = suspendCancellableCoroutine<String?> { cont ->
            try { view.evaluateJavascript(js) { r -> if (cont.isActive) cont.resume(r) } }
            catch (e: Throwable) { if (cont.isActive) cont.resume(null) }
        }
        if (raw.isNullOrBlank() || raw == "null") return null
        val inner = runCatching { json.parseToJsonElement(raw).jsonPrimitive.contentOrNull }.getOrNull() ?: raw
        return runCatching { json.parseToJsonElement(inner).jsonObject }.getOrNull()
    }

    private fun statusLine(prefix: String): String = buildString {
        append(formatStatusLine(prefix, _title.value, _url.value))
        if (_loading.value) append("  [still loading — browser_read again in a moment]")
        lastError?.let { append("  [load note: $it]") }
        lastBlocked?.let { append("  [the page tried to open $it — outside links like that are not followed]") }
        lastDownloadName?.let { append("  [download started: $it — saved in Downloads/Axion]") }
        lastDownloadError?.let { append("  [$it]") }
    }

    // ---- the operations (tools + screen share these; always the ACTIVE tab) ----

    /**
     * Agent calls pass agent=true: agent browsing always uses Obscura tabs (a blank
     * active WebView tab converts in place, otherwise a new Obscura tab opens).
     * The screen's calls leave agent=false and use the tab's own engine.
     */
    suspend fun open(rawUrl: String, agent: Boolean = false): String =
        runEngine(agent, ObscuraMove.NAVIGATE, true, { open(rawUrl) }, { open(rawUrl) })

    /** The screen's address bar: a URL opens, anything else searches. */
    suspend fun openSmart(text: String): String =
        runEngine(false, ObscuraMove.NAVIGATE, true, { openSmart(text) }, { openSmart(text) })

    suspend fun search(query: String, agent: Boolean = false): String =
        runEngine(agent, ObscuraMove.NAVIGATE, true, { search(query) }, { search(query) })

    suspend fun read(): BrowserPage =
        runEngine(false, ObscuraMove.MAYBE, false, { read() }, { read() })

    suspend fun click(index: Int?, text: String?): String =
        runEngine(false, ObscuraMove.MAYBE, true, { click(index, text) }, { click(index, text) })

    suspend fun type(index: Int, text: String, submit: Boolean): String =
        runEngine(false, ObscuraMove.MAYBE, submit, { type(index, text, submit) }, { type(index, text, submit) })

    suspend fun scroll(down: Boolean, amountPx: Int?): String =
        runEngine(false, ObscuraMove.MAYBE, false, { scroll(down, amountPx) }, { scroll(down, amountPx) })

    suspend fun back(): String =
        runEngine(false, ObscuraMove.BACK, true, { back() }, { back() })

    suspend fun forward(): String =
        runEngine(false, ObscuraMove.FORWARD, true, { forward() }, { forward() })

    suspend fun reload(): String =
        runEngine(false, ObscuraMove.MAYBE, true, { reload() }, { reload() })

    fun stopLoading() {
        views[tabManager.activeId]?.stopLoading(); _loading.value = false; loadDone?.complete(Unit)
    }

    /** A picture of what the browser shows right now (the visible viewport), saved as a PNG. */
    suspend fun screenshot(dir: File): File =
        runEngine(false, ObscuraMove.MAYBE, false, { screenshot(dir) }, { screenshot(dir) })

    // ---- engine routing (0.9.6) ----

    /** How an Obscura operation moves the active tab's history in the tab manager. */
    private enum class ObscuraMove { NAVIGATE, BACK, FORWARD, MAYBE }

    /**
     * Runs one operation on the active tab's engine: WebView work on the main
     * thread, Obscura work on IO (the MCP stdio calls block). Agent calls first
     * make sure the agent has an Obscura tab. After an Obscura operation the
     * tab record and the shared flows follow the engine's page, and the Browser
     * screen's Obscura view refreshes when the page may have changed.
     */
    private suspend fun <T> runEngine(
        agent: Boolean,
        move: ObscuraMove,
        refreshView: Boolean,
        obscuraBlock: suspend ObscuraEngine.() -> T,
        webBlock: suspend WebViewEngine.() -> T,
    ): T = mutex.withLock {
        if (agent && !ensureAgentTabLocked()) error("Maximum 10 tabs are open — close one first.")
        if (tabManager.active.engine == BrowserEngineKind.OBSCURA) {
            withContext(Dispatchers.IO) {
                ensureObscuraReady()
                val engine = obscuraEngine ?: error("Obscura engine is not ready.")
                val result = engine.obscuraBlock()
                syncObscuraTab(move)
                if (refreshView) refreshObscuraViewAsync()
                result
            }
        } else {
            withContext(Dispatchers.Main) { webView.webBlock() }
        }
    }

    /**
     * Agent browsing always uses Obscura: converts a blank active WebView tab
     * in place (its WebView, if the screen ever created one, is destroyed on
     * the main thread), otherwise opens a new Obscura tab. False only when the
     * tab limit blocks that new tab. Runs under the controller mutex.
     */
    private suspend fun ensureAgentTabLocked(): Boolean {
        val tab = tabManager.active
        if (tab.engine == BrowserEngineKind.OBSCURA) return true
        val convertingBlank = tab.url.isBlank()
        if (!tabManager.ensureAgentObscuraTab()) return false
        if (convertingBlank) {
            views.remove(tab.id)?.let { v -> withContext(Dispatchers.Main) { (v.parent as? ViewGroup)?.removeView(v); v.destroy() } }
        }
        syncActiveFlows()
        return true
    }

    /**
     * Makes the shared Obscura engine ready: installs Obscura first (download,
     * checksum, unpack — once, skipped by the version marker), then starts its
     * MCP server. Loud when Linux is missing or the install fails; never falls
     * back to the WebView for agent work.
     */
    private suspend fun ensureObscuraReady() = obscuraReadyMutex.withLock {
        if (obscuraEngine != null) return@withLock
        val rt = linuxProvider() ?: error("Linux is not installed yet — install it in Developer tools first.")
        if (!rt.isInstalled()) error("Linux is not installed yet — install it in Developer tools first.")
        _installStatus.value = "Getting Obscura ready…"
        try {
            ObscuraInstaller.ensureInstalled(rt) { msg -> _installStatus.value = msg }
            val dir = File(app.filesDir, "obscura").apply { mkdirs() }
            val server = McpServer(id = "obscura", name = "Obscura", url = "", transport = "stdio", command = "obscura mcp")
            obscuraEngine = ObscuraEngine(
                StdioMcpClient(server, rt, dir),
                checkReady = {
                    if (!rt.isInstalled()) error("Linux is not installed yet — install it in Developer tools first.")
                    if (!ObscuraInstaller.isInstalled(rt.rootfs)) error("Obscura is not installed — open the browser and choose Obscura to install it.")
                },
            )
        } finally {
            _installStatus.value = null
        }
    }

    /**
     * After an Obscura operation, mirrors the engine's page in the active tab's
     * record (URL, title, history) and in the shared flows, so the tab strip,
     * the address bar and the Browser screen all follow the Obscura page.
     */
    private fun syncObscuraTab(move: ObscuraMove) {
        val engine = obscuraEngine ?: return
        val tab = tabManager.active
        when (move) {
            ObscuraMove.NAVIGATE -> if (engine.currentUrl.isNotBlank()) tabManager.navigate(engine.currentUrl)
            ObscuraMove.BACK -> tabManager.goBack()
            ObscuraMove.FORWARD -> tabManager.goForward()
            ObscuraMove.MAYBE -> {
                val u = engine.currentUrl
                if (u.isNotBlank() && u != tab.url) tabManager.navigate(u)
            }
        }
        if (engine.currentUrl.isNotBlank()) tabManager.updateActive(engine.currentUrl, engine.currentTitle)
        val now = tabManager.active
        _url.value = now.url.ifBlank { engine.currentUrl }
        _title.value = now.title.ifBlank { engine.currentTitle }
        _loading.value = false
        _canBack.value = now.canGoBack
        _canForward.value = now.canGoForward
        _obscuraPage.value = engine.lastPage
        _obscuraShot.value = engine.lastScreenshot
        syncTabFlows()
    }

    private fun refreshObscuraViewAsync() {
        obscuraScope.launch { refreshObscuraView() }
    }

    /**
     * Refreshes the Browser screen's Obscura view (screenshot + readable text)
     * for the active Obscura tab. Never throws: a failed refresh leaves the
     * previous content. When the tab's recorded page is not the page the shared
     * engine is showing (the engine holds one page; switching tabs moves it),
     * the engine navigates back to this tab's page first. A blank tab shows
     * nothing yet — its empty state, not an error.
     */
    suspend fun refreshObscuraView() {
        val tabId = tabManager.activeId
        val tab = tabManager.active
        if (tab.engine != BrowserEngineKind.OBSCURA) return
        if (tab.url.isBlank()) { _obscuraPage.value = null; _obscuraShot.value = null; return }
        // The engine's MCP calls block their thread (stdio polling), so all of
        // this runs on IO even when the Browser screen calls it from the main thread.
        withContext(Dispatchers.IO) {
            if (!runCatching { ensureObscuraReady() }.isSuccess) return@withContext
            val engine = obscuraEngine ?: return@withContext
            if (tabManager.activeId != tabId) return@withContext
            if (engine.currentUrl != tab.url) runCatching { engine.open(tab.url) }
            if (tabManager.activeId != tabId) return@withContext
            val shotDir = File(app.filesDir, "obscura-shots").apply { mkdirs() }
            runCatching { engine.screenshot(shotDir) }
            runCatching { engine.read() }
            if (tabManager.activeId != tabId) return@withContext
            _obscuraShot.value = engine.lastScreenshot
            _obscuraPage.value = engine.lastPage
            syncObscuraTab(ObscuraMove.MAYBE)
        }
    }

    // ---- the WebView engine: the original operations, moved here unchanged ----

    /**
     * The WebView engine: one WebView per tab, JavaScript in the page, the same
     * status lines the tools always returned. It runs on the main thread (WebViews
     * demand it) and only ever touches the active tab. [BrowserController] routes
     * to it when the active tab's engine is [BrowserEngineKind.WEBVIEW].
     */
    inner class WebViewEngine : BrowserEngine {
        override suspend fun open(url: String): String {
            val u = normalizeUrl(url) ?: return "That does not look like a web address: \"$url\". Use browser_search for a search instead."
            lastError = null; lastBlocked = null; lastDownloadName = null; lastDownloadError = null
            loadDone = CompletableDeferred(); _loading.value = true
            tabManager.navigate(u); syncTabFlows()
            ensureWv().loadUrl(u)
            awaitLoad(30_000)
            return statusLine("Opened")
        }

        override suspend fun openSmart(text: String): String {
            val t = text.trim()
            return if (normalizeUrl(t) != null) open(t) else search(t)
        }

        override suspend fun search(query: String): String {
            if (query.isBlank()) return "Empty search."
            lastError = null; lastBlocked = null; lastDownloadName = null; lastDownloadError = null
            loadDone = CompletableDeferred(); _loading.value = true
            val searchUrl = duckDuckGoUrl(query)
            tabManager.navigate(searchUrl); syncTabFlows()
            ensureWv().loadUrl(searchUrl)
            awaitLoad(30_000)
            return statusLine("Search results")
        }

        override suspend fun read(): BrowserPage {
            val o = eval(BrowserScripts.EXTRACT) ?: throw IllegalStateException("Nothing to read yet — open a page first (browser_open).")
            val els = (o["elements"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { el ->
                val e = el as? JsonObject ?: return@mapNotNull null
                BrowserElement(
                    index = e["i"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    kind = e["kind"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    text = e["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    href = e["href"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    type = e["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = e["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    placeholder = e["ph"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            return BrowserPage(
                url = o["url"]?.jsonPrimitive?.contentOrNull ?: _url.value,
                title = o["title"]?.jsonPrimitive?.contentOrNull ?: _title.value,
                text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                elements = els,
            )
        }

        override suspend fun click(index: Int?, text: String?): String {
            val js = BrowserScripts.CLICK
                .replace("__IDX__", (index ?: -1).toString())
                .replace("__TEXT_JSON__", JsonPrimitive(text?.trim()?.lowercase().orEmpty()).toString())
            val o = eval(js) ?: return "Click failed — no page is open."
            // kotlinx parses a JSON boolean as a JsonPrimitive whose content is "true"/"false"
            if (o["ok"]?.jsonPrimitive?.contentOrNull != "true") return "Could not click: ${o["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error"}"
            delay(400)
            awaitLoad(12_000)
            return statusLine("Clicked \"${o["text"]?.jsonPrimitive?.contentOrNull.orEmpty()}\"")
        }

        override suspend fun type(index: Int, text: String, submit: Boolean): String {
            val js = BrowserScripts.TYPE
                .replace("__IDX__", index.toString())
                .replace("__VALUE_JSON__", JsonPrimitive(text).toString())
                .replace("__SUBMIT__", submit.toString())
            val o = eval(js) ?: return "Typing failed — no page is open."
            if (o["ok"]?.jsonPrimitive?.contentOrNull != "true") return "Could not type: ${o["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error"}"
            if (submit) { delay(400); awaitLoad(12_000); return statusLine("Typed and submitted") }
            return "Typed into the field."
        }

        override suspend fun scroll(down: Boolean, amountPx: Int?): String {
            val view = ensureWv()
            val dy = (amountPx ?: (view.height.takeIf { it > 0 } ?: 1200) * 85 / 100) * if (down) 1 else -1
            val o = eval(BrowserScripts.SCROLL.replace("__DY__", dy.toString()))
            val y = o?.get("y")?.jsonPrimitive?.contentOrNull ?: "?"
            val max = o?.get("max")?.jsonPrimitive?.contentOrNull ?: "?"
            return if (y == max || (down && y == max)) "Scrolled — bottom of the page reached (position $y of $max)." else "Scrolled to position $y of $max."
        }

        override suspend fun back(): String {
            val view = ensureWv()
            if (!view.canGoBack()) return "No page to go back to."
            loadDone = CompletableDeferred(); _loading.value = true
            view.goBack()
            tabManager.goBack()
            awaitLoad(15_000)
            return statusLine("Back")
        }

        override suspend fun forward(): String {
            val view = ensureWv()
            if (!view.canGoForward()) return "No page to go forward to."
            loadDone = CompletableDeferred(); _loading.value = true
            view.goForward()
            tabManager.goForward()
            awaitLoad(15_000)
            return statusLine("Forward")
        }

        override suspend fun reload(): String {
            loadDone = CompletableDeferred(); _loading.value = true
            ensureWv().reload(); awaitLoad(20_000)
            return statusLine("Reloaded")
        }

        override suspend fun screenshot(dir: File): File {
            val view = ensureWv()
            val w = view.width.takeIf { it > 0 } ?: 1080
            val h = view.height.takeIf { it > 0 } ?: 1900
            if (view.width == 0) {
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, w, h)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp); canvas.drawColor(Color.WHITE); view.draw(canvas)
            dir.mkdirs()
            val f = File(dir, "browser-${System.currentTimeMillis()}.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            return f
        }
    }
}
