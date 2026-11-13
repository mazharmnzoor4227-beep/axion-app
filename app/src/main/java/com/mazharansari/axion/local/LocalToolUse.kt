package com.mazharansari.axion.local

import com.mazharansari.axion.agent.AgentSink
import com.mazharansari.axion.agent.ApprovalGate
import com.mazharansari.axion.agent.Tool
import com.mazharansari.axion.agent.ToolContext
import com.mazharansari.axion.agent.ToolOutput
import com.mazharansari.axion.agent.clip
import com.mazharansari.axion.data.ApprovalsStore
import com.mazharansari.axion.data.Step
import com.mazharansari.axion.data.StepStatus
import com.mazharansari.axion.llm.AssistantItem
import com.mazharansari.axion.llm.ChatItem
import com.mazharansari.axion.llm.SystemItem
import com.mazharansari.axion.llm.ToolResultItem
import com.mazharansari.axion.llm.ToolSpec
import com.mazharansari.axion.llm.UserItem
import com.mazharansari.axion.security.SecretScanner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** One generation from the local model: text, wall time, and the token estimate (≈ chars / 4, same as HistoryCompressor). */
data class LocalGeneration(val text: String, val ms: Long, val approxTokens: Int) {
    val tokPerSec: Double get() = if (ms <= 0) 0.0 else approxTokens * 1000.0 / ms
}

/** Measured local-model speed, shown in the chat UI (P2-4). */
data class LocalSpeed(val tokens: Int, val ms: Long) {
    val tokPerSec: Double get() = if (ms <= 0) 0.0 else tokens * 1000.0 / ms
    fun label(): String = "Local model · ${"%.1f".format(tokPerSec)} tok/s · $tokens tokens"
}

data class LocalToolStep(val tool: String, val arguments: String, val result: String, val ok: Boolean)

data class LocalAgentResult(val answer: String, val steps: List<LocalToolStep>, val generations: List<LocalGeneration>) {
    val totalTokens: Int get() = generations.sumOf { it.approxTokens }
    val totalMs: Long get() = generations.sumOf { it.ms }
    val speed: LocalSpeed get() = LocalSpeed(totalTokens, totalMs)
}

/** What one local-model reply parses to. */
sealed interface ParsedCall {
    data class Call(val tool: String, val args: JsonObject) : ParsedCall
    data class Final(val text: String) : ParsedCall
    data class Invalid(val reason: String) : ParsedCall
}

/**
 * P2-4: function calling for the on-device model (LFM3 1.7B and the other local models).
 *
 * Honest limit, stated here and in the report: the llama.cpp JNI in this repo
 * (app/src/main/cpp/axion_llama.cpp, `generate`) samples with a plain top-k / top-p /
 * temperature chain — it exposes NO grammar parameter, so true grammar-constrained
 * JSON (llama.cpp GBNF) is NOT active. What this file does instead, entirely in
 * Kotlin without touching the native library:
 *   1. a strict tool-call prompt (low temperature, one JSON object, nothing else),
 *   2. a tolerant JSON extractor + validator (fenced or bare JSON, balanced braces),
 *   3. a bounded tool loop (file + shell tools only) that feeds each result back,
 *   4. measured tok/s on every generation, shown in the chat UI.
 * A grammar-constrained sampler would need a new JNI entry point (`generateWithGrammar`)
 * plus a GBNF schema builder in C++ — a native change, deliberately not done here.
 */
object LocalToolUse {
    /** Tools the on-device model may call: file + shell only — the small model cannot drive the rest safely. */
    val ALLOWED_TOOLS = setOf("read_file", "write_file", "list_files", "run_command", "shell_session")

    fun approxTokens(chars: Int): Int = (chars + 3) / 4

    /** The tool-call system prompt: short (small models, 4k context) and strict about the format. */
    fun buildToolPrompt(tools: List<ToolSpec>): String = buildString {
        appendLine("You are Axion, running offline on the user's phone. You can use tools. Reply with exactly ONE JSON object and nothing else.")
        appendLine("TOOLS:")
        for (t in tools) {
            appendLine("- ${t.name} ${t.parameters} — ${t.description.lineSequence().firstOrNull().orEmpty().take(120)}")
        }
        appendLine("FORMAT — a tool call: {\"tool\": \"read_file\", \"arguments\": {\"path\": \"notes.txt\"}}")
        appendLine("FORMAT — a final answer (no more tools needed): {\"answer\": \"your reply here\"}")
        appendLine("RULES: one JSON object only, no text outside it. Use tools for real work; answer directly when no tool is needed. Never invent file contents — read the file first.")
    }

