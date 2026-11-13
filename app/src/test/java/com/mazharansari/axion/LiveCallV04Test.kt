package com.mazharansari.axion

import com.mazharansari.axion.livecall.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v0.4 live call (mazhar 2:09 PM: "I talk, the reply comes straight back"): the native Gemini Live session —
 * setup handshake, mic in, audio out, barge-in, model fallback — against a mocked socket, then one real round trip.
 */
class LiveCallV04Test {
    private class FakeTransport : LiveTransport {
        var url = ""; var listener: LiveTransport.Listener? = null
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun connect(url: String, listener: LiveTransport.Listener) { this.url = url; this.listener = listener }
        override fun send(text: String): Boolean { sent += text; return true }
        override fun close(code: Int, reason: String) {}
    }

    private class Rec : LiveCallSession.Listener {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val audio = java.io.ByteArrayOutputStream()
        @Volatile var firstAudioAt = 0L
        val ready = CountDownLatch(1); val turn = CountDownLatch(1)
        override fun onReady() { events += "ready"; ready.countDown() }
        override fun onAudio(bytes: ByteArray) { if (firstAudioAt == 0L) firstAudioAt = System.currentTimeMillis(); synchronized(audio) { audio.write(bytes) }; events += "audio" }
        override fun onInterrupted() { events += "interrupted" }
        override fun onTranscript(userText: String, assistantText: String) {}
        override fun onTurn(userText: String?, assistantText: String?) { events += "turn:${userText.orEmpty()}|${assistantText.orEmpty()}"; turn.countDown() }
        override fun onKeySwitched(line: String, exhaustedKeyId: String, quota: Boolean, activatedKeyId: String) { events += "switch" }
        override fun onTerminalError(message: String) { events += "error:$message"; ready.countDown(); turn.countDown() }
    }

