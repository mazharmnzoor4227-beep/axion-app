package com.mazharansari.axion.agent

import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.Http
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

class WebSearchTool : Tool {
    override val spec = ToolSpec(
        "web_search",
        "Search the web. Returns titles, URLs and snippets. Use several focused queries for research, then browse_url the best pages.",
        schema { str("query", "Search query"); int("max_results", "How many results (default 8)") },
    )

    override fun label(args: JsonObject) = StepLabel(StepKind.SEARCH, "Searching", args.s("query").orEmpty())

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val q = args.s("query").orEmpty().ifBlank { return@withContext ToolOutput("Empty query", isError = true) }
        val n = (args.i("max_results") ?: 8).coerceIn(1, 15)
        val results = hits(q, n)
        if (results.isEmpty()) return@withContext ToolOutput("No results (search engines unreachable).", isError = true, doneTitle = "Search failed")
        val text = results.mapIndexed { i, r -> "${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}" }.joinToString("\n")
        ToolOutput(text, doneTitle = "Searched")
    }

    /** v0.11: structured hits for the deep-research pipeline — same engines, same DuckDuckGo → Bing fallback. */
    suspend fun hits(query: String, max: Int): List<Hit> = withContext(Dispatchers.IO) {
        val all = runCatching { duck(query) }.getOrElse { emptyList() }
            .ifEmpty { runCatching { bing(query) }.getOrElse { emptyList() } }
        all.take(max)
    }

    data class Hit(val title: String, val url: String, val snippet: String)

    private fun duck(q: String): List<Hit> {
        val req = Request.Builder().url("https://html.duckduckgo.com/html/").header("User-Agent", Http.UA)
            .post(FormBody.Builder().add("q", q).build()).build()
        val html = Http.quick.newCall(req).execute().use { it.body?.string().orEmpty() }
        return Jsoup.parse(html).select(".result").mapNotNull { r ->
            val a = r.selectFirst(".result__a") ?: return@mapNotNull null
            var href = a.attr("href")
            if (href.contains("uddg=")) href = URLDecoder.decode(href.substringAfter("uddg=").substringBefore("&"), "UTF-8")
            if (href.startsWith("//")) href = "https:$href"
            if (href.contains("duckduckgo.com/y.js")) return@mapNotNull null
            Hit(a.text(), href, r.selectFirst(".result__snippet")?.text().orEmpty())
        }
    }

    private fun bing(q: String): List<Hit> {
        val req = Request.Builder().url("https://www.bing.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")).header("User-Agent", Http.UA).build()
        val html = Http.quick.newCall(req).execute().use { it.body?.string().orEmpty() }
        return Jsoup.parse(html).select("li.b_algo").mapNotNull { li ->
            val a = li.selectFirst("h2 a") ?: return@mapNotNull null
            Hit(a.text(), a.attr("href"), li.selectFirst(".b_caption p, p")?.text().orEmpty())
        }
    }
}

class BrowseTool : Tool {
    override val spec = ToolSpec(
        "browse_url",
        "Open a web page and read it: title, meta/SEO tags, headings, links, images, load time, page size and the readable text. Use for research and website audits.",
        schema { str("url", "Full URL (https://…)"); int("max_chars", "Max characters of page text (default 9000)") },
    )

    override fun label(args: JsonObject) = StepLabel(StepKind.BROWSE, "Browsing", host(args.s("url").orEmpty()))

