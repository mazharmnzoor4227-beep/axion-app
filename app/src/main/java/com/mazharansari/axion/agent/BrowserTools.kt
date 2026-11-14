package com.mazharansari.axion.agent

import com.mazharansari.axion.browser.BrowserController
import com.mazharansari.axion.browser.BrowserHub
import com.mazharansari.axion.browser.BrowserPage
import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The agent's hands on Axion's own browser (browser/BrowserController): the Obscura engine —
 * JavaScript runs, cookies and logins persist, pages render exactly as the user sees them in the Browser screen.
 * browse_url stays the quick static read; these tools are for pages that need JS, forms, clicks or a login.
 */

private fun browser(): BrowserController =
    BrowserHub.browser ?: throw IllegalStateException("The browser is not available in this context.")

private fun formatPage(page: BrowserPage, maxChars: Int): String = buildString {
    appendLine("Page: ${page.title}")
    appendLine("URL: ${page.url}")
    if (page.elements.isNotEmpty()) {
        appendLine("Elements (use the number with browser_click / browser_type):")
        page.elements.take(120).forEach { e ->
            append("  [${e.index}] ${e.kind}")
            if (e.type.isNotBlank()) append(" type=${e.type}")
            if (e.text.isNotBlank()) append(" \"${e.text}\"")
            if (e.placeholder.isNotBlank()) append(" placeholder=\"${e.placeholder}\"")
            if (e.href.isNotBlank()) append(" → ${e.href}")
            appendLine()
        }
    }
    appendLine("Text:")
    append(page.text.clip(maxChars))
}

private fun host(u: String) = runCatching { java.net.URI(u.trim()).host?.removePrefix("www.") ?: u }.getOrDefault(u)

class BrowserOpenTool : Tool {
    override val spec = ToolSpec(
        "browser_open",
        "Open a URL in Axion's browser (Obscura engine: JavaScript, cookies, logins). Returns the page title, its numbered elements and the readable text. Use it for JS-heavy pages, forms and anything browse_url cannot read.",
        schema { str("url", "Full URL (https://…) or a domain like example.com") },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.BROWSE, "Opening", host(args.s("url").orEmpty()))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val url = args.s("url").orEmpty()
        if (url.isBlank()) return ToolOutput("No URL", isError = true)
        return try {
            val b = browser()
            val head = b.open(url, agent = true)
            val page = runCatching { b.read() }.getOrNull()
            ToolOutput(if (page != null) head + "\n\n" + formatPage(page, 6000) else head, doneTitle = "Opened page")
        } catch (e: Throwable) { ToolOutput("Browser error: ${e.message}", isError = true) }
    }
}

class BrowserReadTool : Tool {
    override val spec = ToolSpec(
        "browser_read",
        "Read the page currently open in the browser: title, URL, the numbered interactive elements (links, buttons, fields) and the readable text. Element numbers change after each navigation — read again before clicking or typing.",
        schema { int("max_chars", "Max characters of page text (default 9000)") },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.BROWSE, "Reading page", "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = try {
        val max = (args.i("max_chars") ?: 9000).coerceIn(1000, 30000)
        val page = browser().read()
        ToolOutput(formatPage(page, max), display = formatPage(page, 2500), doneTitle = "Read page")
    } catch (e: Throwable) { ToolOutput("Browser error: ${e.message}", isError = true) }
}

class BrowserClickTool : Tool {
    override val spec = ToolSpec(
        "browser_click",
        "Click an element on the open page: give its number from browser_read, or its visible text. Follows links and presses buttons; waits for the new page.",
        schema { int("index", "Element number from browser_read", required = false); str("text", "Visible text of the element to click (if no number)", required = false) },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.BROWSE, "Clicking", args.s("text") ?: args.i("index")?.let { "#$it" } ?: "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val idx = args.i("index"); val text = args.s("text")
        if (idx == null && text.isNullOrBlank()) return ToolOutput("Give an element number or its text.", isError = true)
        return try { ToolOutput(browser().click(idx, text), doneTitle = "Clicked") }
        catch (e: Throwable) { ToolOutput("Browser error: ${e.message}", isError = true) }
    }
}

