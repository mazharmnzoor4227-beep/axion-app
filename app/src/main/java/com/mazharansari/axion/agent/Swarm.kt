package com.mazharansari.axion.agent

import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * PHASE 1 SWARM — general multi-agent orchestration on top of [SubagentOrchestrator].
 *
 * The parent agent calls `subagent_swarm` with one task; the swarm splits it into up to
 * [SubagentLimits.MAX_WORKERS] parallel workers with different ROLES, then merges. A worker
 * that cannot finish alone asks for sub-workers of its own (SPAWN lines, or a research
 * worker whose angle found nothing) — recursion is capped at depth
 * [SubagentLimits.MAX_DEPTH] and [SubagentLimits.MAX_WORKERS_PER_RUN] workers per parent
 * run, so it can never run away.
 *
 * PARENT STAYS IN CHARGE (the rule that makes this safe):
 *  - workers only ever touch their role's allow-list ([SubagentLimits.checkAllowed] throws
 *    otherwise) — no GitHub tools, no posting, no installing, no payments, ever;
 *  - the swarm itself is approval-gated at the parent level ([ApprovalGate]): the user
 *    approves the whole swarm once, workers never ask for approvals;
 *  - a worker's shell commands are refused when they hit the denylist
 *    ([ApprovalGate.hardReason]) — destructive or payment commands never run from a worker;
 *  - only the parent agent merges results and acts on them in the open chat.
 *
 * Original Axion code, following the existing Subagents.kt pattern (bounded workers, isolated
 * failures, merged + deduped facts). No code was copied from any other project.
 */

/** One file a coding worker is asked to create or change. Parsed from the model's plan — pure, unit-tested. */
data class WriteBlock(val path: String, val content: String)

object SwarmPlan {
    /** Lines starting with `SPAWN:` — `SPAWN: ROLE: sub-brief`. Capped at [SubagentLimits.MAX_SPAWN_PER_WORKER] per worker. */
    fun parseSpawnLines(plan: String, parent: WorkerTask): List<WorkerTask> {
        return plan.lines().map { it.trim() }
            .filter { it.startsWith("SPAWN:", ignoreCase = true) }
            .map { it.substringAfter(':', "").trim() }
            .filter { it.length >= 3 }.distinct()
            .take(SubagentLimits.MAX_SPAWN_PER_WORKER)
            .mapIndexed { i, line ->
                val head = line.substringBefore(':', "").trim()
                val role = if (':' in line && head.length <= 12) WorkerRole.fromName(head) else null
                val brief = if (role != null) line.substringAfter(':').trim().ifBlank { line } else line
                WorkerTask(
                    id = "${parent.id}.${i + 1}", topic = parent.topic, angle = brief.take(120),
                    role = role ?: WorkerRole.RESEARCHER, brief = brief,
                    depth = parent.depth + 1, parentId = parent.id,
                )
            }
    }

    /** Lines starting with `READ:` — workspace paths the worker wants to read first. */
    fun parseReadPaths(plan: String): List<String> {
        return plan.lines().map { it.trim() }
            .filter { it.startsWith("READ:", ignoreCase = true) }
            .map { it.substringAfter(':', "").trim().trim('`', '"') }
            .filter { it.isNotEmpty() && it.length <= 200 && ".." !in it.split('/') }
            .distinct().take(4)
    }

    /** Shell commands, one per line — blank lines and `#` comments skipped. */
    fun parseCommands(plan: String): List<String> {
        return plan.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { it.length <= 500 }
            .distinct().take(SubagentLimits.MAX_STEPS_PER_WORKER)
    }

