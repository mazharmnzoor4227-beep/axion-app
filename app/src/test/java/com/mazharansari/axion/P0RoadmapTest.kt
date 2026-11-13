package com.mazharansari.axion

import com.mazharansari.axion.data.DetectResult
import com.mazharansari.axion.data.KeyDetector
import com.mazharansari.axion.llm.ModelRanker
import com.mazharansari.axion.llm.Providers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** P0 roadmap tests (no network: [KeyDetector.detect] takes a fake probe). */
class P0RoadmapTest {
    private val models = listOf(ModelRanker.ModelInfo("test-model"))

    /** P0-5 (HI-3): probing stops at the first success — later providers are never contacted. */
    @Test fun detectionStopsAtFirstSuccess() = runBlocking {
        val probed = mutableListOf<String>()
        val r = KeyDetector.detect("not-a-real-key-1234567890", probeFn = { p, _ ->
            probed += p.id
            if (p.id == "mistral") models else throw IllegalStateException("HTTP 401: bad key")
        })
        assertTrue(r is DetectResult.Ok)
        assertEquals("mistral", (r as DetectResult.Ok).provider.id)
        // Ranked order starts gemini, then mistral — nothing after mistral is ever contacted.
        assertEquals(listOf("gemini", "mistral"), probed)
    }

    /** P0-5 (HI-3): a key whose prefix names its provider is probed there and nowhere else. */
    @Test fun prefixKeyProbesOnlyItsProvider() = runBlocking {
        val probed = mutableListOf<String>()
        val r = KeyDetector.detect("gsk_testkey1234567890", probeFn = { p, _ -> probed += p.id; models })
        assertTrue(r is DetectResult.Ok)
        assertEquals("groq", (r as DetectResult.Ok).provider.id)
        assertEquals(listOf("groq"), probed)
    }

    /** P0-5 (HI-3): an unknown-format key is probed in [Providers.probeOrder]. */
    @Test fun unknownKeyFollowsProbeOrder() {
        assertEquals(Providers.probeOrder, KeyDetector.guessesFor("not-a-real-key-1234567890", null).map { it.id })
    }

    /** P0-5 (HI-3): a single guessed provider that refuses the key reports its reason. */
    @Test fun singleGuessFailureNamesProvider() = runBlocking {
        val r = KeyDetector.detect("whatever-key-1234567890", force = Providers.byId("groq"), probeFn = { _, _ ->
            throw IllegalStateException("HTTP 401: bad key")
        })
        assertTrue(r is DetectResult.Fail)
        assertTrue((r as DetectResult.Fail).message.startsWith("Groq didn't accept this key"))
    }

    /** P0-3 (HI-1): an sk-ant- key is refused with a clear message and is never probed anywhere. */
    @Test fun anthropicKeyRefusedWithoutProbing() = runBlocking {
        val probed = mutableListOf<String>()
        val r = KeyDetector.detect("sk-ant-api03-1234567890abcdefghij", probeFn = { p, _ -> probed += p.id; models })
        assertTrue(r is DetectResult.Fail)
        assertEquals(KeyDetector.UNSUPPORTED_ANTHROPIC, (r as DetectResult.Fail).message)
        assertTrue("sk-ant- key must not be sent to any provider", probed.isEmpty())
    }

    /** P0-3 (HI-1): Anthropic is gone from the provider list, so no prefix guess can ever name it. */
    @Test fun anthropicRemovedFromProviders() {
        assertNull(Providers.byId("anthropic"))
        assertTrue(Providers.all.none { it.id == "anthropic" })
        assertTrue(Providers.guess("sk-ant-api03-1234567890abcdefghij").none { it.id == "anthropic" })
        assertNull(Providers.ownerByPrefix("sk-ant-api03-1234567890abcdefghij"))
    }
}
