package com.mazharansari.axion.quota

/** The arithmetic behind the Quota screen (spec §5). Pure and unit-tested; nothing here invents a number. */
object QuotaMath {
    data class Num(val value: Double?, val tag: Tag)
    data class Day(val limit: Num, val used: Num, val left: Num, val resetAt: Long?,
                   /** Counted tokens today for this key/model. null + UNKNOWN when the provider never reported usage (P1-10): never 0-as-a-count. */
                   val tokens: Num = Num(null, Tag.UNKNOWN))

    /**
     * One key/model for today. limit: LIVE header ‖ FROM 429 / user override ‖ PUBLISHED ‖ unknown.
     * used: LIVE (limit − remaining) when the header is newer than our last counted call, else COUNTED since the provider's day start.
     * A key cooling down after a limit-hit has 0 left until its reset.
     */
    fun day(live: Reading?, liveAt: Long, lastCallAt: Long, override: Double?, overrideIs429: Boolean, published: Double?, counted: Int, coolingUntil: Long, now: Long, providerReset: Long?,
            countedTokens: Long = 0, hasTokens: Boolean = false): Day {
        val limit = when {
            live?.limit != null -> Num(live.limit, Tag.LIVE)
            override != null -> Num(override, if (overrideIs429) Tag.FROM429 else Tag.PUBLISHED)
            published != null -> Num(published, Tag.PUBLISHED)
            else -> Num(null, Tag.UNKNOWN)
        }
        val liveFresh = live?.remaining != null && liveAt >= lastCallAt
        val used = if (liveFresh && live!!.limit != null) Num(live.limit!! - live.remaining!!, Tag.LIVE) else Num(counted.toDouble(), Tag.COUNTED)
        var left = when {
            liveFresh -> Num(live!!.remaining, Tag.LIVE)
            limit.value != null -> Num((limit.value - (used.value ?: 0.0)).coerceAtLeast(0.0), Tag.ESTIMATE)
            else -> Num(null, Tag.UNKNOWN)
        }
        if (coolingUntil > now) left = Num(0.0, left.tag.takeIf { it != Tag.UNKNOWN } ?: Tag.FROM429)
        return Day(limit, used, left, if (coolingUntil > now) coolingUntil else live?.resetAt ?: providerReset,
            if (hasTokens) Num(countedTokens.toDouble(), Tag.COUNTED) else Num(null, Tag.UNKNOWN))
    }

    data class Totals(val limit: Double, val used: Double, val left: Double, val counted: Int, val unknown: Int,
                      /** Counted tokens across the grouped keys (0 + UNKNOWN when no provider reported usage). */
                      val tokens: Long = 0, val tokensTag: Tag = Tag.UNKNOWN,
                      /** Source of each total: the weakest (least direct) source among the groups that feed it — a total that mixes LIVE and ESTIMATE is ESTIMATE. */
                      val limitTag: Tag = Tag.UNKNOWN, val usedTag: Tag = Tag.UNKNOWN, val leftTag: Tag = Tag.UNKNOWN)

