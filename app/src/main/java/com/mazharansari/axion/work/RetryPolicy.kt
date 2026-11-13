package com.mazharansari.axion.work

/**
 * P1-8 — bounded retries with exponential backoff, in one place so the worker,
 * the retry record and the tests all agree on the same numbers.
 *
 * WorkManager already backs off exponentially from its own minimum (10 minutes);
 * these are the delays Axion itself promises when it schedules a retry: the
 * first retry waits 10 minutes, the next 20, then 40, capped at 2 hours. The
 * retry count stays bounded ([ScheduledRunWorker.MAX_ATTEMPTS] / the schedule's
 * own maxRetries), so a failing schedule never retries forever.
 */
object RetryPolicy {
    const val BASE_DELAY_MS = 10L * 60L * 1000L
    const val MAX_DELAY_MS = 2L * 60L * 60L * 1000L

    /**
     * Delay before retry number [attempt] (0 = the first retry after the first
     * failure): base * 2^attempt, capped at [MAX_DELAY_MS]. Never negative.
     */
    fun delayMs(attempt: Int): Long {
        val a = attempt.coerceAtLeast(0)
        var delay = BASE_DELAY_MS
        repeat(a) { delay = (delay * 2L).coerceAtMost(MAX_DELAY_MS) }
        return delay.coerceAtMost(MAX_DELAY_MS)
    }

    /** Whole minutes of [delayMs], for the status line the user sees. */
    fun delayMinutes(attempt: Int): Long = delayMs(attempt) / 60_000L

    /** "about 10 minutes" / "about 2 hours" — the wording shown with a retry record. */
    fun describe(attempt: Int): String {
        val minutes = delayMinutes(attempt)
        return if (minutes < 60) "about $minutes minutes" else "about ${minutes / 60} hours"
    }
}
