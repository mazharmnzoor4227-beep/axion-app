package com.mazharansari.axion.livecall

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.2.40: the Gemini Live wire protocol, as pure functions (no Android,
 * no socket types) so the whole layer is JVM-testable against a fake
 * server. Every fact here comes from Google's official Live API docs —
 * see ~/workspace/kodex-live-call/GEMINI-LIVE-FACTS.md:
 *
 *  - Transport: stateful WebSocket; the first message is `setup`, and
 *    nothing else may be sent until `setupComplete` arrives.
 *  - Native-audio models accept only responseModalities ["AUDIO"]; the
 *    reply as TEXT comes from outputAudioTranscription, the user's words
 *    from inputAudioTranscription — both arrive inside `serverContent`.
 *  - Input audio: 16-bit PCM little-endian, 16 kHz mono, declared per
 *    chunk as mimeType "audio/pcm;rate=16000". Output: 24 kHz PCM in
 *    inlineData parts of the model turn.
 *  - `sessionResumption: {}` + `contextWindowCompression {slidingWindow}`
 *    must be in the FIRST setup — they cannot be retrofitted — and are
 *    what let a call survive the ~10-minute connection recycle and the
 *    15-minute audio-only session cap.
 *  - For gemini-3.8-live, thinkingLevel/thinkingConfig is NOT supported
 *    and is deliberately omitted.
 */
object LiveCallProtocol {

    /** Current default Live model (stable, native audio, free tier per Google's pricing page). */
    const val MODEL = "gemini-3.8-live"
    /** v0.4: the next Live native-audio models to try if Google rejects [MODEL] (the bidi list read from Google on 1 Nov 2026). */
    val FALLBACK_MODELS = listOf("gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-latest")

    const val ENDPOINT =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /** The WebSocket URL for one key. The API key travels as the `key` query parameter (the documented API-key auth for this endpoint). */
    /**
     * v0.5.2: the key never travels in the URL (URLs end up in proxy/server logs). [endpointFor] still carries it as
     * `?key=` for the call setup code, and right before the socket opens it is moved into the `x-goog-api-key` header
     * and removed from the URL. Checked against Google's live endpoint on 1 Nov 2026: a header-only key is read (a fake
     * key gets "API key not valid", no key at all gets "unregistered callers").
     */
    fun moveKeyToHeader(url: String): Pair<String, String?> {
        val u = runCatching { url.replaceFirst("wss://", "https://").let { with(okhttp3.HttpUrl.Companion) { it.toHttpUrl() } } }.getOrNull() ?: return url to null
        val key = u.queryParameter("key")?.takeIf { it.isNotBlank() } ?: return url to null
        return u.newBuilder().removeAllQueryParameters("key").build().toString().replaceFirst("https://", "wss://") to key
    }

    fun endpointFor(key: String, alpha: Boolean = false): String =
        if (key.startsWith("auth_tokens/")) "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained?access_token=$key"
        else if (alpha) "${ENDPOINT.replace(".v1beta.", ".v1alpha.")}?key=$key"
        else "$ENDPOINT?key=$key"

    /**
     * v0.5.3 (mazhar: "a human voice with emotion — if I'm angry it answers in that tone"): optional first attempt with
     * Gemini's native-audio Live model + affective dialog on the v1alpha API ([tryAffective]); any setup error before the call is
     * ready falls back quietly to the plain models below — same as 0.5.2.
     */
    const val AFFECTIVE = "affective:"
    val AFFECTIVE_MODELS = listOf("gemini-2.5-flash-native-audio-latest", "gemini-2.5-flash-native-audio-preview-09-2025")
    fun isAffective(entry: String) = entry.startsWith(AFFECTIVE)
    fun modelOf(entry: String) = entry.removePrefix(AFFECTIVE)
    /** Pure: the order a call tries (affective native-audio first, then the 0.5.2 list). */
    fun attemptOrder(primary: String): List<String> = ((if (tryAffective) AFFECTIVE_MODELS.map { AFFECTIVE + it } else emptyList()) + listOf(primary) + FALLBACK_MODELS).distinct()
    /**
     * OFF in 0.5.3, measured against Google's live endpoint on 1 Nov 2026 (realLiveRoundTrip): with
     * generationConfig.enableAffectiveDialog the native-audio model accepted the setup but never answered (0 s of audio);
     * as a top-level field the setup was rejected (the call then fell back to gemini-3.8-live and answered in 0.66 s).
     * The same model WITHOUT the flag answered in 2.3 s. So the emotion comes from the system instruction below on the
     * fast model; flip this on when Google's affective dialog works for API keys.
     */
    @Volatile var tryAffective = false

