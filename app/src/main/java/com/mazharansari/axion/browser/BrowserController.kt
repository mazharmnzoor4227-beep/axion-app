package com.mazharansari.axion.browser

import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mazharansari.axion.llm.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.net.URLEncoder
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
 * Axion's own browser: ONE WebView, owned here, shared by the Browser screen (the user watches and can take over)
 * and by the agent's browser_* tools. The WebView is created lazily on the main thread with a swappable context —
 * the app context while only the agent uses it, the activity while the Browser screen shows it — so it survives
 * navigation between screens and keeps its page, cookies and login state. All agent actions are serialised.
 */
class BrowserController(private val app: Application) {
    private val mutex = Mutex()
    private val wrapper = MutableContextWrapper(app)
    @Volatile private var wv: WebView? = null
    private val json = Json { ignoreUnknownKeys = true }

    private val _url = MutableStateFlow(""); val url: StateFlow<String> = _url.asStateFlow()
    private val _title = MutableStateFlow(""); val title: StateFlow<String> = _title.asStateFlow()
    private val _loading = MutableStateFlow(false); val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _progress = MutableStateFlow(0); val progress: StateFlow<Int> = _progress.asStateFlow()
    private val _canBack = MutableStateFlow(false); val canBack: StateFlow<Boolean> = _canBack.asStateFlow()
    private val _canForward = MutableStateFlow(false); val canForward: StateFlow<Boolean> = _canForward.asStateFlow()

    @Volatile private var lastError: String? = null
    @Volatile private var lastBlocked: String? = null
    @Volatile private var lastDownload: String? = null
    @Volatile private var loadDone: CompletableDeferred<Unit>? = null

    // ---- WebView creation (main thread only) ----

    private fun ensureWv(): WebView = wv ?: createWv().also { wv = it }

