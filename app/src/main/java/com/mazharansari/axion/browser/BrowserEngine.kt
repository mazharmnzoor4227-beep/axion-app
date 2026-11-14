package com.mazharansari.axion.browser

import java.io.File

/**
 * Which engine renders a tab: the built-in WebView, or the full Obscura browser
 * engine (Obscura's MCP server inside on-device Linux). The user picks per tab
 * in the Browser screen; the agent always uses Obscura.
 */
enum class BrowserEngineKind {
    WEBVIEW, OBSCURA;

    /** How the choice is stored in Settings (lastUserBrowserEngine). */
    val stored: String get() = if (this == OBSCURA) "obscura" else "webview"

    companion object {
        fun fromStored(value: String): BrowserEngineKind = if (value == "obscura") OBSCURA else WEBVIEW
    }
}

/**
 * One method set, both engines implement it: open a page, read it, click and type
 * by element number, scroll, walk history, and take a screenshot. The agent tools
 * and the Browser screen talk to this interface only, through [BrowserController],
 * so the engine behind the active tab can change without the callers noticing.
 * Every method returns the same status-line wording whichever engine runs it.
 */
interface BrowserEngine {
    suspend fun open(url: String): String
    suspend fun openSmart(text: String): String
    suspend fun search(query: String): String
    suspend fun read(): BrowserPage
    suspend fun click(index: Int?, text: String?): String
    suspend fun type(index: Int, text: String, submit: Boolean): String
    suspend fun scroll(down: Boolean, amountPx: Int?): String
    suspend fun back(): String
    suspend fun forward(): String
    suspend fun reload(): String
    suspend fun screenshot(dir: File): File
}

/** "example.com" becomes https://example.com; a search phrase becomes null (search it instead). Shared by both engines. */
internal fun normalizeUrl(raw: String): String? {
    val u = raw.trim()
    if (u.isEmpty()) return null
    if (u.startsWith("http://") || u.startsWith("https://")) return u
    if (!u.contains('.') || u.contains(' ')) return null
    return "https://$u"
}

/** The DuckDuckGo results URL both engines search with. */
internal fun duckDuckGoUrl(query: String): String =
    "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8")

/** "Opened: Title — https://…" — the status line both engines return, same wording. */
internal fun formatStatusLine(prefix: String, title: String, url: String): String =
    "$prefix: ${title.ifBlank { "(no title)" }} — $url"
