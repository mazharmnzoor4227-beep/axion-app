package com.mazharansari.axion.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** One incoming Telegram update, already flattened from the Bot API shape. */
data class TelegramUpdate(
    val updateId: Long,
    val chatId: Long,
    val chatTitle: String = "",
    val text: String = "",
)

interface TelegramApi {
    /** Bot username (getMe), e.g. "AxionBot". */
    suspend fun getMe(): Result<String>

    /** Long-poll for updates with id >= [offset]. */
    suspend fun getUpdates(offset: Long, timeoutSec: Int): Result<List<TelegramUpdate>>

    /** Sends [text], chunked at 4096 chars. */
    suspend fun sendMessage(chatId: Long, text: String): Result<Unit>
}

/** Splits outgoing text on char boundaries without ever splitting a surrogate pair. */
object TelegramText {
    fun chunk(text: String, max: Int = 4096): List<String> {
        require(max > 0) { "max must be > 0" }
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            // Never split a surrogate pair: if the char just before the cut is a
            // high surrogate and the next char is its low surrogate, back off by one.
            if (end < text.length && end > start &&
                text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()
            ) {
                end--
            }
            out.add(text.substring(start, end))
            start = end
        }
        return out
    }
}

@Serializable
private data class MeResponse(
    val ok: Boolean = false,
    val result: MeResult? = null,
)

@Serializable
private data class MeResult(
    val id: Long = 0,
    val username: String? = null,
)

@Serializable
private data class UpdatesResponse(
    val ok: Boolean = false,
    val result: List<UpdateDto> = emptyList(),
)

@Serializable
private data class UpdateDto(
    @SerialName("update_id") val updateId: Long,
    val message: MessageDto? = null,
    @SerialName("channel_post") val channelPost: MessageDto? = null,
)

@Serializable
private data class MessageDto(
    val chat: ChatDto,
    val text: String? = null,
)

@Serializable
private data class ChatDto(
    val id: Long,
    val title: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    val username: String? = null,
)

@Serializable
private data class OkResponse(val ok: Boolean = false)

/**
 * Minimal Telegram Bot API client over OkHttp (no new dependency).
 *
 * The token goes in the URL path — that is how the Bot API works. The URL
 * (and therefore the token) is never logged, and failure messages never
 * contain the token or the URL.
 */
class HttpTelegramApi(
    private val token: () -> String?,
    private val client: OkHttpClient = defaultClient(),
) : TelegramApi {

    companion object {
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Null when no token is saved; every call then fails without touching the network. */
    private fun baseUrl(): String? {
        val t = token() ?: return null
        return "https://api.telegram.org/bot$t"
    }

    private suspend fun get(path: String, readTimeoutSec: Long? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val base = baseUrl() ?: return@withContext Result.failure(
                IllegalStateException("Telegram bot token is not set")
            )
            val callClient = if (readTimeoutSec != null) {
                client.newBuilder().readTimeout(readTimeoutSec, TimeUnit.SECONDS).build()
            } else client
            val request = Request.Builder().url("$base$path").get().build()
            try {
                val result: Result<String> = callClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Result.failure(IllegalStateException("Telegram request failed (HTTP ${resp.code})"))
                    } else {
                        val body = resp.body?.string()
                        if (body == null) Result.failure(IllegalStateException("Telegram returned an empty response"))
                        else Result.success(body)
                    }
                }
                result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Telegram request failed (${e.message ?: "network error"})"))
            }
        }

    private suspend fun post(path: String, bodyJson: String): Result<String> =
        withContext(Dispatchers.IO) {
            val base = baseUrl() ?: return@withContext Result.failure(
                IllegalStateException("Telegram bot token is not set")
            )
            val request = Request.Builder().url("$base$path")
                .post(bodyJson.toRequestBody(JSON)).build()
            try {
                val result: Result<String> = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Result.failure(IllegalStateException("Telegram request failed (HTTP ${resp.code})"))
                    } else {
                        val body = resp.body?.string()
                        if (body == null) Result.failure(IllegalStateException("Telegram returned an empty response"))
                        else Result.success(body)
                    }
                }
                result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Telegram request failed (${e.message ?: "network error"})"))
            }
        }

    override suspend fun getMe(): Result<String> {
        val body = get("/getMe").getOrElse { return Result.failure(it) }
        val parsed = runCatching { json.decodeFromString<MeResponse>(body) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Telegram returned an unreadable response"))
        if (!parsed.ok) return Result.failure(IllegalStateException("Telegram request failed"))
        return Result.success(parsed.result?.username ?: "unknown")
    }

    override suspend fun getUpdates(offset: Long, timeoutSec: Int): Result<List<TelegramUpdate>> {
        // Long poll: the read timeout must outlive the server-side wait.
        val body = get(
            "/getUpdates?offset=$offset&timeout=$timeoutSec",
            readTimeoutSec = (timeoutSec + 10).toLong(),
        ).getOrElse { return Result.failure(it) }
        val parsed = runCatching { json.decodeFromString<UpdatesResponse>(body) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Telegram returned an unreadable response"))
        if (!parsed.ok) return Result.failure(IllegalStateException("Telegram request failed"))
        return Result.success(parsed.result.mapNotNull { dto ->
            val msg = dto.message ?: dto.channelPost ?: return@mapNotNull null
            TelegramUpdate(
                updateId = dto.updateId,
                chatId = msg.chat.id,
                chatTitle = msg.chat.title ?: msg.chat.firstName ?: msg.chat.username ?: "",
                text = msg.text ?: "",
            )
        })
    }

    override suspend fun sendMessage(chatId: Long, text: String): Result<Unit> {
        for (part in TelegramText.chunk(text)) {
            val bodyJson = buildJsonObject {
                put("chat_id", chatId)
                put("text", part)
            }.toString()
            val body = post("/sendMessage", bodyJson).getOrElse { return Result.failure(it) }
            val ok = runCatching { json.decodeFromString<OkResponse>(body).ok }.getOrDefault(false)
            if (!ok) return Result.failure(IllegalStateException("Telegram send failed"))
        }
        return Result.success(Unit)
    }
}