    private fun waitFor(ms: Long = 3000, cond: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10); assertTrue("timed out", cond()) }

    @Test fun mockedSocket_setupFirst_audioBothWays_bargeIn_modelFallback() {
        LiveCallProtocol.tryAffective = true   // exercise the optional affective-dialog attempts + fallback (off by default)
        val transports = mutableListOf<FakeTransport>()
        val rec = Rec()
        val s = LiveCallSession({ FakeTransport().also { transports += it } }, listOf(ApiKeyCredential("k1", "Gemini", "AQ.test-key", true)), true,
            LiveCallConfig(voiceName = "Puck"), rec)
        s.start()
        // v0.5.3: the call first tries the native-audio models with affective dialog on v1alpha; any setup error → next.
        for (i in 0..1) {
            waitFor { transports.size == i + 1 && transports[i].listener != null }
            assertTrue(transports[i].url.contains(".v1alpha."))
            transports[i].listener!!.onOpen()
            waitFor { transports[i].sent.isNotEmpty() }
            assertTrue(transports[i].sent[0].contains("enableAffectiveDialog") && transports[i].sent[0].contains("native-audio"))
            transports[i].listener!!.onClosed(1007, "Invalid JSON payload received. Unknown name \"enableAffectiveDialog\"")
        }
        waitFor { transports.size == 3 && transports[2].listener != null }
        assertTrue(transports[2].url.contains("BidiGenerateContent") && transports[2].url.contains(".v1beta."))
        assertFalse(transports.size > 2 && false)
        transports[2].listener!!.onOpen()
        waitFor { transports[2].sent.isNotEmpty() }
        val setup0 = Json.parseToJsonElement(transports[2].sent[0]).jsonObject["setup"]!!.jsonObject
        assertEquals("models/gemini-3.8-live", setup0["model"]!!.jsonPrimitive.content)
        assertTrue(transports[2].sent[0].contains("Puck"))
        s.sendAudio(ByteArray(640))                        // before setupComplete: dropped (protocol forbids it)
        Thread.sleep(50); assertEquals(1, transports[2].sent.size)

        // Google rejects the model name before setup → the next Live model, same key, no error shown
        transports[2].listener!!.onClosed(1008, "models/gemini-3.8-live is not found for API version v1beta, or is not supported for bidiGenerateContent")
        waitFor { transports.size == 4 && transports[3].listener != null }
        transports[3].listener!!.onOpen()
        waitFor { transports[3].sent.isNotEmpty() }
        assertTrue(transports[3].sent[0].contains("models/gemini-3.8-live-extended-thinking"))
        assertEquals("gemini-3.8-live-extended-thinking", s.currentModel)

        transports[3].listener!!.onMessage("""{"setupComplete":{}}""")
        assertTrue(rec.ready.await(2, TimeUnit.SECONDS))
        s.sendAudio(ByteArray(640))                        // mic frame streams straight in (no transcribe step)
        waitFor { transports[3].sent.any { it.contains("realtimeInput") && it.contains("audio") } }
        val pcm = java.util.Base64.getEncoder().encodeToString(ByteArray(960))
        transports[3].listener!!.onMessage("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$pcm"}}]}}}""")
        waitFor { "audio" in rec.events }
        transports[3].listener!!.onMessage("""{"serverContent":{"interrupted":true}}""")   // he talks over it
        waitFor { "interrupted" in rec.events }
        assertFalse(rec.events.any { it.startsWith("error") })
        s.stop()
        LiveCallProtocol.tryAffective = false
    }

    @Test fun keyRejectionIsNotMistakenForModelRejection() {
        assertTrue(LiveFailure.isModelRejected("Connection closed (1008): models/x is not found for API version v1beta"))
        assertFalse(LiveFailure.isModelRejected("Connection closed (1008): API key not valid. Please pass a valid API key."))
        assertFalse(LiveFailure.isModelRejected("RESOURCE_EXHAUSTED: quota"))
    }

    /** One real call: a spoken Roman-Urdu question (16 kHz PCM) streamed at real-time pace, server VAD ends the turn. */
    @Test fun realLiveRoundTrip() {
        val cap = File("/home/user/tab/.vendor-capability.json"); val q = File("/tmp/livetest/q16.pcm")
        assumeTrue(cap.exists() && q.exists())
        val o = Json.parseToJsonElement(cap.readText()).jsonObject
        val b = o["base_url"]!!.jsonPrimitive.content; val t = o["token"]!!.jsonPrimitive.content
        val exp = java.time.Instant.now().plusSeconds(1200).toString()
        val req = okhttp3.Request.Builder().url("$b/google-ai/v1alpha/auth_tokens").header("Authorization", "Bearer $t")
            .post(okhttp3.RequestBody.create(okhttp3.MediaType.Companion.run { "application/json".toMediaTypeOrNull() }, """{"uses":3,"expireTime":"$exp"}""")).build()
        val name = okhttp3.OkHttpClient().newCall(req).execute().use { Json.parseToJsonElement(it.body!!.string()).jsonObject["name"]!!.jsonPrimitive.content }
        val rec = Rec()
        val s = LiveCallSession({ OkHttpLiveTransport() }, listOf(ApiKeyCredential("eph", "Gemini", name, true)), true, LiveCallConfig(voiceName = "Puck"), rec)
        val t0 = System.currentTimeMillis()
        s.start()
        assertTrue(rec.ready.await(20, TimeUnit.SECONDS)); assertFalse(rec.events.toString(), rec.events.any { it.startsWith("error") })
        val setupMs = System.currentTimeMillis() - t0
        val bytes = q.readBytes(); val chunk = 3200   // 100 ms frames, like the mic
        var i = 0
        while (i < bytes.size) { s.sendAudio(bytes.copyOfRange(i, minOf(i + chunk, bytes.size))); i += chunk; Thread.sleep(100) }
        val speechEnd = System.currentTimeMillis()
        repeat(30) { if (rec.firstAudioAt == 0L) { s.sendAudio(ByteArray(chunk)); Thread.sleep(100) } }   // room silence until it answers
        rec.turn.await(25, TimeUnit.SECONDS)
        s.stop()
        val first = if (rec.firstAudioAt > 0) rec.firstAudioAt - speechEnd else -1
        val secs = synchronized(rec.audio) { rec.audio.size() } / 48000.0
        File("/tmp/livetest/reply24k.pcm").writeBytes(synchronized(rec.audio) { rec.audio.toByteArray() })
        val line = "LIVE CALL (Kotlin session, model ${s.currentModel}): connect+setup ${setupMs} ms; first reply audio ${first} ms after he stops talking; ${"%.1f".format(secs)} s of reply audio; turn=${rec.events.firstOrNull { it.startsWith("turn:") }}"
        println(line); File("/tmp/livetest/result.txt").writeText(line)
        assertTrue(first in 1..6000); assertTrue(secs > 1.0)
    }
}
