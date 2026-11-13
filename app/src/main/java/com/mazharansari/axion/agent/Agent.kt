package com.mazharansari.axion.agent

import com.mazharansari.axion.data.*
import com.mazharansari.axion.llm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.*

data class Attempt(val provider: String, val model: String, val client: LlmClient, val onFail: (LlmException) -> Unit = {}, /** v0.5: local/own-server models get longer before the next one is tried. */ val patientMs: Long = 0)

interface AgentSink {
    fun text(delta: String)
    fun reasoning(delta: String)
    /** The text streamed in this round was narration before tool calls: move it into the Thoughts panel. */
    fun textToThoughts()
    fun step(step: Step)
    fun artifact(a: Artifact)
    fun model(label: String)
    /** v0.5.1: a stalled model is being replaced — show a small "Switching to <model>…" line under Thinking. */
    fun switching(label: String) {}
    /** v0.5.1: drop the text streamed in this round (a model stalled mid-answer; the next model answers from scratch). */
    fun discardRound() {}
    /** v0.5.2 timing: the HTTP request for this attempt is about to leave the phone. */
    fun requestStart() {}
    /** v0.5.2 timing: the first answer token (or visible thinking token) of the turn arrived. */
    fun firstToken() {}
    /** v0.6 (axion-p2): the visible plan changed (set / a step started, finished or failed). */
    fun plan(steps: List<com.mazharansari.axion.data.PlanStep>) {}
    /** v0.6 Tools library: the agent used this tool group (shown under the answer as Used tool). */
    fun usedTool(title: String) {}
}

class NoModelException(msg: String) : Exception(msg)

