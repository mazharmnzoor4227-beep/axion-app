package com.mazharansari.axion

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mazharansari.axion.agent.*
import com.mazharansari.axion.data.*
import com.mazharansari.axion.llm.*
import com.mazharansari.axion.skills.SkillRepo
import com.mazharansari.axion.ui.ChatViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** v0.5.2: "replies start late" (in-app part measured) + the self bug hunt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AxionApp::class)
class V052SpeedBugTest {
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as MutableStateFlow<T>
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** 6 keys × 12 models, like a well-stocked Connectors screen. */
    private fun seedKeys() {
        val models = (1..12).map { "llama-3.3-70b-versatile-$it" }
        val conns = listOf("groq", "gemini", "openrouter", "cerebras", "mistral", "groq").mapIndexed { i, p ->
            Connector("c$i", p, KeyVault.encrypt("test-key-$i-abcdefghijklmnop"), "••••$i", models, models, addedAt = i.toLong())
        }
        File(app.filesDir, "connectors.json").writeText(Json.encodeToString(ListSerializer(Connector.serializer()), conns))
        flow<List<Connector>>(app.graph.connectors, "_list").value = conns
    }

    private fun history(n: Int) = (0 until n).map { i ->
        if (i % 2 == 0) Message("u$i", Role.USER, "Question number $i about the shop website and its products", createdAt = i.toLong())
        else Message("a$i", Role.ASSISTANT, "Answer $i: " + "Here is a detailed explanation with code. ".repeat(40), model = "Groq · llama", createdAt = i.toLong())
    }

    /**
     * The in-app part of "time to first word": everything between tapping Send and the request leaving the phone.
     * before = the v0.5.1 way (every key decrypted for every model, no decrypt cache); after = v0.5.2.
     */
    @Test fun inAppPrepBeforeAfter() {
        seedKeys()
        val vm = ChatViewModel(app)
        val mode = Mode.AUTO
        fun measure(eager: Boolean, noCache: Boolean, runs: Int = 30): Pair<Double, Int> {
            vm.debugEagerClients = eager; KeyVault.debugNoCache = noCache
            repeat(5) { vm.attempts(Mode.FAST) }   // JIT warm-up
            var decodes = 0; var ns = 0L
            repeat(runs) {
                val d0 = KeyVault.decodes; val t0 = System.nanoTime()
                val list = vm.attempts(Mode.FAST)
                // the first attempt's client is what the request actually needs
                list.first().client.stream(LlmRequest(list.first().model, emptyList(), emptyList()))
                ns += System.nanoTime() - t0; decodes += KeyVault.decodes - d0
                if (!eager) { /* lazy client: the decrypt happens when the request starts */ }
            }
            return ns / runs / 1e6 to decodes / runs
        }
        val (beforeMs, beforeDecrypts) = measure(eager = true, noCache = true)
        val (afterMs, afterDecrypts) = measure(eager = false, noCache = false)
        KeyVault.debugNoCache = false; vm.debugEagerClients = false
        println("PREP before: %.2f ms, %d Keystore decrypts per message | after: %.2f ms, %d".format(beforeMs, beforeDecrypts, afterMs, afterDecrypts))
        File(System.getProperty("user.dir"), "build/v052_speed.txt").writeText("attempts_before_ms=%.3f\ndecrypts_before=%d\nattempts_after_ms=%.3f\ndecrypts_after=%d\n".format(beforeMs, beforeDecrypts, afterMs, afterDecrypts))
        assertEquals(72, beforeDecrypts)    // 6 keys × 12 models, every message
        assertTrue(afterDecrypts <= 1)      // at most the one key that answers (0 once cached)
        assertTrue(afterMs <= beforeMs)
    }

    /** Send tap → request start, through the real ViewModel send path with a long chat; and the first word is on screen at once. */
    @Test fun firstWordShowsWithoutWaitingForTheTicker() {
        val gate = CompletableDeferred<Unit>()
        var requestAt = 0L
        val model = object : LlmClient {
            override fun stream(req: LlmRequest) = flow {
                requestAt = System.currentTimeMillis()
                emit(LlmEvent.Text("Assalam"))
                gate.await()
                emit(LlmEvent.Text(" o alaikum")); emit(LlmEvent.Finished("stop"))
            }
        }
        val vm = ChatViewModel(app)
        vm.debugAttempts = { _, _ -> listOf(Attempt("Fake", "fast-model", model)) }
        val setMsgs = vm.javaClass.getDeclaredField("messages\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") (setMsgs.get(vm) as androidx.compose.runtime.MutableState<List<Message>>).value = history(120)
        val t0 = System.currentTimeMillis()
        vm.send("salam")
        // wait (real time) for the fake model to have sent its first token, without advancing the 45 ms UI ticker
        val deadline = System.currentTimeMillis() + 5_000
        while (requestAt == 0L && System.currentTimeMillis() < deadline) Thread.sleep(2)
        Thread.sleep(50)
        idle()   // runs only what's already posted to the main thread — no ticker tick (time not advanced)
        assertEquals("Assalam", vm.messages.last().text)
        gate.complete(Unit)
        val end = System.currentTimeMillis() + 5_000
        while (vm.running && System.currentTimeMillis() < end) { Thread.sleep(5); idle() }
        val (prep, first) = vm.lastTiming!!
        println("SEND→request: ${prep} ms, SEND→first word on screen: ${first} ms (fake model answers instantly)")
        File(System.getProperty("user.dir"), "build/v052_speed.txt").appendText("send_to_request_ms=$prep\nsend_to_first_word_ms=$first\nwall_ms=${requestAt - t0}\n")
        assertTrue(vm.messages.last().firstWordMs > 0)
        assertEquals("Assalam o alaikum", vm.messages.last().text)
    }

