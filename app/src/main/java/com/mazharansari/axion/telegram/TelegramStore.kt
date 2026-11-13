package com.mazharansari.axion.telegram

import android.content.Context
import com.mazharansari.axion.data.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * P2-1 (Remote access: Telegram bot control) — persisted bot settings.
 *
 * Same JSON-file pattern as [com.mazharansari.axion.data.TaskRunStore]:
 * one file in [Context.getFilesDir], kotlinx.serialization, a [StateFlow]
 * that updates synchronously, and file writes on an IO scope.
 *
 * Security: the bot token is NEVER stored in plain text. It is held only as
 * a [KeyVault] blob ([TelegramSettings.tokenBlob]) and is never logged.
 * The bot is OFF by default ([TelegramSettings.enabled] = false) and talks
 * only to the single paired chat ([TelegramSettings.allowedChatId]).
 *
 * The [file] / cipher constructor is the test seam: pure-JUnit tests pass a
 * temp-dir file and a trivial test cipher, because [KeyVault] needs the
 * Android Keystore (its "plain:" fallback exists only under Robolectric).
 * Production code uses the [Context] constructor, which wires [KeyVault].
 */
@Serializable
data class TelegramSettings(
    val enabled: Boolean = false,
    /** KeyVault-encrypted bot token; "" = no token saved. Never plain text. */
    val tokenBlob: String = "",
    /** Paired chat id; 0 = not paired yet. */
    val allowedChatId: Long = 0,
    /** Chat id of a pending "/start" pairing request; 0 = none pending. */
    val pendingPairChatId: Long = 0,
    val pendingPairName: String = "",
    val lastUpdateId: Long = 0,
)

class TelegramStore(
    private val file: File,
    private val encryptFn: (String) -> String = KeyVault::encrypt,
    private val decryptFn: (String) -> String? = KeyVault::decrypt,
) {
    /** Production constructor: telegram.json in the app's files dir, KeyVault cipher. */
    constructor(ctx: Context) : this(File(ctx.filesDir, "telegram.json"))

    companion object {
        /** Bot API token shape: "<bot id>:<35 chars>". Anything else is rejected. */
        val TOKEN_REGEX = Regex("^[0-9]+:[A-Za-z0-9_-]{35}$")

        fun isValidTokenFormat(token: String): Boolean = TOKEN_REGEX.matches(token)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _settings = MutableStateFlow(
        runCatching { json.decodeFromString<TelegramSettings>(file.readText()) }.getOrNull()
            ?: TelegramSettings()
    )
    val settings: StateFlow<TelegramSettings> = _settings.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun save(next: TelegramSettings) {
        _settings.value = next
        io.launch {
            synchronized(file) {
                runCatching { file.writeText(json.encodeToString(next)) }
            }
        }
    }

    /**
     * Validates the token shape and stores it only as a KeyVault blob.
     * @throws IllegalArgumentException when the token does not match [TOKEN_REGEX].
     */
    fun setToken(plain: String) {
        require(isValidTokenFormat(plain)) {
            "Invalid Telegram bot token format. Expected <bot id>:<35 characters> from @BotFather."
        }
        save(_settings.value.copy(tokenBlob = encryptFn(plain)))
    }

    /** The decrypted token, or null when none is saved / it cannot be decrypted. */
    fun token(): String? {
        val blob = _settings.value.tokenBlob
        if (blob.isEmpty()) return null
        return decryptFn(blob)
    }

    fun clearToken() {
        save(_settings.value.copy(tokenBlob = ""))
    }

    fun setEnabled(enabled: Boolean) {
        save(_settings.value.copy(enabled = enabled))
    }

    /** Records a "/start" from an unpaired chat; the user approves it in the app. */
    fun requestPair(chatId: Long, name: String) {
        save(_settings.value.copy(pendingPairChatId = chatId, pendingPairName = name.take(100)))
    }

    /** Pairs the pending chat (allowedChatId = pending) and clears the request. No-op when nothing is pending. */
    fun approvePair() {
        val cur = _settings.value
        if (cur.pendingPairChatId == 0L) return
        save(cur.copy(allowedChatId = cur.pendingPairChatId, pendingPairChatId = 0, pendingPairName = ""))
    }

    /** Discards a pending pairing request. */
    fun rejectPair() {
        save(_settings.value.copy(pendingPairChatId = 0, pendingPairName = ""))
    }

    /** Forgets the paired chat (the bot stops answering it until a new chat pairs). */
    fun unpair() {
        save(_settings.value.copy(allowedChatId = 0))
    }

    fun setLastUpdateId(updateId: Long) {
        val cur = _settings.value
        if (updateId > cur.lastUpdateId) save(cur.copy(lastUpdateId = updateId))
    }
}
