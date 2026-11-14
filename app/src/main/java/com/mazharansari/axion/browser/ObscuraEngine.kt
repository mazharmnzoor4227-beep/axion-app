package com.mazharansari.axion.browser

import com.mazharansari.axion.mcp.McpRpc
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

private val json = Json { ignoreUnknownKeys = true }

/** The 8-byte PNG signature — a screenshot that does not start with it is not a PNG. */
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/**
 * URL + title from a browser_snapshot result: JSON {"url":…, "title":…} first,
 * then "URL: …" / "Title: …" lines. Whatever the server did not say stays blank —
 * the caller keeps its previous value for a blank half.
 */
internal fun parseObscuraSnapshot(text: String): Pair<String, String> {
    runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        val url = o["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (url.isNotBlank() || title.isNotBlank()) return url to title
    }
    var url = ""; var title = ""
    text.lineSequence().forEach { raw ->
        val t = raw.trim()
        val lower = t.lowercase()
        if (lower.startsWith("url:") || lower.startsWith("current url:")) url = t.substringAfter(':').trim()
        else if (lower.startsWith("title:")) title = t.substringAfter(':').trim()
    }
    return url to title
}

private fun parseElementsJson(text: String): List<Pair<BrowserElement, String>>? {
    val el = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
    val arr = when (el) {
        is JsonArray -> el
        is JsonObject -> el["elements"]?.jsonArray
        else -> null
    } ?: return null
    val inputKinds = setOf("input", "textbox", "textarea", "select", "checkbox", "radio", "password", "search", "email", "number", "button", "submit")
    val out = ArrayList<Pair<BrowserElement, String>>()
    arr.forEach { item ->
        val o = item as? JsonObject ?: return@forEach
        val ref = o["ref"]?.jsonPrimitive?.contentOrNull
            ?: o["id"]?.jsonPrimitive?.contentOrNull
            ?: o["selector"]?.jsonPrimitive?.contentOrNull
            ?: return@forEach
        val kind = o["type"]?.jsonPrimitive?.contentOrNull
            ?: o["role"]?.jsonPrimitive?.contentOrNull
            ?: o["tag"]?.jsonPrimitive?.contentOrNull
            ?: "element"
        // The label is the visible text, then the placeholder, then the name —
        // the same fallback order the WebView extractor uses, so a search box
        // with no text still shows as its placeholder instead of blank.
        val label = o["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: o["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: o["placeholder"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: o["value"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val type = o["inputType"]?.jsonPrimitive?.contentOrNull
            ?: if (kind.lowercase() in inputKinds) o["type"]?.jsonPrimitive?.contentOrNull.orEmpty() else ""
        out += BrowserElement(
            index = out.size + 1, kind = kind, text = label.take(120),
            href = o["href"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            type = type,
            name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            placeholder = o["placeholder"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        ) to ref
    }
    return out
}

private fun parseElementsLines(text: String): List<Pair<BrowserElement, String>> {
    val out = ArrayList<Pair<BrowserElement, String>>()
    val lineRe = Regex("""^(e\d+)\s*(?:\[([^\]]+)\])?\s*(.*)$""")
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEach
        val m = lineRe.find(line) ?: return@forEach
        val ref = m.groupValues[1]
        val kind = m.groupValues[2].ifBlank { "element" }
        val rest = m.groupValues[3].trim()
        val (label, href) = if (" -> " in rest) rest.substringBefore(" -> ").trim() to rest.substringAfter(" -> ").trim() else rest to ""
        out += BrowserElement(index = out.size + 1, kind = kind, text = label.take(120), href = href) to ref
    }
    return out
}

/**
 * A browser_interactive_elements result as (element, ref) pairs. JSON first
 * (an array of objects, or {"elements": [...]} — ref/id/selector, type/role/tag,
 * text/label/name/value), then plain "e3 [link] Home -> /" lines. Indices are
 * sequential 1..N in returned order, so the numbers the agent sees are stable
 * for one read and the index-to-ref map below can resolve them.
 */
internal fun parseObscuraElements(text: String): List<Pair<BrowserElement, String>> {
    parseElementsJson(text)?.let { if (it.isNotEmpty()) return it }
    return parseElementsLines(text)
}

/**
 * The Obscura browser engine: Axion's agent browser over Obscura's MCP server
 * (`obscura mcp`, stdio, inside on-device Linux). The mapping, tool by tool:
 * open → browser_navigate + snapshot; read → browser_markdown +
 * browser_interactive_elements; click → browser_click by ref (browser_evaluate
 * click-by-text when only text is given); type → browser_type by ref, then
 * browser_press_key Enter when submit is set; scroll/back/forward/reload →
 * the matching tools; search → browser_navigate to the DuckDuckGo HTML URL;
 * screenshot → browser_screenshot via [McpRpc.callRaw] (the plain call drops
 * the image bytes), decoded and saved as a PNG.
 *
 * Every call runs [checkReady] first and fails loudly when Linux or Obscura is
 * not installed — it never falls back to the WebView for agent work. Calls are
 * serialised: the MCP server is one process and its stdio stream is not safe
 * for two writers at once.
 */
class ObscuraEngine(
    private val rpc: McpRpc,
    private val checkReady: () -> Unit = {},
    private val settleMs: Long = 600,
) : BrowserEngine {
    private val mutex = Mutex()
    private val refByIndex = LinkedHashMap<Int, String>()
    private val elementByIndex = LinkedHashMap<Int, BrowserElement>()

    /** The page the engine is showing now (from the last snapshot). */
    @Volatile var currentUrl: String = ""
        private set
    @Volatile var currentTitle: String = ""
        private set
    /** The last page read() returned (what the Browser screen shows for an Obscura tab). */
    @Volatile var lastPage: BrowserPage? = null
        private set
    /** The last screenshot file (what the Browser screen shows for an Obscura tab). */
    @Volatile var lastScreenshot: File? = null
        private set

    private fun status(prefix: String): String = formatStatusLine(prefix, currentTitle, currentUrl)

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    /** The raw (text, isError) answer; throws when the server reports an error. */
    private fun call(name: String, args: JsonObject = JsonObject(emptyMap())): String {
        val (text, isError) = rawCall(name, args)
        if (isError) error("Obscura $name failed: ${text.ifBlank { "unknown error" }}")
        return text
    }

    /** The raw (text, isError) answer, for callers that map an error to a friendly message themselves. */
    private fun rawCall(name: String, args: JsonObject = JsonObject(emptyMap())): Pair<String, Boolean> {
        checkReady()
        return rpc.call(name, args)
    }

    /** Best-effort refresh of the current URL/title from a snapshot; a failed snapshot leaves the old values. */
    private suspend fun refreshState() {
        delay(settleMs)
        val (url, title) = runCatching { parseObscuraSnapshot(call("browser_snapshot")) }.getOrNull() ?: ("" to "")
        if (url.isNotBlank()) currentUrl = url
        if (title.isNotBlank()) currentTitle = title
    }

    /** The {ok, text} answer of the click-by-text JS: the MCP result may be the JSON itself or a JSON string of it. */
    private fun parseEvalClick(text: String): Pair<Boolean, String> {
        val candidates = listOf(text, runCatching { json.parseToJsonElement(text).jsonPrimitive.contentOrNull }.getOrNull().orEmpty())
        for (c in candidates) {
            if (c.isBlank()) continue
            val o = runCatching { json.parseToJsonElement(c).jsonObject }.getOrNull() ?: continue
            val ok = o["ok"]?.jsonPrimitive?.booleanOrNull ?: continue
            return ok to (o["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        return false to ""
    }

    override suspend fun open(url: String): String {
        val u = normalizeUrl(url) ?: return "That does not look like a web address: \"$url\". Use browser_search for a search instead."
        return mutex.withLock {
            val (text, isError) = rawCall("browser_navigate", args("url" to u))
            if (isError) error("Could not open $u: ${text.ifBlank { "unknown error" }}")
            currentUrl = u
            refreshState()
            status("Opened")
        }
    }

    override suspend fun openSmart(text: String): String {
        val t = text.trim()
        return if (normalizeUrl(t) != null) open(t) else search(t)
    }

    override suspend fun search(query: String): String {
        if (query.isBlank()) return "Empty search."
        val searchUrl = duckDuckGoUrl(query)
        return mutex.withLock {
            val (text, isError) = rawCall("browser_navigate", args("url" to searchUrl))
            if (isError) error("Search failed: ${text.ifBlank { "unknown error" }}")
            currentUrl = searchUrl
            refreshState()
            status("Search results")
        }
    }

    override suspend fun read(): BrowserPage {
        if (currentUrl.isBlank()) throw IllegalStateException("Nothing to read yet — open a page first (browser_open).")
        return mutex.withLock {
            // Ask for the full text the tools clip to (9000 chars) instead of
            // the server's 4000-char default, matching the WebView read.
            val text = call("browser_markdown", buildJsonObject { put("max_chars", 9000) })
            val elementsText = call("browser_interactive_elements")
            refByIndex.clear(); elementByIndex.clear()
            parseObscuraElements(elementsText).forEach { (el, ref) -> refByIndex[el.index] = ref; elementByIndex[el.index] = el }
            val page = BrowserPage(url = currentUrl, title = currentTitle, text = text, elements = elementByIndex.values.toList())
            lastPage = page
            page
        }
    }

    override suspend fun click(index: Int?, text: String?): String {
        if (index == null && text.isNullOrBlank()) return "Give an element number or its text."
        return mutex.withLock {
            val clickedText: String
            if (index != null) {
                val ref = refByIndex[index] ?: return@withLock "Could not click: element not found — call browser_read again for fresh numbers"
                val (result, isError) = rawCall("browser_click", args("ref" to ref))
                if (isError) return@withLock "Could not click: ${result.ifBlank { "unknown error" }}"
                clickedText = elementByIndex[index]?.text.orEmpty()
            } else {
                // No number: find the first clickable whose visible text contains the query and click it in the page,
                // the same way the WebView engine does — the server's browser_search is a web search, not a page search.
                val q = text!!.trim()
                val js = """
                    (function(){
                    var q=${JsonPrimitive(q.lowercase())};
                    var nodes=document.querySelectorAll('a[href], button, [role="button"], [role="link"], input[type="submit"], input[type="button"]');
                    for(var i=0;i<nodes.length;i++){
                    var t=(nodes[i].innerText||nodes[i].textContent||nodes[i].getAttribute('value')||'').replace(/\s+/g,' ').trim().toLowerCase();
                    if(t&&t.indexOf(q)>=0){nodes[i].click();return JSON.stringify({ok:true,text:t.slice(0,80)})}
                    }
                    return JSON.stringify({ok:false,error:'element not found'})
                    })()
                """.trimIndent()
                val (ok, found) = parseEvalClick(call("browser_evaluate", args("expression" to js)))
                if (!ok) return@withLock "Could not click: element not found — call browser_read again for fresh numbers"
                clickedText = found
            }
            refreshState()
            status("Clicked \"$clickedText\"")
        }
    }

    override suspend fun type(index: Int, text: String, submit: Boolean): String {
        return mutex.withLock {
            val ref = refByIndex[index] ?: return@withLock "Could not type: field not found — call browser_read again for fresh numbers"
            val (result, isError) = rawCall("browser_type", args("ref" to ref, "text" to text))
            if (isError) return@withLock "Could not type: ${result.ifBlank { "unknown error" }}"
            if (!submit) return@withLock "Typed into the field."
            call("browser_press_key", args("key" to "Enter"))
            refreshState()
            status("Typed and submitted")
        }
    }

    override suspend fun scroll(down: Boolean, amountPx: Int?): String = mutex.withLock {
        val px = amountPx ?: 800
        // The server's schema names the pixel count "amount" (a number); the
        // default is one viewport height when it is omitted.
        val result = call("browser_scroll", buildJsonObject { put("direction", if (down) "down" else "up"); put("amount", px) })
        val t = result.trim()
        if (t.isNotBlank()) t.take(200) else if (down) "Scrolled down." else "Scrolled up."
    }

    override suspend fun back(): String = mutex.withLock {
        val (result, isError) = rawCall("browser_back")
        if (isError) return@withLock "No page to go back to."
        refreshState()
        status("Back")
    }

    override suspend fun forward(): String = mutex.withLock {
        val (result, isError) = rawCall("browser_forward")
        if (isError) return@withLock "No page to go forward to."
        refreshState()
        status("Forward")
    }

    override suspend fun reload(): String = mutex.withLock {
        call("browser_reload")
        refreshState()
        status("Reloaded")
    }

    override suspend fun screenshot(dir: File): File = mutex.withLock {
        checkReady()
        val result = rpc.callRaw("browser_screenshot", JsonObject(emptyMap()))
        val data = result["content"]?.jsonArray?.firstNotNullOfOrNull { item ->
            val o = item as? JsonObject ?: return@firstNotNullOfOrNull null
            if (o["type"]?.jsonPrimitive?.contentOrNull == "image") o["data"]?.jsonPrimitive?.contentOrNull else null
        } ?: error("Obscura did not return a screenshot image.")
        val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull()
            ?: error("Obscura returned a screenshot that is not valid base64.")
        if (bytes.size < PNG_SIGNATURE.size || !bytes.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            error("Obscura returned a screenshot that is not a PNG image.")
        }
        dir.mkdirs()
        val f = File(dir, "browser-${System.currentTimeMillis()}.png")
        FileOutputStream(f).use { it.write(bytes) }
        lastScreenshot = f
        f
    }
}
