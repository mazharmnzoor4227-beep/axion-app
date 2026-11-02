package com.mazharansari.axion.build

import com.mazharansari.axion.agent.AgentSink
import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.data.Project
import com.mazharansari.axion.data.Step
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.data.StepStatus
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What a coding brain (Antigravity's agy, or a fake in tests) reports while it works. */
sealed interface BrainEvent {
    data class Text(val delta: String) : BrainEvent
    data class Thought(val delta: String) : BrainEvent
    /** A tool step started. [path] = the file it writes/reads (project-relative or absolute guest path), [content] = code it is writing if known. */
    data class ToolStart(val id: String, val tool: String, val target: String, val path: String? = null, val content: String? = null) : BrainEvent
    data class ToolProgress(val id: String, val content: String) : BrainEvent
    data class ToolDone(val id: String, val ok: Boolean = true, val output: String = "") : BrainEvent
    data class Note(val text: String) : BrainEvent
}

data class BrainResult(val ok: Boolean, val error: String? = null, val text: String = "")

/** One coding agent turn inside the project folder. Antigravity = [AgyBrain]. */
interface BuildBrain {
    suspend fun turn(project: Project, prompt: String, fresh: Boolean, events: (BrainEvent) -> Unit): BrainResult
}

/** Axion's own checks after the brain's turn (fast, parallel). */
interface BuildVerifier {
    data class Report(val problems: List<String>, val notes: List<String> = emptyList(), val screenshot: File? = null, val previewable: Boolean = false)
    suspend fun verify(project: Project, folder: File, onStep: (Step) -> Unit): Report
}

/**
 * The Build agent loop (addendum 5): brain turn (plan → write/edit files → run commands) → Axion verifies (link check,
 * build, headless-Chromium screenshot + console errors) → if something is wrong, the same brain conversation gets the exact
 * errors and fixes them with small edits → verify again (up to [maxFixRounds]) → one design self-review on the screenshot
 * for a new site. Every tool step becomes a live step card; while a file is being written its code streams into the card,
 * and when the step ends the card shows the diff (+N −M).
 */
class BuildLoop(
    private val brain: BuildBrain,
    private val verifier: BuildVerifier?,
    private val folder: File,
    private val guestRoot: String,
    private val sink: AgentSink,
    private val maxFixRounds: Int = 2,
    private val selfReview: Boolean = true,
    private val pollMs: Long = 150,
) {
    data class Outcome(val ok: Boolean, val rounds: Int, val problems: List<String>, val changedFiles: Set<String>, val error: String? = null)

    private val steps = ConcurrentHashMap<String, Step>()
    private val live = ConcurrentHashMap<String, Job>()
    private val changed = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    /** Each file's text after its last finished edit in this run: the diff base for the next edit (a fix round edits what round 1 wrote). */
    private val lastKnown = ConcurrentHashMap<String, String>()
    /** Text of every file before this turn — the diff base and the Undo point. */
    lateinit var before: Map<String, String>; private set

    suspend fun run(project: Project, userText: String, fresh: Boolean): Outcome = coroutineScope {
        before = withContext(Dispatchers.IO) { ProjectFiles.snapshot(folder) }
        val hadSite = SiteChecker.siteDir(folder) != null
        var round = 0
        var prompt = BuildPrompts.turn(project, userText, guestRoot, fresh)
        var res = brainTurn(project, prompt, fresh, round)
        if (!res.ok) return@coroutineScope Outcome(false, 0, emptyList(), changed.toSet(), res.error)
        var problems: List<String> = emptyList()
        var reviewed = false
        while (verifier != null && changed.isNotEmpty()) {
            val rep = verifier.verify(project, folder) { sink.step(it) }
            problems = rep.problems
            rep.screenshot?.takeIf { it.isFile }?.let { sink.artifact(Artifact(it.name, "image/png", it.absolutePath, it.length())) }
            if (problems.isNotEmpty() && round < maxFixRounds) {
                round++
                sink.text("\n\n")
                prompt = BuildPrompts.fix(problems, guestRoot)
                res = brainTurn(project, prompt, false, round)
                if (!res.ok) break
                continue
            }
            if (problems.isEmpty() && selfReview && !reviewed && !hadSite && rep.screenshot != null && round < maxFixRounds + 1) {
                reviewed = true; round++
                sink.text("\n\n")
                res = brainTurn(project, BuildPrompts.review(rep.screenshot, folder, guestRoot), false, round)
                if (!res.ok) break
                continue
            }
            break
        }
        Outcome(problems.isEmpty() && res.ok, round, problems, changed.toSet(), res.error)
    }

    private suspend fun brainTurn(project: Project, prompt: String, fresh: Boolean, round: Int): BrainResult = coroutineScope {
        val r = brain.turn(project, prompt, fresh) { ev -> handle(ev, this) }
        // anything still open when the brain ends is closed now (agy sometimes skips a DONE)
        steps.keys.toList().forEach { id -> if (steps[id]?.status == StepStatus.RUNNING) finish(id, true, "") }
        r
    }

    private fun handle(ev: BrainEvent, scope: CoroutineScope) {
        when (ev) {
            is BrainEvent.Text -> sink.text(ev.delta)
            is BrainEvent.Thought -> sink.reasoning(ev.delta)
            is BrainEvent.Note -> sink.reasoning(ev.text + "\n")
            is BrainEvent.ToolStart -> {
                sink.textToThoughts()
                val kind = kindOf(ev.tool)
                val rel = ev.path?.let { relPath(it) }
                val s = Step(ev.id, kind, titleOf(kind, ev.tool, rel != null && before.containsKey(rel)), rel ?: ev.target, startedAt = System.currentTimeMillis(), detail = ev.content.orEmpty())
                steps[ev.id] = s; sink.step(s)
                if (kind == StepKind.WRITE && rel != null) {
                    // Live code: tail the file on disk while agy writes it (works whatever agy's events carry).
                    live[ev.id] = scope.launch(Dispatchers.IO) {
                        val f = File(folder, rel); var last = ""
                        while (isActive) {
                            val now = runCatching { if (f.isFile && f.length() < 400_000) f.readText() else null }.getOrNull()
                            if (now != null && now != last && now != (lastKnown[rel] ?: before[rel])) { last = now; update(ev.id) { it.copy(detail = now) } }
                            delay(pollMs)
                        }
                    }
                }
            }
            is BrainEvent.ToolProgress -> update(ev.id) { it.copy(detail = ev.content) }
            is BrainEvent.ToolDone -> finish(ev.id, ev.ok, ev.output)
        }
    }

    private fun update(id: String, f: (Step) -> Step) { steps[id]?.let { val n = f(it); steps[id] = n; sink.step(n) } }

    private fun finish(id: String, ok: Boolean, output: String) {
        live.remove(id)?.cancel()
        val s = steps[id] ?: return
        var n = s.copy(status = if (ok) StepStatus.DONE else StepStatus.ERROR, endedAt = System.currentTimeMillis(), output = output.take(8000).ifBlank { s.output })
        if (s.kind == StepKind.WRITE && s.target.isNotBlank() && !s.target.startsWith("/")) {
            val f = File(folder, s.target)
            val now = runCatching { if (f.isFile) f.readText() else "" }.getOrDefault("")
            val old = lastKnown[s.target] ?: before[s.target].orEmpty()
            lastKnown[s.target] = now
            if (now != old) {
                changed += s.target
                val d = PatchEngine.diff(old, now); val st = PatchEngine.stats(d)
                n = n.copy(title = (if (old.isEmpty()) "Created" else "Edited") + " ($st)",
                    detail = if (old.isEmpty()) now else PatchEngine.unified(old, now, s.target))
            }
        }
        if (s.kind == StepKind.COMMAND && !ok) n = n.copy(status = StepStatus.ERROR)
        steps[id] = n; sink.step(n)
    }

    fun relPath(p: String): String? {
        val t = p.trim().removePrefix("file://")
        return when {
            t.startsWith("$guestRoot/") -> t.removePrefix("$guestRoot/")
            t.startsWith(folder.absolutePath + "/") -> t.removePrefix(folder.absolutePath + "/")
            t.startsWith("/") -> null
            else -> t.removePrefix("./")
        }?.takeIf { it.isNotBlank() && !it.contains("..") }
    }

    companion object {
        fun kindOf(tool: String): StepKind {
            val t = tool.lowercase()
            return when {
                "write" in t || "replace" in t || "edit" in t || "create_file" in t || "patch" in t -> StepKind.WRITE
                "command" in t || "bash" in t || "shell" in t || "terminal" in t || t == "run" -> StepKind.COMMAND
                "view" in t || "read" in t -> StepKind.READ
                "search" in t || "grep" in t || "find" in t -> StepKind.SEARCH
                "list" in t -> StepKind.READ
                "browse" in t || "url" in t || "fetch" in t -> StepKind.BROWSE
                else -> StepKind.OTHER
            }
        }
        fun titleOf(kind: StepKind, tool: String, exists: Boolean) = when (kind) {
            StepKind.WRITE -> if (exists) "Editing" else "Writing"
            StepKind.COMMAND -> "Bash"
            StepKind.READ -> if ("list" in tool.lowercase()) "Listed" else "Read"
            StepKind.SEARCH -> "Searched"
            StepKind.BROWSE -> "Opened"
            else -> tool.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}