    /**
     * `WRITE: <path>` followed by a fenced code block — the file content. A block without a
     * fence takes the lines until the next marker (WRITE:/READ:/SPAWN:) or the end.
     */
    fun parseWriteBlocks(plan: String): List<WriteBlock> {
        val out = mutableListOf<WriteBlock>()
        val lines = plan.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (!line.startsWith("WRITE:", ignoreCase = true)) { i++; continue }
            val path = line.substringAfter(':', "").trim().trim('`', '"')
            i++
            if (path.isEmpty() || ".." in path.split('/')) continue
            // Skip a blank line, then expect a fence; without one, read until the next marker.
            while (i < lines.size && lines[i].isBlank()) i++
            val content = StringBuilder()
            if (i < lines.size && lines[i].trimStart().startsWith("```")) {
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { content.appendLine(lines[i]); i++ }
                if (i < lines.size) i++ // closing fence
            } else {
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (t.startsWith("WRITE:", ignoreCase = true) || t.startsWith("READ:", ignoreCase = true) || t.startsWith("SPAWN:", ignoreCase = true)) break
                    content.appendLine(lines[i]); i++
                }
            }
            val text = content.toString().trimEnd('\n')
            if (text.isNotEmpty()) out += WriteBlock(path, text)
        }
        return out.take(SubagentLimits.MAX_STEPS_PER_WORKER)
    }
}

/** Runs one worker task with only its role's tools. Every tool call goes through [guarded]. */
class SwarmWorkerExecutor(private val ctx: ToolContext) {
    private val search = WebSearchTool()
    private val read = ReadFileTool()
    private val write = WriteFileTool()
    private val list = ListFilesTool()
    private val cmd = RunCommandTool()

    suspend fun run(task: WorkerTask): WorkerResult = when (task.role) {
        WorkerRole.RESEARCHER -> runResearcher(task)
        WorkerRole.CODER -> runCoder(task)
        WorkerRole.TESTER -> runTester(task)
        WorkerRole.REVIEWER -> runReviewer(task)
    }

    private fun fail(task: WorkerTask, reason: String, steps: Int, spawn: List<WorkerTask> = emptyList()) =
        WorkerResult(task.id, task.id, failed = reason, stepsUsed = steps, angle = task.angle, role = task.role, depth = task.depth, spawn = spawn)