class BrowserTypeTool : Tool {
    override val spec = ToolSpec(
        "browser_type",
        "Type text into a form field on the open page (the field's number from browser_read). Set submit=true to send the form / press Enter afterwards (e.g. a search box).",
        schema { int("index", "Field number from browser_read"); str("text", "Text to type"); bool("submit", "Submit the form after typing (default false)") },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.BROWSE, "Typing", args.i("index")?.let { "#$it" } ?: "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val idx = args.i("index") ?: return ToolOutput("Give the field number from browser_read.", isError = true)
        return try { ToolOutput(browser().type(idx, args.s("text").orEmpty(), args.b("submit") ?: false), doneTitle = "Typed") }
        catch (e: Throwable) { ToolOutput("Browser error: ${e.message}", isError = true) }
    }
}

class BrowserScrollTool : Tool {
    override val spec = ToolSpec(
        "browser_scroll",
        "Scroll the open page down (or up) so more content loads; then browser_read again to see it.",
        schema { str("direction", "\"down\" (default) or \"up\"", required = false); int("amount", "Pixels to scroll (default: most of a screen)", required = false) },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.BROWSE, "Scrolling", args.s("direction") ?: "down")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = try {
        ToolOutput(browser().scroll((args.s("direction") ?: "down") != "up", args.i("amount")), doneTitle = "Scrolled")
    } catch (e: Throwable) { ToolOutput("Browser error: ${e.message}", isError = true) }
}

class BrowserBackTool : Tool {
    override val spec = ToolSpec("browser_back", "Go back to the previous page in the browser.", schema { })
    override fun label(args: JsonObject) = StepLabel(StepKind.BROWSE, "Back", "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = try {
        ToolOutput(browser().back(), doneTitle = "Went back")
    } catch (e: Throwable) { ToolOutput("Browser error: ${e.message}", isError = true) }
}

class BrowserForwardTool : Tool {
    override val spec = ToolSpec("browser_forward", "Go forward in the browser (after browser_back).", schema { })
    override fun label(args: JsonObject) = StepLabel(StepKind.BROWSE, "Forward", "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = try {
        ToolOutput(browser().forward(), doneTitle = "Went forward")
    } catch (e: Throwable) { ToolOutput("Browser error: ${e.message}", isError = true) }
}

class BrowserReloadTool : Tool {
    override val spec = ToolSpec("browser_reload", "Reload the page currently open in the browser.", schema { })
    override fun label(args: JsonObject) = StepLabel(StepKind.BROWSE, "Reloading", "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = try {
        ToolOutput(browser().reload(), doneTitle = "Reloaded")
    } catch (e: Throwable) { ToolOutput("Browser error: ${e.message}", isError = true) }
}

class BrowserSearchTool : Tool {
    override val spec = ToolSpec(
        "browser_search",
        "Search the web inside Axion's browser and land on the results page; then browser_read to see the result links and browser_click to open one. (web_search is the lighter alternative when you do not need the browser.)",
        schema { str("query", "Search query") },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.SEARCH, "Searching in browser", args.s("query").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val q = args.s("query").orEmpty()
        if (q.isBlank()) return ToolOutput("Empty query", isError = true)
        return try {
            val b = browser()
            val head = b.search(q, agent = true)
            val page = runCatching { b.read() }.getOrNull()
            ToolOutput(if (page != null) head + "\n\n" + formatPage(page, 6000) else head, doneTitle = "Searched")
        } catch (e: Throwable) { ToolOutput("Browser error: ${e.message}", isError = true) }
    }
}

class BrowserScreenshotTool : Tool {
    override val spec = ToolSpec(
        "browser_screenshot",
        "Take a picture of the page the browser is showing right now and save it in outputs/browser/ (the user sees it as a file card). Good for showing the user what a page looks like.",
        schema { },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.BROWSE, "Screenshot", "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = try {
        val f = browser().screenshot(File(ctx.workspace.outputs, "browser"))
        ToolOutput(
            "Saved a screenshot of the page to ${ctx.workspace.rel(f)}",
            artifact = Artifact(f.name, "image/png", f.absolutePath, f.length()),
            doneTitle = "Screenshot saved",
        )
    } catch (e: Throwable) { ToolOutput("Browser error: ${e.message}", isError = true) }
}

