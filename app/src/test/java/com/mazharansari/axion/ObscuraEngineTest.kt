package com.mazharansari.axion

import com.mazharansari.axion.browser.ObscuraEngine
import com.mazharansari.axion.browser.parseObscuraElements
import com.mazharansari.axion.browser.parseObscuraSnapshot
import com.mazharansari.axion.mcp.McpRpc
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 0.9.6: ObscuraEngine against a fake McpRpc — the tool mapping, the index-to-ref
 * map, the screenshot bytes, and the loud failures. No network, no Linux, no
 * Android. The one real-binary test lives in ObscuraRealBinaryTest.
 */
class ObscuraEngineTest {

    /** A 1x1 transparent PNG (70 bytes, verified PNG signature). */
    private val oneByOnePng =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    private class FakeObscuraRpc(
        var markdown: String = "# Example\n\nHello world",
        var elementsJson: String = """[{"ref":"e1","type":"link","text":"Home","href":"/"},{"ref":"e2","type":"textbox","text":"","placeholder":"Search","name":"q"}]""",
        var failTools: Set<String> = emptySet(),
        private val pngB64: String = "",
    ) : McpRpc {
        val calls = ArrayList<Pair<String, JsonObject>>()
        val rawCalls = ArrayList<Pair<String, JsonObject>>()
        var lastNavigatedUrl = ""

        override fun listTools(): List<JsonObject> = emptyList()
        override fun call(name: String, args: JsonObject): Pair<String, Boolean> {
            calls += name to args
            if (name in failTools) return "simulated failure" to true
            return when (name) {
                "browser_navigate" -> {
                    lastNavigatedUrl = args["url"]?.jsonPrimitive?.content.orEmpty()
                    "Navigated" to false
                }
                "browser_snapshot" -> buildJsonObject {
                    put("url", lastNavigatedUrl.ifBlank { "https://example.com/" })
                    put("title", "Example")
                }.toString() to false
                "browser_markdown" -> markdown to false
                "browser_interactive_elements" -> elementsJson to false
                "browser_click" -> "Clicked" to false
                "browser_type" -> "Typed" to false
                "browser_press_key" -> "Pressed" to false
                "browser_evaluate" -> """{"ok":true,"text":"home"}""" to false
                "browser_scroll" -> "Scrolled to position 800 of 2400." to false
                "browser_back", "browser_forward", "browser_reload" -> "Done" to false
                else -> "" to false
            }
        }
        override fun callRaw(name: String, args: JsonObject): JsonObject {
            rawCalls += name to args
            return Json.parseToJsonElement(
                """{"content":[{"type":"image","mimeType":"image/png","data":"$pngB64"}]}""",
            ).jsonObject
        }
        override fun listResources(): List<JsonObject> = emptyList()
        override fun readResource(uri: String) = ""
        override fun listPrompts(): List<JsonObject> = emptyList()
        override fun getPrompt(name: String, args: JsonObject) = ""
    }

    private fun engine(fake: FakeObscuraRpc = FakeObscuraRpc(pngB64 = oneByOnePng)) =
        ObscuraEngine(fake, settleMs = 0)

    private fun arg(fake: FakeObscuraRpc, tool: String, key: String): String? =
        fake.calls.first { it.first == tool }.second[key]?.jsonPrimitive?.content

    @Test fun openNavigatesAndReadsTitleFromSnapshot() = runBlocking {
        val fake = FakeObscuraRpc(pngB64 = oneByOnePng)
        val e = engine(fake)
        val status = e.open("example.com")
        assertEquals("https://example.com", arg(fake, "browser_navigate", "url"))
        assertEquals("https://example.com", e.currentUrl)
        assertEquals("Example", e.currentTitle)
        assertEquals("Opened: Example — https://example.com", status)
    }