    // ── bug hunt ───────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun replyCutOffByProcessDeathIsNotStuckThinking() {
        val c = Conversation("x", "t", listOf(Message("u", Role.USER, "hi"), Message("a", Role.ASSISTANT, "Wa", status = MsgStatus.STREAMING,
            steps = listOf(Step("s", StepKind.COMMAND, "Running", status = StepStatus.RUNNING)))))
        val s = app.graph.chats.settle(c)
        assertEquals(MsgStatus.INTERRUPTED, s.messages.last().status)
        assertEquals(StepStatus.ERROR, s.messages.last().steps.first().status)
    }

    @Test fun offlineSaysSoAtOnceInsteadOfBurningEveryKey() {
        seedKeys()
        val vm = ChatViewModel(app); vm.debugOnline = false
        vm.send("hello")
        assertFalse(vm.running)
        assertEquals(MsgStatus.ERROR, vm.messages.last().status)
        assertTrue(vm.messages.last().error.startsWith("No internet"))
    }

    @Test fun pastedKeyIsMaskedBeforeItIsSent() {
        val seen = mutableListOf<String>()
        val model = object : LlmClient { override fun stream(req: LlmRequest) = flow { seen += req.items.filterIsInstance<UserItem>().joinToString { it.text }; emit(LlmEvent.Text("ok")); emit(LlmEvent.Finished("stop")) } }
        val vm = ChatViewModel(app); vm.debugAttempts = { _, _ -> listOf(Attempt("Fake", "m", model)) }
        val key = "gsk_" + "A1b2C3d4E5f6G7h8J9k0L1m2N3p4Q5r6S7t8U9v0W1x2Y3z4"
        vm.send("is this key ok? $key")
        val end = System.currentTimeMillis() + 5_000
        while (vm.running && System.currentTimeMillis() < end) { Thread.sleep(5); idle() }
        assertFalse(vm.messages.first().text.contains(key)); assertTrue(vm.messages.first().text.contains("gsk_••••"))
        assertFalse(seen.joinToString().contains(key))
        assertTrue(vm.toast!!.contains("hid an API key"))
    }

    @Test fun giantPasteBecomesAnAttachment() {
        val model = object : LlmClient { override fun stream(req: LlmRequest) = flow { emit(LlmEvent.Text("read it")); emit(LlmEvent.Finished("stop")) } }
        val vm = ChatViewModel(app); vm.debugAttempts = { _, _ -> listOf(Attempt("Fake", "m", model)) }
        vm.send("x".repeat(200_000))
        val u = vm.messages.first()
        assertTrue(u.text.length < 200); assertEquals(1, u.attachments.size); assertEquals(200_000L, File(u.attachments[0].path).length())
        val end = System.currentTimeMillis() + 5_000
        while (vm.running && System.currentTimeMillis() < end) { Thread.sleep(5); idle() }
    }

    @Test fun rapidDoubleSendStartsOneReply() {
        val gate = CompletableDeferred<Unit>()
        val model = object : LlmClient { override fun stream(req: LlmRequest) = flow { gate.await(); emit(LlmEvent.Text("one")); emit(LlmEvent.Finished("stop")) } }
        val vm = ChatViewModel(app); vm.debugAttempts = { _, _ -> listOf(Attempt("Fake", "m", model)) }
        vm.send("hi"); vm.send("hi"); vm.send("hi")
        assertEquals(2, vm.messages.size)
        gate.complete(Unit)
        val end = System.currentTimeMillis() + 5_000
        while (vm.running && System.currentTimeMillis() < end) { Thread.sleep(5); idle() }
    }

    @Test fun twoQuickChatTapsOpenTheLastOne() = runBlocking {
        val store = app.graph.chats
        store.save(Conversation("A", "first", listOf(Message("u", Role.USER, "a"))), true)
        store.save(Conversation("B", "second", listOf(Message("u", Role.USER, "b"))), true)
        Thread.sleep(300)
        val vm = ChatViewModel(app)
        vm.open("A"); vm.open("B")
        val end = System.currentTimeMillis() + 3_000
        while (vm.conversationId == null && System.currentTimeMillis() < end) { Thread.sleep(5); idle() }
        Thread.sleep(100); idle()
        assertEquals("B", vm.conversationId)
    }

    @Test fun prewarmIsKeylessHttpsOnlyAndThrottled() {
        val now = System.currentTimeMillis()
        assertTrue(Http.prewarm("https://api.groq.com/openai/v1", now))
        assertFalse(Http.prewarm("https://api.groq.com/openai/v1/chat", now + 1000))   // same host, within 45 s
        assertFalse(Http.prewarm("http://1.2.3.4:11434/v1", now))
    }
}
