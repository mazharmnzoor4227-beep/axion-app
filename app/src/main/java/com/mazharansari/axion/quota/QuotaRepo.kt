package com.mazharansari.axion.quota

import android.content.Context
import com.mazharansari.axion.antigravity.AgyAccount
import com.mazharansari.axion.antigravity.AgyAccounts
import com.mazharansari.axion.data.Connector
import com.mazharansari.axion.data.ConnectorRepo
import com.mazharansari.axion.llm.Http
import com.mazharansari.axion.llm.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import java.io.File

/** Every HTTP answer from an LLM provider passes through here (OpenAiClient / GeminiClient call it). */
object QuotaHooks {
    @Volatile var sink: ((tag: String?, providerId: String, model: String, code: Int, headers: Map<String, String>, errorBody: String?) -> Unit)? = null
    fun report(tag: String?, providerId: String, model: String, code: Int, headers: okhttp3.Headers, errorBody: String?) {
        val s = sink ?: return
        runCatching { s(tag, providerId, model, code, headers.names().associateWith { headers[it].orEmpty() }.filterKeys { RateHeaders.interesting(it) }, errorBody) }
    }
}

data class KeyRow(
    val connectorId: String, val providerId: String, val providerName: String, val keyHint: String,
    val model: String, val models: Int, val day: QuotaMath.Day, val perModel: List<Pair<String, QuotaMath.Day>>,
    val minute: Reading?, val note: String, val retired: Boolean = false, val free: Boolean = false,
)

data class AgyRow(val account: AgyAccount, val buckets: List<AgyUsageParser.Bucket>, val probedAt: Long?, val runsToday: Int, val tokensToday: Long, val raw: List<String>)

data class QuotaModel(
    val updatedAt: Long,
    val agy: List<AgyRow>, val agyCombined: Double, val agyUnknown: Int, val agyRunsToday: Int, val agyTokensToday: Long, val agyMoreRuns: Double?, val agyEarliestReset: Long?,
    val keys: List<KeyRow>, val totals: QuotaMath.Totals, val ratePerHour: Double?, val runOut: QuotaMath.RunOut, val callsPerChat: Double?,
) {
    companion object {
        /** Shown for the first frame while the real numbers load off the main thread. */
        fun empty() = QuotaModel(System.currentTimeMillis(), emptyList(), 0.0, 0, 0, 0, null, null, emptyList(), QuotaMath.Totals(0.0, 0.0, 0.0, 0, 0), null, QuotaMath.RunOut.NotEnoughData, null)
    }
}