    private fun createWv(): WebView {
        val view = WebView(wrapper)
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
                _loading.value = true; _progress.value = 0
                if (u != null) _url.value = u
                // keep the waiter an operation already created (open/back/…): replacing it here would
                // strand that waiter until its timeout, because onPageFinished completes only the latest one
                if (loadDone?.isCompleted != false) loadDone = CompletableDeferred()
            }
            override fun onPageFinished(v: WebView, u: String?) {
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
            override fun onProgressChanged(v: WebView, p: Int) { _progress.value = p; if (p == 100) _title.value = v.title.orEmpty() }
            override fun onReceivedTitle(v: WebView, t: String?) { _title.value = t.orEmpty() }
        }
        view.setDownloadListener { u, _, _, _, _ -> lastDownload = u }   // noted, never fetched behind the user's back
        return view
    }

    /** For the Browser screen: attach the shared WebView to the visible activity. Main thread (Compose factory). */
    fun displayView(activityContext: Context): View {
        wrapper.baseContext = activityContext
        val view = ensureWv()
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    /** The Browser screen went away: keep the WebView (and its page) alive on the app context. Main thread. */
    fun releaseDisplay() {
        wv?.let { (it.parent as? ViewGroup)?.removeView(it) }
        wrapper.baseContext = app
    }

    /** First open of the Browser screen: start somewhere real if the agent has not already opened a page. */
    suspend fun ensureStarted() { if (_url.value.isBlank()) open("https://duckduckgo.com/") }

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
        append(prefix).append(": ").append(_title.value.ifBlank { "(no title)" }).append(" — ").append(_url.value)
        if (_loading.value) append("  [still loading — browser_read again in a moment]")
        lastError?.let { append("  [load note: $it]") }
        lastBlocked?.let { append("  [the page tried to open $it — outside links like that are not followed]") }
        lastDownload?.let { append("  [the page offered a file download: $it — the browser does not save downloads; use browse_url or run_command with curl for files]") }
    }

    private fun normalize(raw: String): String? {
        val u = raw.trim()
        if (u.isEmpty()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (!u.contains('.') || u.contains(' ')) return null
        return "https://$u"
    }

    // ---- the operations (tools + screen share these) ----

    suspend fun open(rawUrl: String): String = action {
        val u = normalize(rawUrl) ?: return@action "That does not look like a web address: \"$rawUrl\". Use browser_search for a search instead."
        lastError = null; lastBlocked = null; lastDownload = null
        loadDone = CompletableDeferred(); _loading.value = true
        ensureWv().loadUrl(u)
        awaitLoad(30_000)
        statusLine("Opened")
    }

    /** The screen's address bar: a URL opens, anything else searches. */
    suspend fun openSmart(text: String): String {
        val t = text.trim()
        return if (normalize(t) != null) open(t) else search(t)
    }

    suspend fun search(query: String): String = action {
        if (query.isBlank()) return@action "Empty search."
        lastError = null; lastBlocked = null; lastDownload = null
        loadDone = CompletableDeferred(); _loading.value = true
        ensureWv().loadUrl("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))
        awaitLoad(30_000)
        statusLine("Search results")
    }

    suspend fun read(): BrowserPage = action {
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
        BrowserPage(
            url = o["url"]?.jsonPrimitive?.contentOrNull ?: _url.value,
            title = o["title"]?.jsonPrimitive?.contentOrNull ?: _title.value,
            text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            elements = els,
        )
    }

    suspend fun click(index: Int?, text: String?): String = action {
        val js = BrowserScripts.CLICK
            .replace("__IDX__", (index ?: -1).toString())
            .replace("__TEXT_JSON__", JsonPrimitive(text?.trim()?.lowercase().orEmpty()).toString())
        val o = eval(js) ?: return@action "Click failed — no page is open."
        // kotlinx parses a JSON boolean as a JsonPrimitive whose content is "true"/"false"
        if (o["ok"]?.jsonPrimitive?.contentOrNull != "true") return@action "Could not click: ${o["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error"}"
        delay(400)
        awaitLoad(12_000)
        statusLine("Clicked \"${o["text"]?.jsonPrimitive?.contentOrNull.orEmpty()}\"")
    }

    suspend fun type(index: Int, text: String, submit: Boolean): String = action {
        val js = BrowserScripts.TYPE
            .replace("__IDX__", index.toString())
            .replace("__VALUE_JSON__", JsonPrimitive(text).toString())
            .replace("__SUBMIT__", submit.toString())
        val o = eval(js) ?: return@action "Typing failed — no page is open."
        if (o["ok"]?.jsonPrimitive?.contentOrNull != "true") return@action "Could not type: ${o["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error"}"
        if (submit) { delay(400); awaitLoad(12_000); statusLine("Typed and submitted") }
        else "Typed into the field."
    }

    suspend fun scroll(down: Boolean, amountPx: Int?): String = action {
        val view = ensureWv()
        val dy = (amountPx ?: (view.height.takeIf { it > 0 } ?: 1200) * 85 / 100) * if (down) 1 else -1
        val o = eval(BrowserScripts.SCROLL.replace("__DY__", dy.toString()))
        val y = o?.get("y")?.jsonPrimitive?.contentOrNull ?: "?"
        val max = o?.get("max")?.jsonPrimitive?.contentOrNull ?: "?"
        if (y == max || (down && y == max)) "Scrolled — bottom of the page reached (position $y of $max)." else "Scrolled to position $y of $max."
    }

    suspend fun back(): String = action {
        val view = ensureWv()
        if (!view.canGoBack()) return@action "No page to go back to."
        loadDone = CompletableDeferred(); _loading.value = true
        view.goBack(); awaitLoad(15_000)
        statusLine("Back")
    }

    suspend fun forward(): String = action {
        val view = ensureWv()
        if (!view.canGoForward()) return@action "No page to go forward to."
        loadDone = CompletableDeferred(); _loading.value = true
        view.goForward(); awaitLoad(15_000)
        statusLine("Forward")
    }

    suspend fun reload(): String = action {
        loadDone = CompletableDeferred(); _loading.value = true
        ensureWv().reload(); awaitLoad(20_000)
        statusLine("Reloaded")
    }

    fun stopLoading() {
        wv?.stopLoading(); _loading.value = false; loadDone?.complete(Unit)
    }

    /** A picture of what the browser shows right now (the visible viewport), saved as a PNG. */
    suspend fun screenshot(dir: File): File = action {
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
        f
    }
}
