package com.mazharansari.axion.quota

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Where a number came from — every number on the Quota screen carries one (spec §0). */
enum class Tag(val label: String) { LIVE("LIVE"), FROM429("FROM 429"), COUNTED("COUNTED"), PUBLISHED("PUBLISHED"), ESTIMATE("ESTIMATE"), UNKNOWN("UNKNOWN") }

/** One limit window as the provider (or we) know it. unit: "req" | "tok" | "fraction". */
data class Reading(
    val window: String,          // "minute" | "day" | "5h" | "week" | "month"
    val unit: String,
    val limit: Double?,
    val remaining: Double?,
    val resetAt: Long?,
    val tag: Tag,
    val label: String = "",
    val model: String? = null,
    val raw: String = "",
)

object Durations {
    /** "2m59.56s", "1h2m3s", "7.66s", "450ms", "30" (seconds) → ms. */
    fun parse(s: String?): Long? {
        val v = s?.trim()?.lowercase() ?: return null
        if (v.isEmpty()) return null
        v.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        var ms = 0.0; var any = false
        Regex("(\\d+(?:\\.\\d+)?)(ms|h|m|s|d)").findAll(v).forEach {
            any = true
            val n = it.groupValues[1].toDouble()
            ms += n * when (it.groupValues[2]) { "ms" -> 1.0; "s" -> 1000.0; "m" -> 60_000.0; "h" -> 3_600_000.0; else -> 86_400_000.0 }
        }
        return if (any) ms.toLong() else null
    }
}

/**
 * Rate-limit header sniffer (spec §3.3). Groq's unsuffixed x-ratelimit-*-requests is PER DAY and *-tokens per minute; on every
 * other provider unsuffixed means per minute; "-day"/"-minute" suffixes say so themselves (SambaNova, Cerebras).
 */
object RateHeaders {
    fun interesting(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("x-ratelimit-") || n.startsWith("ratelimit-") || n.startsWith("anthropic-ratelimit-") || n == "retry-after"
    }

    fun parse(providerId: String, headers: Map<String, String>, now: Long): List<Reading> {
        val h = headers.mapKeys { it.key.lowercase() }.filterKeys { interesting(it) }
        if (h.isEmpty()) return emptyList()
        val raw = h.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        data class Acc(var limit: Double? = null, var remaining: Double? = null, var reset: Long? = null)
        val groups = linkedMapOf<Pair<String, String>, Acc>()   // (window, unit) → numbers
        for ((k, v) in h) {
            val name = k.removePrefix("x-ratelimit-").removePrefix("anthropic-ratelimit-").removePrefix("ratelimit-")
            val field = when { name.startsWith("limit") -> "limit"; name.startsWith("remaining") -> "remaining"; name.startsWith("reset") -> "reset"; else -> continue }
            val rest = name.removePrefix(field).trimStart('-')     // "requests", "requests-day", "tokens-minute", ""
            val unit = if (rest.startsWith("token") || rest.contains("input-tokens") || rest.contains("output-tokens")) "tok" else "req"
            val window = when {
                rest.endsWith("-day") || rest.endsWith("day") -> "day"
                rest.endsWith("-minute") || rest.endsWith("minute") -> "minute"
                rest.endsWith("-hour") -> "hour"
                providerId == "groq" && unit == "req" -> "day"
                else -> "minute"
            }
            val acc = groups.getOrPut(window to unit) { Acc() }
            when (field) {
                "limit" -> acc.limit = v.trim().toDoubleOrNull()
                "remaining" -> acc.remaining = v.trim().toDoubleOrNull()
                "reset" -> acc.reset = resetToEpoch(v, now)
            }
        }
        return groups.map { (key, a) -> Reading(key.first, key.second, a.limit, a.remaining, a.reset, Tag.LIVE, raw = raw) }
            .filter { it.limit != null || it.remaining != null }
    }

    /** "2m59.56s" (Groq) | "12" seconds | an epoch in s or ms (OpenRouter's X-RateLimit-Reset) | an ISO time (Anthropic). */
    fun resetToEpoch(v: String, now: Long): Long? {
        val t = v.trim()
        t.toLongOrNull()?.let { n -> return when { n > 1_000_000_000_000L -> n; n > 1_000_000_000L -> n * 1000; else -> now + n * 1000 } }
        runCatching { return Instant.parse(t).toEpochMilli() }
        return Durations.parse(t)?.let { now + it }
    }
}

/** Gemini's 429 body (verified live 1 Nov 2026: gemini-3.5-flash-lite free = 500 RPD, retryDelay 7739s). */
object Gemini429 {
    data class Info(val limit: Double?, val quotaId: String?, val model: String?, val window: String, val retryMs: Long?)