    /** One guarded tool call: the role allow-list is checked first, then the real tool runs. */
    private suspend fun guarded(task: WorkerTask, tool: String, args: JsonObject): ToolOutput {
        SubagentLimits.checkAllowed(task.role, tool)
        return when (tool) {
            "read_file" -> read.run(args, ctx)
            "write_file" -> write.run(args, ctx)
            "run_command" -> cmd.run(args, ctx)
            "list_files" -> list.run(args, ctx)
            else -> throw IllegalArgumentException("No in-process runner for tool $tool")
        }
    }

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is Int -> put(k, JsonPrimitive(v))
                is String -> put(k, JsonPrimitive(v))
                is Boolean -> put(k, JsonPrimitive(v))
                else -> put(k, JsonPrimitive(v.toString()))
            }
        }
    }

    private fun result(task: WorkerTask, facts: List<WorkerFact>, steps: Int, spawn: List<WorkerTask> = emptyList()) =
        WorkerResult(task.id, task.id, facts = facts.take(task.maxFacts), stepsUsed = steps, angle = task.angle, role = task.role, depth = task.depth, spawn = spawn)

    /** One step note to the worker's card + transcript ("Read x", "Wrote y", "Ran: …"). */
    private fun progress(task: WorkerTask, steps: Int, note: String) = ctx.onWorkerProgress(task.id, steps, note)

    /** This worker's model call: cheap steps ride the fast models, hard steps the strongest. */
    private fun askFn(task: WorkerTask): (suspend (String) -> String)? {
        val withHint = ctx.askWith
        if (withHint != null) return { p: String -> withHint(task.role.modelHint, p) }
        return ctx.ask
    }

    /** Researcher: one bounded search on its angle. A dead angle spawns two narrower ones instead of giving up. */
    private suspend fun runResearcher(task: WorkerTask): WorkerResult {
        var steps = 0
        val hits = try {
            steps++
            search.hits(task.angle, task.maxFacts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return fail(task, e.message ?: "search failed", steps)
        }
        progress(task, steps, if (hits.isEmpty()) "Searched: ${task.angle} — no results" else "Searched: ${task.angle} → ${hits.size} results")
        if (hits.isEmpty()) {
            val spawn = listOf(
                WorkerTask(id = "${task.id}.1", topic = task.topic, angle = "${task.angle} examples", role = WorkerRole.RESEARCHER, brief = "${task.angle} examples", depth = task.depth + 1, parentId = task.id),
                WorkerTask(id = "${task.id}.2", topic = task.topic, angle = "${task.angle} how to", role = WorkerRole.RESEARCHER, brief = "${task.angle} how to", depth = task.depth + 1, parentId = task.id),
            )
            return fail(task, "no results for angle \"${task.angle}\"", steps, spawn)
        }
        val facts = hits.take(task.maxFacts).map { h ->
            WorkerFact(text = "${h.title} — ${h.snippet}".trim().take(600), sourceUrl = h.url, workerId = task.id)
        }
        return result(task, facts, steps)
    }

    /** Coder: the model plans READs + WRITEs (+ optional SPAWN); the worker executes them, guarded. */
    private suspend fun runCoder(task: WorkerTask): WorkerResult {
        val ask = askFn(task) ?: return fail(task, "no model available for the coding worker", 0)
        var steps = 0
        val plan = try {
            ask(
                """
                You are a coding worker. Do ONLY this brief, nothing else:
                ${task.brief.ifBlank { task.angle }}
                Workspace root is the working directory; paths are relative to it (outputs/ for new files).
                Reply in this exact shape, nothing else:
                READ: <path> (one per line, at most 4 files you must see before writing)
                WRITE: <path> then the full file content in one fenced code block (one block per file)
                SPAWN: ROLE: <sub-brief> (only if one part of this brief needs its own worker; at most 2)
                """.trimIndent()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return fail(task, "planning failed: ${e.message}", steps)
        }
        val spawn = SwarmPlan.parseSpawnLines(plan, task)
        val facts = mutableListOf<WorkerFact>()
        for (path in SwarmPlan.parseReadPaths(plan)) {
            if (steps >= task.maxSteps) break
            steps++
            val out = runCatching { guarded(task, "read_file", args("path" to path)) }
                .getOrElse { return fail(task, "read $path failed: ${it.message}", steps, spawn) }
            if (out.isError) facts += WorkerFact("Read $path: ${out.forModel.take(200)}", sourceUrl = path, workerId = task.id)
            progress(task, steps, "Read $path")
        }
        for (block in SwarmPlan.parseWriteBlocks(plan)) {
            if (steps >= task.maxSteps) break
            steps++
            val out = runCatching { guarded(task, "write_file", args("path" to block.path, "content" to block.content)) }
                .getOrElse { return fail(task, "write ${block.path} failed: ${it.message}", steps, spawn) }
            if (out.isError) return fail(task, "write ${block.path}: ${out.forModel.take(200)}", steps, spawn)
            facts += WorkerFact("Wrote ${block.path} (${block.content.length} chars)", sourceUrl = block.path, workerId = task.id)
            progress(task, steps, "Wrote ${block.path} (${block.content.length} chars)")
        }
        if (facts.isEmpty() && spawn.isEmpty()) return fail(task, "coding worker produced no file", steps)
        return result(task, facts, steps, spawn)
    }

    /** Tester: the model lists test commands; the worker runs them, guarded, denylist enforced. */
    private suspend fun runTester(task: WorkerTask): WorkerResult {
        val ask = askFn(task) ?: return fail(task, "no model available for the testing worker", 0)
        var steps = 0
        val plan = try {
            ask(
                """
                You are a testing worker. Do ONLY this brief, nothing else:
                ${task.brief.ifBlank { task.angle }}
                Reply with the shell test commands to run, one plain command per line, at most ${task.maxSteps}.
                No deletions, no installs, no payments, no network calls that change anything.
                If one part needs its own worker, add a line: SPAWN: ROLE: <sub-brief> (at most 2).
                """.trimIndent()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return fail(task, "planning failed: ${e.message}", steps)
        }
        val spawn = SwarmPlan.parseSpawnLines(plan, task)
        val facts = mutableListOf<WorkerFact>()
        val commandLines = plan.lines().filter { !it.trim().startsWith("SPAWN:", ignoreCase = true) }.joinToString("\n")
        for (command in SwarmPlan.parseCommands(commandLines)) {
            if (steps >= task.maxSteps) break
            // A worker never runs a destructive or payment command, even if the model listed it.
            ApprovalGate.hardReason(command)?.let { return fail(task, "refused unsafe test command ($command): $it", steps, spawn) }
            steps++
            val out = runCatching { guarded(task, "run_command", args("command" to command, "timeout_sec" to 120)) }
                .getOrElse { return fail(task, "test `$command` failed: ${it.message}", steps, spawn) }
            facts += WorkerFact(
                "Test `$command` → exit ${out.exitCode ?: "?"}: ${out.forModel.take(300)}",
                sourceUrl = "", workerId = task.id,
            )
            progress(task, steps, "Ran: $command → exit ${out.exitCode ?: "?"}")
        }
        if (facts.isEmpty() && spawn.isEmpty()) return fail(task, "testing worker ran no command", steps)
        return result(task, facts, steps, spawn)
    }

    /** Reviewer: reads the files named in its brief, then the model reviews what was read. */
    private suspend fun runReviewer(task: WorkerTask): WorkerResult {
        val paths = SwarmPlan.parseReadPaths(task.brief)
        if (paths.isEmpty()) return fail(task, "reviewer needs file paths in its brief (READ: <path>)", 0)
        var steps = 0
        val contents = StringBuilder()
        for (path in paths) {
            if (steps >= task.maxSteps) break
            steps++
            val out = runCatching { guarded(task, "read_file", args("path" to path)) }
                .getOrElse { return fail(task, "read $path failed: ${it.message}", steps) }
            if (out.isError) continue
            contents.append("\n--- $path ---\n").append(out.forModel.take(6000))
            progress(task, steps, "Read $path")
        }
        if (contents.isEmpty()) return fail(task, "reviewer could not read any file", steps)
        val ask = askFn(task) ?: return fail(task, "no model available to review", steps)
        val review = try {
            ask(
                """
                Review this code for bugs, security problems and mistakes. Be concrete: file, line, problem, fix.
                Keep it to at most 6 findings, one per line, each under 200 characters.
                $contents
                """.trimIndent()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return fail(task, "review failed: ${e.message}", steps)
        }
        val facts = review.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(6).map { line ->
            WorkerFact(text = line.take(400), sourceUrl = paths.first(), workerId = task.id)
        }
        if (facts.isEmpty()) return fail(task, "reviewer found nothing to report", steps)
        progress(task, steps, "Review: ${facts.size} findings")
        return result(task, facts, steps)
    }
}

/**
 * `subagent_swarm` — delegate a task to a swarm of parallel workers with different roles.
 * The model plans the split (or the researcher fallback is used when no model plans it);
 * the orchestrator runs the workers, expands any spawn requests within the caps, and merges.
 * The parent agent — and only the parent — acts on the merged result in the open chat.
 */
class SubagentSwarmTool : Tool {
    override val spec = ToolSpec(
        "subagent_swarm",
        "Delegate a task to a swarm of up to 4 parallel workers with different roles instead of doing it alone: RESEARCHER (web facts), CODER (write/change workspace files), TESTER (run shell test commands), REVIEWER (read files and review). A worker that cannot finish alone spawns its own sub-workers (max depth 2, max 6 workers per run). Results are merged with duplicates removed and every fact attributed to the worker that found it, then VERIFIED before delivery (sources cited, files read back, test exit codes checked) with one bounded fix attempt; anything still unverified is marked 'Needs check', never hidden. Use it when a task has several independent parts. Only the parent agent acts on the merged result — workers never post, install or pay anything.",
        schema { str("task", "The task to delegate, in full") },
    )

    override fun label(args: JsonObject) = StepLabel(StepKind.WORKER, "Swarming with workers", args.s("task").orEmpty().take(70))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val task = args.s("task")?.trim().orEmpty()
        if (task.isBlank()) return ToolOutput("Give a task to swarm on.", isError = true)
        var specs: List<WorkerTask> = emptyList()
        if (ctx.ask != null) {
            val planPrompt = """
                Break this task into at most 4 independent parts that can be done in parallel by different workers.
                Task: "$task"
                Output ONLY one line per part, in this shape — ROLE: brief
                Roles: RESEARCHER (find facts on the web), CODER (write or change files in the workspace), TESTER (run shell test commands), REVIEWER (read files and review them).
                The brief must be complete on its own: what to do, on what, and what counts as done.
            """.trimIndent()
            specs = runCatching { ctx.ask(planPrompt) }.getOrNull().orEmpty()
                .let { SubagentOrchestrator.parseWorkerSpecs(it, task) }
        }
        if (specs.isEmpty()) specs = SubagentOrchestrator.splitAngles(task, listOf(task, "$task latest", "$task comparison alternatives"))
        ctx.progress("Swarming with ${specs.size} workers (${specs.joinToString { it.role.label.lowercase() }})…")
        val executor = SwarmWorkerExecutor(ctx)
        var result = SubagentOrchestrator.fanOut(specs, { t -> executor.run(t) }, onWorker = ctx.onWorker, parentId = ctx.swarmParentId)
        if (result.facts.isEmpty()) {
            return ToolOutput(
                "Swarm on \"$task\" ran ${result.workersRun} workers but none returned a usable result" +
                    if (result.failures.isNotEmpty()) " (${result.failures.joinToString("; ") { "${it.workerId}: ${it.reason}" }})." else ".",
                doneTitle = "Swarm found nothing",
            )
        }
        // BRAIN — verify-before-deliver: check every worker result, then ONE bounded fix
        // attempt (at most 2 fix workers) on the failed parts, then re-check. Whatever is
        // still unverified is marked "Needs check" in the result — never hidden, never
        // papered over with a confident claim.
        var verification = SwarmVerify.verify(result, ctx.workspace, result.results)
        if (!verification.allOk) {
            val fixSpecs = SwarmVerify.fixSpecs(verification.problems, task)
            if (fixSpecs.isNotEmpty()) {
                ctx.progress("Verification found ${verification.problems.size} unchecked result(s) — one fix attempt…")
                val fix = SubagentOrchestrator.fanOut(fixSpecs, { t -> executor.run(t) }, onWorker = ctx.onWorker, parentId = ctx.swarmParentId)
                result = FanOutResult(
                    facts = SwarmVerify.mergeFacts(result.facts, fix.facts),
                    failures = result.failures + fix.failures,
                    workersRun = result.workersRun + fix.workersRun,
                    workersFailed = result.workersFailed + fix.workersFailed,
                    results = result.results + fix.results,
                )
                verification = SwarmVerify.verify(result, ctx.workspace, result.results)
            }
        }
        val notes = verification.checks.filter { it.note.isNotBlank() }.associate { it.fact.text to it.note }
        val body = buildString {
            appendLine(verification.line)
            appendLine()
            appendLine("Swarm: $task (${result.facts.size} results from ${result.workersRun - result.workersFailed} of ${result.workersRun} workers)")
            appendLine()
            result.facts.forEachIndexed { i, f ->
                appendLine("${i + 1}. [${f.workerId}] ${f.text}${notes[f.text]?.let { " $it" } ?: ""}")
                if (f.sourceUrl.isNotBlank()) appendLine("   ${f.sourceUrl}")
            }
            if (result.failures.isNotEmpty()) {
                appendLine()
                appendLine("Workers that failed (their parts were not covered): " +
                    result.failures.joinToString("; ") { "${it.workerId} (\"${it.angle}\": ${it.reason})" })
            }
        }
        // The model also gets the learning nudge (not shown on the step card): when this
        // taught a reusable procedure, save it with skill_create and say so in the reply.
        val forModel = body + "\n\nIf this swarm taught a reusable procedure, save it with skill_create (or a short memory_add note) and say 'Learned: …' in your reply — never save silently."
        return ToolOutput(forModel, display = body.clip(2500), doneTitle = "Swarmed with ${result.workersRun} workers · ${verification.mark}")
    }
}
