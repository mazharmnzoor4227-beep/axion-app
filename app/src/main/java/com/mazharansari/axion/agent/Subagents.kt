package com.mazharansari.axion.agent

import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

/**
 * P1-4 SUBAGENT ORCHESTRATION — the agent fans out bounded sub-tasks to parallel worker runs,
 * then merges the results. Modelled on the Hermes maintainer-orchestrator pattern (bounded
 * workers, serialized public actions — here: workers only read the web, they never act):
 *
 *   BOUNDED   at most [SubagentLimits.MAX_WORKERS] workers; each worker gets its own step
 *             budget ([SubagentLimits.MAX_STEPS_PER_WORKER]) and its own tool allow-list
 *             ([SubagentLimits.RESEARCH_TOOLS] — web search / read only, never shell, files
 *             or commands), so a worker can never wander off into unrelated tools;
 *   ISOLATED  a worker that throws is recorded as a failure and the parent run continues
 *             with the surviving workers' results — a worker failure never kills the run
 *             (a user Stop is still a cancellation and propagates, like everywhere else);
 *   MERGED    [SubagentOrchestrator.merge] dedupes by URL first, then by fact text, so two
 *             workers finding the same page or the same claim do not duplicate work in the
 *             merged result; every surviving fact keeps its worker attribution.
 *
 * Pure merge/dedupe logic lives in [SubagentOrchestrator] so it is unit-testable without a
 * model; the [SubagentResearchTool] below is the one wired worker (3 parallel searchers).
 */
object SubagentLimits {
    /** Hard cap on parallel workers in one fan-out. */
    const val MAX_WORKERS = 3
    /** Max tool steps one worker may take (searches + page reads). */
    const val MAX_STEPS_PER_WORKER = 8
    /** Max facts one worker may contribute to the merged result. */
    const val MAX_FACTS_PER_WORKER = 6
    /** What a research worker is allowed to use: web only, never shell/files/commands. */
    val RESEARCH_TOOLS: Set<String> = setOf("web_search", "browse_url")
}

/** One bounded sub-task handed to a worker. */
data class WorkerTask(
    val id: String,
    val topic: String,
    /** The search angle this worker owns (distinct per worker, so work is not duplicated). */
    val angle: String,
    val allowedTools: Set<String> = SubagentLimits.RESEARCH_TOOLS,
    val maxSteps: Int = SubagentLimits.MAX_STEPS_PER_WORKER,
    val maxFacts: Int = SubagentLimits.MAX_FACTS_PER_WORKER,
)

/** One fact a worker found, attributed to the worker that found it. */
data class WorkerFact(val text: String, val sourceUrl: String, val workerId: String)

/** What one worker returned — or why it did not. `failed` is set instead of throwing. */
data class WorkerResult(
    val taskId: String,
    val workerId: String,
    val facts: List<WorkerFact> = emptyList(),
    val failed: String? = null,
    val stepsUsed: Int = 0,
    /** The angle this worker owned, so a failure can say what was not covered. */
    val angle: String = "",
)

data class WorkerFailure(val workerId: String, val angle: String, val reason: String)

/** The merged outcome of a fan-out: deduped facts plus the failures that were isolated. */
data class FanOutResult(
    val facts: List<WorkerFact>,
    val failures: List<WorkerFailure>,
    val workersRun: Int,
    val workersFailed: Int,
)

object SubagentOrchestrator {

    /** Split a topic into at most [SubagentLimits.MAX_WORKERS] distinct worker tasks. */
    fun splitAngles(topic: String, angles: List<String>): List<WorkerTask> {
        val clean = angles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .take(SubagentLimits.MAX_WORKERS)
        val owned = if (clean.isEmpty()) listOf(topic.trim()) else clean
        return owned.mapIndexed { i, angle ->
            WorkerTask(id = "w${i + 1}", topic = topic, angle = angle)
        }
    }

    /**
     * Run every worker in parallel and merge. A worker that throws (network, timeout, bad
     * result) is recorded in [FanOutResult.failures] and the run continues with the survivors.
     * Only a user Stop ([CancellationException]) propagates — it must cancel the parent too.
     */
    suspend fun fanOut(
        tasks: List<WorkerTask>,
        runWorker: suspend (WorkerTask) -> WorkerResult,
    ): FanOutResult = coroutineScope {
        val results = tasks.map { task ->
            async {
                try {
                    runWorker(task)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    WorkerResult(task.id, task.id, failed = e.message ?: e.javaClass.simpleName, angle = task.angle)
                }
            }
        }.map { it.await() }
        merge(results)
    }

    /** Merge worker results: dedupe by URL first, then by fact text; first worker wins. */
    fun merge(results: List<WorkerResult>): FanOutResult {
        val seenUrls = HashSet<String>()
        val seenTexts = HashSet<String>()
        val facts = mutableListOf<WorkerFact>()
        val failures = mutableListOf<WorkerFailure>()
        var failed = 0
        for (r in results) {
            if (r.failed != null) {
                failed++
                failures += WorkerFailure(r.workerId, r.angle, r.failed)
                continue
            }
            for (f in r.facts) {
                val urlKey = normaliseUrl(f.sourceUrl)
                val textKey = normaliseText(f.text)
                if (urlKey.isNotBlank() && urlKey in seenUrls) continue
                if (textKey in seenTexts) continue
                if (urlKey.isNotBlank()) seenUrls += urlKey
                seenTexts += textKey
                facts += f
            }
        }
        return FanOutResult(facts, failures, workersRun = results.size, workersFailed = failed)
    }

