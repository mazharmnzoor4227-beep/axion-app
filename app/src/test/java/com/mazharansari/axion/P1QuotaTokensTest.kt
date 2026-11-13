package com.mazharansari.axion

import androidx.test.core.app.ApplicationProvider
import com.mazharansari.axion.data.Connector
import com.mazharansari.axion.llm.LlmRequest
import com.mazharansari.axion.llm.OpenAiClient
import com.mazharansari.axion.llm.Providers
import com.mazharansari.axion.llm.UserItem
import com.mazharansari.axion.quota.LlmUsage
import com.mazharansari.axion.quota.QuotaMath
import com.mazharansari.axion.quota.Tag
import com.mazharansari.axion.quota.TokenUsage
import com.mazharansari.axion.quota.UsageStore
import com.mazharansari.axion.quota.quotaGroupKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P1-10: real token counting (ME-4) + shared-project quota grouping (ME-3) + source labels on every number. */
class P1QuotaTokensTest {
    private val now = 1_792_000_000_000L

    // ── usage parsing ──

    @Test fun openAiUsageParsed() {
        val obj = Json.parseToJsonElement("""{"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":80,"total_tokens":200}}""").jsonObject
        val u = LlmUsage.parseOpenAi(obj)!!
        assertEquals(120L, u.prompt); assertEquals(80L, u.completion); assertEquals(200L, u.total)
    }

    @Test fun openAiStreamingChunkUsageParsed() {
        // The last SSE chunk carries usage next to an empty choices array.
        val obj = Json.parseToJsonElement("""{"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":4,"total_tokens":13}}""").jsonObject
        assertEquals(13L, LlmUsage.parseOpenAi(obj)!!.total)
    }

    @Test fun openAiUsageAbsentIsNullNeverZero() {
        assertNull(LlmUsage.parseOpenAi(Json.parseToJsonElement("""{"choices":[]}""").jsonObject))
        assertNull(LlmUsage.parseOpenAi(Json.parseToJsonElement("""{"usage":{}}""").jsonObject))
        assertNull(LlmUsage.parseOpenAi(Json.parseToJsonElement("""{"usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}""").jsonObject))
    }

    @Test fun openAiTotalOnlyAttributedToPrompt() {
        val obj = Json.parseToJsonElement("""{"usage":{"total_tokens":42}}""").jsonObject
        val u = LlmUsage.parseOpenAi(obj)!!
        assertEquals(42L, u.prompt); assertEquals(0L, u.completion)
    }

    @Test fun geminiUsageMetadataParsed() {
        val obj = Json.parseToJsonElement("""{"candidates":[],"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":30,"totalTokenCount":80}}""").jsonObject
        val u = LlmUsage.parseGemini(obj)!!
        assertEquals(50L, u.prompt); assertEquals(30L, u.completion); assertEquals(80L, u.total)
    }

    @Test fun geminiUsageAbsentIsNullNeverZero() {
        assertNull(LlmUsage.parseGemini(Json.parseToJsonElement("""{"candidates":[]}""").jsonObject))
        assertNull(LlmUsage.parseGemini(Json.parseToJsonElement("""{"usageMetadata":{}}""").jsonObject))
    }

    // ── stream_options: usage is asked for only where the provider accepts it ──

