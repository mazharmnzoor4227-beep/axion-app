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
        )
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
            Intent.CHAT -> if (IntentGate.isSmallTalk(user.text, intent)) emptyList() else all.filter { it.spec.name in IntentGate.chatTools }
            Intent.LOOKUP -> all.filter { it.spec.name in IntentGate.chatTools }
            Intent.ACTION -> all
        } + planTools
        val byName = tools.associateBy { it.spec.name }
        val followUp = IntentGate.isFollowUp(user.text)
        val hint = ScriptHint.note(user.text, history, followUp)
        // Keep requests small (Groq free tier: 8k tokens/min): recent turns verbatim, everything older → "Session notes" (decisions never dropped).
        val folded = HistoryCompressor.compress(history)
        val sys = systemPrompt(profile, tools, intent) + hint + (if (folded.notes.isNotBlank()) "\n\n" + folded.notes else "") + (if (wantsPlan) "\n\n" + Planner.INSTRUCTIONS else "") +
            (if (tools.isNotEmpty()) ToolCatalog.promptNote(sw).let { if (it.isBlank()) "" else "\n\n" + it } else "")
        val items = mutableListOf<ChatItem>(SystemItem(sys))
        items += folded.recent
        items += user
        val answer = StringBuilder()
        val quick = IntentGate.isSmallTalk(user.text, intent)
        // v0.5.1: short/simple prompts ask reasoning models to think little (gpt-oss: reasoning_effort "low").
        val effort = if (quick || (intent == Intent.CHAT && user.text.trim().length <= 160)) "low" else null
        var planPhase = wantsPlan && tools.any { it.spec.name == Planner.PLAN_SET }
        var toolsOff = false
        val policy = FaultPolicy()
        repeat(maxRounds) { round ->
            val specs = when { toolsOff -> emptyList(); planPhase -> listOf(Planner.planSetSpec); else -> tools.map { it.spec } }
            val (text, calls) = streamOnce(HistoryCompressor.compressRun(items), specs, attempts, sink, quick, effort)
            planPhase = false
            items += AssistantItem(text, calls)
            if (calls.isEmpty()) { answer.append(text); if (planState.active) { planState.finish(ok = !toolsOff, reason = "stopped early"); onPlan() }; return answer.toString() }
            if (text.isNotBlank()) sink.textToThoughts()
            val ctx = ToolContext(workspace, skills)
            for (call in calls) {
                val tool = byName[call.name]
                val args = runCatching { Json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject }.getOrElse { JsonObject(emptyMap()) }
                if (tool == null) { items += ToolResultItem(call.id, call.name, if (call.name in offNames) ToolCatalog.refusalFor(call.name) else "Unknown tool ${call.name}"); continue }
                if (call.name == Planner.PLAN_SET || call.name == Planner.PLAN_UPDATE) {   // the plan card is its own UI, no step row
                    val o = runCatching { tool.run(args, ctx) }.getOrElse { ToolOutput("Plan error: ${it.message}", isError = true) }
                    items += ToolResultItem(call.id, call.name, o.forModel); continue
                }
                if (toolsOff) { items += ToolResultItem(call.id, call.name, "Tools are switched off for the rest of this task. Explain to the user what failed and why."); continue }
                val label = tool.label(args)
                val detail = when (call.name) {
                    "write_file" -> (args["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    "create_pdf" -> (args["markdown"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    else -> ""
                }.take(200_000).let { com.mazharansari.axion.security.SecretScanner.scrub(it) }
                val step = Step(id = UUID.randomUUID().toString(), kind = label.kind, title = label.title, target = label.target, status = StepStatus.RUNNING, startedAt = System.currentTimeMillis(), detail = detail)
                sink.step(step)
                var lastPush = 0L
                val live = ToolContext(workspace, skills) { partial ->
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
                val shown = if (failedKind) (if (outcome.mustReport) "Gave up: " else "Trying another way — ") + out.display.take(300) else out.display
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