    @Test fun readReturnsMarkdownWithSequentialElementIndices() = runBlocking {
        val fake = FakeObscuraRpc(pngB64 = oneByOnePng)
        val e = engine(fake)
        e.open("https://example.com/")
        val page = e.read()
        assertEquals("9000", arg(fake, "browser_markdown", "max_chars"))
        assertEquals("# Example\n\nHello world", page.text)
        assertEquals("https://example.com/", page.url)
        assertEquals(2, page.elements.size)
        assertEquals(listOf(1, 2), page.elements.map { it.index })
        assertEquals("link", page.elements[0].kind)
        assertEquals("Home", page.elements[0].text)
        assertEquals("/", page.elements[0].href)
        assertEquals("textbox", page.elements[1].kind)
        assertEquals("Search", page.elements[1].text)
        assertEquals("Search", page.elements[1].placeholder)
        assertEquals("q", page.elements[1].name)
    }

    @Test fun clickByIndexCallsBrowserClickWithRef() = runBlocking {
        val fake = FakeObscuraRpc(pngB64 = oneByOnePng)
        val e = engine(fake)
        e.open("https://example.com/")
        e.read()
        val status = e.click(1, null)
        assertEquals("e1", arg(fake, "browser_click", "ref"))
        assertTrue(status.contains("Clicked"))
        assertTrue(status.contains("Home"))
    }

    @Test fun typeWithSubmitCallsTypeThenEnter() = runBlocking {
        val fake = FakeObscuraRpc(pngB64 = oneByOnePng)
        val e = engine(fake)
        e.open("https://example.com/")
        e.read()
        val status = e.type(2, "hello", true)
        assertEquals("e2", arg(fake, "browser_type", "ref"))
        assertEquals("hello", arg(fake, "browser_type", "text"))
        assertEquals("Enter", arg(fake, "browser_press_key", "key"))
        assertTrue("type must run before Enter",
            fake.calls.indexOfFirst { it.first == "browser_type" } < fake.calls.indexOfFirst { it.first == "browser_press_key" })
        assertTrue(status.startsWith("Typed and submitted"))
    }

    @Test fun typeWithoutSubmitDoesNotPressEnter() = runBlocking {
        val fake = FakeObscuraRpc(pngB64 = oneByOnePng)
        val e = engine(fake)
        e.open("https://example.com/")
        e.read()
        assertEquals("Typed into the field.", e.type(2, "hello", false))
        assertFalse(fake.calls.any { it.first == "browser_press_key" })
    }

    @Test fun searchNavigatesToDuckDuckGo() = runBlocking {
        val fake = FakeObscuraRpc(pngB64 = oneByOnePng)
        val e = engine(fake)
        val status = e.search("axion browser")
        val url = arg(fake, "browser_navigate", "url").orEmpty()
        assertTrue(url.startsWith("https://html.duckduckgo.com/html/?q="))
        assertTrue(url.contains("axion"))
        assertEquals(url, e.currentUrl)
        assertTrue(status.startsWith("Search results"))
    }

    @Test fun openRejectsSearchPhrases() = runBlocking {
        val e = engine()
        assertEquals(
            "That does not look like a web address: \"hello world\". Use browser_search for a search instead.",
            e.open("hello world"),
        )
    }

    @Test fun searchRejectsEmptyQuery() = runBlocking {
        assertEquals("Empty search.", engine().search("   "))
    }