    /**
     * Section total over keys with a known limit. Keys flagged as sharing one quota are counted once (by [shareGroup]):
     * P1-10 (ME-3) — two Gemini keys on one Google project share one bucket, so the group contributes its limit once and
     * its used is the counted calls across the group (or the freshest LIVE used, which already covers the whole bucket),
     * never the sum of two limits. A one-key group keeps its own Day exactly (cooling included).
     */
    fun totals(days: List<Pair<String, Day>>, shareGroup: (String) -> String = { it }): Totals {
        val groups = days.filter { it.second.limit.value != null }.groupBy { shareGroup(it.first) }
        var limit = 0.0; var used = 0.0; var left = 0.0; var tokens = 0L
        var tokensTag = Tag.UNKNOWN
        var limitTag: Tag? = null; var usedTag: Tag? = null; var leftTag: Tag? = null
        fun weaker(cur: Tag?, b: Tag) = if (cur == null || b.ordinal >= cur.ordinal) b else cur
        for ((_, members) in groups) {
            val ds = members.map { it.second }
            if (ds.size == 1) {
                val d = ds[0]
                limit += d.limit.value!!; used += d.used.value ?: 0.0; left += d.left.value ?: 0.0
                limitTag = weaker(limitTag, d.limit.tag); usedTag = weaker(usedTag, d.used.tag); leftTag = weaker(leftTag, d.left.tag)
            } else {
                val lim = ds.firstNotNullOfOrNull { it.limit.value }!!
                val limTag = ds.first { it.limit.value != null }.limit.tag
                val liveUsed = ds.filter { it.used.tag == Tag.LIVE }.mapNotNull { it.used.value }.maxOrNull()
                val countedSum = ds.filter { it.used.tag == Tag.COUNTED }.sumOf { it.used.value ?: 0.0 }
                val gUsed = maxOf(liveUsed ?: 0.0, countedSum)
                val gUsedTag = if (liveUsed != null && liveUsed >= countedSum) Tag.LIVE else Tag.COUNTED
                val gLeft = (lim - gUsed).coerceAtLeast(0.0)
                val gLeftTag = if (gUsedTag == Tag.LIVE && ds.all { it.left.tag == Tag.LIVE }) Tag.LIVE else Tag.ESTIMATE
                limit += lim; used += gUsed; left += gLeft
                limitTag = weaker(limitTag, limTag); usedTag = weaker(usedTag, gUsedTag); leftTag = weaker(leftTag, gLeftTag)
            }
            val gTokens = ds.mapNotNull { it.tokens.value }
            if (gTokens.isNotEmpty()) { tokens += gTokens.sum().toLong(); tokensTag = Tag.COUNTED }
        }
        return Totals(limit, used, left, groups.size, days.count { it.second.limit.value == null }, tokens, tokensTag,
            limitTag ?: Tag.UNKNOWN, usedTag ?: Tag.UNKNOWN, leftTag ?: Tag.UNKNOWN)
    }

    /** Requests per hour: EWMA over 10-minute buckets of the last hour (α 0.3); fewer than 3 calls → today's average; nothing today → null. */
    fun ratePerHour(callTimes: List<Long>, now: Long, dayStart: Long): Double? {
        val today = callTimes.filter { it in dayStart..now }
        if (today.size < 3) return null
        val lastHour = today.filter { it > now - 3_600_000L }
        if (lastHour.size < 3) {
            val hours = ((now - today.min()).coerceAtLeast(600_000L)) / 3_600_000.0
            return today.size / hours
        }
        var ewma: Double? = null
        for (b in 5 downTo 0) {
            val from = now - (b + 1) * 600_000L; val to = now - b * 600_000L
            val perHour = lastHour.count { it in from until to } * 6.0
            ewma = if (ewma == null) perHour else 0.3 * perHour + 0.7 * ewma
        }
        return ewma
    }

    sealed interface RunOut {
        data object NotEnoughData : RunOut
        data class Before(val ms: Long) : RunOut
        data class LastsPastReset(val resetAt: Long?) : RunOut
    }

    fun runOut(left: Double?, ratePerHour: Double?, resetAt: Long?, now: Long): RunOut {
        if (left == null || ratePerHour == null || ratePerHour <= 0) return RunOut.NotEnoughData
        val ms = (left / ratePerHour * 3_600_000).toLong()
        return if (resetAt != null && now + ms >= resetAt) RunOut.LastsPastReset(resetAt) else RunOut.Before(ms)
    }

    /** Antigravity: "2.3 of 4 accounts full" = Σ remaining fraction (unknown buckets don't count, and are reported). */
    fun agyCombined(fractions: List<Double?>): Pair<Double, Int> = fractions.filterNotNull().sum() to fractions.count { it == null }

    /** ≈ N more runs like recent ones: median fraction burned per run; hidden with fewer than 3 measured runs. */
    fun agyMoreRuns(sumRemaining: Double, perRunBurn: List<Double>): Double? {
        val b = perRunBurn.filter { it > 0 }.sorted()
        if (b.size < 3) return null
        val median = if (b.size % 2 == 1) b[b.size / 2] else (b[b.size / 2 - 1] + b[b.size / 2]) / 2
        return sumRemaining / median
    }

    /** Fraction used today from successive probes; a jump up (a refresh) is not counted as negative use. */
    fun agyUsedFromProbes(probes: List<Double>): Double = probes.zipWithNext { a, b -> (a - b).coerceAtLeast(0.0) }.sum()

    fun shortDuration(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(1)
        return when { m < 60 -> "${m} min"; m < 48 * 60 -> "${m / 60} h ${m % 60} min".replace(" 0 min", ""); else -> "${m / 1440} days" }
    }
}
