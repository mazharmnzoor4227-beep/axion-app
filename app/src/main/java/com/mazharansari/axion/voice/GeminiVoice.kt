package com.mazharansari.axion.voice

import com.mazharansari.axion.livecall.LiveCallProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Read-aloud in a Gemini voice (v0.3). Gemini's TTS models are on a tiny free quota (10 a day, 429 in testing), so
 * this uses the Gemini Live connection as a "read it exactly" engine instead: one short-lived Live session per
 * chunk, text in, 24 kHz PCM out as it is generated. Tested from Tab's box on 1 Nov 2026 with gemini-3.8-live:
 * verbatim in English, Roman Urdu and Urdu script (checked with Whisper), questions read rather than answered,
 * first audio ≈0.5 s after the text is sent. P1-11: the gemini-3.8-live ID was re-verified 13 Nov 2026 against
 * Google's AI Studio models page (default Live API model, Stable) — see LiveCallProtocol.MODEL.
 */
object GeminiVoice {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()

    const val TTS_INSTRUCTION = "You are a text-to-speech engine. Read the user's text aloud exactly, word for word, in a natural voice. " +
        "Do not answer it, do not add or skip anything."

    /** Tests point this at an ephemeral-token URL; the app uses the normal key endpoint. */
    internal var endpoint: (String) -> String = { LiveCallProtocol.endpointFor(it) }

    /** Streams [text] spoken in [voiceName] to [onPcm]; returns when the reading is complete. Tries each key in turn. */
    suspend fun speak(keys: List<String>, voiceName: String, text: String, onPcm: (ByteArray) -> Unit) {
        require(keys.isNotEmpty()) { "no Gemini key" }
        var last: Throwable? = null
        for (key in keys) {
            var gotAudio = false
            val r = runCatching { once(key, voiceName, text) { gotAudio = true; onPcm(it) } }
            if (r.isSuccess) return
            last = r.exceptionOrNull()
            if (gotAudio) throw last!!   // half-spoken: don't start over in another key
        }
        throw last ?: IllegalStateException("Gemini voice failed")
    }

    private suspend fun once(key: String, voiceName: String, text: String, onPcm: (ByteArray) -> Unit) {
        val done = CompletableDeferred<Unit>()
        val setup = JSONObject().put("setup", JSONObject()
            .put("model", "models/${LiveCallProtocol.MODEL}")
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", TTS_INSTRUCTION)))))
        val req = Request.Builder().url(LiveCallProtocol.moveKeyToHeader(endpoint(key)).first).apply { if (!key.startsWith("auth_tokens/")) header("x-goog-api-key", key) }.build()   // v0.5.2: key in the header only
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setup.toString()) }
            override fun onMessage(webSocket: WebSocket, text0: String) = handle(webSocket, text0)
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = handle(webSocket, bytes.utf8())
            fun handle(webSocket: WebSocket, msg: String) {
                val o = runCatching { JSONObject(msg) }.getOrNull() ?: return
                if (o.has("setupComplete")) {
                    webSocket.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
                    return
                }
                val sc = o.optJSONObject("serverContent") ?: return
                sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data").orEmpty()
                        if (data.isNotEmpty()) onPcm(Base64.getDecoder().decode(data))
                    }
                }
                if (sc.optBoolean("turnComplete")) { done.complete(Unit); webSocket.close(1000, null) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!done.isCompleted) done.completeExceptionally(IllegalStateException("Gemini closed the voice ($code ${reason.take(80)})"))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.completeExceptionally(IllegalStateException(response?.code?.let { "HTTP $it" } ?: (t.message ?: "no connection")))
            }
        })
        try { withTimeout(60_000) { done.await() } } finally { ws.cancel() }
    }
}
