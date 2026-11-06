package com.mazharansari.axion.agent

import com.mazharansari.axion.data.PlanStatus
import com.mazharansari.axion.data.PlanStep
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.serialization.json.*

/**
 * v0.6 (Phase 2.2) PLANNING. Idea from JetBrains Koog (Apache-2.0): an agent runs as a strategy of nodes — here PLAN (only `plan_set` is
 * offered, so the model must lay out steps first) → EXECUTE (all tools + `plan_update`) → FINISH. The plan is shown to the user
 * as a live checklist (ui/chat/PlanCard.kt). Original Kotlin; see third_party/NOTICE-koog.md.
 */
class PlanState {
    @Volatile var steps: List<PlanStep> = emptyList(); private set
    val active get() = steps.isNotEmpty()

    fun set(texts: List<String>): String {
        val clean = texts.map { it.trim().replace(Regex("\\s+"), " ").take(140) }.filter { it.isNotEmpty() }.take(8)
        if (clean.isEmpty()) return "Plan not set: give 2-7 short steps."
        steps = clean.mapIndexed { i, t -> PlanStep(t, if (i == 0) PlanStatus.RUNNING else PlanStatus.PENDING) }
        return "Plan set (${clean.size} steps). Work through it in order; call plan_update(index, status) as each step starts or finishes."
    }

    /** index is 1-based. status: running | done | failed | pending | skipped. A step that starts running moves earlier running steps to done. */
    fun update(index: Int, status: String, note: String = ""): String {
        if (steps.isEmpty()) return "There is no plan yet."
        if (index !in 1..steps.size) return "Step $index does not exist (1-${steps.size})."
        val st = when (status.trim().lowercase()) { "done", "complete", "completed", "finished" -> PlanStatus.DONE; "failed", "fail", "error" -> PlanStatus.FAILED
            "running", "doing", "started", "in_progress", "in progress" -> PlanStatus.RUNNING; "pending", "todo" -> PlanStatus.PENDING
            "skipped", "skip", "not needed", "not_needed" -> PlanStatus.SKIPPED
            else -> return "Unknown status '$status' (use running, done, failed, skipped)." }
        steps = steps.mapIndexed { i, s ->
            when {
                i == index - 1 -> s.copy(status = st, note = note.trim().take(160))
                st == PlanStatus.RUNNING && i < index - 1 && s.status == PlanStatus.RUNNING -> s.copy(status = PlanStatus.DONE)
                else -> s
            }
        }
        return "Step $index is now ${st.name.lowercase()}."
    }

    /**
     * The answer is out. The step that was actually running may count as done; a step that NEVER ran must not claim "done" —
     * v0.6.1: it becomes SKIPPED on a successful run (an honest card), and stays pending on a failed one.
     */
    fun finish(ok: Boolean, reason: String = "") {
        if (steps.isEmpty()) return
        steps = steps.map { s ->
            when (s.status) {
                PlanStatus.DONE, PlanStatus.FAILED, PlanStatus.SKIPPED -> s
                PlanStatus.RUNNING -> if (ok) s.copy(status = PlanStatus.DONE) else s.copy(status = PlanStatus.FAILED, note = reason.take(160))
                PlanStatus.PENDING -> if (ok) s.copy(status = PlanStatus.SKIPPED, note = "not run") else s
            }
        }
    }
    /** Marks the running step failed (a tool gave up) so the card shows where it stopped. */
    fun failRunning(reason: String) { steps = steps.map { if (it.status == PlanStatus.RUNNING) it.copy(status = PlanStatus.FAILED, note = reason.take(160)) else it } }
}

