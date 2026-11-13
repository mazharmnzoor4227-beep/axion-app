package com.mazharansari.axion.browser

import java.util.UUID

/**
 * P1-7 — tab state for Axion's own browser, pure Kotlin (no Android calls, unit-tested
 * in BrowserTabsTest).
 *
 * One tab = one page. Each tab keeps its own URL, title and back/forward history
 * ([history] with the current position at [historyIndex]); switching tabs restores
 * that tab's page — the [BrowserController] keeps one WebView per tab, so the tab's
 * real page, cookies and login state survive the switch as well. The history list
 * here mirrors the WebView's own back/forward stack so the tab strip and the
 * agent can reason about it without touching a View.
 */
data class BrowserTabState(
    val id: String,
    val url: String = "",
    val title: String = "",
    val history: List<String> = emptyList(),
    val historyIndex: Int = -1,
) {
    val canGoBack: Boolean get() = historyIndex > 0
    val canGoForward: Boolean get() = historyIndex >= 0 && historyIndex < history.lastIndex
    /** What the tab strip shows: the title, the host, or "New tab" for a blank tab. */
    fun displayName(): String {
        if (title.isNotBlank()) return title
        if (url.isNotBlank()) return runCatching { java.net.URI(url).host?.removePrefix("www.") ?: url }.getOrDefault(url)
        return "New tab"
    }
}

class BrowserTabManager {
    companion object {
        /** At least 3 tabs (the P1-7 acceptance bar); 10 keeps the tab strip usable. */
        const val MIN_TABS = 3
        const val MAX_TABS = 10
        const val START_URL = "https://duckduckgo.com/"
    }

    private val _tabs = mutableListOf<BrowserTabState>()
    val tabs: List<BrowserTabState> get() = _tabs.toList()

    private var _activeId: String? = null
    val active: BrowserTabState get() = _tabs.firstOrNull { it.id == _activeId } ?: _tabs.first()
    val activeId: String get() = active.id

    init {
        val first = BrowserTabState(id = UUID.randomUUID().toString())
        _tabs += first
        _activeId = first.id
    }

    private fun replace(tab: BrowserTabState) {
        val i = _tabs.indexOfFirst { it.id == tab.id }
        if (i >= 0) _tabs[i] = tab
    }

    /** Opens a new blank tab and makes it active. False when the tab limit is reached. */
    fun newTab(): Boolean {
        if (_tabs.size >= MAX_TABS) return false
        val tab = BrowserTabState(id = UUID.randomUUID().toString())
        _tabs += tab
        _activeId = tab.id
        return true
    }

    /** Switches to [id]. False when there is no such tab (the active tab is unchanged). */
    fun switchTo(id: String): Boolean {
        if (_tabs.none { it.id == id }) return false
        _activeId = id
        return true
    }

    /**
     * Closes [id]. False when there is no such tab. The last tab is never really
     * removed — closing it resets it to a blank tab, so the browser always has
     * a page to show. The active tab moves to a neighbour when its own tab closes.
     */
    fun closeTab(id: String): Boolean {
        val i = _tabs.indexOfFirst { it.id == id }
        if (i < 0) return false
        if (_tabs.size == 1) {
            _tabs[0] = BrowserTabState(id = _tabs[0].id)
            _activeId = _tabs[0].id
            return true
        }
        val wasActive = _tabs[i].id == _activeId
        _tabs.removeAt(i)
        if (wasActive) _activeId = _tabs[minOf(i, _tabs.lastIndex)].id
        return true
    }

    /** Records a navigation in the active tab: the URL becomes current, the forward stack is dropped. */
    fun navigate(url: String) {
        val tab = active
        val kept = if (tab.historyIndex >= 0) tab.history.take(tab.historyIndex + 1) else emptyList()
        val history = (kept + url).takeLast(100)
        replace(tab.copy(url = url, history = history, historyIndex = history.lastIndex))
    }

    /** Moves the active tab one step back in its own history. Null when already at the first page. */
    fun goBack(): String? {
        val tab = active
        if (!tab.canGoBack) return null
        val next = tab.history[tab.historyIndex - 1]
        replace(tab.copy(url = next, historyIndex = tab.historyIndex - 1))
        return next
    }

    /** Moves the active tab one step forward in its own history. Null when already at the newest page. */
    fun goForward(): String? {
        val tab = active
        if (!tab.canGoForward) return null
        val next = tab.history[tab.historyIndex + 1]
        replace(tab.copy(url = next, historyIndex = tab.historyIndex + 1))
        return next
    }

    /**
     * Syncs the active tab's URL/title from the WebView (redirects, page titles,
     * back/forward through the WebView itself). This never touches [history]:
     * history moves only through [navigate]/[goBack]/[goForward].
     */
    fun updateActive(url: String, title: String) {
        val tab = active
        if (tab.url == url && tab.title == title) return
        replace(tab.copy(url = url, title = title))
    }

    /** Syncs a background tab's URL/title (its WebView finished loading while another tab was active). */
    fun updateTab(id: String, url: String, title: String) {
        val tab = _tabs.firstOrNull { it.id == id } ?: return
        if (tab.url == url && tab.title == title) return
        replace(tab.copy(url = url, title = title))
    }
}