    @Test fun streamOptionsAskedWhereSupported() {
        val req = LlmRequest("openai/gpt-oss-120b", listOf(UserItem("hi")))
        val groq = OpenAiClient(Providers.byId("groq")!!, "k").buildBody(req)
        assertEquals(true, groq["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.content?.toBoolean())
        val openai = OpenAiClient(Providers.byId("openai")!!, "k").buildBody(req)
        assertEquals(true, openai["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.content?.toBoolean())
        // Unverified for these providers: not asked for, but still parsed whenever they send it.
        val mistral = OpenAiClient(Providers.byId("mistral")!!, "k").buildBody(req)
        assertNull(mistral["stream_options"])
        val cerebras = OpenAiClient(Providers.byId("cerebras")!!, "k").buildBody(req)
        assertNull(cerebras["stream_options"])
    }

    // ── Day token counts ──

    @Test fun dayTokensCountedOrUnknown() {
        val counted = QuotaMath.day(null, 0, 0, 500.0, false, null, 10, 0, now, null, countedTokens = 1234, hasTokens = true)
        assertEquals(1234.0, counted.tokens.value!!, 0.0); assertEquals(Tag.COUNTED, counted.tokens.tag)
        val unknown = QuotaMath.day(null, 0, 0, 500.0, false, null, 10, 0, now, null)
        assertNull(unknown.tokens.value); assertEquals(Tag.UNKNOWN, unknown.tokens.tag)
    }

    // ── shared-project grouping (ME-3) ──

    @Test fun totalsGroupSharedQuotaOnce() {
        fun d(l: Double, u: Int) = QuotaMath.day(null, 0, 0, l, false, null, u, 0, now, null)
        val t = QuotaMath.totals(
            listOf("a" to d(1000.0, 100), "b" to d(1000.0, 50), "c" to d(500.0, 10)),
            shareGroup = { if (it in setOf("a", "b")) "proj-a" else it },
        )
        assertEquals(1500.0, t.limit, 0.0)   // 1000 shared + 500, not 2500
        assertEquals(160.0, t.used, 0.0)     // 100 + 50 counted in the shared bucket + 10
        assertEquals(1340.0, t.left, 0.0)
        assertEquals(2, t.counted)
    }

    @Test fun totalsGroupLiveUsedCoversWholeBucket() {
        fun live(l: Double, u: Double) = QuotaMath.Day(QuotaMath.Num(l, Tag.LIVE), QuotaMath.Num(u, Tag.LIVE), QuotaMath.Num(l - u, Tag.LIVE), null)
        fun counted(l: Double, u: Double) = QuotaMath.Day(QuotaMath.Num(l, Tag.PUBLISHED), QuotaMath.Num(u, Tag.COUNTED), QuotaMath.Num(l - u, Tag.ESTIMATE), null)
        val t = QuotaMath.totals(listOf("a" to live(1000.0, 300.0), "b" to counted(1000.0, 50.0)), shareGroup = { "proj-a" })
        assertEquals(1000.0, t.limit, 0.0)
        assertEquals(300.0, t.used, 0.0)   // the LIVE used already covers the bucket; the counted 50 must not be added on top
        assertEquals(700.0, t.left, 0.0)
        assertEquals(Tag.LIVE, t.usedTag)
    }

    @Test fun totalsTagsAreWeakestSource() {
        fun d(l: Double, is429: Boolean) = QuotaMath.day(null, 0, 0, l, is429, null, 10, 0, now, null)
        val t = QuotaMath.totals(listOf("a" to d(1000.0, true), "b" to d(500.0, false)))
        assertEquals(Tag.PUBLISHED, t.limitTag)   // FROM429 + PUBLISHED → the weaker (less direct) source wins
        assertEquals(Tag.COUNTED, t.usedTag)
        assertEquals(Tag.ESTIMATE, t.leftTag)
    }

    @Test fun quotaGroupKeyOnlyGroupsGemini() {
        val gemA = Connector("id-a", "gemini", "x", "…", listOf("gemini-3.8-flash"), listOf("gemini-3.8-flash"), quotaGroup = "project-a")
        val gemB = Connector("id-b", "gemini", "x", "…", listOf("gemini-3.8-flash"), listOf("gemini-3.8-flash"), quotaGroup = "project-a")
        val gemSolo = Connector("id-c", "gemini", "x", "…", listOf("gemini-3.8-flash"), listOf("gemini-3.8-flash"))
        val groq = Connector("id-d", "groq", "x", "…", listOf("openai/gpt-oss-120b"), listOf("openai/gpt-oss-120b"), quotaGroup = "project-a")
        assertEquals(quotaGroupKey(gemA), quotaGroupKey(gemB))
        assertNotEquals(quotaGroupKey(gemA), quotaGroupKey(gemSolo))
        assertEquals("id-c", quotaGroupKey(gemSolo))
        assertEquals("id-d", quotaGroupKey(groq))   // a group name on a non-Gemini key groups nothing
    }

    // ── UsageStore: usage fills the event row, never a second event ──

    @RunWith(RobolectricTestRunner::class)
    @Config(sdk = [34], application = AxionApp::class)
    class UsageStoreTest {
        @Test fun recordUsageFillsEventRow() {
            val store = UsageStore(ApplicationProvider.getApplicationContext())
            val at = System.currentTimeMillis()
            store.event(at, "key", "key-1", "gemini", "gemini-3.8-flash", true, 200)
            assertFalse(store.hasTokens("key-1", at - 1000))
            assertEquals(0L, store.tokens("key-1", at - 1000))
            store.recordUsage("key-1", "gemini-3.8-flash", TokenUsage(10, 5))
            assertTrue(store.hasTokens("key-1", at - 1000))
            assertEquals(15L, store.tokens("key-1", at - 1000))
            assertEquals(15L, store.tokens("key-1", at - 1000, "gemini-3.8-flash"))
            assertEquals(1, store.count("key-1", at - 1000))   // still one request, not two
        }

        @Test fun recordUsageFallsBackToRefWhenModelDiffers() {
            val store = UsageStore(ApplicationProvider.getApplicationContext())
            val at = System.currentTimeMillis()
            store.event(at, "key", "key-2", "gemini", "gemini-3.8-flash", true, 200)
            store.recordUsage("key-2", "gemini-some-other-model", TokenUsage(7, 0))
            assertEquals(7L, store.tokens("key-2", at - 1000))
        }
    }
}
