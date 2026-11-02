package com.mazharansari.axion.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * "Free (slow)": Pollinations' keyless OpenAI-compatible endpoint (tested 1 Nov 2026: 200 with tool calls; then
 * "1 reply every 45 seconds per IP"). Used only when no key can answer, or when picked in the model menu. No key is embedded anywhere.
 *
 * v0.5.2 (mazhar's phone test: a 2nd message within ~15 s → Thinking, then "No connection… Network: Canceled"):
 *  - Axion remembers when the last free reply was asked for and waits out the 45 s gap itself, with a visible
 *    countdown ("Free model is busy — retrying in 12 s…") instead of sending a request that will be refused;
 *  - a "busy" answer (429/402) is waited out up to twice, with the same countdown;
 *  - the wait is no longer streamed as "thinking" text (it could end up shown as the answer).
 */
object FreeFallback {
    const val NAME = "Free (slow)"
    val provider = Provider(
        id = "pollinations", name = NAME, api = ApiKind.OPENAI,
        baseUrl = "https://text.pollinations.ai/openai", keyPage = "https://pollinations.ai",
        free = true, note = "Built in, no key · about 1 reply every 45 seconds", fallbackModel = "openai",
    )
    const val MODEL = "openai"
    const val GAP_MS = 45_000L
    /** How long the free model may take in total (waits included) before Axion gives up on it. */
    const val PATIENT_MS = 150_000L
    const val BUSY = "The free model is busy (it allows about 1 reply every 45 seconds). Try again in a minute, or add a free Gemini or Groq key in Connectors for fast replies."

    @Volatile var lastAskAt = 0L

    /** ms to wait before the next free request is allowed (0 = go now). */
    fun waitNeeded(now: Long = System.currentTimeMillis()): Long = (lastAskAt + GAP_MS - now).coerceAtLeast(0)

    class Client(private val onWait: (Int) -> Unit = {}, private val base: String = provider.baseUrl) : LlmClient {
        override fun stream(req: LlmRequest): Flow<LlmEvent> = flow {
            suspend fun countdown(ms: Long) {
                var left = ms
                while (left > 0) { onWait(((left + 999) / 1000).toInt()); val step = minOf(1000L, left); delay(step); left -= step }
                onWait(0)
            }
            waitNeeded().takeIf { it > 0 }?.let { countdown(it + 300) }
            var waits = 0
            while (true) {
                lastAskAt = System.currentTimeMillis()
                try {
                    OpenAiClient(provider.copy(baseUrl = base), "", tag = "free").stream(req.copy(model = MODEL)).collect { emit(it) }
                    return@flow
                } catch (e: LlmException) {
                    val busy = e.code == 402 || e.code == 429
                    val ms = e.retryAfterMs.takeIf { it > 0 } ?: GAP_MS
                    if (!busy) throw e
                    if (waits >= 2 || ms > 90_000) throw LlmException(BUSY, FailKind.RATE, e.code, ms)
                    waits++
                    countdown(ms + 500)
                }
            }
        }
    }
}