    private fun host(u: String) = runCatching { URI(u.trim()).host?.removePrefix("www.") ?: u }.getOrDefault(u)

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        var url = args.s("url").orEmpty().trim()
        if (url.isEmpty()) return@withContext ToolOutput("No URL", isError = true)
        if (!url.startsWith("http")) url = "https://$url"
        val max = (args.i("max_chars") ?: 9000).coerceIn(1000, 30000)
        val t0 = System.currentTimeMillis()
        val resp = runCatching {
            Http.quick.newCall(Request.Builder().url(url).header("User-Agent", Http.UA).header("Accept-Language", "en,ur;q=0.8").build()).execute()
        }.getOrElse { return@withContext ToolOutput("Could not open $url: ${it.message}", isError = true, doneTitle = "Couldn't open") }
        resp.use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val ms = System.currentTimeMillis() - t0
            val ctype = r.header("content-type").orEmpty()
            if (!ctype.contains("html") && !ctype.contains("xml") && !ctype.startsWith("text")) {
                val f = java.io.File(ctx.workspace.root, "downloads/" + (r.request.url.pathSegments.lastOrNull()?.ifBlank { null } ?: "download.bin"))
                f.parentFile?.mkdirs(); f.writeBytes(bytes)
                return@withContext ToolOutput("Downloaded ${bytes.size} bytes ($ctype) to ${ctx.workspace.rel(f)}", doneTitle = "Downloaded")
            }
            val doc = Jsoup.parse(String(bytes, Charsets.UTF_8), r.request.url.toString())
            fun meta(n: String) = doc.selectFirst("meta[name=$n], meta[property=$n]")?.attr("content").orEmpty()
            val imgs = doc.select("img")
            val noAlt = imgs.count { it.attr("alt").isBlank() }
            val links = doc.select("a[href]")
            val internal = links.count { it.absUrl("href").contains(r.request.url.host) }
            val headings = doc.select("h1, h2, h3").take(40).joinToString("\n") { "${it.tagName()}: ${it.text().take(120)}" }
            doc.select("script, style, noscript, svg, iframe").remove()
            val text = doc.body()?.text().orEmpty().clip(max)
            val schema = doc.select("script[type=application/ld+json]").size
            val report = buildString {
                appendLine("URL: ${r.request.url}  (HTTP ${r.code}, ${ms} ms, ${bytes.size / 1024} KB HTML)")
                appendLine("Title: ${doc.title()}  [${doc.title().length} chars]")
                appendLine("Meta description: ${meta("description").ifBlank { "(missing)" }}")
                appendLine("Canonical: ${doc.selectFirst("link[rel=canonical]")?.attr("href") ?: "(missing)"}  Viewport: ${meta("viewport").ifBlank { "(missing)" }}")
                appendLine("Open Graph: title=${meta("og:title").ifBlank { "-" }} image=${meta("og:image").ifBlank { "-" }}  JSON-LD blocks: $schema")
                appendLine("Images: ${imgs.size} (${noAlt} without alt)  Links: ${links.size} ($internal internal)  H1 count: ${doc.select("h1").size}")
                appendLine("Headings:\n$headings")
                appendLine("\nText:\n$text")
            }
            ToolOutput(report, display = report.clip(2500), doneTitle = "Browsed")
        }
    }
}

class HttpRequestTool : Tool {
    override val spec = ToolSpec(
        "http_request",
        "Call a web API directly (GET/POST/PUT/DELETE) and get the raw response. Good for JSON APIs.",
        schema {
            str("url", "Full URL"); str("method", "GET, POST, PUT, PATCH or DELETE", required = false)
            str("headers", "Headers as 'Name: value' lines", required = false); str("body", "Request body", required = false)
        },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.BROWSE, "Calling", (args.s("method") ?: "GET") + " " + args.s("url").orEmpty().take(80))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val method = (args.s("method") ?: "GET").uppercase()
        val rb = Request.Builder().url(args.s("url").orEmpty()).header("User-Agent", Http.UA)
        args.s("headers")?.lines()?.forEach { l -> l.split(":", limit = 2).takeIf { it.size == 2 }?.let { rb.header(it[0].trim(), it[1].trim()) } }
        val body = args.s("body")
        rb.method(method, if (method in listOf("GET", "HEAD")) null else (body ?: "").toRequestBody("application/json".toMediaTypeOrNull()))
        runCatching {
            Http.quick.newCall(rb.build()).execute().use { r -> ToolOutput("HTTP ${r.code}\n" + r.body?.string().orEmpty().clip(12000), doneTitle = "Called API") }
        }.getOrElse { ToolOutput("Request failed: ${it.message}", isError = true) }
    }
}