    /**
     * The spoken-conversation system instruction: short spoken replies,
     * the user's own language, nothing that only makes sense as text
     * (the words are spoken aloud by the model itself).
     */
    const val SYSTEM_INSTRUCTION =
        "You are Axion, a friendly voice assistant inside the Axion app. " +
            "Keep every reply short and conversational — one to three spoken sentences unless the user asks for more. " +
            "Speak in the language the user speaks to you — English, Hindi, Urdu or a mix — and follow them if they switch. " +
            "Never use markdown, lists, code formatting, or emojis: your words are spoken aloud. " +
            "If you do not know something, say so plainly instead of guessing. " +
            "Sound like a warm, natural human, not a robot: speak expressively and match the user's mood and tone — calm and kind if they are upset or angry, " +
            "lively if they are excited, gentle if they are sad — and mirror their language and dialect (Urdu, Hindi, English or a mix)."

    /** The 30 prebuilt Live voices (name to one-word style), from Google's speech-generation page. */
    val VOICES: List<Pair<String, String>> = listOf(
        "Zephyr" to "Bright",
        "Puck" to "Upbeat",
        "Charon" to "Informative",
        "Kore" to "Firm",
        "Fenrir" to "Excitable",
        "Leda" to "Youthful",
        "Orus" to "Firm",
        "Aoede" to "Breezy",
        "Callirrhoe" to "Easy-going",
        "Autonoe" to "Bright",
        "Enceladus" to "Breathy",
        "Iapetus" to "Clear",
        "Umbriel" to "Easy-going",
        "Algieba" to "Smooth",
        "Despina" to "Smooth",
        "Erinome" to "Clear",
        "Algenib" to "Gravelly",
        "Rasalgethi" to "Informative",
        "Laomedeia" to "Upbeat",
        "Achernar" to "Soft",
        "Alnilam" to "Firm",
        "Schedar" to "Even",
        "Gacrux" to "Mature",
        "Pulcherrima" to "Forward",
        "Achird" to "Friendly",
        "Zubenelgenubi" to "Casual",
        "Vindemiatrix" to "Gentle",
        "Sadachbia" to "Lively",
        "Sadaltager" to "Knowledgeable",
        "Sulafat" to "Warm",
    )

    const val DEFAULT_VOICE = "Kore"

    /**
     * The setup message — the first and only message allowed before
     * `setupComplete`. [resumptionHandle] is null for a fresh session and
     * the latest server-issued handle when reconnecting (the handle is
     * what carries the conversation across the connection recycle).
     */
    fun buildSetupJson(
        voiceName: String,
        resumptionHandle: String? = null,
        model: String = MODEL,
        systemInstruction: String = SYSTEM_INSTRUCTION,
    ): String {
        val voice = VOICES.firstOrNull { it.first == voiceName }?.first ?: DEFAULT_VOICE
        val affective = isAffective(model)
        val setup = JSONObject()
            .put("model", "models/${modelOf(model)}")
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", voice),
                            ),
                        ),
                    ),
            )
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))),
            )
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put(
                "sessionResumption",
                if (resumptionHandle.isNullOrBlank()) {
                    JSONObject()
                } else {
                    JSONObject().put("handle", resumptionHandle)
                },
            )
            .put(
                "contextWindowCompression",
                JSONObject().put("slidingWindow", JSONObject()),
            )
        if (affective) {
            setup.getJSONObject("generationConfig").put("enableAffectiveDialog", true)
            // proactiveAudio is NOT sent: with it the model may decide to stay silent (seen in the live test: no reply audio).
        }
        return JSONObject().put("setup", setup).toString()
    }

    /** One mic chunk as a realtimeInput message (16 kHz PCM16 mono, base64). */
    fun buildAudioFrameJson(pcm: ByteArray): String =
        JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put(
                    "audio",
                    JSONObject()
                        .put("data", Base64.getEncoder().encodeToString(pcm))
                        .put("mimeType", "audio/pcm;rate=16000"),
                ),
            )
            .toString()

    /** Flush signal for a paused mic (mute): the docs' audioStreamEnd. */
    fun buildAudioStreamEndJson(): String =
        JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()

    /**
     * Parses one server message into the events it carries. One message
     * holds one payload field, but a serverContent payload can bundle
     * audio parts + a transcription + a completion flag, so this returns
     * a list (possibly empty for usage-only or unknown messages).
     */
    fun parseServerMessage(text: String): List<LiveServerEvent> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val events = mutableListOf<LiveServerEvent>()
        if (root.has("setupComplete")) {
            events += LiveServerEvent.SetupComplete
        }
        root.optJSONObject("sessionResumptionUpdate")?.let { update ->
            events += LiveServerEvent.ResumptionUpdate(
                resumable = update.optBoolean("resumable", false),
                handle = update.optString("newHandle").ifBlank { null },
            )
        }
        root.optJSONObject("goAway")?.let { goAway ->
            events += LiveServerEvent.GoAway(goAway.optString("timeLeft").ifBlank { null })
        }
        root.optJSONObject("serverContent")?.let { content ->
            content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                    val encoded = data.optString("data")
                    if (encoded.isBlank()) continue
                    val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: continue
                    events += LiveServerEvent.AudioChunk(bytes)
                }
            }
            content.optJSONObject("inputTranscription")?.let { t ->
                val value = t.optString("text")
                if (value.isNotEmpty()) {
                    events += LiveServerEvent.InputTranscription(value, t.optBoolean("finished", false))
                }
            }
            content.optJSONObject("outputTranscription")?.let { t ->
                val value = t.optString("text")
                if (value.isNotEmpty()) {
                    events += LiveServerEvent.OutputTranscription(value, t.optBoolean("finished", false))
                }
            }
            if (content.optBoolean("interrupted", false)) {
                events += LiveServerEvent.Interrupted
            }
            if (content.optBoolean("generationComplete", false)) {
                events += LiveServerEvent.GenerationComplete
            }
            if (content.optBoolean("turnComplete", false)) {
                events += LiveServerEvent.TurnComplete
            }
        }
        return events
    }
}

