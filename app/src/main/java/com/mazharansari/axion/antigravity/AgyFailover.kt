package com.mazharansari.axion.antigravity

/**
 * Ported verbatim from Kodex (vayro runtime/AntigravityFailover.kt, v3.2.50): pure classification of an agy failure
 * for the auto-switch. Plus one Axion addition: [resetHint] reads "resets in 2h 10m" / "retry in 45s" / an ISO time.
 */
object AgyFailover {
    fun isQuotaFailure(reason: String): Boolean {
        val v = reason.lowercase()
        return listOf(
            "quota", "rate limit", "ratelimit", "resource_exhausted", "resource exhausted", "429", "out of credits", "credits",
            "limit exceeded", "exhausted", "too many requests", "60 minute", "minute limit", "hourly limit", "hour limit", "daily limit",
            "day limit", "weekly limit", "week limit", "usage limit", "plan limit", "account limit", "capacity", "overloaded",
            "quota_exceeded", "rate_limit_exceeded", "reached your limit", "hit your limit", "exceeded your limit",
            "exceeded your current quota", "insufficient quota", "free tier limit", "tokens per minute", "requests per minute",
            "requests per day", "requests per week", "billing", "credit balance", "quota limit",
        ).any { it in v }
    }

    fun isAuthFailure(reason: String): Boolean {
        val v = reason.lowercase()
        return listOf(
            "unauthenticated", "401", "invalid authentication credentials", "oauth 2 access token", "login cookie",
            "authentication required", "authentication failed", "auth expired", "sign-in", "not signed in", "token expired",
        ).any { it in v }
    }

    /** Google refusing the account itself ("Eligibility check failed: Your current account is not eligible for Antigravity…"). */
    fun isEligibilityFailure(reason: String): Boolean {
        val v = reason.lowercase()
        return "not eligible" in v || "eligibility" in v
    }

    fun isFailoverEligible(reason: String): Boolean = isQuotaFailure(reason) || isAuthFailure(reason) || isEligibilityFailure(reason)

    /** Epoch ms when the limit lifts, if the error text says so; null otherwise (caller falls back to Kodex's 60 minutes). */
    fun resetHint(reason: String, now: Long): Long? {
        Regex("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2}))").find(reason)?.let { m ->
            runCatching { return java.time.OffsetDateTime.parse(m.value).toInstant().toEpochMilli() }
            runCatching { return java.time.Instant.parse(m.value).toEpochMilli() }
        }
        val m = Regex("(?i)(?:reset|resets|retry|try again|available)\\w*\\s+(?:in|after)\\s+((?:\\d+(?:\\.\\d+)?\\s*(?:d|days?|h|hours?|hrs?|m|min|mins|minutes?|s|sec|secs|seconds?)\\s*,?\\s*(?:and\\s+)?)+)").find(reason)
            ?: return null
        var ms = 0.0
        Regex("(\\d+(?:\\.\\d+)?)\\s*(d|days?|h|hours?|hrs?|m|min|mins|minutes?|s|sec|secs|seconds?)", RegexOption.IGNORE_CASE).findAll(m.groupValues[1]).forEach {
            val n = it.groupValues[1].toDouble()
            ms += n * when (it.groupValues[2].lowercase().first()) { 'd' -> 86_400_000.0; 'h' -> 3_600_000.0; 'm' -> 60_000.0; else -> 1000.0 }
        }
        return if (ms > 0) now + ms.toLong() else null
    }
}
