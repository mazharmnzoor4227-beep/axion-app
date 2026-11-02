package com.mazharansari.axion.llm

import kotlinx.serialization.json.JsonObject

data class ImagePart(val mime: String, val base64: String)

sealed interface ChatItem
data class SystemItem(val text: String) : ChatItem
data class UserItem(val text: String, val media: List<ImagePart> = emptyList()) : ChatItem
data class AssistantItem(val text: String, val toolCalls: List<ToolCall> = emptyList()) : ChatItem
data class ToolResultItem(val callId: String, val name: String, val content: String) : ChatItem

data class ToolCall(
    val id: String,
    val name: String,
    /** Raw JSON object text. */
    val arguments: String,
    /** Gemini thought signature, echoed back verbatim on the next request. */
    val signature: String? = null,
)

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class LlmRequest(
    val model: String,
    val items: List<ChatItem>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    /** v0.4: small talk ("hi bhai", "kaise ho") — answer at once: reasoning off/minimal, no thought text. */
    val quick: Boolean = false,
    /** v0.5.1: "low" = short/simple prompt; reasoning models are asked to think little (thoughts still stream). */
    val effort: String? = null,
)

sealed interface LlmEvent {
    data class Text(val delta: String) : LlmEvent
    data class Reasoning(val delta: String) : LlmEvent
    data class ToolCalls(val calls: List<ToolCall>) : LlmEvent
    data class Finished(val reason: String?) : LlmEvent
}

/** RATE = a per-minute limit (e.g. Groq "tokens per minute … try again in 28.17s"): skip to the next model now, retry this one in seconds. */
enum class FailKind { QUOTA, RATE, AUTH, MODEL_UNAVAILABLE, BAD_REQUEST, SERVER, NETWORK }

class LlmException(message: String, val kind: FailKind, val code: Int = 0, val retryAfterMs: Long = 0) : Exception(message)

interface LlmClient {
    /** Cold flow: collecting it performs one streaming request. Cancel the collector to stop. */
    fun stream(req: LlmRequest): kotlinx.coroutines.flow.Flow<LlmEvent>
}

/**
 * v0.5.2 speed: the client (and the key decrypt behind it) is made only when this attempt is actually tried. Before, every
 * key was decrypted for every model on the list before the first request went out — most of them never used.
 */
class LazyClient(factory: () -> LlmClient) : LlmClient {
    private val real by lazy(factory)
    override fun stream(req: LlmRequest): kotlinx.coroutines.flow.Flow<LlmEvent> = real.stream(req)
}

/** Classifies an HTTP error the same way for every provider. */
fun classifyHttp(code: Int, body: String): FailKind {
    val b = body.lowercase()
    val perMinute = "per minute" in b || "tokens per min" in b || "requests per min" in b || "(tpm)" in b || "(rpm)" in b ||
        Regex("try again in (\\d+m)?\\d+(\\.\\d+)?(ms|s)").containsMatchIn(b) && "per day" !in b && "(tpd)" !in b && "(rpd)" !in b
    return when {
        code == 429 && perMinute -> FailKind.RATE
        code == 429 || "quota" in b || "rate limit" in b || "rate_limit" in b || "resource_exhausted" in b ||
            "exceeded" in b && "limit" in b || "insufficient" in b && ("balance" in b || "credit" in b || "quota" in b) ||
            code == 402 || "overdue" in b || "billing" in b || "payment" in b || "suspended" in b || "restricted" in b -> FailKind.QUOTA
        code == 401 || code == 403 && ("key" in b || "auth" in b || "permission" in b || "token" in b) -> FailKind.AUTH
        code == 404 || "model" in b && ("not found" in b || "does not exist" in b || "no longer available" in b ||
            "not supported" in b || "decommissioned" in b || "unavailable" in b) -> FailKind.MODEL_UNAVAILABLE
        code in 500..599 -> FailKind.SERVER
        else -> FailKind.BAD_REQUEST
    }
}

/** Reads Google's "retryDelay": "32024s" or a plain Retry-After seconds value. */
fun parseRetryMs(body: String, header: String?): Long {
    header?.trim()?.toDoubleOrNull()?.let { return (it * 1000).toLong() }
    // Groq / OpenAI: "Please try again in 28.17s" / "try again in 1m2.5s" / "try again in 450ms"
    Regex("try again in (?:(\\d+)m)?(\\d+(?:\\.\\d+)?)(ms|s)", RegexOption.IGNORE_CASE).find(body)?.let { m ->
        val min = m.groupValues[1].toLongOrNull() ?: 0; val v = m.groupValues[2].toDouble()
        return min * 60_000 + if (m.groupValues[3].equals("ms", true)) v.toLong() else (v * 1000).toLong()
    }
    val m = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(body)
    return m?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0
}