    fun parse(body: String): Info? {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
        val err = (root as? JsonObject)?.get("error")?.jsonObject ?: (root as? JsonArray)?.firstOrNull()?.jsonObject?.get("error")?.jsonObject ?: return null
        val details = err["details"]?.jsonArray ?: return null
        var limit: Double? = null; var qid: String? = null; var model: String? = null; var retry: Long? = null
        for (d in details) {
            val o = d.jsonObject; val type = o["@type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (type.endsWith("QuotaFailure")) {
                // Several violations can come back (per-minute and per-day); the per-day one is the one that matters for "today".
                val vs = o["violations"]?.jsonArray.orEmpty().map { it.jsonObject }
                val v = vs.firstOrNull { it["quotaId"]?.jsonPrimitive?.contentOrNull?.contains("PerDay") == true } ?: vs.firstOrNull()
                if (v != null) {
                    limit = v["quotaValue"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    qid = v["quotaId"]?.jsonPrimitive?.contentOrNull
                    model = v["quotaDimensions"]?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull
                }
            }
            if (type.endsWith("RetryInfo")) retry = Durations.parse(o["retryDelay"]?.jsonPrimitive?.contentOrNull)
        }
        if (limit == null && qid == null && retry == null) return null
        val window = when { qid == null -> "day"; "PerDay" in qid -> "day"; "PerMinute" in qid -> "minute"; "PerHour" in qid -> "hour"; else -> "day" }
        return Info(limit, qid, model, window, retry)
    }
}

/** OpenRouter GET /api/v1/key → today's free-model requests (UTC day). */
object OpenRouterKey {
    data class Info(val used: Double?, val limit: Double?, val remaining: Double?, val creditsLeft: Double?, val isFreeTier: Boolean?)
    fun parse(body: String): Info? {
        val d = runCatching { Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject }.getOrNull() ?: return null
        val f = d["free_model_daily_requests"] as? JsonObject
        fun JsonObject?.num(k: String) = (this?.get(k) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        return Info(f.num("used"), f.num("limit"), f.num("remaining") ?: f.num("limit")?.let { l -> f.num("used")?.let { l - it } },
            d.num("limit_remaining"), (d["is_free_tier"] as? JsonPrimitive)?.booleanOrNull)
    }
}

/** P1-10 (ME-4): real token counts, parsed from the providers' own responses.
 * OpenAI-compatible chat completions return `usage: {prompt_tokens, completion_tokens, total_tokens}` — in the
 * single-JSON answer, and in the last SSE chunk when the request asked for it (`stream_options.include_usage`).
 * Gemini returns `usageMetadata: {promptTokenCount, candidatesTokenCount, totalTokenCount}` in the last
 * streamGenerateContent chunk (and in the single-JSON answer). Both are counted, never estimated: when neither
 * object is present the result is null and the Quota screen says so (UNKNOWN), it never shows 0 as a count. */
data class TokenUsage(val prompt: Long, val completion: Long) { val total: Long get() = prompt + completion }

object LlmUsage {
    private fun JsonObject.longNum(vararg keys: String): Long? {
        for (k in keys) {
            val p = this[k] as? JsonPrimitive ?: continue
            p.longOrNull?.let { return it }
            p.contentOrNull?.toLongOrNull()?.let { return it }
        }
        return null
    }

    fun parseOpenAi(obj: JsonObject): TokenUsage? {
        val u = obj["usage"]?.jsonObject ?: return null
        val prompt = u.longNum("prompt_tokens") ?: 0L
        val completion = u.longNum("completion_tokens") ?: 0L
        val total = u.longNum("total_tokens") ?: 0L
        if (prompt == 0L && completion == 0L && total == 0L) return null
        // Some gateways send only total_tokens: attribute it all to the prompt rather than inventing a split.
        return if (prompt == 0L && completion == 0L) TokenUsage(total, 0L) else TokenUsage(prompt, completion)
    }

    fun parseGemini(obj: JsonObject): TokenUsage? {
        val u = obj["usageMetadata"]?.jsonObject ?: return null
        val prompt = u.longNum("promptTokenCount") ?: 0L
        val completion = u.longNum("candidatesTokenCount") ?: 0L
        val total = u.longNum("totalTokenCount") ?: 0L
        if (prompt == 0L && completion == 0L && total == 0L) return null
        return if (prompt == 0L && completion == 0L) TokenUsage(total, 0L) else TokenUsage(prompt, completion)
    }
}

/**
 * agy `/usage` (alias /quota) parser. The real shape needs a signed-in account and has NOT been seen yet (spec §3.2), so this walks
 * the JSON generically: any object carrying remaining_percentage | remaining_fraction | used_percentage is a bucket. Falls back to
 * agy's tab-separated text. A bucket with no number is reported with remaining = null and shown as "—", never 0 %.
 */
object AgyUsageParser {
    data class Bucket(val label: String, val remainingFraction: Double?, val resetAt: Long?, val window: String, val disabled: Boolean = false, val models: List<String> = emptyList())
    data class Result(val buckets: List<Bucket>, val tier: String?, val credits: Double?, val parsed: Boolean)

