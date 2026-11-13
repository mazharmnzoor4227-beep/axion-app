package com.mazharansari.axion

import com.mazharansari.axion.browser.BrowserTabManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-7: tab state — each tab keeps its own URL, title and back/forward history,
 * and switching tabs restores that tab's page.
 */
class BrowserTabsTest {
    @Test fun startsWithOneBlankTab() {
        val m = BrowserTabManager()
        assertEquals(1, m.tabs.size)
        assertEquals("", m.active.url)
        assertEquals("New tab", m.active.displayName())
    }

    @Test fun newTabBecomesActiveAndOldTabKeepsItsPage() {
        val m = BrowserTabManager()
        m.navigate("https://example.com/")
        m.updateActive("https://example.com/", "Example")
        val firstId = m.activeId
        assertTrue(m.newTab())
        assertEquals("", m.active.url)
        assertTrue(m.switchTo(firstId))
        assertEquals("https://example.com/", m.active.url)
        assertEquals("Example", m.active.title)
    }

    @Test fun atLeastThreeTabs() {
        val m = BrowserTabManager()
        assertTrue(m.newTab())
        assertTrue(m.newTab())
        assertEquals(3, m.tabs.size)
    }

    @Test fun tabLimitIsTen() {
        val m = BrowserTabManager()
        repeat(9) { assertTrue(m.newTab()) }
        assertEquals(10, m.tabs.size)
        assertFalse(m.newTab())
        assertEquals(10, m.tabs.size)
    }

    @Test fun switchToUnknownTabFailsAndKeepsActive() {
        val m = BrowserTabManager()
        val id = m.activeId
        assertFalse(m.switchTo("no-such-tab"))
        assertEquals(id, m.activeId)
    }

    @Test fun navigateBuildsHistoryAndBackForwardWalkIt() {
        val m = BrowserTabManager()
        m.navigate("https://a.example/")
        m.navigate("https://b.example/")
        m.navigate("https://c.example/")
        assertEquals("https://c.example/", m.active.url)
        assertTrue(m.active.canGoBack)
        assertFalse(m.active.canGoForward)
        assertEquals("https://b.example/", m.goBack())
        assertEquals("https://b.example/", m.active.url)
        assertTrue(m.active.canGoForward)
        assertEquals("https://a.example/", m.goBack())
        assertNull(m.goBack())
        assertEquals("https://a.example/", m.active.url)
        assertEquals("https://b.example/", m.goForward())
        assertEquals("https://c.example/", m.goForward())
        assertNull(m.goForward())
    }

    @Test fun navigateDropsForwardHistory() {
        val m = BrowserTabManager()
        m.navigate("https://a.example/")
        m.navigate("https://b.example/")
        m.goBack()
        m.navigate("https://c.example/")
        assertFalse(m.active.canGoForward)
        assertEquals("https://b.example/", m.goBack())
        assertEquals("https://c.example/", m.goForward())
    }

    @Test fun historyIsPerTab() {
        val m = BrowserTabManager()
        m.navigate("https://a.example/")
        val firstId = m.activeId
        m.newTab()
        m.navigate("https://x.example/")
        m.navigate("https://y.example/")
        assertEquals("https://y.example/", m.active.url)
        m.switchTo(firstId)
        assertEquals("https://a.example/", m.active.url)
        assertFalse(m.active.canGoBack)
    }

    @Test fun closeTabMovesActiveToNeighbour() {
        val m = BrowserTabManager()
        m.navigate("https://a.example/")
        val firstId = m.activeId
        m.newTab()
        m.navigate("https://b.example/")
        assertTrue(m.closeTab(m.activeId))
        assertEquals(1, m.tabs.size)
        assertEquals(firstId, m.activeId)
        assertEquals("https://a.example/", m.active.url)
    }

    @Test fun closeUnknownTabFails() {
        val m = BrowserTabManager()
        assertFalse(m.closeTab("no-such-tab"))
        assertEquals(1, m.tabs.size)
    }

    @Test fun closingLastTabResetsItToBlank() {
        val m = BrowserTabManager()
        m.navigate("https://a.example/")
        val id = m.activeId
        assertTrue(m.closeTab(id))
        assertEquals(1, m.tabs.size)
        assertEquals(id, m.activeId)
        assertEquals("", m.active.url)
    }

    @Test fun updateActiveDoesNotTouchHistory() {
        val m = BrowserTabManager()
        m.navigate("https://a.example/")
        m.navigate("https://b.example/")
        m.updateActive("https://b.example/page", "Bee")
        assertEquals("Bee", m.active.title)
        assertEquals("https://a.example/", m.goBack())
    }

    @Test fun displayNamePrefersTitleThenHost() {
        val m = BrowserTabManager()
        m.navigate("https://www.example.com/some/page")
        assertEquals("example.com", m.active.displayName())
        m.updateActive("https://www.example.com/some/page", "Example Page")
        assertEquals("Example Page", m.active.displayName())
    }
}
