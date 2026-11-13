package com.mazharansari.axion

import com.mazharansari.axion.telegram.TelegramApi
import com.mazharansari.axion.telegram.TelegramBot
import com.mazharansari.axion.telegram.TelegramStore
import com.mazharansari.axion.telegram.TelegramText
import com.mazharansari.axion.telegram.TelegramUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P2-1 (Remote access: Telegram bot control) pure-JUnit tests:
 * no network, no real credentials, no Android framework, no Robolectric.
 *
 * The store is backed by a temp-dir file with a trivial test cipher
 * ("enc:" prefix) instead of KeyVault, which needs the Android Keystore
 * (its "plain:" fallback exists only under Robolectric). The bot talks
 * only to [FakeTelegramApi], never to a real Telegram server.
 */
class P2TelegramTest {

    private val validToken = "123456789:" + "A".repeat(35)

    /** In-memory TelegramApi: queued updates are served once, sent messages recorded. */
    private class FakeTelegramApi : TelegramApi {
        val queued = ArrayDeque<TelegramUpdate>()
        val sent = mutableListOf<Pair<Long, String>>()
        var pollFailuresLeft = 0

        override suspend fun getMe(): Result<String> = Result.success("TestBot")

        override suspend fun getUpdates(offset: Long, timeoutSec: Int): Result<List<TelegramUpdate>> {
            if (pollFailuresLeft > 0) {
                pollFailuresLeft--
                return Result.failure(IllegalStateException("network down"))
            }
            val due = queued.filter { it.updateId >= offset }
            queued.removeAll(due.toSet())
            return Result.success(due)
        }

        override suspend fun sendMessage(chatId: Long, text: String): Result<Unit> {
            sent.add(chatId to text)
            return Result.success(Unit)
        }
    }

    private fun testStore(dir: File = Files.createTempDirectory("telegram-test").toFile()): TelegramStore =
        TelegramStore(
            file = File(dir, "telegram.json"),
            encryptFn = { plain -> "enc:$plain" },
            decryptFn = { blob -> blob.removePrefix("enc:") },
        )

    private fun pairedStore(allowedChatId: Long = 111L): TelegramStore {
        val store = testStore()
        store.setToken(validToken)
        store.setEnabled(true)
        store.requestPair(allowedChatId, "Tester")
        store.approvePair()
        assertEquals(allowedChatId, store.settings.value.allowedChatId)
        return store
    }

