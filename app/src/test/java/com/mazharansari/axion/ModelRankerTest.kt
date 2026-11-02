package com.mazharansari.axion

import com.mazharansari.axion.llm.*
import org.junit.Assert.*
import org.junit.Test

class ModelRankerTest {
    // Live Gemini model list as read on 1 Nov 2026 from the Gemini API (models.list).
    private val gemini = listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-preview-tts", "gemma-4-26b-a4b-it", "gemma-4-31b-it",
        "gemini-flash-latest", "gemini-flash-lite-latest", "gemini-pro-latest", "gemini-2.5-flash-lite", "gemini-2.5-flash-image", "gemini-3-flash-preview",
        "gemini-3.1-pro-preview", "gemini-3.1-flash-lite-preview", "gemini-3.1-flash-lite", "gemini-3-pro-image-preview", "gemini-3.5-flash", "gemini-3.5-flash-lite",
        "gemini-omni-flash-preview", "gemini-3.5-transcribe", "gemini-3.6-flash", "gemini-3.7-flash", "gemini-3.8-flash", "gemini-3.1-flash-tts-preview",
        "gemini-embedding-001", "antigravity-preview-latest", "deep-research-preview-04-2026", "aqa", "lyria-3.5").map { ModelRanker.ModelInfo(it) }

    @Test fun geminiExpertPicksNewestFlash() {
        val r = ModelRanker.rank(Providers.byId("gemini")!!, gemini, Mode.EXPERT)
        println("Gemini expert: " + r.take(6))
        assertEquals("gemini-3.8-flash", r.first())
        assertTrue(r.none { "tts" in it || "embedding" in it || "image" in it || "antigravity" in it || "lyria" in it })
    }

    @Test fun geminiFastPicksLite() {
        val r = ModelRanker.rank(Providers.byId("gemini")!!, gemini, Mode.FAST)
        println("Gemini fast: " + r.take(6))
        assertTrue(r.first().contains("lite"))
    }

    @Test fun openRouterOnlyFreeToolModels() {
        val list = listOf(ModelRanker.ModelInfo("openai/gpt-5", true, false), ModelRanker.ModelInfo("google/gemma-4-31b-it:free", true, true),
            ModelRanker.ModelInfo("nvidia/nemotron-3-ultra-550b-a55b:free", true, true), ModelRanker.ModelInfo("x/no-tools:free", false, true))
        val r = ModelRanker.rank(Providers.byId("openrouter")!!, list, Mode.EXPERT)
        println("OpenRouter: $r")
        assertTrue(r.all { it.endsWith(":free") }); assertFalse("x/no-tools:free" in r)
    }

    @Test fun keyPrefixDetection() {
        assertEquals("gemini", Providers.guess("AIzaSyA-1234567890abcdefghijklmnopqrstu").first().id)
        assertEquals("groq", Providers.guess("gsk_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKL").first().id)
        assertEquals("openrouter", Providers.guess("sk-or-v1-0123456789abcdef").first().id)
        assertEquals("huggingface", Providers.guess("hf_abcdefghijklmnopqrstuvwxyz").first().id)
        // GitHub Models was retired as a provider (Providers.retired), so its key prefix is no longer matched.
        assertEquals("nvidia", Providers.guess("nvapi-abcdef").first().id)
        assertEquals("cerebras", Providers.guess("csk-abcdef0123").first().id)
        // Unknown formats try Gemini first since v0.3 (AI Studio keys changed format in 2026), then the heuristic guess.
        assertTrue("sambanova" in Providers.guess("123e4567-e89b-12d3-a456-426614174000").map { it.id }.take(2))
        assertTrue("mistral" in Providers.guess("AbCdEfGhIjKlMnOpQrStUvWxYz012345").map { it.id }.take(2))
    }

    @Test fun quotaClassification() {
        assertEquals(FailKind.QUOTA, classifyHttp(429, "{}"))
        assertEquals(FailKind.QUOTA, classifyHttp(402, "Payment required"))
        assertEquals(FailKind.AUTH, classifyHttp(401, "invalid api key"))
        assertEquals(FailKind.MODEL_UNAVAILABLE, classifyHttp(404, "model not found"))
        assertEquals(32024000L, parseRetryMs("\"retryDelay\": \"32024s\"", null))
    }
}