    /** URL dedupe key: scheme/host/path, lowercased, trailing slash and tracking params dropped. */
    fun normaliseUrl(url: String): String {
        val u = url.trim().lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        // Strip the query/fragment FIRST: "example.com/a/?utm=x" must lose its slash too,
        // or it never matches "example.com/a" (trimEnd before substringBefore kept the "/").
        return u.substringBefore('?').substringBefore('#').trimEnd('/')
    }

    /** Fact-text dedupe key: case/whitespace/punctuation-insensitive, capped so long texts compare cheaply. */
    fun normaliseText(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim().take(400)
}

/**
 * P1-4 tool: `subagent_research` — a research task fans out to 3 parallel fetchers (one search
 * angle each), then merges without duplicating work. The model may plan the angles, or the
 * fallback angles are used when no model is wired into this surface. A worker that finds
 * nothing (search unreachable) is reported as a failure of that worker only — the merged
 * result still returns the surviving workers' facts.
 */
class SubagentResearchTool : Tool {
    override val spec = ToolSpec(
        "subagent_research",
        "Research a topic with 3 parallel workers instead of one search: each worker searches a different angle at the same time, then the results are merged with duplicates removed and every fact attributed to the worker that found it. A worker that fails never stops the others. Use it for comparisons and fact-heavy questions where several angles matter. Returns merged facts with source URLs and a note about any worker that failed.",
        schema { str("topic", "The research topic or question, in full") },
    )

    override fun label(args: JsonObject) = StepLabel(StepKind.SEARCH, "Researching with 3 workers", args.s("topic").orEmpty().take(70))

    private val searchTool = WebSearchTool()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val topic = args.s("topic")?.trim().orEmpty()
        if (topic.isBlank()) return ToolOutput("Give a research topic or question.", isError = true)
        val ask = ctx.ask
        var angles: List<String> = emptyList()
        if (ask != null) {
            val planPrompt = """
                Break this research topic into exactly 3 different search angles that together cover it well (core facts, latest developments, comparisons or criticism).
                Topic: "$topic"
                Output ONLY the 3 search queries, one per line — no numbering, no bullets, no explanations.
            """.trimIndent()
            angles = runCatching { ask(planPrompt) }.getOrNull().orEmpty()
                .lines().map { it.trim().trimStart('-', '•', '*', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim().trim('"') }
                .filter { it.length in 3..200 }.distinct()
        }
        if (angles.isEmpty()) angles = listOf(topic, "$topic latest news", "$topic comparison alternatives")
        val tasks = SubagentOrchestrator.splitAngles(topic, angles)
        ctx.progress("Researching with ${tasks.size} parallel workers…")
        val result = SubagentOrchestrator.fanOut(tasks) { task ->
            runWorker(task)
        }
        if (result.facts.isEmpty()) {
            return ToolOutput(
                "Subagent research on \"$topic\" ran ${result.workersRun} parallel workers but none returned a usable fact" +
                    if (result.failures.isNotEmpty()) " (${result.failures.joinToString("; ") { "${it.workerId}: ${it.reason}" }})." else ".",
                doneTitle = "Research found nothing",
            )
        }
        val body = buildString {
            appendLine("Subagent research: $topic (${result.facts.size} facts from ${result.workersRun - result.workersFailed} of ${result.workersRun} workers)")
            appendLine()
            result.facts.forEachIndexed { i, f ->
                appendLine("${i + 1}. [${f.workerId}] ${f.text}")
                appendLine("   ${f.sourceUrl}")
            }
            if (result.failures.isNotEmpty()) {
                appendLine()
                appendLine("Workers that failed (their angles were not covered): " +
                    result.failures.joinToString("; ") { "${it.workerId} (\"${it.angle}\": ${it.reason})" })
            }
        }
        return ToolOutput(body, display = body.clip(2500), doneTitle = "Researched with ${result.workersRun} workers")
    }

    /** One worker: one bounded search on its own angle, capped at its own fact budget. */
    private suspend fun runWorker(task: WorkerTask): WorkerResult {
        var steps = 0
        val hits = try {
            steps++
            searchTool.hits(task.angle, task.maxFacts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return WorkerResult(task.id, task.id, failed = e.message ?: "search failed", stepsUsed = steps, angle = task.angle)
        }
        if (hits.isEmpty()) return WorkerResult(task.id, task.id, failed = "no results for angle \"${task.angle}\"", stepsUsed = steps, angle = task.angle)
        val facts = hits.take(task.maxFacts).map { h ->
            WorkerFact(text = "${h.title} — ${h.snippet}".trim().take(600), sourceUrl = h.url, workerId = task.id)
        }
        return WorkerResult(task.id, task.id, facts = facts, stepsUsed = steps, angle = task.angle)
    }
}