    @Test fun readWithNoPageThrows() = runBlocking {
        try {
            engine().read()
            fail("read with no page must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("open a page first"))
        }
    }

    @Test fun clickUnknownIndexFailsHonestly() = runBlocking {
        val e = engine()
        e.open("https://example.com/")
        e.read()
        assertEquals(
            "Could not click: element not found — call browser_read again for fresh numbers",
            e.click(99, null),
        )
    }

    @Test fun clickByTextUsesEvaluateFallback() = runBlocking {
        val fake = FakeObscuraRpc(pngB64 = oneByOnePng)
        val e = engine(fake)
        val status = e.click(null, "home")
        assertTrue(fake.calls.any { it.first == "browser_evaluate" })
        assertFalse("a text click must not call browser_click without a ref", fake.calls.any { it.first == "browser_click" })
        assertTrue(status.contains("Clicked"))
        assertTrue(status.contains("home"))
    }

    @Test fun clickWithNoTargetAsksForOne() = runBlocking {
        assertEquals("Give an element number or its text.", engine().click(null, null))
    }

    @Test fun backWithNoHistoryReturnsFriendlyMessage() = runBlocking {
        val e = ObscuraEngine(FakeObscuraRpc(failTools = setOf("browser_back"), pngB64 = oneByOnePng), settleMs = 0)
        e.open("https://example.com/")
        assertEquals("No page to go back to.", e.back())
    }

    @Test fun forwardWithNoHistoryReturnsFriendlyMessage() = runBlocking {
        val e = ObscuraEngine(FakeObscuraRpc(failTools = setOf("browser_forward"), pngB64 = oneByOnePng), settleMs = 0)
        e.open("https://example.com/")
        assertEquals("No page to go forward to.", e.forward())
    }

    @Test fun scrollPassesThroughServerMessage() = runBlocking {
        val fake = FakeObscuraRpc(pngB64 = oneByOnePng)
        val e = engine(fake)
        assertEquals("Scrolled to position 800 of 2400.", e.scroll(true, null))
        assertEquals("down", arg(fake, "browser_scroll", "direction"))
        assertEquals("800", arg(fake, "browser_scroll", "amount"))
    }

    @Test fun screenshotDecodesPngToFile() = runBlocking {
        val fake = FakeObscuraRpc(pngB64 = oneByOnePng)
        val e = engine(fake)
        val dir = File.createTempFile("obscura-shot", "").apply { delete(); mkdirs() }
        try {
            val f = e.screenshot(dir)
            assertTrue(f.name.startsWith("browser-") && f.name.endsWith(".png"))
            assertTrue(f.isFile && f.length() > 0)
            val bytes = f.readBytes()
            val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            assertTrue("saved file must be a real PNG", bytes.copyOf(8).contentEquals(sig))
            assertEquals(f, e.lastScreenshot)
            assertEquals("browser_screenshot", fake.rawCalls.single().first)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun engineRefusesWhenNotReady() = runBlocking {
        val e = ObscuraEngine(
            FakeObscuraRpc(pngB64 = oneByOnePng),
            checkReady = { error("Linux is not installed yet") },
            settleMs = 0,
        )
        try {
            e.open("https://example.com/")
            fail("a missing Linux must fail loudly")
        } catch (err: IllegalStateException) {
            assertTrue(err.message!!.contains("Linux is not installed"))
        }
    }

    @Test fun parseSnapshotJson() {
        assertEquals(
            "https://a.example/" to "A",
            parseObscuraSnapshot("""{"url":"https://a.example/","title":"A"}"""),
        )
    }

    @Test fun parseSnapshotLines() {
        assertEquals(
            "https://b.example/" to "Bee",
            parseObscuraSnapshot("Page loaded\nURL: https://b.example/\nTitle: Bee\n"),
        )
    }

    @Test fun parseElementsJson() {
        val pairs = parseObscuraElements(
            """[{"ref":"e3","type":"link","text":"Docs","href":"/docs"},{"ref":"e4","type":"button","text":"Go"}]""",
        )
        assertEquals(2, pairs.size)
        assertEquals(1, pairs[0].first.index)
        assertEquals("e3", pairs[0].second)
        assertEquals("Docs", pairs[0].first.text)
        assertEquals("/docs", pairs[0].first.href)
        assertEquals(2, pairs[1].first.index)
        assertEquals("e4", pairs[1].second)
    }

    @Test fun parseElementsLines() {
        val pairs = parseObscuraElements("e1 [link] Home -> /\ne2 [button] Go\n")
        assertEquals(2, pairs.size)
        assertEquals(1, pairs[0].first.index)
        assertEquals("link", pairs[0].first.kind)
        assertEquals("Home", pairs[0].first.text)
        assertEquals("/", pairs[0].first.href)
        assertEquals("e2", pairs[1].second)
    }
}