object Planner {
    const val PLAN_SET = "plan_set"; const val PLAN_UPDATE = "plan_update"
    private val connectors = Regex("(\\bthen\\b|\\band then\\b|\\bafter that\\b|\\bphir\\b|\\bfir\\b|\\bus ke baad\\b|\\bpehle\\b|\\bfirst\\b|\\bfinally\\b|\\bphir se\\b|\\balso\\b|\\baur\\b|\\band\\b|\\+|;|\\n\\s*(\\d+[.)]|[-*])\\s)", RegexOption.IGNORE_CASE)
    private val bigThing = Regex("\\b(website|web ?app|app|project|landing page|dashboard|report|audit|pipeline|bot|game|store|portfolio|scraper|api|backend|frontend)\\b", RegexOption.IGNORE_CASE)

    private val actionVerbs = Regex("\\b(banao|bana do|bana|likho|likh|lagao|laga do|add|create|build|make|design|fix|update|change|remove|hatao|delete|bhejo|bhej|download|upload|setup|set up|install|convert|merge|split|rename|move|copy|generate|prepare|tayyar|shuru)\\b", RegexOption.IGNORE_CASE)

    /** Cheap, model-free: only real multi-step ACTION requests get a plan (a greeting or a one-liner never does). v0.6.1: shorter multi-step orders count too. */
    fun needsPlan(text: String, intent: Intent): Boolean {
        if (intent != Intent.ACTION) return false
        val t = text.trim(); if (t.length < 24) return false
        val links = connectors.findAll(t).count()
        val lines = t.lines().count { it.isNotBlank() }
        val verbs = actionVerbs.findAll(t).count()
        return links >= 2 || lines >= 3 || t.length >= 160 || (bigThing.containsMatchIn(t) && links >= 1) ||
            (links >= 1 && verbs >= 2) || verbs >= 3 || (lines >= 2 && verbs >= 2)
    }

    const val INSTRUCTIONS = "PLANNING: this is a multi-step task. Your FIRST action must be one call to plan_set with 3-7 short steps (what you will do, in order). " +
        "Then do the work step by step; before you start a step call plan_update(index, \"running\"), and when it is finished plan_update(index, \"done\") (or \"failed\" with a note). " +
        "Keep the plan short; do not re-plan unless something fails."

    val planSetSpec = ToolSpec(PLAN_SET, "Show the user your plan before you start: 3-7 short steps in order.",
        schema { str("steps", "JSON array of short step strings, e.g. [\"Read the uploaded file\",\"Write the page\",\"Check the preview\"]") })
    val planUpdateSpec = ToolSpec(PLAN_UPDATE, "Update one plan step's status as you go.",
        schema { int("index", "Step number, starting at 1", required = true); str("status", "running | done | failed | skipped"); str("note", "Short note (optional)", required = false) })

    /** Accepts a JSON array in `steps`, an array element, or a numbered / bulleted text list. */
    fun parseSteps(args: JsonObject): List<String> {
        val raw = args["steps"] ?: return emptyList()
        val arr = (raw as? JsonArray) ?: (raw as? JsonPrimitive)?.contentOrNull?.let { c -> runCatching { Json.parseToJsonElement(c) as? JsonArray }.getOrNull() }
        if (arr != null) return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val txt = (raw as? JsonPrimitive)?.contentOrNull.orEmpty()
        return txt.lines().map { it.trim().replace(Regex("^(\\d+[.)]|[-*•])\\s*"), "") }.filter { it.isNotBlank() }
    }
}

class PlanSetTool(private val state: PlanState, private val onChange: () -> Unit) : Tool {
    override val spec = Planner.planSetSpec
    override fun label(args: JsonObject) = StepLabel(StepKind.THINK, "Planned", "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val msg = state.set(Planner.parseSteps(args)); onChange(); return ToolOutput(msg, isError = !state.active)
    }
}
class PlanUpdateTool(private val state: PlanState, private val onChange: () -> Unit) : Tool {
    override val spec = Planner.planUpdateSpec
    override fun label(args: JsonObject) = StepLabel(StepKind.THINK, "Plan", "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val msg = state.update(args.i("index") ?: 0, args.s("status").orEmpty(), args.s("note").orEmpty()); onChange(); return ToolOutput(msg)
    }
}
