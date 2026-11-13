package com.mazharansari.axion.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Streaming chat client for every OpenAI-compatible provider (Groq, Cerebras, Mistral, OpenRouter, GitHub, HF, NVIDIA…). */
class OpenAiClient(
    private val provider: Provider,
    private val apiKey: String,
    private val baseUrl: String = provider.baseUrl,
    /** Connector id, so the Quota screen can file this call's rate-limit headers under the right key. */
    private val tag: String? = null,
) : LlmClient {

    override fun stream(req: LlmRequest): Flow<LlmEvent> = flow {
        val body = buildBody(req).toString()
        val rb = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
        provider.extraHeaders.forEach { (k, v) -> rb.header(k, v) }
        val call = Http.client.newCall(rb.build())
        val job = currentCoroutineContext()[Job]
        val handle = job?.cancelHook { call.cancel() }   // v0.4: fires the moment Stop cancels, not after the stream ends
        try {
            val resp = try { call.execute() } catch (e: IOException) {
                // v0.5.2: a call closed because the watchdog or Stop cancelled it is a cancel, not "no connection"
                // (mazhar's test: Free (slow) showed "No connection… Network: Canceled").
                currentCoroutineContext().ensureActive()
                throw LlmException("Network: ${e.message}", FailKind.NETWORK)
            }
            resp.use { r ->
                if (r.isSuccessful) com.mazharansari.axion.quota.QuotaHooks.report(tag, provider.id, req.model, r.code, r.headers, null)
                if (!r.isSuccessful) {
                    val err = r.body?.string().orEmpty()
                    com.mazharansari.axion.quota.QuotaHooks.report(tag, provider.id, req.model, r.code, r.headers, err)
                    throw LlmException(shortError(err, r.code), classifyHttp(r.code, err), r.code, parseRetryMs(err, r.header("retry-after")))
                }
                val src = r.body!!.source()
                val calls = sortedMapOf<Int, PartialCall>()
                var finish: String? = null
                var usage: com.mazharansari.axion.quota.TokenUsage? = null
                val ctype = r.header("content-type").orEmpty()
                if (!ctype.contains("event-stream")) {
                    // Some gateways ignore stream=true and answer one JSON object.
                    val obj = Json.parseToJsonElement(src.readUtf8()).jsonObject
                    usage = com.mazharansari.axion.quota.LlmUsage.parseOpenAi(obj)
                    val msg = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                    (msg?.str("reasoning_content") ?: msg?.str("reasoning"))?.let { if (it.isNotEmpty()) emit(LlmEvent.Reasoning(it)) }
                    msg?.str("content")?.let { if (it.isNotEmpty()) emit(LlmEvent.Text(it)) }
                    msg?.get("tool_calls")?.jsonArray?.forEachIndexed { i, tc -> mergeCall(calls, i, tc.jsonObject) }
                } else {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = src.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.substring(5).trim()
                        if (data == "[DONE]") break
                        if (data.isEmpty()) continue
                        val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                        obj["error"]?.let { e ->
                            val m = e.toString()
                            throw LlmException(shortError(m, 0), classifyHttp(0, m))
                        }
                        // P1-10 (ME-4): the last chunk carries the real token counts (asked for in buildBody).
                        com.mazharansari.axion.quota.LlmUsage.parseOpenAi(obj)?.let { usage = it }
                        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                        val delta = choice["delta"]?.jsonObject
                        if (delta != null) {
                            reasoningDelta(delta)?.let { if (it.isNotEmpty()) emit(LlmEvent.Reasoning(it)) }
                            delta.str("content")?.let { if (it.isNotEmpty()) emit(LlmEvent.Text(it)) }
                            delta["tool_calls"]?.let { arr ->
                                if (arr is JsonArray) arr.forEach { tc ->
                                    val o = tc.jsonObject
                                    val idx = o["index"]?.jsonPrimitive?.intOrNull ?: calls.size
                                    mergeCall(calls, idx, o)
                                }
                            }
                        }
                        choice.str("finish_reason")?.let { finish = it }
                    }
                }
                if (calls.isNotEmpty()) {
                    emit(LlmEvent.ToolCalls(calls.values.mapIndexed { i, c ->
                        ToolCall(id = c.id.ifEmpty { "call_${System.nanoTime()}_$i" }, name = c.name, arguments = c.args.toString().ifBlank { "{}" })
                    }))
                }
                // P1-10 (ME-4): real token counts for the Quota screen; absent usage stays UNKNOWN, never 0.
                usage?.let { com.mazharansari.axion.quota.QuotaHooks.reportUsage(tag, provider.id, req.model, it) }
                emit(LlmEvent.Finished(finish))
            }
        } catch (e: IOException) {
            // v0.4: Stop closed the socket under the read — that's a cancel, not a network error to retry.
            currentCoroutineContext().ensureActive()
            throw LlmException("Network: ${e.message}", FailKind.NETWORK)
        } finally {
            handle?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    /** Groq (gpt-oss: `reasoning`; qwen/deepseek with reasoning_format=parsed: `reasoning`), DeepSeek/others
     *  (`reasoning_content`), OpenRouter (`reasoning`, or only `reasoning_details[].text`). */
    internal fun reasoningDelta(delta: JsonObject): String? {
        delta.str("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { return it }
        delta.str("reasoning")?.takeIf { it.isNotEmpty() }?.let { return it }
        val det = delta["reasoning_details"] as? JsonArray ?: return null
        return det.mapNotNull { (it as? JsonObject)?.let { o -> o.str("text") ?: o.str("summary") } }.joinToString("").ifEmpty { null }
    }

    private class PartialCall(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private fun mergeCall(calls: MutableMap<Int, PartialCall>, idx: Int, o: JsonObject) {
        val pc = calls.getOrPut(idx) { PartialCall() }
        o.str("id")?.let { if (it.isNotEmpty()) pc.id = it }
        val fn = o["function"]?.jsonObject ?: return
        fn.str("name")?.let { if (it.isNotEmpty()) { if (pc.name.isEmpty()) pc.name = it else if (it != pc.name) pc.name += it } }
        when (val a = fn["arguments"]) {
            is JsonPrimitive -> if (a.isString) pc.args.append(a.content)
            is JsonObject -> pc.args.append(a.toString())
            else -> {}
        }
    }

    private fun toolId(id: String): String {
        if (!provider.shortToolIds) return id
        val alnum = id.filter { it.isLetterOrDigit() }
        if (alnum.length == 9) return alnum
        val h = (id.hashCode().toLong() and 0xffffffffL).toString(36).padStart(9, 'a')
        return h.takeLast(9)
    }

    /** v0.4: the documented "think less / don't show it" switches, only on models known to take them. */
    internal fun quickReasoning(providerId: String, model: String): Map<String, JsonElement>? {
        val m = model.lowercase()
        return when {
            providerId == "groq" && "gpt-oss" in m -> mapOf("reasoning_effort" to JsonPrimitive("low"), "include_reasoning" to JsonPrimitive(false))
            providerId == "groq" && "qwen3" in m -> mapOf("reasoning_effort" to JsonPrimitive("none"))
            providerId == "openrouter" && com.mazharansari.axion.agent.IntentGate.isReasoningModel(m) ->
                mapOf("reasoning" to buildJsonObject { put("effort", "low"); put("exclude", true) })
            else -> null
        }
    }

    /** v0.5.1: short/simple (non-small-talk) prompts — think little, but keep streaming the thoughts into the panel. */
    internal fun lowReasoning(providerId: String, model: String): Map<String, JsonElement>? {
        val m = model.lowercase()
        return when {
            providerId == "groq" && "gpt-oss" in m -> mapOf("reasoning_effort" to JsonPrimitive("low"))
            providerId == "groq" && ("qwen3" in m || "deepseek-r1" in m) -> mapOf("reasoning_format" to JsonPrimitive("parsed"))
            providerId == "openrouter" && com.mazharansari.axion.agent.IntentGate.isReasoningModel(m) ->
                mapOf("reasoning" to buildJsonObject { put("effort", "low") })
            else -> null
        }
    }

    /** P1-10 (ME-4): ask for the usage object in the last stream chunk. Only on providers whose docs (or the
     * OpenAI spec they follow) accept `stream_options.include_usage` — other OpenAI-compatible providers still get
     * their usage parsed whenever they send it, they just aren't asked for it. */
    internal fun includeUsage(providerId: String): Boolean =
        providerId in setOf("openai", "groq", "deepseek", "openrouter", "together", "xai")

    fun buildBody(req: LlmRequest): JsonObject = buildJsonObject {
        put("model", req.model)
        put("stream", true)
        if (includeUsage(provider.id)) putJsonObject("stream_options") { put("include_usage", true) }
        req.temperature?.let { put("temperature", it) }
        if (req.quick) quickReasoning(provider.id, req.model)?.forEach { (k, v) -> put(k, v) }
        else if (req.effort == "low") lowReasoning(provider.id, req.model)?.forEach { (k, v) -> put(k, v) }
        putJsonArray("messages") {
            for (item in req.items) when (item) {
                is SystemItem -> addJsonObject { put("role", "system"); put("content", item.text) }
                is UserItem -> addJsonObject {
                    put("role", "user")
                    if (item.media.isEmpty() || !provider.vision) put("content", item.text)
                    else putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", item.text) }
                        item.media.filter { it.mime.startsWith("image/") }.forEach { m ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", "data:${m.mime};base64,${m.base64}") }
                            }
                        }
                    }
                }
                is AssistantItem -> addJsonObject {
                    put("role", "assistant")
                    if (item.text.isNotEmpty() || item.toolCalls.isEmpty()) put("content", item.text) else put("content", JsonNull)
                    if (item.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                        item.toolCalls.forEach { c ->
                            addJsonObject {
                                put("id", toolId(c.id)); put("type", "function")
                                putJsonObject("function") { put("name", c.name); put("arguments", c.arguments) }
                            }
                        }
                    }
                }
                is ToolResultItem -> addJsonObject {
                    put("role", "tool"); put("tool_call_id", toolId(item.callId)); put("name", item.name); put("content", item.content)
                }
            }
        }
        if (req.tools.isNotEmpty()) {
            putJsonArray("tools") {
                req.tools.forEach { t ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") { put("name", t.name); put("description", t.description); put("parameters", t.parameters) }
                    }
                }
            }
            put("tool_choice", "auto")
        }
    }
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun shortError(body: String, code: Int): String {
    val msg = runCatching {
        val el = Json.parseToJsonElement(body)
        val o = if (el is JsonArray) el.first().jsonObject else el.jsonObject
        val e = o["error"]
        when (e) {
            is JsonObject -> e.str("message") ?: e.toString()
            is JsonPrimitive -> e.content
            else -> o.str("message") ?: body
        }
    }.getOrElse { body }
    return (if (code > 0) "HTTP $code: " else "") + msg.trim().take(300)
}
