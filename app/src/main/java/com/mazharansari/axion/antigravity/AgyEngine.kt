package com.mazharansari.axion.antigravity

import com.mazharansari.axion.linux.LinuxRuntime
import com.mazharansari.axion.llm.*
import com.mazharansari.axion.quota.QuotaRepo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile

/** agy's stream-json events (Kodex AntigravityEventParser, ported). */
sealed interface AgyEvent {
    data class Init(val conversationId: String) : AgyEvent
    data class Text(val delta: String) : AgyEvent
    data class Tool(val name: String, val detail: String, val done: Boolean) : AgyEvent
    data class Result(val conversationId: String?, val status: String, val response: String?, val error: String?, val inTok: Long, val outTok: Long) : AgyEvent
}

object AgyEvents {
    fun parse(line: String): AgyEvent? {
        val root = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (root.s("event")) {
            "init" -> root.s("conversation_id").takeIf { it.isNotBlank() }?.let { AgyEvent.Init(it) }
            "step_update" -> {
                val step = root["step_update"] as? JsonObject ?: return null
                val delta = step.s("text_delta")
                if (delta.isNotEmpty()) return AgyEvent.Text(delta)
                val type = step.s("step_type")
                if (type.contains("tool", true) || type.contains("command", true)) {
                    val raw = step.s("tool_name").ifBlank { (step["tool_info"] as? JsonObject)?.s("name").orEmpty() }.ifBlank { type.ifBlank { "Tool" } }
                    val params = (step["tool_info"] as? JsonObject)?.get("parameters") as? JsonObject
                    val detail = step.s("description").ifBlank {
                        listOf("CommandLine", "command", "TargetFile", "AbsolutePath", "DirectoryPath", "Query", "path").firstNotNullOfOrNull { k -> (params?.get(k) as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    }
                    AgyEvent.Tool(displayName(raw), redact(detail), step.s("state") == "DONE")
                } else null
            }
            "result" -> {
                val r = root["result"] as? JsonObject ?: root
                val u = r["usage"] as? JsonObject ?: root["usage"] as? JsonObject
                fun n(k: String) = (u?.get(k) as? JsonPrimitive)?.longOrNull ?: 0L
                AgyEvent.Result(r.s("conversation_id").ifBlank { null }, r.s("status").ifBlank { "ERROR" }, r.s("response").ifBlank { null }, r.s("error").ifBlank { null },
                    n("input_tokens") + n("cache_read_tokens"), n("output_tokens") + n("thinking_tokens"))
            }
            else -> null
        }
    }

    fun displayName(name: String) = when (name.lowercase()) {
        "run_command" -> "Bash"; "write_to_file", "replace_file_content", "multi_replace_file_content" -> "Write"; "view_file" -> "Read"
        "list_dir" -> "List files"; "grep_search", "search_files" -> "Search"; else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    private fun redact(v: String) = v.replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)"), "$1$2••••").replace(Regex("\\s+"), " ").trim().take(300)

    /** The headless command (Kodex antigravityCommand). */
    fun command(model: String): List<String> = buildList {
        add("/root/.local/bin/agy"); addAll(listOf("--input-format", "stream-json", "--output-format", "stream-json", "--print-timeout", "60m", "--dangerously-skip-permissions"))
        if (model.isNotBlank()) addAll(listOf("--model", model))
        add("--new-project")
    }
}

/**
 * Antigravity as a model source in Axion's chat: one agy run per request, on the best account. On a limit / sign-in / eligibility
 * failure it marks the account, switches to the next one (Kodex's retryAntigravityWithNextAccount), says so in the thoughts, and
 * re-runs the same request. Axion's own tools (PDF, web, images) are not available inside an agy run — agy uses its own.
 */
class AgyClient(private val linux: LinuxRuntime, private val accounts: AgyAccounts, private val quota: QuotaRepo?, private val probe: AgyQuotaProbe?) : LlmClient {
    override fun stream(req: LlmRequest): Flow<LlmEvent> = channelFlow {
        if (!linux.isInstalled()) throw LlmException("Antigravity needs the Linux download first (Settings → Antigravity).", FailKind.AUTH)
        val prompt = promptOf(req.items)
        val tried = mutableSetOf<String>()
        while (true) {
            val acc = accounts.pickForRun(exclude = tried) ?: run {
                val back = AgySelector.earliestBack(accounts.accounts(), System.currentTimeMillis())
                throw LlmException(if (back != null) "All Antigravity accounts hit their limit. ${back.label.ifBlank { back.email }} is back at ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(back.cooldownUntil))}."
                    else "No Antigravity account is ready. Add one in Settings → Antigravity.", FailKind.QUOTA, 429)
            }
            tried += acc.email
            accounts.markUsed(acc.email)
            send(LlmEvent.Reasoning("Antigravity · ${acc.label.ifBlank { acc.email }}\n"))
            val out = File(linux.home, "agy-run-${System.nanoTime()}.log")
            val bind = accounts.bindFor(acc)
            val proc = try { linux.spawn(AgyEvents.command(accounts.store.value.model), "/workspace", listOf(bind), outputFile = out) } catch (e: Throwable) { accounts.release(acc); throw e }
            var result: AgyEvent.Result? = null
            try {
                val msg = buildJsonObject { put("event", "user"); putJsonObject("message") { put("content", prompt) } }.toString() + "\n"
                proc.outputStream.write(msg.toByteArray()); proc.outputStream.flush(); proc.outputStream.close()
                var offset = 0L; val partial = StringBuilder(); var lastAny = System.currentTimeMillis(); val plain = StringBuilder(); var streamed = false
                while (true) {
                    ensureActive()
                    val len = out.length()
                    if (len > offset) {
                        val bytes = RandomAccessFile(out, "r").use { f -> f.seek(offset); ByteArray(minOf(len - offset, 65536L).toInt()).also { f.readFully(it) } }
                        offset += bytes.size; partial.append(bytes.decodeToString()); lastAny = System.currentTimeMillis()
                        var nl = partial.indexOf("\n")
                        while (nl >= 0) {
                            val line = partial.substring(0, nl).trim(); partial.delete(0, nl + 1)
                            when (val ev = AgyEvents.parse(line)) {
                                is AgyEvent.Text -> { streamed = true; send(LlmEvent.Text(ev.delta)) }
                                is AgyEvent.Tool -> if (!ev.done) send(LlmEvent.Reasoning("▸ ${ev.name}: ${ev.detail}\n"))
                                is AgyEvent.Result -> result = ev
                                is AgyEvent.Init -> {}
                                null -> if (line.isNotBlank()) plain.append(line).append('\n')
                            }
                            nl = partial.indexOf("\n")
                        }
                        continue
                    }
                    if (!proc.isAlive) { proc.waitFor(); if (out.length() <= offset) break else continue }
                    if (System.currentTimeMillis() - lastAny > 62 * 60_000L) { proc.destroyForcibly(); break }
                    delay(100)
                }
                val r = result
                quota?.recordAgyRun(acc.email, r?.status == "SUCCESS" || r?.status == "OK", r?.inTok ?: 0, r?.outTok ?: 0)
                val ok = r != null && r.error == null && !r.status.equals("ERROR", true)
                if (ok) {
                    if (!streamed && !r!!.response.isNullOrBlank()) send(LlmEvent.Text(r.response!!))
                    send(LlmEvent.Finished("stop"))
                    probe?.probeLater(acc)
                    return@channelFlow
                }
                val why = r?.error ?: plain.toString().takeLast(400).ifBlank { "Antigravity stopped without an answer" }
                val now = System.currentTimeMillis()
                val canSwitch = accounts.store.value.autoSwitch && AgyFailover.isFailoverEligible(why)
                when {
                    AgyFailover.isEligibilityFailure(why) -> accounts.markIneligible(acc.email)
                    AgyFailover.isAuthFailure(why) -> accounts.markAuthExpired(acc.email)
                    AgyFailover.isQuotaFailure(why) -> accounts.markQuota(acc.email, AgyFailover.resetHint(why, now))
                }
                val next = if (canSwitch) accounts.pickForRun(exclude = tried) else null
                if (next == null) throw LlmException(friendly(why), when { AgyFailover.isAuthFailure(why) -> FailKind.AUTH; AgyFailover.isQuotaFailure(why) -> FailKind.QUOTA; else -> FailKind.SERVER }, if (AgyFailover.isQuotaFailure(why)) 429 else 0)
                send(LlmEvent.Reasoning("${acc.label.ifBlank { acc.email }} hit its limit — switching to ${next.label.ifBlank { next.email }}.\n"))
            } finally {
                if (proc.isAlive) { proc.destroyForcibly(); runCatching { proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) } }
                out.delete()
                accounts.release(acc)   // v0.5.2: sign-in files sealed again
            }
        }
    }

    private fun promptOf(items: List<ChatItem>): String {
        val sys = items.filterIsInstance<SystemItem>().firstOrNull()?.text.orEmpty()
        val turns = items.filter { it is UserItem || it is AssistantItem }.takeLast(9)
        return buildString {
            appendLine("<axion>")
            appendLine("You are running inside Axion on the user's Android phone, in a private Ubuntu sandbox. The working folder is /workspace (the app's files: uploads/ for files the user gave, outputs/ for anything you make). Reply in the user's language and script, short and natural.")
            if (sys.isNotBlank()) { appendLine("Axion's instructions:"); appendLine(sys.take(3000)) }
            appendLine("</axion>")
            turns.dropLast(1).forEach { t -> when (t) { is UserItem -> appendLine("User: ${t.text.take(2000)}"); is AssistantItem -> appendLine("Assistant: ${t.text.take(2000)}"); else -> {} } }
            appendLine(); append((turns.lastOrNull() as? UserItem)?.text.orEmpty())
        }
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

/** `agy -p "/usage" --output-format json` per account — spends no quota (agy changelog 1.1.11). One process at a time per account. */
class AgyQuotaProbe(private val linux: LinuxRuntime, private val accounts: AgyAccounts, private val quota: QuotaRepo) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = kotlinx.coroutines.sync.Mutex()
    val running = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    suspend fun probe(acc: AgyAccount): String? = lock.withLockSafe {
        if (!linux.isInstalled() || !accounts.hasToken(acc)) return@withLockSafe null
        running.value = running.value + acc.email
        try {
            val (code, text) = try { linux.run("/root/.local/bin/agy -p \"/usage\" --output-format json < /dev/null", timeoutMs = 45_000, binds = listOf(accounts.bindFor(acc))) } finally { accounts.release(acc) }
            val raw = if (code == -1) "$text\n[timed out after 45 s]" else text
            if (AgyFailover.isAuthFailure(raw) && raw.contains("accounts.google.com")) accounts.markAuthExpired(acc.email)
            quota.recordAgyProbe(acc.email, raw)
            raw
        } finally { running.value = running.value - acc.email }
    }

    fun probeLater(acc: AgyAccount) { scope.launch { runCatching { probe(acc) } } }
    suspend fun probeAll() { accounts.accounts().filter { it.enabled }.forEach { runCatching { probe(it) } } }
    fun probeAllLater() { scope.launch { probeAll() } }

    /** `agy models --output-format json` → model ids for the picker. */
    suspend fun models(): List<String> {
        val acc = accounts.active() ?: accounts.accounts().firstOrNull() ?: return emptyList()
        val (code, text) = try { linux.run("/root/.local/bin/agy models --output-format json < /dev/null", 45_000, binds = listOf(accounts.bindFor(acc))) } finally { accounts.release(acc) }
        if (code != 0) return emptyList()
        val el = runCatching { Json.parseToJsonElement(text.substring(text.indexOf('[').coerceAtLeast(0).let { if (text.trim().startsWith("{")) text.indexOf('{') else it })) }.getOrNull()
        val ids = mutableListOf<String>()
        fun walk(e: JsonElement) { when (e) { is JsonObject -> { (e["id"] as? JsonPrimitive)?.contentOrNull?.let { ids += it }; e.values.forEach { walk(it) } }; is JsonArray -> e.forEach { walk(it) }; is JsonPrimitive -> {} } }
        el?.let { walk(it) }
        return ids.distinct().also { if (it.isNotEmpty()) accounts.setModels(it) }
    }
}

internal suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockSafe(block: suspend () -> T): T { lock(); try { return block() } finally { unlock() } }