    /** Pulls the first balanced JSON object out of the reply (fenced ```json or bare, prose around it allowed). */
    fun extractJson(text: String): String? {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text.trim())?.groupValues?.get(1)?.trim()
        val src = fenced ?: text
        val start = src.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until src.length) {
            val c = src[i]
            if (esc) { esc = false; continue }
            when (c) {
                '\\' -> if (inStr) esc = true
                '"' -> inStr = !inStr
                '{' -> if (!inStr) depth++
                '}' -> if (!inStr) {
                    depth--
                    if (depth == 0) return src.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun parse(text: String, allowed: Set<String>): ParsedCall {
        val json = extractJson(text) ?: return ParsedCall.Invalid("no JSON object found")
        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return ParsedCall.Invalid("not valid JSON")
        (obj["answer"] as? JsonPrimitive)?.contentOrNull?.let { return ParsedCall.Final(it) }
        val tool = (obj["tool"] as? JsonPrimitive)?.contentOrNull
            ?: return ParsedCall.Invalid("no \"tool\" field")
        if (tool !in allowed) return ParsedCall.Invalid("unknown tool \"$tool\"")
        val args = obj["arguments"] as? JsonObject
            ?: return ParsedCall.Invalid("\"arguments\" must be an object")
        return ParsedCall.Call(tool, args)
    }
}

/**
 * The on-device agent loop: prompt → generate → parse → run the tool → feed the result back,
 * up to [maxSteps] tool calls, then a final answer. Every generation is timed, so the chat
 * can show real tok/s. Tool steps are reported through [sink] exactly like the cloud agent's,
 * so the UI shows them the same way. Approval-gated shell work follows the same rule as the
 * cloud agent in a headless run: a remembered rule passes, anything else is refused here
 * (there is no dialog inside this loop — the user approves from the chat instead).
 */
class LocalAgentRunner(
    private val generate: suspend (items: List<ChatItem>) -> LocalGeneration,
    tools: List<Tool>,
    private val ctx: ToolContext,
    private val approvals: ApprovalsStore? = null,
    private val sink: AgentSink? = null,
    private val maxSteps: Int = 6,
) {
    private val byName = tools.filter { it.spec.name in LocalToolUse.ALLOWED_TOOLS }.associateBy { it.spec.name }

    suspend fun run(history: List<ChatItem>, user: UserItem): LocalAgentResult {
        val items = ArrayList<ChatItem>()
        items += SystemItem(LocalToolUse.buildToolPrompt(byName.values.map { it.spec }))
        items += history.takeLast(6)
        items += user
        val steps = ArrayList<LocalToolStep>()
        val gens = ArrayList<LocalGeneration>()
        var invalid = 0
        repeat(maxSteps + 1) { round ->
            val gen = generate(items)
            gens += gen
            when (val p = LocalToolUse.parse(gen.text, byName.keys)) {
                is ParsedCall.Final -> return LocalAgentResult(p.text, steps, gens)
                is ParsedCall.Invalid -> {
                    invalid++
                    if (invalid >= 2 || round == maxSteps) {
                        return LocalAgentResult(
                            "The local model could not format a tool call (${p.reason}). Try a shorter request, or switch to a cloud model for this task.",
                            steps, gens,
                        )
                    }
                    items += AssistantItem(gen.text)
                    items += UserItem("That was not a valid tool call (${p.reason}). Reply with exactly one JSON object: {\"tool\": ..., \"arguments\": {...}} or {\"answer\": ...}.")
                }
                is ParsedCall.Call -> {
                    val tool = byName[p.tool]!!
                    val gate = ApprovalGate.requestFor(p.tool, p.args)
                    if (gate != null && !( !gate.hard && approvals?.matches(gate.pattern) == true)) {
                        val msg = "The command was NOT run: it needs the user's approval (${gate.summary}). Ask again from the chat and approve it there."
                        steps += LocalToolStep(p.tool, p.args.toString(), msg, false)
                        return LocalAgentResult("I could not run that — it needs your approval first. ${gate.summary}", steps, gens)
                    }
                    val label = tool.label(p.args)
                    val step = Step(id = UUID.randomUUID().toString(), kind = label.kind, title = label.title, target = label.target, status = StepStatus.RUNNING, startedAt = System.currentTimeMillis())
                    sink?.step(step)
                    val out = runCatching { tool.run(p.args, ctx) }.getOrElse { ToolOutput("Tool error: ${it.message}", isError = true) }
                    sink?.step(step.copy(status = if (out.isError) StepStatus.ERROR else StepStatus.DONE, output = out.display.clip(20000), endedAt = System.currentTimeMillis(), exitCode = out.exitCode))
                    steps += LocalToolStep(p.tool, p.args.toString(), out.forModel, !out.isError)
                    items += AssistantItem(gen.text)
                    items += ToolResultItem(UUID.randomUUID().toString(), p.tool, SecretScanner.scrub(out.forModel))
                }
            }
        }
        return LocalAgentResult(
            "The local model used all $maxSteps tool steps without a final answer. What it did:\n" +
                steps.joinToString("\n") { "- ${it.tool}: ${it.result.take(200)}" },
            steps, gens,
        )
    }
}
