package com.mazharansari.axion.quota

/** The arithmetic behind the Quota screen (spec §5). Pure and unit-tested; nothing here invents a number. */
object QuotaMath {
    data class Num(val value: Double?, val tag: Tag)
    data class Day(val limit: Num, val used: Num, val left: Num, val resetAt: Long?)

    /**
     * One key/model for today. limit: LIVE header ‖ FROM 429 / user override ‖ PUBLISHED ‖ unknown.
     * used: LIVE (limit − remaining) when the header is newer than our last counted call, else COUNTED since the provider's day start.
     * A key cooling down after a limit-hit has 0 left until its reset.
     */
    fun day(live: Reading?, liveAt: Long, lastCallAt: Long, override: Double?, overrideIs429: Boolean, published: Double?, counted: Int, coolingUntil: Long, now: Long, providerReset: Long?): Day {
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
        return Day(limit, used, left, if (coolingUntil > now) coolingUntil else live?.resetAt ?: providerReset)
    }

    data class Totals(val limit: Double, val used: Double, val left: Double, val counted: Int, val unknown: Int)

    /** Section total over keys with a known limit; keys flagged as sharing one quota are counted once (by [shareGroup]). */
    fun totals(days: List<Pair<String, Day>>, shareGroup: (String) -> String = { it }): Totals {
        val known = days.filter { it.second.limit.value != null }.groupBy { shareGroup(it.first) }.map { it.value.first().second }
        return Totals(known.sumOf { it.limit.value!! }, known.sumOf { it.used.value ?: 0.0 }, known.sumOf { it.left.value ?: 0.0 }, known.size, days.count { it.second.limit.value == null })
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
