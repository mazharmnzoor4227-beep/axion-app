package com.mazharansari.axion.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Native Gemini client (streamGenerateContent + function calling). Native rather than the OpenAI shim so that
 * Gemini 3 thought signatures round-trip, PDFs/images go inline, and voice (TTS) uses the same key.
 */
class GeminiClient(
    private val apiKey: String,
    private val baseUrl: String = Providers.byId("gemini")!!.baseUrl,
    /** "x-goog-api-key" for AI Studio keys. Tests can point baseUrl at a proxy that swaps the header. */
    private val authHeader: Pair<String, String> = "x-goog-api-key" to apiKey,
    private val tag: String? = null,
) : LlmClient {

    override fun stream(req: LlmRequest): Flow<LlmEvent> = flow {
        val body = buildBody(req).toString()
        val model = req.model.removePrefix("models/")
        val call = Http.client.newCall(
            Request.Builder()
                .url("$baseUrl/models/$model:streamGenerateContent?alt=sse")
                .header(authHeader.first, authHeader.second)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        )
        val handle = currentCoroutineContext()[Job]?.cancelHook { call.cancel() }   // v0.4: fires the moment Stop cancels, not after the stream ends
        try {
            val resp = try { call.execute() } catch (e: IOException) {
                currentCoroutineContext().ensureActive()   // v0.5.2: a cancel stays a cancel
                throw LlmException("Network: ${e.message}", FailKind.NETWORK)
            }
            resp.use { r ->
                if (r.isSuccessful) com.mazharansari.axion.quota.QuotaHooks.report(tag, "gemini", model, r.code, r.headers, null)
                if (!r.isSuccessful) {
                    val err = r.body?.string().orEmpty()
                    com.mazharansari.axion.quota.QuotaHooks.report(tag, "gemini", model, r.code, r.headers, err)
                    throw LlmException(shortError(err, r.code), classifyHttp(r.code, err), r.code, parseRetryMs(err, r.header("retry-after")))
                }
                val src = r.body!!.source()
                val calls = mutableListOf<ToolCall>()
                var finish: String? = null
                var usage: com.mazharansari.axion.quota.TokenUsage? = null
                var n = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val obj = runCatching { Json.parseToJsonElement(line.substring(5).trim()).jsonObject }.getOrNull() ?: continue
                    obj["error"]?.let { val m = it.toString(); throw LlmException(shortError(m, 0), classifyHttp(0, m)) }
                    // P1-10 (ME-4): the last chunk carries usageMetadata with the real token counts.
                    com.mazharansari.axion.quota.LlmUsage.parseGemini(obj)?.let { usage = it }
                    val cand = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                    cand.str("finishReason")?.let { finish = it }
                    val parts = cand["content"]?.jsonObject?.get("parts")?.jsonArray ?: continue
                    for (p in parts) {
                        val po = p.jsonObject
                        val sig = po.str("thoughtSignature")
                        val fc = po["functionCall"]?.jsonObject
                        val text = po.str("text")
                        when {
                            fc != null -> calls += ToolCall(
                                id = fc.str("id") ?: "gc_${System.nanoTime()}_${n++}",
                                name = fc.str("name").orEmpty(),
                                arguments = (fc["args"] as? JsonObject ?: JsonObject(emptyMap())).toString(),
                                signature = sig,
                            )
                            text != null && po["thought"]?.jsonPrimitive?.booleanOrNull == true -> emit(LlmEvent.Reasoning(text))
                            text != null && text.isNotEmpty() -> emit(LlmEvent.Text(text))
                        }
                    }
                }
                if (finish == "SAFETY" || finish == "PROHIBITED_CONTENT") emit(LlmEvent.Text("\n\n_(Gemini stopped this answer for safety.)_"))
                if (calls.isNotEmpty()) emit(LlmEvent.ToolCalls(calls))
                // P1-10 (ME-4): real token counts for the Quota screen; absent usage stays UNKNOWN, never 0.
                usage?.let { com.mazharansari.axion.quota.QuotaHooks.reportUsage(tag, "gemini", model, it) }
                emit(LlmEvent.Finished(finish))
            }
        } catch (e: IOException) {
            // v0.4: Stop closed the socket under the read — that's a cancel, not a network error to retry.
            currentCoroutineContext().ensureActive()
            throw LlmException("Network: ${e.message}", FailKind.NETWORK)
        } finally { handle?.dispose() }
    }.flowOn(Dispatchers.IO)

    fun buildBody(req: LlmRequest): JsonObject = buildJsonObject {
        val sys = req.items.filterIsInstance<SystemItem>().joinToString("\n\n") { it.text }
        if (sys.isNotBlank()) putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", sys) } } }
        putJsonArray("contents") {
            val items = req.items.filter { it !is SystemItem }
            var i = 0
            while (i < items.size) {
                when (val item = items[i]) {
                    is UserItem -> { addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            if (item.text.isNotEmpty()) addJsonObject { put("text", item.text) }
                            item.media.forEach { m -> addJsonObject { putJsonObject("inlineData") { put("mimeType", m.mime); put("data", m.base64) } } }
                            if (item.text.isEmpty() && item.media.isEmpty()) addJsonObject { put("text", " ") }
                        }
                    }; i++ }
                    is AssistantItem -> { addJsonObject {
                        put("role", "model")
                        putJsonArray("parts") {
                            if (item.text.isNotEmpty()) addJsonObject { put("text", item.text) }
                            item.toolCalls.forEachIndexed { idx, c ->
                                addJsonObject {
                                    putJsonObject("functionCall") {
                                        put("name", c.name)
                                        put("args", runCatching { Json.parseToJsonElement(c.arguments).jsonObject }.getOrElse { JsonObject(emptyMap()) })
                                    }
                                    // Gemini 3 validates signatures on the current turn's calls; a call made by another
                                    // provider (after failover) has none, so it carries Google's documented skip value.
                                    if (idx == 0) put("thoughtSignature", c.signature ?: "skip_thought_signature_validator")
                                    else c.signature?.let { put("thoughtSignature", it) }
                                }
                            }
                            if (item.text.isEmpty() && item.toolCalls.isEmpty()) addJsonObject { put("text", " ") }
                        }
                    }; i++ }
                    is ToolResultItem -> { addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            while (i < items.size && items[i] is ToolResultItem) {
                                val t = items[i] as ToolResultItem
                                addJsonObject { putJsonObject("functionResponse") {
                                    put("name", t.name)
                                    putJsonObject("response") { put("result", t.content) }
                                } }
                                i++
                            }
                        }
                    } }
                    is SystemItem -> i++
                }
            }
        }
        if (req.tools.isNotEmpty()) putJsonArray("tools") {
            addJsonObject { putJsonArray("functionDeclarations") {
                req.tools.forEach { t -> addJsonObject { put("name", t.name); put("description", t.description); put("parameters", t.parameters) } }
            } }
        }
        putJsonObject("generationConfig") {
            req.temperature?.let { put("temperature", it) }
            if (req.model.contains("gemini") && !req.model.contains("tts")) putJsonObject("thinkingConfig") {
                if (req.quick) {
                    // v0.4: small talk answers at once. Gemini 2.x takes a budget (0 = off on Flash); Gemini 3+ takes a level.
                    put("includeThoughts", false)
                    val v = Regex("gemini-(\\d+)").find(req.model)?.groupValues?.get(1)?.toIntOrNull() ?: 3
                    if (v <= 2) put("thinkingBudget", if (req.model.contains("pro")) 128 else 0)
                    else put("thinkingLevel", if (req.model.contains("flash")) "minimal" else "low")
                } else {
                    put("includeThoughts", true)
                    // v0.5.1: short/simple prompts think little on Gemini 3+ (thoughts still stream into the panel).
                    val v = Regex("gemini-(\\d+)").find(req.model)?.groupValues?.get(1)?.toIntOrNull() ?: 3
                    if (req.effort == "low" && v >= 3) put("thinkingLevel", "low")
                }
            }
        }
    }
}
