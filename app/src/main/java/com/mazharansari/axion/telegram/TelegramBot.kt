package com.mazharansari.axion.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * P2-1 (Remote access: Telegram bot control) — the long-poll loop.
 *
 * The bot is OFF by default and only polls while the store is enabled and a
 * token is saved. It talks to exactly ONE chat: the paired
 * [TelegramSettings.allowedChatId]. Until a chat is paired, a "/start" is
 * recorded as a pending pairing request and gets NO reply — the user approves
 * it in the app, and the bot never talks to strangers. Messages from any
 * other chat are silently ignored (no reply). No token appears in any log or
 * error string. No phone control, no accessibility, no wake word: a paired
 * message is simply handed to [onMessage] and its answer sent back.
 *
 * [onMessage] is the coordinator-wired handler (chat text in, reply text out).
 */
class TelegramBot(
    private val store: TelegramStore,
    private val api: TelegramApi,
    private val onMessage: suspend (chatId: Long, text: String) -> String,
    private val scope: CoroutineScope,
) {
    companion object {
        const val LONG_POLL_SECONDS = 30
        const val POLL_FAILURE_BACKOFF_MS = 5_000L
        const val PAUSED_MESSAGE = "Telegram control paused. Turn it back on in Settings."

        const val HELP_TEXT = "Hi, this is Axion. Send me a message and I will answer it here.\n" +
            "\n" +
            "Commands:\n" +
            "/start - show this help\n" +
            "/stop - pause Telegram control"
    }

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts polling. No-op when already running, when the bot is disabled in
     * settings, or when no token is saved.
     */
    fun start() {
        if (isRunning) return
        if (!store.settings.value.enabled) return
        if (store.token() == null) return
        job = scope.launch {
            while (isActive) {
                // Stop polling if the user turned the bot off or cleared the token.
                if (!store.settings.value.enabled || store.token() == null) break
                val offset = store.settings.value.lastUpdateId + 1
                val result = try {
                    api.getUpdates(offset, LONG_POLL_SECONDS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                val updates = result.getOrNull()
                if (updates == null) {
                    // One failed poll must not kill the loop: back off and keep going.
                    delay(POLL_FAILURE_BACKOFF_MS)
                    continue
                }
                for (update in updates) {
                    if (!isActive) break
                    handle(update)
                }
                // A real long poll already waited server-side; a short pause keeps
                // a fast-returning (empty) poll from busy-spinning.
                if (updates.isEmpty()) delay(500)
            }
        }
    }

    /** Cancels the poll loop. Safe to call when not running. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Every update advances lastUpdateId first, even when it is ignored. */
    private suspend fun handle(update: TelegramUpdate) {
        store.setLastUpdateId(update.updateId)
        val text = update.text.trim()
        if (text.isEmpty()) return
        val command = text.split(" ", "\n").first().substringBefore("@")

        val allowed = store.settings.value.allowedChatId
        if (allowed == 0L) {
            // Not paired yet: only a "/start" is recorded, and it gets no reply.
            if (command == "/start") {
                store.requestPair(update.chatId, update.chatTitle.ifEmpty { "Telegram user" })
            }
            return
        }
        // Paired: any other chat is silently ignored.
        if (update.chatId != allowed) return

        when (command) {
            "/start" -> api.sendMessage(update.chatId, HELP_TEXT)
            "/stop" -> {
                // Reply first, then stop and stay stopped: turning the bot off in
                // settings persists, so the next app start does not revive it.
                api.sendMessage(update.chatId, PAUSED_MESSAGE)
                store.setEnabled(false)
                stop()
            }
            else -> {
                val reply = try {
                    onMessage(update.chatId, text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Sorry, that request failed."
                }
                if (reply.isNotBlank()) api.sendMessage(update.chatId, reply)
            }
        }
    }
}
