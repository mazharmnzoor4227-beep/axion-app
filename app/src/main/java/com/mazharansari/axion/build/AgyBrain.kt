package com.mazharansari.axion.build

import com.mazharansari.axion.antigravity.AgyAccounts
import com.mazharansari.axion.antigravity.AgyFailover
import com.mazharansari.axion.antigravity.AgySelector
import com.mazharansari.axion.data.Project
import com.mazharansari.axion.linux.LinuxRuntime
import com.mazharansari.axion.quota.QuotaRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile

/** agy stream-json lines → Build events, keeping each tool's parameters (file path, code, command). Pure, unit-tested. */
object AgyStepParser {
    sealed interface Ev {
        data class Init(val conversationId: String) : Ev
        data class Text(val delta: String) : Ev
        data class Thought(val delta: String) : Ev
        data class Tool(val id: String, val tool: String, val state: String, val path: String?, val command: String?, val content: String?, val output: String?, val description: String) : Ev
        data class Result(val conversationId: String?, val ok: Boolean, val response: String?, val error: String?, val inTok: Long, val outTok: Long) : Ev
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject?.any(vararg keys: String): String? = this?.let { o -> keys.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } } }

    fun parse(line: String): Ev? {
        val root = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        return when (root.s("event")) {
            "init" -> root.s("conversation_id").takeIf { it.isNotBlank() }?.let { Ev.Init(it) }
            "step_update" -> {
                val step = root["step_update"] as? JsonObject ?: return null
                step.s("text_delta").takeIf { it.isNotEmpty() }?.let { return Ev.Text(it) }
                step.any("thinking_delta", "thought_delta", "reasoning_delta")?.let { return Ev.Thought(it) }
                val type = step.s("step_type")
                val info = step["tool_info"] as? JsonObject
                val name = step.s("tool_name").ifBlank { info?.s("name").orEmpty() }
                if (!(type.contains("tool", true) || type.contains("command", true) || name.isNotBlank())) return null
                val params = (info?.get("parameters") as? JsonObject) ?: (step["parameters"] as? JsonObject)
                val id = step.any("step_id", "step_index", "tool_call_id", "id") ?: info.any("id", "call_id")
                    ?: (name + "|" + (params.any("TargetFile", "AbsolutePath", "CommandLine") ?: ""))
                // multi_replace sends chunks: join their replacements so the card shows the new code
                val chunks = (params?.get("ReplacementChunks") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.any("ReplacementContent") }?.joinToString("\n…\n")
                Ev.Tool(
                    id = id, tool = name.ifBlank { type.ifBlank { "tool" } }, state = step.s("state").ifBlank { info?.s("state").orEmpty() }.uppercase(),
                    path = params.any("TargetFile", "AbsolutePath", "FilePath", "path", "file"),
                    command = params.any("CommandLine", "command", "Command"),
                    content = params.any("CodeContent", "ReplacementContent", "Content", "content") ?: chunks,
                    output = step.any("output", "result", "stdout", "combined_output") ?: info.any("output", "result"),
                    description = step.s("description").ifBlank { params.any("Query", "DirectoryPath", "Url", "SearchPath") ?: "" },
                )
            }
            "result" -> {
                val r = root["result"] as? JsonObject ?: root
                val u = r["usage"] as? JsonObject ?: root["usage"] as? JsonObject
                fun n(k: String) = (u?.get(k) as? JsonPrimitive)?.longOrNull ?: 0L
                val status = r.s("status").ifBlank { "ERROR" }
                val err = r.s("error").ifBlank { null }
                Ev.Result(r.s("conversation_id").ifBlank { null }, err == null && !status.equals("ERROR", true), r.s("response").ifBlank { null }, err,
                    n("input_tokens") + n("cache_read_tokens"), n("output_tokens") + n("thinking_tokens"))
            }
            else -> null
        }
    }

    fun isDone(state: String) = state == "DONE" || state == "COMPLETED" || state == "SUCCESS" || state == "FAILED" || state == "ERROR" || state == "CANCELED"
    fun isFailed(state: String) = state == "FAILED" || state == "ERROR"

    fun command(model: String, conversation: String?): List<String> = buildList {
        add("/root/.local/bin/agy"); addAll(listOf("--input-format", "stream-json", "--output-format", "stream-json", "--print-timeout", "60m", "--dangerously-skip-permissions"))
        if (model.isNotBlank() && model != "auto") addAll(listOf("--model", model))
        if (conversation != null) addAll(listOf("--conversation", conversation)) else add("--new-project")
    }
}

