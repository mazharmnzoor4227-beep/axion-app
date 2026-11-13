package com.mazharansari.axion.agent

import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.Http
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.11 DEEP RESEARCH — an iterative investigation pipeline inside ONE tool call, modelled on how
 * Perplexity Deep Research / Hermes-style research agents work (all code is Axion's own):
 *
 *   PLAN        the chat model breaks the topic into several search angles (or fixed fallback angles
 *               when no model is wired into this surface);
 *   SEARCH+READ every angle is searched, and the best hits are READ IN FULL (page text, not just
 *               snippets) — up to ~24 sources in deep mode;
 *   GAP LOOP    after each round the model is asked what is still missing and the follow-up
 *               searches run as another round (3 rounds deep, 2 standard);
 *   SYNTHESISE  the model writes a structured report from the numbered evidence only, told to
 *               cross-check key numbers/dates across at least two independent sources and to put
 *               disagreements in a "Conflicting views" section. The numbered Sources list is
 *               attached by the tool itself, from the pages that were actually read — never invented.
 *
 * The report is also saved to outputs/research/ and returned as a file card. Pages that need
 * JavaScript (too little readable text) are skipped and counted, not faked: this pipeline never
 * hijacks the user's visible browser mid-research. A failing source never fails the run — only a
 * run with zero readable sources reports that plainly (as a normal result, so FaultPolicy does
 * not burn a second full pipeline on its one retry).
 */
class DeepResearchTool : Tool {
    override val spec = ToolSpec(
        "deep_research",
        "Investigate a topic PROPERLY: plans sub-questions, searches several angles, reads the best sources in full, follows up on gaps in extra rounds, cross-checks key claims across independent sources and returns a structured report with numbered citations and a sources list (also saved as a file in outputs/research/). Use it for research requests, comparisons, buying decisions, and any fact-heavy question where a single search is not enough. depth: 'deep' (default — up to 3 rounds, ~24 sources) or 'standard' (2 rounds, ~10 sources). It takes minutes, not seconds — that is expected.",
        schema {
            str("topic", "The research topic or question, in full")
            str("depth", "'deep' (default) or 'standard'", required = false)
        },
    )

    override fun label(args: JsonObject) = StepLabel(StepKind.SEARCH, "Deep research", args.s("topic").orEmpty().take(70))

    private data class Source(val n: Int, val title: String, val url: String, val text: String)
    private data class Fetched(val title: String, val url: String, val text: String, val fail: String? = null)

    private val searchTool = WebSearchTool()
    private val gate = Semaphore(4)

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val topic = args.s("topic")?.trim().orEmpty()
        if (topic.isBlank()) return ToolOutput("Give a research topic or question.", isError = true)
        val deep = args.s("depth")?.trim()?.lowercase() != "standard"
        val maxRounds = if (deep) 3 else 2
        val maxSources = if (deep) 24 else 10
        val maxAngles = if (deep) 8 else 6
        val perPageChars = if (deep) 6_000 else 5_000
        // Per-round read cap: round 1 must NOT swallow the whole source budget, or the gap rounds never happen.
        val perRoundCap = if (deep) 10 else 6
        val timeBudgetMs = if (deep) 1_500_000L else 750_000L
        val started = System.currentTimeMillis()
        val ask = ctx.ask
        val dateStr = SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(Date())

        fun progress(stage: String) = ctx.progress("Deep research — $stage")

        // ── 1. PLAN ──────────────────────────────────────────────────────────────────────────
        progress("planning the research…")
        var angles: List<String> = emptyList()
        if (ask != null) {
            val planPrompt = """
                You plan a deep web research investigation.
                Topic: "$topic"
                Today is $dateStr.
                Break the topic into at most $maxAngles search angles that together cover it well: the core facts, the latest developments, data/numbers, comparisons with alternatives, and criticism or problems.
                Output ONLY the search queries, one per line — no numbering, no bullets, no explanations.
                Write the queries in English unless the topic is clearly in another language.
            """.trimIndent()
            angles = (askOrNull(ask, planPrompt) ?: "")
                .lines().map { cleanLine(it) }.filter { it.length in 3..200 }.distinct().take(maxAngles)
        }
        if (angles.isEmpty()) angles = listOf(
            topic, "$topic explained", "$topic latest news $dateStr", "$topic pros and cons",
            "$topic problems criticism", "$topic comparison alternatives",
        ).distinct().take(maxAngles)
        if (topic !in angles) angles = listOf(topic) + angles

        // ── 2..4. SEARCH → READ → GAP rounds ─────────────────────────────────────────────────
        val sources = mutableListOf<Source>()
        val attempted = HashSet<String>()
        val searched = mutableListOf<String>()
        var searchesRun = 0
        var skipped = 0
        var pending = angles
        var roundsDone = 0
        for (round in 1..maxRounds) {
            if (pending.isEmpty() || sources.size >= maxSources) break
            if (System.currentTimeMillis() - started > timeBudgetMs) break
            roundsDone = round
            progress("round $round of $maxRounds: searching ${pending.size} angle${if (pending.size == 1) "" else "s"}…")
            val hits = LinkedHashMap<String, WebSearchTool.Hit>()
            for (q in pending) {
                if (q in searched) continue
                searched += q; searchesRun++
                searchOrEmpty(q).forEach { h ->
                    val key = h.url.trim().lowercase().trimEnd('/')
                    if (key.isNotBlank() && key !in attempted && key !in hits) hits[key] = h
                }
            }
            val candidates = hits.values.take(minOf(perRoundCap, (maxSources - sources.size).coerceAtLeast(0)))
            if (candidates.isEmpty()) break
            progress("round $round: reading ${candidates.size} source${if (candidates.size == 1) "" else "s"} (${sources.size} read so far)…")
            candidates.forEach { attempted += it.url.trim().lowercase().trimEnd('/') }
            val fetched = coroutineScope {
                candidates.map { h -> async(Dispatchers.IO) { gate.withPermit { fetchPage(h.url, perPageChars) } } }.awaitAll()
            }
            for (f in fetched) {
                if (f.fail == null && f.text.isNotBlank()) sources += Source(sources.size + 1, f.title.ifBlank { f.url }, f.url, f.text)
                else skipped++
            }
            if (round == maxRounds || sources.size >= maxSources) break
            // GAP ANALYSIS: what is still missing?
            progress("round $round done: checking what is still missing…")
            var followUps: List<String> = emptyList()
            if (ask != null && sources.isNotEmpty()) {
                val digest = sources.joinToString("\n") { s -> "[${s.n}] ${s.title} — ${s.text.take(400).replace('\n', ' ')}" }.take(9_000)
                val gapPrompt = """
                    Topic: "$topic"
                    Research so far — sources already read:
                    $digest
                    Searches already run: ${searched.joinToString("; ")}
                    What important gaps, unverified claims or new questions remain?
                    Output ONLY up to 4 NEW search queries, one per line, no numbering, no explanations.
                    If the topic is already covered thoroughly, output exactly: DONE
                """.trimIndent()
                val raw = askOrNull(ask, gapPrompt) ?: ""
                if (!raw.trim().equals("DONE", ignoreCase = true)) {
                    followUps = raw.lines().map { cleanLine(it) }.filter { it.length in 3..200 && it !in searched }.distinct().take(4)
                }
            }
            if (followUps.isEmpty() && ask == null) {
                followUps = listOf("$topic latest updates", "$topic data statistics", "$topic expert review")
                    .filter { it !in searched }
            }
            pending = followUps
            if (pending.isEmpty()) break
        }

        if (sources.isEmpty()) {
            return ToolOutput(
                "Deep research on \"$topic\" ran $searchesRun searches but could not read a single source " +
                    "(search engines unreachable, or every page blocked / needing JavaScript). Tell the user plainly that the research could not be completed and why; do not invent an answer.",
                doneTitle = "Research found nothing",
            )
        }

        // ── 5. SYNTHESISE (with cross-check rules); evidence pack when no model is wired ──────
        val evidence = buildString {
            var used = 0
            for (s in sources) {
                val block = "[${s.n}] ${s.title} (${s.url})\n${s.text.take(2_200)}\n\n"
                if (used + block.length > 55_000) { appendLine("[${s.n}] ${s.title} (${s.url}) — (title only; evidence budget reached)"); continue }
                append(block); used += block.length
            }
        }.trim()
        progress("cross-checking claims and writing the report…")
        var body: String? = null
        if (ask != null) {
            val synthPrompt = """
                You are writing the final report of a deep research investigation.
                Topic: "$topic"
                Today is $dateStr.

                Below are the sources that were actually READ, numbered. Rules — follow them exactly:
                - Use ONLY these sources. Never invent facts, numbers, dates or URLs.
                - Cite claims inline like [1] or [2][5], using the source numbers.
                - CROSS-CHECK: a key number, date or price needs support from at least 2 independent sources. If only one source states it, write "(single source)" after the claim.
                - If sources disagree, do not pick a side silently: give both versions in the "Conflicting views" section.
                - Structure: a 3-5 line executive summary first, then "## Key findings" (headings/bullets as needed), then "## Conflicting views" (write "None found" if the sources agree), then "## Gaps and uncertainties" (what could not be verified).
                - Do NOT add a sources list of your own — it is attached separately.
                - Write in the same language as the topic.

                SOURCES:
                $evidence
            """.trimIndent()
            body = askOrNull(ask, synthPrompt)?.trim()?.takeIf { it.length > 200 }
        }
        val counts = "$searchesRun searches · ${sources.size} sources read · $roundsDone round${if (roundsDone == 1) "" else "s"}" +
            if (skipped > 0) " · $skipped pages skipped (blocked or JavaScript-only)" else ""
        val report = buildString {
            appendLine("# Deep research: $topic")
            appendLine()
            appendLine("*$dateStr · ${if (deep) "Deep" else "Standard"} mode · $counts*")
            appendLine()
            if (body != null) appendLine(body)
            else {
                appendLine("## Evidence gathered")
                appendLine()
                appendLine("(The write-up model was not available in this surface — the evidence below is what the research actually read. The assistant should write the report from it, citing [n].)")
                appendLine()
                append(evidence)
                appendLine()
            }
            appendLine()
            appendLine("## Sources")
            sources.forEach { appendLine("${it.n}. ${it.title} — ${it.url}") }
        }

        // ── 6. VERIFY CITATIONS IN CODE (P1-6) + SAVE + RETURN ─────────────────────────────
        // The synthesis prompt asks for citations, but only this pass enforces them: every key
        // claim must carry an [n], every [n] must exist in Sources, and a key number needs
        // 2+ independent sources — otherwise the report itself says so ("single source" /
        // "citation needed") before it is shown or saved.
        val checked = CitationCheck.verify(report)
        val finalReport = CitationCheck.annotate(report)
        val checkNote = if (checked.violations.isEmpty()) "Citation check passed."
        else "Citation check found ${checked.violations.size} issue${if (checked.violations.size == 1) "" else "s"} (marked in the report)."
        val slug = topic.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "topic" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val file = runCatching {
            val dir = File(ctx.workspace.outputs, "research").apply { mkdirs() }
            File(dir, "$slug-$stamp.md").apply { writeText(finalReport) }
        }.getOrNull()
        val savedNote = file?.let { "Saved as ${ctx.workspace.rel(it)}.\n\n" } ?: ""
        return ToolOutput(
            forModel = "Deep research complete ($counts). $checkNote Present the report below to the user IN FULL — keep the [n] citations, the Citation check note and the Sources list exactly as they are.\n\n" + savedNote + finalReport,
            display = finalReport.take(2_500),
            artifact = file?.let { Artifact(it.name, "text/markdown", it.absolutePath, it.length()) },
            doneTitle = "Researched ${sources.size} sources",
        )
    }

    private fun cleanLine(raw: String): String =
        raw.trim().trimStart('-', '•', '*', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim().trim('"')

    /** One page, read for real: OkHttp + Jsoup (the same stack as browse_url), bounded and quiet about failures. */
    private suspend fun fetchPage(url: String, maxChars: Int): Fetched = withContext(Dispatchers.IO) {
        var u = url.trim()
        if (!u.startsWith("http")) u = "https://$u"
        runCatching {
            Http.quick.newCall(
                Request.Builder().url(u).header("User-Agent", Http.UA).header("Accept-Language", "en,ur;q=0.8").build(),
            ).execute().use { r ->
                if (!r.isSuccessful) return@use Fetched("", u, "", "HTTP ${r.code}")
                val ctype = r.header("content-type").orEmpty()
                if (!ctype.contains("html") && !ctype.contains("xml") && !ctype.startsWith("text")) return@use Fetched("", u, "", "not a web page")
                val stream = r.body?.byteStream() ?: return@use Fetched("", u, "", "empty page")
                val bytes = stream.readBytesBounded(1_500_000)
                val doc = Jsoup.parse(String(bytes, Charsets.UTF_8), r.request.url.toString())
                val title = doc.title()
                doc.select("script, style, noscript, svg, iframe, nav, footer, aside, form").remove()
                val text = doc.body()?.text().orEmpty().replace(Regex("\\s+"), " ").trim()
                if (text.length < 300) Fetched(title, r.request.url.toString(), "", "too little readable text (JavaScript-only page?)")
                else Fetched(title, r.request.url.toString(), text.clip(maxChars))
            }
        }.getOrElse { if (it is CancellationException) throw it; Fetched("", u, "", it.message ?: "failed") }
    }

    /** ask() with honest cancellation: a user Stop propagates; any model failure just means "no answer". */
    private suspend fun askOrNull(ask: suspend (String) -> String, prompt: String): String? =
        try { ask(prompt) } catch (e: CancellationException) { throw e } catch (e: Throwable) { null }

    private suspend fun searchOrEmpty(q: String): List<WebSearchTool.Hit> =
        try { searchTool.hits(q, 8) } catch (e: CancellationException) { throw e } catch (e: Throwable) { emptyList() }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16_384)
        var total = 0
        while (total < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - total))
            if (n < 0) break
            out.write(buf, 0, n); total += n
        }
        return out.toByteArray()
    }
}