class QuotaRepo(private val ctx: Context, private val connectors: ConnectorRepo, private val agy: AgyAccounts) {
    val store by lazy { UsageStore(ctx) }
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private val rawFile = File(ctx.filesDir, "agy_usage_raw.json")
    private val published: JsonObject = runCatching { Json.parseToJsonElement(ctx.assets.open("limits.json").bufferedReader().readText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    init {
        QuotaHooks.sink = { tag, pid, model, code, headers, body -> onResponse(tag ?: pid, pid, model, code, headers, body) }
        runCatching { store.prune(System.currentTimeMillis()) }
    }

    private fun bump() { _version.value = _version.value + 1 }

    fun onResponse(ref: String, providerId: String, model: String, code: Int, headers: Map<String, String>, body: String?, now: Long = System.currentTimeMillis()) {
        store.event(now, "key", ref, providerId, model, code in 200..299, code)
        RateHeaders.parse(providerId, headers, now).forEach { store.snapshot(now, "key", ref, model, it) }
        if (code == 429 && body != null && providerId == "gemini") Gemini429.parse(body)?.let { g ->
            val m = g.model ?: model
            if (g.limit != null && g.window == "day") store.override(ref, m, "day", g.limit, "429")
            if (g.window == "day") store.snapshot(now, "key", ref, m, Reading("day", "req", g.limit, 0.0, g.retryMs?.let { now + it } ?: ProviderDay.nextReset("gemini", now), Tag.FROM429, raw = body.take(2000)))
        }
        bump()
    }

    fun recordAgyRun(email: String, ok: Boolean, inTok: Long, outTok: Long) { store.event(System.currentTimeMillis(), "agy", email, "antigravity", "", ok, if (ok) 200 else 0, inTok, outTok); bump() }

    /** Store one `agy -p /usage` answer: parsed buckets as fraction snapshots + the raw text (last 3 per account). */
    fun recordAgyProbe(email: String, raw: String, now: Long = System.currentTimeMillis()): AgyUsageParser.Result {
        val r = AgyUsageParser.parse(raw, now)
        r.buckets.forEach { b -> store.snapshot(now, "agy", email, b.models.joinToString(","), Reading(b.window, "fraction", 1.0, b.remainingFraction, b.resetAt, Tag.LIVE, label = b.label + if (b.disabled) " (disabled)" else "", raw = "")) }
        if (r.buckets.isEmpty()) store.snapshot(now, "agy", email, "", Reading("", "raw", null, null, null, Tag.UNKNOWN, label = "unparsed", raw = raw.take(4000)))
        r.tier?.let { t -> agy.setTier(email, when { t.contains("ultra", true) -> "ultra"; t.contains("pro", true) -> "pro"; t.contains("free", true) || t.contains("standard", true) -> "free"; else -> t.lowercase() }) }
        val all = rawMap().toMutableMap(); all[email] = (listOf("${java.time.Instant.ofEpochMilli(now)}\n$raw") + (all[email] ?: emptyList())).take(3)
        runCatching { rawFile.writeText(JsonObject(all.mapValues { JsonArray(it.value.map(::JsonPrimitive)) }).toString()) }
        // A probe saying the group is back above 0 clears an old limit-hit cooldown.
        if (r.buckets.isNotEmpty() && r.buckets.any { (it.remainingFraction ?: 0.0) > 0.02 }) agy.clearQuota(email)
        bump(); return r
    }

    fun rawMap(): Map<String, List<String>> = runCatching { Json.parseToJsonElement(rawFile.readText()).jsonObject.mapValues { e -> e.value.jsonArray.map { it.jsonPrimitive.content } } }.getOrDefault(emptyMap())

    /** OpenRouter's /key answers without spending a request. At most once a minute per key. */
    suspend fun refreshOpenRouter(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        connectors.list.value.filter { it.enabled && it.providerId == "openrouter" }.forEach { c ->
            if (now - (store.latest(c.id, "day", "")?.second ?: 0) < 60_000) return@forEach
            runCatching {
                Http.quick.newCall(Request.Builder().url("https://openrouter.ai/api/v1/key").header("Authorization", "Bearer ${c.key()}").build()).execute().use { r ->
                    if (r.isSuccessful) OpenRouterKey.parse(r.body?.string().orEmpty())?.let { k ->
                        if (k.limit != null || k.remaining != null) store.snapshot(now, "key", c.id, "", Reading("day", "req", k.limit, k.remaining, ProviderDay.nextReset("openrouter", now), Tag.LIVE, label = "free models"))
                    }
                }
            }
        }
        bump()
    }

    private fun publishedDay(providerId: String, model: String): Double? {
        val p = published[providerId] as? JsonObject ?: return null
        (p["rpd"] as? JsonObject)?.get(model)?.jsonPrimitive?.doubleOrNull?.let { return it }
        (p["rpd"] as? JsonObject)?.entries?.firstOrNull { model.startsWith(it.key) }?.value?.jsonPrimitive?.doubleOrNull?.let { return it }
        if (providerId == "openrouter" && model.endsWith(":free")) return p["free_rpd_low"]?.jsonPrimitive?.doubleOrNull
        return p["rpd_default"]?.jsonPrimitive?.doubleOrNull
    }

    private fun perModelProvider(id: String) = id in setOf("gemini", "groq", "sambanova")

    fun dayFor(c: Connector, model: String, now: Long): QuotaMath.Day {
        val m = if (perModelProvider(c.providerId)) model else ""
        val live = store.latest(c.id, "day", if (perModelProvider(c.providerId)) model else null)
        val ov = store.overrideOf(c.id, model)
        val start = ProviderDay.start(c.providerId, now)
        val liveToday = live?.takeIf { it.second >= start || (it.first.resetAt ?: 0) > now }
        return QuotaMath.day(liveToday?.first, liveToday?.second ?: 0, store.lastCallAt(c.id), ov?.first, ov?.second == "429", publishedDay(c.providerId, model),
            store.count(c.id, start, m.ifEmpty { null }), connectors.coolingUntil(c, model), now, ProviderDay.nextReset(c.providerId, now))
    }

    /** For routing: how many requests this key still has today (null = unknown). */
    fun leftToday(c: Connector, now: Long = System.currentTimeMillis()): Double? = runCatching { dayFor(c, c.pinnedModel ?: c.models.firstOrNull() ?: "", now).left.value }.getOrNull()

    fun model(now: Long = System.currentTimeMillis()): QuotaModel {
        // API keys
        val rows = connectors.list.value.map { c ->
            val p = Providers.byId(c.providerId)
            val primary = c.pinnedModel ?: c.models.firstOrNull() ?: ""
            val per = if (perModelProvider(c.providerId)) (listOfNotNull(c.pinnedModel) + c.models).distinct().take(6).map { it to dayFor(c, it, now) } else emptyList()
            val known = per.filter { it.second.limit.value != null }
            val day = if (known.size > 1) {
                val l = known.sumOf { it.second.limit.value!! }; val u = known.sumOf { it.second.used.value ?: 0.0 }; val left = known.sumOf { it.second.left.value ?: 0.0 }
                val tag = known.map { it.second.limit.tag }.minByOrNull { it.ordinal } ?: Tag.PUBLISHED
                QuotaMath.Day(QuotaMath.Num(l, tag), QuotaMath.Num(u, known.first().second.used.tag), QuotaMath.Num(left, if (known.all { it.second.left.tag == Tag.LIVE }) Tag.LIVE else Tag.ESTIMATE), known.mapNotNull { it.second.resetAt }.minOrNull())
            } else per.firstOrNull { it.first == primary }?.second ?: dayFor(c, primary, now)
            val minute = store.latest(c.id, "minute", null)?.takeIf { now - it.second < 10 * 60_000 }?.first
            val note = when (c.providerId) {
                "gemini" -> "Counted by Axion · limit learned from Google's limit error · keys from one Google project share one quota"
                "groq" -> "Live from Groq's headers · each model has its own daily bucket"
                "openrouter" -> "Live from OpenRouter · free models share one daily count (UTC)"
                "sambanova" -> "Live from headers · 20 requests/day per model on free"
                "mistral" -> "Per-second limit only · no daily cap shown by Mistral"
                else -> p?.note ?: ""
            }
            KeyRow(c.id, c.providerId, p?.name ?: c.providerId, c.keyHint, primary, maxOf(per.size, 1), day, per, minute, note, free = p?.free == true)
        }
        val totals = QuotaMath.totals(rows.filter { !it.retired }.map { it.connectorId to it.day })
        val dayStart = rows.minOfOrNull { ProviderDay.start(it.providerId, now) } ?: (now - 86_400_000L)
        val rate = QuotaMath.ratePerHour(store.times("key", now - 86_400_000L), now, dayStart)
        val reset = rows.mapNotNull { it.day.resetAt }.minOrNull()
        val runOut = QuotaMath.runOut(totals.left.takeIf { totals.counted > 0 }, rate, reset, now)

        // Antigravity
        val localStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val raws = rawMap()
        val agyRows = agy.accounts().map { a ->
            val set = store.latestSet(a.email)
            val buckets = set?.first?.filter { it.unit == "fraction" }?.map { AgyUsageParser.Bucket(it.label, it.remaining, it.resetAt, it.window, it.label.endsWith("(disabled)"), it.model?.split(',')?.filter { m -> m.isNotBlank() }.orEmpty()) }.orEmpty()
            AgyRow(a, buckets, set?.second, store.count(a.email, localStart), store.tokens(a.email, localStart), raws[a.email].orEmpty())
        }
        // Combined: per account, the main group (first bucket) — the one the model in use belongs to.
        val fr = agyRows.filter { it.account.enabled }.map { r -> if (r.account.cooldownUntil > now) 0.0 else r.buckets.firstOrNull()?.remainingFraction }
        val (combined, unknown) = QuotaMath.agyCombined(fr)
        val burns = agyRows.flatMap { r -> store.fractions(r.account.email, now - 7 * 86_400_000L).map { it.second }.zipWithNext { x, y -> x - y }.filter { it > 0 && it < 0.5 } }
        val calls = store.times("key", localStart).size; val chats = connectorsChatsToday(localStart)
        return QuotaModel(now, agyRows, combined, unknown, agyRows.sumOf { it.runsToday }, agyRows.sumOf { it.tokensToday }, QuotaMath.agyMoreRuns(combined, burns),
            agyRows.flatMap { r -> r.buckets.mapNotNull { it.resetAt } + listOfNotNull(r.account.cooldownUntil.takeIf { it > now }) }.minOrNull(),
            rows, totals, rate, runOut, if (chats > 0) calls.toDouble() / chats else null)
    }

    @Volatile var chatsToday: () -> Int = { 0 }
    private fun connectorsChatsToday(since: Long) = runCatching { chatsToday() }.getOrDefault(0)
}
