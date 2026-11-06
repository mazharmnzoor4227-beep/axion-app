package com.mazharansari.axion

import com.mazharansari.axion.livecall.ApiKeyCredential
import com.mazharansari.axion.livecall.LiveCallConfig
import com.mazharansari.axion.livecall.LiveCallProtocol
import com.mazharansari.axion.livecall.LiveCallSession
import com.mazharansari.axion.livecall.LiveServerEvent
import com.mazharansari.axion.livecall.LiveTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v0.7.0 Live-call tools: declarations ride in the setup, the model's function calls are executed off the
 * event thread and answered by id, a cancelled call is never answered, and end_call answers first, then
 * hangs up. All against a fake socket — no network, no keys.
 */
class LiveToolCallTest {
    private class FakeTransport : LiveTransport {
        var url = ""; var listener: LiveTransport.Listener? = null
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun connect(url: String, listener: LiveTransport.Listener) { this.url = url; this.listener = listener }
        override fun send(text: String): Boolean { sent += text; return true }
        override fun close(code: Int, reason: String) {}
    }

    private class Rec : LiveCallSession.Listener {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val ready = CountDownLatch(1)
        override fun onReady() { events += "ready"; ready.countDown() }
        override fun onAudio(bytes: ByteArray) {}
        override fun onInterrupted() {}
        override fun onTranscript(userText: String, assistantText: String) {}
        override fun onTurn(userText: String?, assistantText: String?) {}
        override fun onKeySwitched(line: String, exhaustedKeyId: String, quota: Boolean, activatedKeyId: String) {}
        override fun onTerminalError(message: String) { events += "error:$message"; ready.countDown() }
    }

    private fun waitFor(ms: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("timed out", cond())
    }

    private val decl = LiveCallProtocol.LiveToolDecl(
        "web_search", "Search the web",
        JSONObject().put("type", "object").put("properties", JSONObject().put("query", JSONObject().put("type", "string"))),
    )

    private fun readySession(
        transports: MutableList<FakeTransport>, rec: Rec,
        executor: (suspend (String, JSONObject) -> JSONObject)? = null,
        onEndCall: (() -> Unit)? = null,
        withTools: Boolean = true,
    ): LiveCallSession {
        val s = LiveCallSession(
            { FakeTransport().also { transports += it } },
            listOf(ApiKeyCredential("k1", "Gemini", "AQ.test-key", true)), true,
            LiveCallConfig(voiceName = "Kore", tools = if (withTools) listOf(decl) else emptyList()), rec,
            toolExecutor = executor, onEndCall = onEndCall, endCallDelayMs = 50,
        )
        s.start()
        waitFor { transports.isNotEmpty() && transports[0].listener != null }
        transports[0].listener!!.onOpen()
        waitFor { transports[0].sent.isNotEmpty() }
        transports[0].listener!!.onMessage("""{"setupComplete":{}}""")
        assertTrue(rec.ready.await(2, TimeUnit.SECONDS))
        return s
    }

    @Test fun setupCarriesDeclarationsOnlyWhenToolsAreGiven() {
        val withT = mutableListOf<FakeTransport>(); readySession(withT, Rec(), executor = { _, _ -> JSONObject() }).stop()
        assertTrue(withT[0].sent[0].contains("\"tools\"") && withT[0].sent[0].contains("functionDeclarations") && withT[0].sent[0].contains("web_search"))
        val without = mutableListOf<FakeTransport>(); readySession(without, Rec(), withTools = false).stop()
        assertFalse(without[0].sent[0].contains("functionDeclarations"))
    }

    @Test fun parsesToolCallsAndCancellations() {
        val events = LiveCallProtocol.parseServerMessage(
            """{"toolCall":{"functionCalls":[{"id":"c1","name":"web_search","args":{"query":"mausam"}},{"id":"c2","name":"end_call","args":{}}]}}""",
        )
        val batch = events.filterIsInstance<LiveServerEvent.ToolCalls>().single()
        assertEquals(listOf("c1", "c2"), batch.calls.map { it.id })
        assertEquals("mausam", batch.calls[0].args.optString("query"))
        val cancelled = LiveCallProtocol.parseServerMessage("""{"toolCallCancellation":{"ids":["c1"]}}""")
        assertEquals(setOf("c1"), cancelled.filterIsInstance<LiveServerEvent.ToolCallCancelled>().single().ids)
    }

    @Test fun toolResponseShape() {
        val json = JSONObject(LiveCallProtocol.buildToolResponseJson(listOf(Triple("c1", "web_search", JSONObject().put("result", "ok")))))
        val fr = json.getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("c1", fr.getString("id")); assertEquals("web_search", fr.getString("name"))
        assertEquals("ok", fr.getJSONObject("response").getString("result"))
    }

    @Test fun executesAndAnswersById() {
        val transports = mutableListOf<FakeTransport>()
        val seen = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val s = readySession(transports, Rec(), executor = { name, args ->
            seen += name to args.optString("query"); JSONObject().put("result", "sunny, 30 degrees")
        })
        transports[0].listener!!.onMessage("""{"toolCall":{"functionCalls":[{"id":"call-1","name":"web_search","args":{"query":"karachi weather"}}]}}""")
        waitFor { transports[0].sent.any { it.contains("\"toolResponse\"") } }
        assertEquals(listOf("web_search" to "karachi weather"), seen.toList())
        val answer = JSONObject(transports[0].sent.first { it.contains("\"toolResponse\"") })
        val fr = answer.getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("call-1", fr.getString("id")); assertEquals("web_search", fr.getString("name"))
        assertEquals("sunny, 30 degrees", fr.getJSONObject("response").getString("result"))
        s.stop()
    }

    @Test fun endCallAnswersFirstThenHangsUp() {
        val transports = mutableListOf<FakeTransport>()
        val ended = CountDownLatch(1)
        val s = readySession(transports, Rec(), executor = { _, _ -> JSONObject().put("result", "ending") }, onEndCall = { ended.countDown() })
        transports[0].listener!!.onMessage("""{"toolCall":{"functionCalls":[{"id":"c9","name":"end_call","args":{}}]}}""")
        waitFor { transports[0].sent.any { it.contains("\"toolResponse\"") } }
        assertTrue("onEndCall must fire after the answer", ended.await(2, TimeUnit.SECONDS))
        s.stop()
    }

    @Test fun cancelledCallIsNeverAnswered() {
        val transports = mutableListOf<FakeTransport>()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val s = readySession(transports, Rec(), executor = { _, _ ->
            started.countDown(); release.await(5, TimeUnit.SECONDS); JSONObject().put("result", "late")
        })
        transports[0].listener!!.onMessage("""{"toolCall":{"functionCalls":[{"id":"cx","name":"web_search","args":{}}]}}""")
        assertTrue(started.await(2, TimeUnit.SECONDS))
        transports[0].listener!!.onMessage("""{"toolCallCancellation":{"ids":["cx"]}}""")
        release.countDown()
        Thread.sleep(400)
        assertFalse(transports[0].sent.any { it.contains("\"toolResponse\"") })
        s.stop()
    }

    @Test fun withoutABridgeToolCallsAreIgnored() {
        val transports = mutableListOf<FakeTransport>()
        val rec = Rec()
        val s = readySession(transports, rec, executor = null)
        transports[0].listener!!.onMessage("""{"toolCall":{"functionCalls":[{"id":"c1","name":"web_search","args":{}}]}}""")
        Thread.sleep(300)
        assertFalse(transports[0].sent.any { it.contains("\"toolResponse\"") })
        assertFalse(rec.events.any { it.startsWith("error") })
        s.stop()
    }
}
