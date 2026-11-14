package com.mazharansari.axion

import com.mazharansari.axion.browser.BrowserEngineKind
import com.mazharansari.axion.browser.BrowserTabManager
import com.mazharansari.axion.browser.duckDuckGoUrl
import com.mazharansari.axion.browser.normalizeUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.9.6: tabs carry an engine, and agent browsing always uses Obscura — a
 * blank active tab converts in place, otherwise a new Obscura tab opens.
 * Pure tab-manager logic (the controller's wiring around it is the same two
 * calls); no Android, no network.
 */
class BrowserEngineTabTest {

    @Test fun initialTabIsWebView() {
        val m = BrowserTabManager()
        assertEquals(BrowserEngineKind.WEBVIEW, m.active.engine)
        assertEquals("", m.active.url)
    }

    @Test fun newTabAssignsRequestedEngine() {
        val m = BrowserTabManager()
        assertTrue(m.newTab(BrowserEngineKind.OBSCURA))
        assertEquals(BrowserEngineKind.OBSCURA, m.active.engine)
        assertEquals(2, m.tabs.size)
        assertEquals(BrowserEngineKind.WEBVIEW, m.tabs[0].engine)
    }

    @Test fun newTabDefaultsToWebView() {
        val m = BrowserTabManager()
        assertTrue(m.newTab())
        assertEquals(BrowserEngineKind.WEBVIEW, m.active.engine)
    }

    @Test fun agentOpenConvertsBlankTabInPlace() {
        val m = BrowserTabManager()
        val id = m.activeId
        assertTrue(m.ensureAgentObscuraTab())
        assertEquals(1, m.tabs.size)
        assertEquals(id, m.activeId)
        assertEquals(BrowserEngineKind.OBSCURA, m.active.engine)
        assertEquals("", m.active.url)
    }

    @Test fun agentOpenSpawnsNewTabWhenActiveHasPage() {
        val m = BrowserTabManager()
        m.navigate("https://example.com/")
        val firstId = m.activeId
        assertTrue(m.ensureAgentObscuraTab())
        assertEquals(2, m.tabs.size)
        assertEquals(BrowserEngineKind.OBSCURA, m.active.engine)
        assertEquals("", m.active.url)
        m.switchTo(firstId)
        assertEquals(BrowserEngineKind.WEBVIEW, m.active.engine)
        assertEquals("https://example.com/", m.active.url)
    }

    @Test fun agentOpenKeepsObscuraTab() {
        val m = BrowserTabManager()
        assertTrue(m.ensureAgentObscuraTab())
        assertTrue(m.ensureAgentObscuraTab())
        assertEquals(1, m.tabs.size)
        assertEquals(BrowserEngineKind.OBSCURA, m.active.engine)
    }

    @Test fun agentOpenFailsAtTabLimit() {
        val m = BrowserTabManager()
        m.navigate("https://example.com/")
        val firstId = m.activeId
        repeat(9) { assertTrue(m.newTab()) }
        assertEquals(10, m.tabs.size)
        m.switchTo(firstId)
        assertFalse("no room for a new Obscura tab", m.ensureAgentObscuraTab())
        assertEquals(firstId, m.activeId)
        assertEquals(BrowserEngineKind.WEBVIEW, m.active.engine)
    }

    @Test fun closingLastObscuraTabResetsToWebView() {
        val m = BrowserTabManager()
        assertTrue(m.ensureAgentObscuraTab())
        assertEquals(BrowserEngineKind.OBSCURA, m.active.engine)
        val id = m.activeId
        assertTrue(m.closeTab(id))
        assertEquals(1, m.tabs.size)
        assertEquals(id, m.activeId)
        assertEquals(BrowserEngineKind.WEBVIEW, m.active.engine)
        assertEquals("", m.active.url)
    }

    @Test fun engineKindStoredRoundTrip() {
        assertEquals(BrowserEngineKind.OBSCURA, BrowserEngineKind.fromStored("obscura"))
        assertEquals(BrowserEngineKind.WEBVIEW, BrowserEngineKind.fromStored("webview"))
        assertEquals(BrowserEngineKind.WEBVIEW, BrowserEngineKind.fromStored("junk"))
        assertEquals("obscura", BrowserEngineKind.OBSCURA.stored)
        assertEquals("webview", BrowserEngineKind.WEBVIEW.stored)
    }

    @Test fun normalizeUrlAndSearchUrl() {
        assertEquals("https://example.com", normalizeUrl("example.com"))
        assertEquals("https://example.com", normalizeUrl("https://example.com"))
        assertEquals(null, normalizeUrl("two words here"))
        assertEquals(null, normalizeUrl("   "))
        val search = duckDuckGoUrl("axion browser")
        assertTrue(search.startsWith("https://html.duckduckgo.com/html/?q="))
        assertTrue(search.contains("axion"))
    }
}