class BrowserTabsTool : Tool {
    override val spec = ToolSpec(
        "browser_tabs",
        "Manage the browser's tabs (P1-7). Each tab keeps its own page and history; switching tabs restores that tab's page. Actions: 'list' (all tabs, the active one marked), 'new' (open a new blank tab), 'switch' (go to a tab by its number), 'close' (close a tab by its number). The tab number is its position in the list, starting at 1.",
        schema {
            str("action", "'list', 'new', 'switch' or 'close'")
            int("tab", "Tab number from browser_tabs list (needed for 'switch' and 'close')", required = false)
        },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.BROWSE, "Tabs", args.s("action").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = try {
        val b = browser()
        val tabs = b.tabs.value
        fun listText(): String = tabs.mapIndexed { i, t ->
            "${i + 1}. ${if (t.id == b.activeTabId.value) "[active] " else ""}${t.displayName()} — ${t.url.ifBlank { "(blank)" }}"
        }.joinToString("\n")
        when (args.s("action")?.trim()?.lowercase()) {
            "list" -> ToolOutput("Browser tabs (${tabs.size}):\n${listText()}", doneTitle = "${tabs.size} tabs")
            "new" -> {
                if (!b.newTab(agent = true)) ToolOutput("Maximum 10 tabs are open — close one first.", isError = true)
                else ToolOutput("Opened a new blank tab.\n${listText()}", doneTitle = "New tab")
            }
            "switch" -> {
                val n = args.i("tab") ?: return ToolOutput("Give the tab number from browser_tabs list.", isError = true)
                val tab = tabs.getOrNull(n - 1) ?: return ToolOutput("There is no tab $n.", isError = true)
                if (!b.switchTab(tab.id)) ToolOutput("Could not switch to tab $n.", isError = true)
                else ToolOutput("Switched to tab $n: ${tab.displayName()} — ${tab.url.ifBlank { "(blank)" }}", doneTitle = "Switched tab")
            }
            "close" -> {
                val n = args.i("tab") ?: return ToolOutput("Give the tab number from browser_tabs list.", isError = true)
                val tab = tabs.getOrNull(n - 1) ?: return ToolOutput("There is no tab $n.", isError = true)
                if (!b.closeTab(tab.id)) ToolOutput("Could not close tab $n.", isError = true)
                else ToolOutput("Closed tab $n.\n${listText()}", doneTitle = "Tab closed")
            }
            else -> ToolOutput("Action must be 'list', 'new', 'switch' or 'close'.", isError = true)
        }
    } catch (e: Throwable) { ToolOutput("Browser error: ${e.message}", isError = true) }
}

class BrowserDownloadsTool : Tool {
    override val spec = ToolSpec(
        "browser_downloads",
        "List the browser's downloads (P1-7). Files save into the Downloads/Axion folder on the phone, with progress while they download. The user opens them from the Browser screen.",
        schema { },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.BROWSE, "Downloads", "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        return try {
            val list = browser().downloads.value
            if (list.isEmpty()) return ToolOutput("No downloads yet. When a page offers a file, the browser saves it into Downloads/Axion.")
            val out = list.joinToString("\n") { d ->
                "- ${d.fileName}: " + when (d.status) {
                    com.mazharansari.axion.browser.DownloadStatus.DOWNLOADING ->
                        if (d.progressPercent >= 0) "downloading, ${d.progressPercent}%" else "downloading"
                    com.mazharansari.axion.browser.DownloadStatus.COMPLETE -> "complete, saved in Downloads/Axion"
                    com.mazharansari.axion.browser.DownloadStatus.FAILED -> "failed"
                }
            }
            ToolOutput("Downloads (${list.size}):\n$out", doneTitle = "${list.size} downloads")
        } catch (e: Throwable) { ToolOutput("Browser error: ${e.message}", isError = true) }
    }
}
