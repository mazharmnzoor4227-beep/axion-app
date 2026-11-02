package com.mazharansari.axion

import androidx.test.core.app.ApplicationProvider
import com.mazharansari.axion.agent.*
import com.mazharansari.axion.data.*
import com.mazharansari.axion.llm.*
import com.mazharansari.axion.skills.SkillRepo
import com.mazharansari.axion.ui.ChatViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/** v0.4: mazhar 2:17 PM — (A) "hi bhai" sat in Thinking; (B) the stop button did nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AxionApp::class)
class ChatSpeedStopTest {
    private val smallTalk = listOf("hi bhai", "kaise ho", "hello", "salam", "Assalam o alaikum", "kya haal hai?")

    @Test fun smallTalkIsChatAndQuick() {
        for (s in smallTalk) {
            assertEquals(s, Intent.CHAT, IntentGate.classify(s))
            assertTrue(s, IntentGate.isSmallTalk(s))
        }
        assertFalse(IntentGate.isSmallTalk("Mere store ki sales report PDF bana do"))
        assertFalse(IntentGate.isSmallTalk("aaj ka dollar rate kya hai"))   // a lookup, not small talk
    }

    @Test fun quickRequestsTurnReasoningOff() {
        val gem = GeminiClient("k").buildBody(LlmRequest("gemini-3.8-flash", listOf(UserItem("hi bhai")), quick = true))
        val tc = gem["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals(false, tc["includeThoughts"]!!.jsonPrimitive.boolean)
        assertEquals("minimal", tc["thinkingLevel"]!!.jsonPrimitive.content)
        val gem25 = GeminiClient("k").buildBody(LlmRequest("gemini-2.5-flash", listOf(UserItem("hi")), quick = true))
        assertEquals(0, gem25["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.int)
        val normal = GeminiClient("k").buildBody(LlmRequest("gemini-3.8-flash", listOf(UserItem("build me a site"))))
        assertEquals(true, normal["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["includeThoughts"]!!.jsonPrimitive.boolean)
        val groq = OpenAiClient(Providers.byId("groq")!!, "k").buildBody(LlmRequest("openai/gpt-oss-120b", listOf(UserItem("hi bhai")), quick = true))
        assertEquals("low", groq["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals(false, groq["include_reasoning"]!!.jsonPrimitive.boolean)
        val llama = OpenAiClient(Providers.byId("groq")!!, "k").buildBody(LlmRequest("llama-3.3-70b-versatile", listOf(UserItem("hi")), quick = true))
        assertNull(llama["reasoning_effort"])   // non-reasoning models get nothing they might reject
    }

    @Test fun quickOrderPutsNonReasoningModelsFirst() {
        val order = ChatViewModel.quickOrder(listOf("openai/gpt-oss-120b", "qwen/qwen3-32b", "llama-3.3-70b-versatile", "gemini-3.8-flash"), { it })
        assertEquals(listOf("llama-3.3-70b-versatile", "gemini-3.8-flash", "openai/gpt-oss-120b", "qwen/qwen3-32b"), order)
    }

    /** The agent shows no Thoughts block for small talk even if a model streams reasoning anyway. */
    @Test fun smallTalkShowsNoThinkingBlock() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AxionApp>()
        val agent = Agent(Workspace(File(app.filesDir, "workspace")), SkillRepo(app, File(app.filesDir, "skills")))
        var seenQuick: Boolean? = null
        val fake = object : LlmClient {
            override fun stream(req: LlmRequest) = flow {
                seenQuick = req.quick; assertTrue(req.tools.isEmpty())
                emit(LlmEvent.Reasoning("The user greets me…")); emit(LlmEvent.Text("Walaikum salam bhai! Kaise ho?")); emit(LlmEvent.Finished("stop"))
            }
        }
        for (s in smallTalk) {
            val reasoning = StringBuilder(); val text = StringBuilder()
            val sink = object : AgentSink {
                override fun text(delta: String) { text.append(delta) }
                override fun reasoning(delta: String) { reasoning.append(delta) }
                override fun textToThoughts() {}
                override fun step(step: Step) { fail("no tools for small talk") }
                override fun artifact(a: Artifact) {}
                override fun model(label: String) {}
            }
            agent.run(emptyList(), UserItem(s), { listOf(Attempt("Fake", "gpt-oss", fake)) }, sink, intent = IntentGate.classify(s))
            assertEquals(s, true, seenQuick); assertEquals(s, "", reasoning.toString()); assertTrue(text.isNotEmpty())
        }
    }

    /** Stop while tokens stream: the request is torn down within 300 ms, not when the server finishes. */
    @Test fun stopHaltsStreamingWithin300ms() = runBlocking {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { s ->
                val inp = s.getInputStream().bufferedReader(); var len = 0
                while (true) { val l = inp.readLine() ?: break; if (l.startsWith("Content-Length:", true)) len = l.substringAfter(':').trim().toInt(); if (l.isEmpty()) break }
                repeat(len) { inp.read() }
                val out = s.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                for (i in 0 until 600) {   // a slow model: one token every 50 ms for 30 s
                    runCatching { out.write("data: {\"choices\":[{\"delta\":{\"content\":\"tok$i \"}}]}\n\n".toByteArray()); out.flush() }.onFailure { return@thread }
                    Thread.sleep(50)
                }
            }
        }
        val client = OpenAiClient(Providers.byId("groq")!!, "k", baseUrl = "http://127.0.0.1:${server.localPort}")
        var tokens = 0
        val job = launch(Dispatchers.Default) { client.stream(LlmRequest("llama-3.3-70b-versatile", listOf(UserItem("likho ek lambi kahani")))).collect { if (it is LlmEvent.Text) tokens++ } }
        while (tokens < 5) delay(10)
        val t0 = System.currentTimeMillis()
        job.cancel(); job.join()
        val ms = System.currentTimeMillis() - t0
        println("STOP: generation halted ${ms} ms after Stop (after $tokens tokens)")
        assertTrue("stop took $ms ms", ms < 300)
        server.close()
    }
}