/**
 * Antigravity as the Build brain: agy runs inside the project folder (/root/projects/<slug>) and keeps ONE conversation per
 * project, so follow-ups continue the same session. Multi-account: on a limit / sign-out / eligibility failure the account is
 * marked and the same turn re-runs on the next account (Kodex's retryAntigravityWithNextAccount) — on a new account the
 * conversation starts fresh with the project folder as its memory.
 */
class AgyBrain(
    private val linux: LinuxRuntime,
    private val accounts: AgyAccounts,
    private val store: ProjectStore,
    private val quota: QuotaRepo?,
) : BuildBrain {
    override suspend fun turn(project: Project, prompt: String, fresh: Boolean, events: (BrainEvent) -> Unit): BrainResult = withContext(Dispatchers.IO) {
        if (!linux.isInstalled()) return@withContext BrainResult(false, "Build needs Developer tools (Linux) first: Settings → Developer tools.")
        val tried = mutableSetOf<String>()
        var p = store.get(project.id) ?: project
        var promptNow = prompt
        while (true) {
            val acc = accounts.pickForRun(exclude = tried) ?: run {
                val back = AgySelector.earliestBack(accounts.accounts(), System.currentTimeMillis())
                return@withContext BrainResult(false, if (back != null) "Every Antigravity account has hit its limit. ${back.label.ifBlank { back.email }} is back at ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(back.cooldownUntil))}."
                    else "No Antigravity account is ready. Add one in Settings → Antigravity.")
            }
            tried += acc.email
            accounts.markUsed(acc.email)
            val sameAccount = p.agyAccount.equals(acc.email, true)
            val conversation = if (sameAccount && !fresh) p.agyConversation else null
            if (conversation == null && !fresh && !promptNow.contains("<axion_build>\nYou are Axion")) promptNow = BuildPrompts.system(p, store.guestPath(p)) + "\n\n" + promptNow
            events(BrainEvent.Note("Antigravity · ${acc.label.ifBlank { acc.email }}"))
            val out = File(linux.home, "agy-build-${System.nanoTime()}.log")
            val cwd = store.guestPath(p)
            val bind = accounts.bindFor(acc)
            val proc = try { linux.spawn(AgyStepParser.command(accounts.store.value.model, conversation), cwd, listOf(bind), outputFile = out) } catch (e: Throwable) { accounts.release(acc); throw e }
            var result: AgyStepParser.Ev.Result? = null
            val plain = StringBuilder(); val said = StringBuilder()
            val open = HashMap<String, AgyStepParser.Ev.Tool>()
            try {
                val msg = buildJsonObject { put("event", "user"); putJsonObject("message") { put("content", promptNow) } }.toString() + "\n"
                proc.outputStream.write(msg.toByteArray()); proc.outputStream.flush(); proc.outputStream.close()
                var offset = 0L; val partial = StringBuilder(); var lastAny = System.currentTimeMillis()
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val len = out.length()
                    if (len > offset) {
                        val bytes = RandomAccessFile(out, "r").use { f -> f.seek(offset); ByteArray(minOf(len - offset, 65536L).toInt()).also { f.readFully(it) } }
                        offset += bytes.size; partial.append(bytes.decodeToString()); lastAny = System.currentTimeMillis()
                        var nl = partial.indexOf("\n")
                        while (nl >= 0) {
                            val line = partial.substring(0, nl).trim(); partial.delete(0, nl + 1)
                            when (val ev = AgyStepParser.parse(line)) {
                                is AgyStepParser.Ev.Init -> { store.setAgy(p.id, ev.conversationId, acc.email); p = store.get(p.id) ?: p }
                                is AgyStepParser.Ev.Text -> { said.append(ev.delta); events(BrainEvent.Text(ev.delta)) }
                                is AgyStepParser.Ev.Thought -> events(BrainEvent.Thought(ev.delta))
                                is AgyStepParser.Ev.Tool -> {
                                    val known = open[ev.id]
                                    if (known == null && !AgyStepParser.isDone(ev.state)) {
                                        open[ev.id] = ev
                                        events(BrainEvent.ToolStart(ev.id, ev.tool, ev.command ?: ev.description.ifBlank { ev.path.orEmpty() }, ev.path, ev.content))
                                    } else if (known == null) {
                                        events(BrainEvent.ToolStart(ev.id, ev.tool, ev.command ?: ev.description.ifBlank { ev.path.orEmpty() }, ev.path, ev.content))
                                        events(BrainEvent.ToolDone(ev.id, !AgyStepParser.isFailed(ev.state), ev.output.orEmpty()))
                                    } else if (AgyStepParser.isDone(ev.state)) {
                                        open.remove(ev.id); events(BrainEvent.ToolDone(ev.id, !AgyStepParser.isFailed(ev.state), ev.output.orEmpty()))
                                    } else if (ev.content != null && ev.content != known.content) {
                                        open[ev.id] = ev; events(BrainEvent.ToolProgress(ev.id, ev.content))
                                    }
                                }
                                is AgyStepParser.Ev.Result -> result = ev
                                null -> if (line.isNotBlank()) plain.append(line).append('\n')
                            }
                            nl = partial.indexOf("\n")
                        }
                        continue
                    }
                    if (!proc.isAlive) { proc.waitFor(); if (out.length() <= offset) break else continue }
                    if (System.currentTimeMillis() - lastAny > 62 * 60_000L) { proc.destroyForcibly(); break }
                    delay(80)
                }
                open.keys.forEach { events(BrainEvent.ToolDone(it, true)) }
                val r = result
                quota?.recordAgyRun(acc.email, r?.ok == true, r?.inTok ?: 0, r?.outTok ?: 0)
                r?.conversationId?.let { store.setAgy(p.id, it, acc.email) }
                if (r != null && r.ok) {
                    if (said.isEmpty() && !r.response.isNullOrBlank()) events(BrainEvent.Text(r.response))
                    return@withContext BrainResult(true, text = said.toString().ifBlank { r.response.orEmpty() })
                }
                val why = r?.error ?: plain.toString().takeLast(400).ifBlank { "Antigravity stopped without an answer" }
                val now = System.currentTimeMillis()
                when {
                    AgyFailover.isEligibilityFailure(why) -> accounts.markIneligible(acc.email)
                    AgyFailover.isAuthFailure(why) -> accounts.markAuthExpired(acc.email)
                    AgyFailover.isQuotaFailure(why) -> accounts.markQuota(acc.email, AgyFailover.resetHint(why, now))
                    // a stale conversation id (deleted/expired on Google's side): retry once on the same account, fresh
                    conversation != null && (why.contains("conversation", true) && (why.contains("not found", true) || why.contains("invalid", true))) -> {
                        store.setAgy(p.id, null, null); p = store.get(p.id) ?: p; tried -= acc.email; continue
                    }
                }
                val canSwitch = accounts.store.value.autoSwitch && AgyFailover.isFailoverEligible(why)
                val next = if (canSwitch) accounts.pickForRun(exclude = tried) else null
                if (next == null) return@withContext BrainResult(false, friendly(why))
                events(BrainEvent.Note("${acc.label.ifBlank { acc.email }} hit its limit — continuing on ${next.label.ifBlank { next.email }}."))
            } finally {
                if (proc.isAlive) { proc.destroyForcibly(); runCatching { proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) } }
                out.delete()
                accounts.release(acc)   // v0.5.2: sign-in files sealed again
            }
        }
        @Suppress("UNREACHABLE_CODE") BrainResult(false)
    }

    private fun friendly(raw: String): String {
        val v = raw.replace(Regex("\\s+"), " ").trim()
        return when {
            AgyFailover.isAuthFailure(v) -> "Antigravity sign-in expired. Sign in again in Settings → Antigravity."
            AgyFailover.isEligibilityFailure(v) -> "Google says this account is not eligible for Antigravity."
            AgyFailover.isQuotaFailure(v) -> "Antigravity limit reached on every account."
            else -> v.take(400)
        }
    }
}