class Agent(
    private val workspace: Workspace,
    private val skills: com.mazharansari.axion.skills.SkillRepo,
    private val baseTools: List<Tool> = defaultTools(),
    private val extraTools: suspend () -> List<Tool> = { emptyList() },
    private val maxRounds: Int = 24,
    /** v0.6 Tools library: group id to on/off, read at the start of every run (so a switch takes effect on the next message). */
    private val toolSwitches: () -> Map<String, Boolean> = { emptyMap() },
    /** v0.5.1 watchdog (mazhar 3:36 PM, "dusra do" sat in Thinking 26 s+): no answer text within this → next model. */
    private val firstContentMs: Long = 8_000,
    /** …or the stream goes silent this long once it has started → next model. */
    private val stallMs: Long = 10_000,
    /** A reasoning model may think visibly (reasoning keeps streaming) this long before it must start answering. */
    private val thinkCapMs: Long = 45_000,
    /** v0.10 learning loop: the persistent, agent-curated memory (snapshot goes into every prompt). */
    private val memory: com.mazharansari.axion.data.MemoryStore? = null,
    /** v0.10 learning loop: saved conversations, backing the session_search tool. */
    private val conversations: com.mazharansari.axion.data.ConversationStore? = null,
    /** v0.11: the user switched Deep Research mode on in the composer — factual turns must research, not recall. */
    private val researchMode: Boolean = false,
    /** v0.12: remembered approvals for gated shell work (null = no memory: only the interactive approver can allow). */
    private val approvals: com.mazharansari.axion.data.ApprovalsStore? = null,
    /** v0.12: asks the user about a gated action (null = headless: remembered kinds only, everything else refused). */
    private val approver: Approver? = null,
    /** v0.12: Long task mode — bigger round/tool budgets and plan-verify-finish guidance. */
    private val longTask: Boolean = false,
    /** v0.12 scheduler: the user's schedules, handed to the schedule_* tools through the ToolContext. */
    private val schedules: com.mazharansari.axion.data.SchedulesStore? = null,
    /** v0.12 scheduler: application context so schedule tools can arm WorkManager. */
    private val appCtx: android.content.Context? = null,
) {
    companion object {
        fun defaultTools(): List<Tool> = listOf(
            WebSearchTool(), BrowseTool(), RunCommandTool(), ReadFileTool(), WriteFileTool(), ListFilesTool(),
            ExtractZipTool(), CreatePdfTool(), LoadSkillTool(), InstallSkillsTool(), HttpRequestTool(),
            VideoProbeTool(), VideoEditTool(),   // axion-p1.6
            // v0.9: Axion's own browser (real WebView) + the persistent terminal and background jobs
            BrowserOpenTool(), BrowserReadTool(), BrowserClickTool(), BrowserTypeTool(), BrowserScrollTool(),
            BrowserBackTool(), BrowserForwardTool(), BrowserReloadTool(), BrowserSearchTool(), BrowserScreenshotTool(),
            ShellSessionTool(), ShellResetTool(), RunBackgroundTool(), JobOutputTool(), JobKillTool(),
            // v0.10: the learning loop — persistent memory, self-written skills, past-chat search
            MemoryAddTool(), MemoryReplaceTool(), MemoryRemoveTool(), MemoryReadTool(),
            SkillCreateTool(), SkillUpdateTool(), SkillDeleteTool(), SkillViewTool(), SessionSearchTool(),
            // v0.11: deep research — the iterative search → read → gap → cross-check pipeline
            DeepResearchTool(),
            // v0.12: scheduled tasks — create/list/delete; WorkManager runs them headlessly later
            ScheduleCreateTool(), ScheduleListTool(), ScheduleDeleteTool(),
        )

        /** v0.11: while Research mode is on, the model must investigate instead of answering from memory. */
        const val RESEARCH_GUIDANCE = """RESEARCH MODE is ON (the user switched it on in the composer):
- For anything that needs facts — current information, prices, comparisons, "which is better", how something works in the real world, a decision the user is weighing — call deep_research FIRST with the user's topic (depth "deep"; use "standard" only when the user asks for something quick), then present its report in full, keeping the [n] citations and the Sources list.
- Do not answer a factual question from your own memory alone while this mode is on.
- Pure conversation — greetings, opinions, thanks, and follow-ups about a report already given — needs no new research."""

        /** P0-6 (HI-4): Research mode is on but the Deep research tool group is OFF, so deep_research was filtered out. Never a silent normal chat: the model must say so instead of answering from memory. */
        const val RESEARCH_OFF_NOTE = """RESEARCH MODE was switched on in the composer, but the Deep research tool group is OFF (Settings → Tools library), so deep_research is not available in this run.
- If this turn needs facts or research, do NOT answer it as a normal chat from memory, and do NOT work around the switched-off tool with other tools: reply briefly that Deep research is switched off, and that the user can turn it back on in Settings → Tools library (tapping the Research toggle again also turns it back on).
- Pure conversation — greetings, opinions, thanks, and follow-ups about a report already given — needs no research: answer it normally."""

        /** v0.12: Long task mode — the run is a project, not a quick favour. */
        const val LONG_TASK_GUIDANCE = """LONG TASK MODE is ON (the user switched it on in the composer):
- Treat this as a long task: set a plan first (plan_set) with every step, then work through it step by step.
- After each step, verify the result before moving on (re-read the file, re-run the check, look at the output). Do not assume a step worked.
- You have a much larger step and tool budget than usual; use it — do not rush to finish early or skip verification to save steps.
- If a step fails, try a different approach before giving up on it.
- Keep the plan updated as you go (plan_update). Finish with a summary of everything that was done and anything left over."""

        /** One internal model call (research planning / synthesis) may take this long before the next model is tried. */
        const val ASK_TIMEOUT_MS = 180_000L

        /** Hermes-style learning nudge, appended whenever the memory/skill tools are in play. */
        const val LEARNING_GUIDANCE = """LEARNING — you improve between chats, like this:
- MEMORY: you have a small persistent memory (memory_add / memory_replace / memory_remove / memory_read). When the user states a preference or habit, corrects you, or you learn a durable fact about them or their projects (names, decisions, lessons), save it — 'user' target for who they are and how they like things, 'memory' for facts and lessons. When a saved fact changes, memory_replace it instead of adding a conflicting second entry. Keep entries short and durable; never save passwords, keys or one-off small talk. Your saved memory is shown at the top of this prompt in future chats.
- SKILLS: when you finish a task that took several steps, a tricky workaround, or a procedure the user may ask for again, write it down with skill_create (clear steps, pitfalls, how to verify) so you repeat it the same way; when using a skill teaches you something better, improve it with skill_update. Before a specialised task, load the matching skill (load_skill / skill_view) — your own learned skills are in the skills list like the others.
- PAST CHATS: when the user refers to something from an earlier conversation ("last time…", "jo pehle decide hua tha") or you need an old decision, use session_search instead of guessing."""
    }

    suspend fun run(
        history: List<ChatItem>,
        user: UserItem,
        attempts: () -> List<Attempt>,
        sink: AgentSink,
        profile: Profile = Profile(),
        intent: Intent = Intent.ACTION,
    ): String {
        // axion-p2: strategy = PLAN (only plan_set offered) → EXECUTE (all tools, guarded by FaultPolicy) → FINISH; old turns folded into session notes.
        val planState = PlanState()
        val onPlan = { sink.plan(planState.steps) }
        val wantsPlan = Planner.needsPlan(user.text, intent)
        val planTools: List<Tool> = if (wantsPlan) listOf(PlanSetTool(planState, onPlan), PlanUpdateTool(planState, onPlan)) else emptyList()
        val sw = toolSwitches()
        val every = baseTools + if (intent == Intent.ACTION) runCatching { extraTools() }.getOrDefault(emptyList()) else emptyList()
        val all = every.filter { ToolCatalog.isEnabled(it.spec.name, sw) }                       // axion-p3: Tools library switches
        val offNames = every.map { it.spec.name }.filter { !ToolCatalog.isEnabled(it, sw) }.toSet()
        // v0.7.0: one capability set in every surface. Small talk still gets NO tools (instant, cheap); any real
        // chat or lookup request gets the web tools (IntentGate.chatTools), so "open the browser and
        // research this" works wherever the user says it. File/command tools still need an ACTION turn.
        val tools = when (intent) {
            Intent.CHAT -> if (IntentGate.isSmallTalk(user.text, intent) && !researchMode) emptyList() else all.filter { it.spec.name in IntentGate.chatTools }
            Intent.LOOKUP -> all.filter { it.spec.name in IntentGate.chatTools }
            Intent.ACTION -> all
        } + planTools
        val byName = tools.associateBy { it.spec.name }
        val followUp = IntentGate.isFollowUp(user.text)
        val hint = ScriptHint.note(user.text, history, followUp)
        // Keep requests small (Groq free tier: 8k tokens/min): recent turns verbatim, everything older → "Session notes" (decisions never dropped).
        val folded = HistoryCompressor.compress(history)
        // v0.10: the memory snapshot rides in EVERY prompt (even small talk — it is how Axion knows the user);
        // the learning guidance is added whenever this turn actually has the memory/skill tools.
        val memBlock = memory?.promptBlock().orEmpty()
        val learnBlock = if (memory != null && tools.any { it.spec.name == "memory_add" || it.spec.name == "skill_create" }) "\n\n" + LEARNING_GUIDANCE else ""
        // P0-6 (HI-4): the composer Research toggle on its own is not enough — if the Deep research
        // tool group is off, deep_research was filtered out above and RESEARCH_GUIDANCE must not
        // be added; instead the model is told the group is off, so the run warns instead of
        // silently answering as a normal chat.
        val researchAvailable = tools.any { it.spec.name == "deep_research" }
        val researchBlock = if (researchMode && researchAvailable) "\n\n" + RESEARCH_GUIDANCE else ""
        val researchOffBlock = if (researchMode && !researchAvailable) "\n\n" + RESEARCH_OFF_NOTE else ""
        val longTaskBlock = if (longTask && intent == Intent.ACTION) "\n\n" + LONG_TASK_GUIDANCE else ""
        val sys = systemPrompt(profile, tools, intent) + memBlock + hint + (if (folded.notes.isNotBlank()) "\n\n" + folded.notes else "") + (if (wantsPlan) "\n\n" + Planner.INSTRUCTIONS else "") +
            (if (tools.isNotEmpty()) ToolCatalog.promptNote(sw).let { if (it.isBlank()) "" else "\n\n" + it } else "") + learnBlock + researchBlock + researchOffBlock + longTaskBlock
        val items = mutableListOf<ChatItem>(SystemItem(sys))
        items += folded.recent
        items += user
        val answer = StringBuilder()
        val quick = !researchMode && IntentGate.isSmallTalk(user.text, intent)
        // v0.5.1: short/simple prompts ask reasoning models to think little (gpt-oss: reasoning_effort "low").
        val effort = if (quick || (intent == Intent.CHAT && user.text.trim().length <= 160)) "low" else null
        var planPhase = wantsPlan && tools.any { it.spec.name == Planner.PLAN_SET }
        var toolsOff = false
        // v0.12: a long task gets a far bigger budget (and more alternative attempts) than a quick favour.
        val policy = FaultPolicy(maxAlternatives = if (longTask) 6 else 2, maxToolCalls = if (longTask) 200 else 40)
        // v0.11: tools that think internally (deep research) get plain completions on this run's own models.
        val ask: suspend (String) -> String = { prompt -> askOnce(attempts, prompt) }
        repeat(maxRounds) { round ->
            val specs = when { toolsOff -> emptyList(); planPhase -> listOf(Planner.planSetSpec); else -> tools.map { it.spec } }
            val (text, calls) = streamOnce(HistoryCompressor.compressRun(items), specs, attempts, sink, quick, effort)
            planPhase = false
            items += AssistantItem(text, calls)
            if (calls.isEmpty()) { answer.append(text); if (planState.active) { planState.finish(ok = !toolsOff, reason = "stopped early"); onPlan() }; return answer.toString() }
            if (text.isNotBlank()) sink.textToThoughts()
            val ctx = ToolContext(workspace, skills, memory = memory, chats = conversations, schedules = schedules, appCtx = appCtx, ask = ask)
            for (call in calls) {
                val tool = byName[call.name]
                val args = runCatching { Json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject }.getOrElse { JsonObject(emptyMap()) }
                if (tool == null) { items += ToolResultItem(call.id, call.name, if (call.name in offNames) ToolCatalog.refusalFor(call.name) else "Unknown tool ${call.name}"); continue }
                if (call.name == Planner.PLAN_SET || call.name == Planner.PLAN_UPDATE) {   // the plan card is its own UI, no step row
                    val o = runCatching { tool.run(args, ctx) }.getOrElse { ToolOutput("Plan error: ${it.message}", isError = true) }
                    items += ToolResultItem(call.id, call.name, o.forModel); continue
                }
                if (toolsOff) { items += ToolResultItem(call.id, call.name, "Tools are switched off for the rest of this task. Explain to the user what failed and why."); continue }
                // v0.12: the approval gate — consequential shell work needs the user's OK (or a remembered rule).
                var rememberedNote = false
                val gate = ApprovalGate.requestFor(call.name, args)
                if (gate != null) {
                    if (!gate.hard && approvals?.matches(gate.pattern) == true) {
                        rememberedNote = true
                    } else {
                        val decision = if (approver != null) approver.decide(gate) else ApprovalDecision.DENY
                        if (decision == ApprovalDecision.ALLOW_REMEMBER && !gate.hard) approvals?.add(gate.pattern, gate.summary)
                        if (decision == ApprovalDecision.DENY) {
                            val gl = tool.label(args)
                            val why = when {
                                gate.hard -> "needs a fresh confirmation every time (${gate.hardReason})"
                                approver == null -> "needs the user's approval, and nobody can be asked in this run"
                                else -> "the user did not approve it"
                            }
                            sink.step(Step(id = UUID.randomUUID().toString(), kind = gl.kind, title = "Not approved", target = gl.target, status = StepStatus.ERROR,
                                startedAt = System.currentTimeMillis(), endedAt = System.currentTimeMillis(),
                                output = "Blocked: this command $why."))
                            items += ToolResultItem(call.id, call.name,
                                "The command was NOT run: it $why. Do not try to work around this (no other tool, no different spelling of the same command). Either continue the task without it or explain to the user what you needed it for.")
                            continue
                        }
                    }
                }
                val label = tool.label(args)
                val detail = when (call.name) {
                    "write_file" -> (args["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    "create_pdf" -> (args["markdown"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    else -> ""
                }.take(200_000).let { com.mazharansari.axion.security.SecretScanner.scrub(it) }
                val step = Step(id = UUID.randomUUID().toString(), kind = label.kind, title = label.title, target = label.target, status = StepStatus.RUNNING, startedAt = System.currentTimeMillis(), detail = detail)
                sink.step(step)
                var lastPush = 0L
                val live = ToolContext(workspace, skills, memory = memory, chats = conversations, schedules = schedules, appCtx = appCtx, ask = ask) { partial ->
                    val now = System.currentTimeMillis()
                    if (now - lastPush > 250) { lastPush = now; sink.step(step.copy(output = com.mazharansari.axion.security.SecretScanner.scrub(partial))) }
                }
                val outcome = try {
                    policy.execute(call.name, call.arguments, FaultPolicy.timeoutFor(call.name, args), onRetry = { sink.step(step.copy(title = "Retrying…")) }) { tool.run(args, live) }
                } catch (e: CancellationException) {
                    sink.step(step.copy(status = StepStatus.ERROR, output = "Stopped", endedAt = System.currentTimeMillis())); throw e
                }
                val out = outcome.output
                val failedKind = outcome.kind == FaultPolicy.Kind.FAILED || outcome.kind == FaultPolicy.Kind.TIMED_OUT || outcome.kind == FaultPolicy.Kind.SKIPPED_REPEAT || outcome.kind == FaultPolicy.Kind.BUDGET
                val doneTitle = (out.doneTitle ?: label.title) + if (outcome.kind == FaultPolicy.Kind.RETRIED_OK) " (retried)" else ""
                val shown = (if (rememberedNote) "(remembered approval) " else "") + if (failedKind) (if (outcome.mustReport) "Gave up: " else "Trying another way — ") + out.display.take(300) else out.display
                sink.step(step.copy(
                    title = doneTitle, status = if (out.isError) StepStatus.ERROR else StepStatus.DONE,
                    output = com.mazharansari.axion.security.SecretScanner.scrub(shown.clip(20000)), endedAt = System.currentTimeMillis(), exitCode = out.exitCode,   // axion-p1.4; v0.6.1: persisted step output cap 4k -> 20k
                ))
                if (!out.isError) out.artifact?.let(sink::artifact)
                sink.usedTool(ToolCatalog.titleOf(call.name))   // axion-p3: Used tool: …
                // v0.5.2: a key printed by a command (cat .env, env, a config file) never goes back to the AI provider.
                items += ToolResultItem(call.id, call.name, com.mazharansari.axion.security.SecretScanner.scrub(outcome.forModel))
                if (outcome.mustReport) { toolsOff = true; planState.failRunning(out.forModel.lineSequence().firstOrNull().orEmpty()); onPlan() }
            }
            if (round == maxRounds - 2) items += UserItem("(System note: you are near the step limit. Finish now with your best answer.)")
        }
        if (planState.active) { planState.finish(ok = false, reason = "step limit"); onPlan() }
        return answer.toString()
    }

    /**
     * v0.11: ONE plain completion for a tool that thinks internally (deep-research planning, gap
     * analysis, synthesis). Tries this run's attempts in order with a per-attempt timeout; a model
     * that answers only in its reasoning channel has that text used, like streamOnce does.
     */
    private suspend fun askOnce(attempts: () -> List<Attempt>, prompt: String): String {
        val list = attempts()
        if (list.isEmpty()) throw NoModelException("No AI connector yet. Open Connectors and paste a free API key (Gemini, Groq, OpenRouter…).")
        var last: Exception? = null
        for (a in list) {
            try {
                val text = StringBuilder(); val reasoning = StringBuilder()
                val finished = kotlinx.coroutines.withTimeoutOrNull(ASK_TIMEOUT_MS) {
                    a.client.stream(LlmRequest(a.model, listOf(UserItem(prompt)))).collect { ev ->
                        when (ev) {
                            is LlmEvent.Text -> text.append(ev.delta)
                            is LlmEvent.Reasoning -> reasoning.append(ev.delta)
                            else -> {}
                        }
                    }
                    true
                }
                val out = (if (text.isNotBlank()) text.toString() else reasoning.toString()).trim()
                if (finished == true && out.isNotEmpty()) return out
                last = LlmException("${a.model} gave no answer", FailKind.SERVER)
            } catch (e: CancellationException) { throw e }
            catch (e: LlmException) { a.onFail(e); last = e }
            catch (e: Throwable) { last = LlmException(e.message ?: "model call failed", FailKind.SERVER) }
        }
        throw last ?: NoModelException("No model answered.")
    }

    private class SlowModel(val why: String) : CancellationException(why)

    /**
     * One model call with automatic failover across connectors/models until one answers.
     * v0.5.1 watchdog — never an endless "Thinking": every attempt (the last one too) must
     *  - start the answer (text or a tool call) within [firstContentMs] — reasoning tokens count only as "still alive"
     *    for models showing their thinking, up to [thinkCapMs]; small talk must answer within [firstContentMs];
     *  - never go silent for more than [stallMs] once something has arrived.
     * A stalled model is cancelled (the HTTP call is closed) and the next model/key in the rotation answers from scratch,
     * with a "Switching to …" line. A model that puts its whole answer in the reasoning channel (content empty) has that
     * text shown as the answer.
     */
    private suspend fun streamOnce(items: List<ChatItem>, specs: List<ToolSpec>, attempts: () -> List<Attempt>, sink: AgentSink, quick: Boolean = false, effort: String? = null): Pair<String, List<ToolCall>> {
        val list = attempts()
        if (list.isEmpty()) throw NoModelException("No AI connector yet. Open Connectors and paste a free API key (Gemini, Groq, OpenRouter…).")
        var last: LlmException? = null
        for ((idx, a) in list.withIndex()) {
            val text = StringBuilder(); val reasoning = StringBuilder(); var calls = emptyList<ToolCall>()
            val t0 = System.currentTimeMillis()
            val lastEvent = java.util.concurrent.atomic.AtomicLong(t0)
            val answering = java.util.concurrent.atomic.AtomicBoolean(false)
            val thinking = java.util.concurrent.atomic.AtomicBoolean(false)
            val slowWhy = java.util.concurrent.atomic.AtomicReference<String?>(null)
            if (idx > 0) sink.switching(if (a.model == a.provider) a.provider else "${a.provider} · ${a.model}")
            try {
                sink.model(if (a.model == a.provider) a.provider else "${a.provider} · ${a.model}")
                kotlinx.coroutines.coroutineScope {
                    val first = maxOf(firstContentMs, a.patientMs)
                    val stall = maxOf(stallMs, a.patientMs)
                    val cap = maxOf(thinkCapMs, a.patientMs)
                    val watchdog = launch {
                        while (true) {
                            kotlinx.coroutines.delay(minOf(250L, first / 4).coerceAtLeast(20L))
                            val now = System.currentTimeMillis()
                            val why = when {
                                now - lastEvent.get() > stall && (answering.get() || thinking.get()) -> "stopped sending for ${stall / 1000} s"
                                !answering.get() && !thinking.get() && now - t0 > first -> "sent no answer for ${first / 1000} s"
                                !answering.get() && thinking.get() && now - t0 > cap -> "kept thinking past ${cap / 1000} s"
                                else -> null
                            }
                            if (why != null) { slowWhy.set(why); this@coroutineScope.cancel(SlowModel(why)) }
                        }
                    }
                    sink.requestStart()
                    a.client.stream(LlmRequest(a.model, items, specs, quick = quick, effort = effort)).collect { ev ->
                        when (ev) {
                            is LlmEvent.Text -> { lastEvent.set(System.currentTimeMillis()); if (!answering.getAndSet(true)) sink.firstToken(); text.append(ev.delta); sink.text(ev.delta) }
                            is LlmEvent.Reasoning -> {
                                reasoning.append(ev.delta)
                                // Small talk hides thoughts and must answer fast: its reasoning doesn't count as progress.
                                if (!quick) { lastEvent.set(System.currentTimeMillis()); if (!thinking.getAndSet(true) && !answering.get()) sink.firstToken(); sink.reasoning(ev.delta) }
                            }
                            is LlmEvent.ToolCalls -> { lastEvent.set(System.currentTimeMillis()); answering.set(true); calls = ev.calls }
                            is LlmEvent.Finished -> lastEvent.set(System.currentTimeMillis())
                        }
                    }
                    watchdog.cancel()
                }
                if (text.isBlank() && calls.isEmpty()) {
                    val r = reasoning.toString().trim()
                    if (r.isEmpty()) throw LlmException("Empty reply", FailKind.SERVER)
                    // Reasoning-only output (some gpt-oss / R1 replies): the answer is in there — show it.
                    sink.text(r)
                    return r to calls
                }
                return text.toString() to calls
            } catch (e: SlowModel) {
                currentCoroutineContextActive()
                val le = LlmException("${a.model} ${e.why}", FailKind.SERVER)
                a.onFail(le); last = le
                if (text.isNotEmpty()) sink.discardRound()
            } catch (e0: LlmException) {
                // v0.5.2: the watchdog closed the call → whatever the socket said ("Canceled"), the truth is "too slow".
                val e = slowWhy.get()?.let { LlmException("${a.model} $it", FailKind.SERVER) } ?: e0
                a.onFail(e)
                last = e
                if (text.isNotEmpty()) {
                    // Failed mid-answer: if another model is left, it answers from scratch; otherwise keep what we have.
                    if (idx == list.size - 1) throw e
                    sink.discardRound()
                }
            }
        }
        throw last ?: NoModelException("No model answered.")
    }

    /** A user's Stop cancels the parent job: that must stay a cancel, not turn into a "switch model". */
    private suspend fun currentCoroutineContextActive() = kotlinx.coroutines.currentCoroutineContext().let { ctx ->
        ctx[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw CancellationException("stopped") }
    }

    private fun systemPrompt(p: Profile, tools: List<Tool>, intent: Intent): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        return buildString {
            appendLine("You are ${p.assistantName}, a friendly personal AI assistant on the user's Android phone, made for ${p.userName.ifBlank { "the user" }}.")
            appendLine("Now: $now (user's local time).")
            appendLine("LANGUAGE + SCRIPT: always reply in the user's language AND the same script they typed. Roman Urdu / Hinglish (Urdu or Hindi written in English letters, e.g. \"Kya hal he\") → reply in natural Roman Urdu in English letters — NEVER switch to Devanagari (हिंदी) or Urdu script (اردو) for it; Urdu script → Urdu script; Devanagari → Devanagari; English → English. Mixed → follow the script of the user's latest message. Understand casual spelling (\"he\" = hai, \"kese\" = kaise). Files you make (PDFs, docs) follow the same language and script unless asked otherwise.${if (p.language.isNotBlank()) " Preferred language: ${p.language}." else ""}")
            appendLine("STYLE: talk like a smart friend in a chat — short and natural. Greetings and small talk get one or two warm lines, no headings, no lists. Use markdown only for real content (steps, tables, code).")
            appendLine("ACTIONS: only DO things (search, create files, run commands, build) when the user clearly asks for that action. Never create or write files for a greeting, a question, or small talk. If you are not sure what they want, ask ONE short question instead of acting.")
            appendLine("ABOUT YOU (answer honestly if asked): you can chat in English/Urdu/Roman Urdu, search the web and read pages, read uploaded files (PDF, docs, ZIP), write files and code, make PDF reports and images, run shell commands (${com.mazharansari.axion.linux.LinuxHub.devtools?.summaryForAgent() ?: "Android sh with toybox tools — Python/Node are NOT installed until the user installs Developer tools in Settings"}), use installed skills and MCP connectors, and do voice calls. Websites/apps are built in the Build tab.")
            appendLine("You also have your OWN real web browser (the browser_* tools: pages open with JavaScript, cookies and logins persist, you can click, type into forms and take screenshots — the user can watch or take over in the Browser screen) and a persistent Linux terminal (shell_session keeps its directory and variables between commands; run_background runs long jobs).")
            if (tools.isEmpty()) {
                appendLine("This message is conversation: answer directly from what you know. No tools are available for it; if the user wants you to search or make something, they can just ask.")
            } else {
                appendLine()
                appendLine("Tools for this request:")
                if (intent == Intent.LOOKUP || intent == Intent.CHAT) appendLine("- Use web_search, then browse_url on the best results, and answer with facts you actually read. Never invent URLs, prices or numbers. Only act when the user clearly asks. If a page needs JavaScript, a form, a click or a login, use your browser instead: browser_open the URL, browser_read it, then browser_click / browser_type with the element numbers from the read.")
                else {
                    appendLine("- web_search + browse_url for current facts or websites; run_command for shell work; read_file/list_files/extract_zip for uploads (in uploads/); write_file for code/documents (files go in outputs/); create_pdf for reports; generate_image for pictures.")
                    appendLine("- YOUR BROWSER (browser_* tools): a real browser — use it when a page needs JavaScript, clicking, typing into forms, logging in, or step-by-step navigation. Flow: browser_open (or browser_search) → read the numbered elements → browser_click / browser_type by number → browser_read again after each navigation (numbers change). browse_url is still the faster choice for a plain article or a quick static read. browser_screenshot saves a picture of the page for the user.")
                    appendLine("- TERMINAL: run_command is a fresh shell each time (quick one-off commands). shell_session is ONE persistent shell — cd and exported variables survive between calls; use it for multi-step work in one place. run_background + job_output + job_kill are for commands that take many minutes (builds, downloads, servers) so the chat is not blocked.")
                    appendLine("- Load the matching skill (load_skill) before a specialised task. A GitHub URL with skills → install_skills_from_github.")
                    appendLine("- Safe steps need no permission; ask before anything destructive, paid or public. Finish with a short summary of what you did.")
                    val skillList = skills.skills.value.filter { it.enabled }.joinToString("\n") { "  - ${it.name}: ${it.description.take(100)}" }
                    if (skillList.isNotBlank()) { appendLine("Skills:"); appendLine(skillList) }
                    val mcp = tools.filter { it.spec.name.startsWith("mcp_") }.map { it.spec.description.substringBefore("]").removePrefix("[") }.distinct()
                    if (mcp.isNotEmpty()) appendLine("MCP servers (tools prefixed mcp_): ${mcp.joinToString()}")
                }
            }
            if (p.instructions.isNotBlank()) { appendLine(); appendLine("The user's custom instructions:"); appendLine(p.instructions) }
        }
    }
}

data class Profile(
    val assistantName: String = "Axion",
    val userName: String = "",
    val language: String = "",
    val instructions: String = "",
)