    fun parse(text: String, now: Long): Result {
        val start = text.indexOf('{').takeIf { it >= 0 }
        val el = start?.let { s -> runCatching { Json.parseToJsonElement(text.substring(s, text.lastIndexOf('}') + 1)) }.getOrNull() }
        if (el != null) {
            val out = mutableListOf<Bucket>(); var tier: String? = null; var credits: Double? = null
            fun walk(e: JsonElement, ctxLabel: String) {
                when (e) {
                    is JsonObject -> {
                        val label = listOf("label", "display_name", "displayName", "group", "name", "model").firstNotNullOfOrNull { (e[it] as? JsonPrimitive)?.contentOrNull } ?: ctxLabel
                        (e["tier"] as? JsonPrimitive)?.contentOrNull?.let { tier = it } ; (e["plan"] as? JsonPrimitive)?.contentOrNull?.let { if (tier == null) tier = it }
                        listOf("remaining_credits", "credits").forEach { k -> (e[k] as? JsonPrimitive)?.doubleOrNull?.let { credits = it } }
                        fun d(k: String) = (e[k] as? JsonPrimitive)?.doubleOrNull
                        val keys = setOf("remaining_percentage", "remainingPercentage", "remaining_fraction", "remainingFraction", "used_percentage", "usedPercentage")
                        if (e.keys.any { it in keys }) {
                            val frac = d("remaining_fraction") ?: d("remainingFraction") ?: d("remaining_percentage")?.div(100) ?: d("remainingPercentage")?.div(100)
                                ?: d("used_percentage")?.let { 1 - it / 100 } ?: d("usedPercentage")?.let { 1 - it / 100 }
                            val reset = (e["reset_time"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                                ?: (e["resetTime"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                                ?: d("reset_in_seconds")?.let { now + (it * 1000).toLong() }
                            val models = (e["models"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }.orEmpty()
                            val disabled = (e["disabled"] as? JsonPrimitive)?.booleanOrNull == true
                            out += Bucket(if (label != ctxLabel && ctxLabel.isNotBlank()) "$ctxLabel · $label" else label, frac?.coerceIn(0.0, 1.0), reset, windowOf(label, reset, now), disabled, models)
                        } else if (e.keys.any { it == "remaining_percentage" }) Unit
                        e.forEach { (k, v) -> if (v is JsonObject || v is JsonArray) walk(v, if (k in setOf("groups", "buckets", "models", "quota", "data")) label else label) }
                    }
                    is JsonArray -> e.forEach { walk(it, ctxLabel) }
                    else -> {}
                }
            }
            walk(el, "")
            if (out.isNotEmpty() || tier != null) return Result(out.distinctBy { it.label + it.resetAt }, tier, credits, true)
        }
        // Text / TSV mode: one record per line; pick a percentage and an ISO time per line.
        val rows = text.lines().mapNotNull { line ->
            val pct = Regex("(\\d{1,3}(?:\\.\\d+)?)\\s*%").find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
            val iso = Regex("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z").find(line)?.value
            val reset = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            val cols = line.split('\t').map { it.trim() }.filter { it.isNotBlank() }
            val label = cols.firstOrNull { c -> c.none { it.isDigit() } } ?: cols.firstOrNull() ?: "Quota"
            val usedMeant = line.contains("used", true) && !line.contains("remaining", true)
            Bucket(label, (if (usedMeant) 100 - pct else pct) / 100.0, reset, windowOf(line, reset, now))
        }
        return Result(rows, null, null, rows.isNotEmpty())
    }

    private fun windowOf(label: String, reset: Long?, now: Long): String = when {
        label.contains("week", true) -> "week"
        label.contains("5h", true) || label.contains("5-hour", true) || label.contains("five", true) -> "5h"
        reset != null && reset - now in 1..(5 * 3_600_000L + 60_000) -> "5h"
        reset != null && reset - now > 5 * 3_600_000L -> "week"
        else -> ""
    }
}

/** Each provider's "today" starts at its own midnight (spec §3.3). */
object ProviderDay {
    fun zoneOf(providerId: String): ZoneId = when (providerId) {
        "gemini" -> ZoneId.of("America/Los_Angeles")
        "openrouter", "sambanova", "cerebras" -> ZoneOffset.UTC
        else -> ZoneId.systemDefault()
    }
    fun start(providerId: String, now: Long): Long { val z = zoneOf(providerId); return LocalDate.ofInstant(Instant.ofEpochMilli(now), z).atStartOfDay(z).toInstant().toEpochMilli() }
    fun nextReset(providerId: String, now: Long): Long { val z = zoneOf(providerId); return LocalDate.ofInstant(Instant.ofEpochMilli(now), z).plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() }
}