    private suspend fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(25)
        }
        return condition()
    }

    /** Runs [block] with a bot scope that is always cancelled afterwards. */
    private fun withBotScope(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(scope)
        } finally {
            scope.cancel()
        }
    }

    // ── Token format validation ──────────────────────────────────────────────────

    @Test fun tokenFormatAcceptsWellFormedToken() {
        assertTrue(TelegramStore.isValidTokenFormat(validToken))
        assertTrue(TelegramStore.isValidTokenFormat("1:" + "aB3_-".repeat(7)))
    }

    @Test fun tokenFormatRejectsAbcEmptyAndWrongShape() {
        assertFalse(TelegramStore.isValidTokenFormat("abc"))
        assertFalse(TelegramStore.isValidTokenFormat(""))
        assertFalse(TelegramStore.isValidTokenFormat("123:short"))
        assertFalse(TelegramStore.isValidTokenFormat("abc:" + "A".repeat(35)))
        assertFalse(TelegramStore.isValidTokenFormat("123456789:" + "A".repeat(34)))
        assertFalse(TelegramStore.isValidTokenFormat("123456789:" + "A".repeat(36)))
        assertFalse(TelegramStore.isValidTokenFormat("123456789:" + "A".repeat(34) + "!"))
        assertFalse(TelegramStore.isValidTokenFormat("123456789"))
    }

    @Test fun setTokenRejectsBadFormatAndStoresBlobNotPlaintext() {
        val store = testStore()
        assertThrows(IllegalArgumentException::class.java) { store.setToken("abc") }
        assertThrows(IllegalArgumentException::class.java) { store.setToken("") }
        assertEquals("", store.settings.value.tokenBlob)

        store.setToken(validToken)
        assertEquals(validToken, store.token())
        assertNotEquals(validToken, store.settings.value.tokenBlob)
        assertFalse(store.settings.value.tokenBlob.contains(validToken))

        store.clearToken()
        assertNull(store.token())
        assertEquals("", store.settings.value.tokenBlob)
    }

    @Test fun approveAndRejectPairing() {
        val store = testStore()
        store.requestPair(222L, "Someone")
        assertEquals(222L, store.settings.value.pendingPairChatId)
        assertEquals("Someone", store.settings.value.pendingPairName)
        store.rejectPair()
        assertEquals(0L, store.settings.value.pendingPairChatId)
        assertEquals("", store.settings.value.pendingPairName)
        assertEquals(0L, store.settings.value.allowedChatId)

        store.requestPair(333L, "Other")
        store.approvePair()
        assertEquals(333L, store.settings.value.allowedChatId)
        assertEquals(0L, store.settings.value.pendingPairChatId)
    }

    // ── Chunking ─────────────────────────────────────────────────────────────────

    @Test fun chunkSplits9000CharsIntoThreeChunksOfAtMost4096() {
        val chunks = TelegramText.chunk("a".repeat(9000))
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.length <= 4096 })
        assertEquals(listOf(4096, 4096, 808), chunks.map { it.length })
        assertEquals("a".repeat(9000), chunks.joinToString(""))
    }

    @Test fun chunkNeverSplitsSurrogatePairAtBoundary() {
        // Emoji (one surrogate pair) straddles the 4096 boundary: its high
        // surrogate would land at index 4095 if the cut were naive.
        val text = "x".repeat(4095) + "\uD83D\uDE00" + "y".repeat(100)
        val chunks = TelegramText.chunk(text)
        assertEquals(text, chunks.joinToString(""))
        assertEquals(4095, chunks[0].length)
        assertTrue(chunks.all { chunk ->
            !chunk.endsWith("\uD83D") && !chunk.startsWith("\uDE00")
        })
        // Every chunk is valid UTF-16 on its own (no lone surrogates).
        assertTrue(chunks.all { chunk ->
            chunk.indices.none { i ->
                (chunk[i].isHighSurrogate() && (i + 1 >= chunk.length || !chunk[i + 1].isLowSurrogate())) ||
                    (chunk[i].isLowSurrogate() && (i == 0 || !chunk[i - 1].isHighSurrogate()))
            }
        })
    }

    // ── Bot gating ───────────────────────────────────────────────────────────────

    @Test fun botIgnoresMessageFromNonAllowListedChatAndStillAdvancesUpdateId() = withBotScope { scope ->
        val store = pairedStore(allowedChatId = 111L)
        val api = FakeTelegramApi()
        var handlerCalls = 0
        val bot = TelegramBot(store, api, { _, _ -> handlerCalls++; "answer" }, scope)
        api.queued.add(TelegramUpdate(updateId = 10, chatId = 999L, chatTitle = "Stranger", text = "hello"))
        bot.start()
        assertTrue(waitUntil { store.settings.value.lastUpdateId == 10L })
        assertTrue(api.sent.isEmpty())
        assertEquals(0, handlerCalls)
        bot.stop()
    }

    @Test fun botRecordsPendingPairingOnStartFromUnpairedChatAndSendsNothing() = withBotScope { scope ->
        val store = testStore()
        store.setToken(validToken)
        store.setEnabled(true)
        val api = FakeTelegramApi()
        val bot = TelegramBot(store, api, { _, _ -> "answer" }, scope)
        api.queued.add(TelegramUpdate(updateId = 5, chatId = 777L, chatTitle = "New Person", text = "/start"))
        bot.start()
        assertTrue(waitUntil { store.settings.value.pendingPairChatId == 777L })
        assertEquals("New Person", store.settings.value.pendingPairName)
        assertEquals(0L, store.settings.value.allowedChatId)
        assertTrue(api.sent.isEmpty())
        assertEquals(5L, store.settings.value.lastUpdateId)
        bot.stop()
    }

    @Test fun botIgnoresNonStartMessageFromUnpairedChat() = withBotScope { scope ->
        val store = testStore()
        store.setToken(validToken)
        store.setEnabled(true)
        val api = FakeTelegramApi()
        var handlerCalls = 0
        val bot = TelegramBot(store, api, { _, _ -> handlerCalls++; "answer" }, scope)
        api.queued.add(TelegramUpdate(updateId = 6, chatId = 777L, chatTitle = "New Person", text = "hello?"))
        bot.start()
        assertTrue(waitUntil { store.settings.value.lastUpdateId == 6L })
        assertEquals(0L, store.settings.value.pendingPairChatId)
        assertTrue(api.sent.isEmpty())
        assertEquals(0, handlerCalls)
        bot.stop()
    }

    @Test fun botAnswersPairedChatViaOnMessageAfterApprovePair() = withBotScope { scope ->
        val store = testStore()
        store.setToken(validToken)
        store.setEnabled(true)
        val api = FakeTelegramApi()
        var seen: Pair<Long, String>? = null
        val bot = TelegramBot(store, api, { chatId, text -> seen = chatId to text; "Echo: $text" }, scope)
        api.queued.add(TelegramUpdate(updateId = 7, chatId = 555L, chatTitle = "Owner", text = "/start"))
        bot.start()
        assertTrue(waitUntil { store.settings.value.pendingPairChatId == 555L })
        store.approvePair()
        api.queued.add(TelegramUpdate(updateId = 8, chatId = 555L, chatTitle = "Owner", text = "what time is it?"))
        assertTrue(waitUntil { api.sent.size == 1 })
        assertEquals(555L to "Echo: what time is it?", api.sent.single())
        assertEquals(555L to "what time is it?", seen)
        bot.stop()
    }

    @Test fun stopCommandFromPairedChatStopsPollerAndSendsPausedMessage() = withBotScope { scope ->
        val store = pairedStore(allowedChatId = 111L)
        val api = FakeTelegramApi()
        val bot = TelegramBot(store, api, { _, _ -> "answer" }, scope)
        api.queued.add(TelegramUpdate(updateId = 20, chatId = 111L, chatTitle = "Tester", text = "/stop"))
        bot.start()
        assertTrue(waitUntil { api.sent.size == 1 })
        assertEquals(111L to TelegramBot.PAUSED_MESSAGE, api.sent.single())
        assertTrue(waitUntil { !bot.isRunning })
        bot.stop()
    }

    @Test fun botDoesNotStartWhenDisabledOrTokenMissing() = withBotScope { scope ->
        val disabled = testStore()
        disabled.setToken(validToken)
        disabled.setEnabled(false)
        val botDisabled = TelegramBot(disabled, FakeTelegramApi(), { _, _ -> "x" }, scope)
        botDisabled.start()
        assertFalse(botDisabled.isRunning)

        val noToken = testStore()
        noToken.setEnabled(true)
        val botNoToken = TelegramBot(noToken, FakeTelegramApi(), { _, _ -> "x" }, scope)
        botNoToken.start()
        assertFalse(botNoToken.isRunning)
    }

    @Test fun botSurvivesAFailedPollAndKeepsGoing() = withBotScope { scope ->
        val store = pairedStore(allowedChatId = 111L)
        val api = FakeTelegramApi()
        api.pollFailuresLeft = 1
        val bot = TelegramBot(store, api, { _, _ -> "answer" }, scope)
        api.queued.add(TelegramUpdate(updateId = 30, chatId = 111L, chatTitle = "Tester", text = "hi"))
        bot.start()
        // The first poll fails (5s back-off), the next one serves the message.
        assertTrue(waitUntil(timeoutMs = 10_000) { api.sent.size == 1 })
        assertEquals(111L to "answer", api.sent.single())
        bot.stop()
    }
}