/** One thing the Live server told us. See [LiveCallProtocol.parseServerMessage]. */
sealed interface LiveServerEvent {
    data object SetupComplete : LiveServerEvent

    /** A chunk of the model's voice (24 kHz PCM16). A class, not a data class — ByteArray equality is referential. */
    class AudioChunk(val bytes: ByteArray) : LiveServerEvent

    /** A piece of the USER's speech, transcribed in-stream. Concatenate; [finished] closes the utterance. */
    data class InputTranscription(val text: String, val finished: Boolean) : LiveServerEvent

    /** A piece of the MODEL's speech, transcribed in-stream. Concatenate; [finished] closes the utterance. */
    data class OutputTranscription(val text: String, val finished: Boolean) : LiveServerEvent

    /** The user spoke over the model: playback must stop NOW and the queue must be cleared. */
    data object Interrupted : LiveServerEvent

    data object GenerationComplete : LiveServerEvent

    /** The model's turn is over — the moment to save the turn's transcript pair. */
    data object TurnComplete : LiveServerEvent

    /** The connection is about to be recycled ([timeLeft] until it dies): reconnect with the resumption handle. */
    data class GoAway(val timeLeft: String?) : LiveServerEvent

    /** The latest session-resumption state; [handle] stays valid for 2 hours after the session ends. */
    data class ResumptionUpdate(val resumable: Boolean, val handle: String?) : LiveServerEvent
}

/**
 * Failure classification for the Live path. Builds on the app's shared
 * key-failure matchers and adds the Live API's own status vocabulary
 * (RESOURCE_EXHAUSTED travels in close reasons / handshake errors).
 * An HTTP 400-class setup rejection is deliberately NOT a key failure:
 * the key may be fine and the request wrong — that error is shown as-is.
 */
object LiveFailure {
    fun isKeyFailure(reason: String): Boolean {
        if (LiveKeys.isKeyFailure(reason)) return true
        val value = reason.lowercase()
        return "resource_exhausted" in value || "resource exhausted" in value ||
            "permission_denied" in value || "api key not valid" in value ||
            "invalid api key" in value || "unauthenticated" in value
    }

    /** The model name itself was refused (renamed / retired / not enabled for Live): try the next model, same key. */
    fun isModelRejected(reason: String): Boolean {
        val v = reason.lowercase()
        if (isKeyFailure(reason)) return false
        return "model" in v && ("not found" in v || "not supported" in v || "is not found" in v || "unsupported" in v || "invalid" in v || "does not exist" in v || "1008" in v)
    }

    fun isQuotaFailure(reason: String): Boolean {
        if (LiveKeys.isQuotaFailure(reason)) return true
        val value = reason.lowercase()
        return "resource_exhausted" in value || "resource exhausted" in value
    }
}
