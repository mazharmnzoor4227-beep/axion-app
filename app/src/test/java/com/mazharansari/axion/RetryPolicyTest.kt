package com.mazharansari.axion

import com.mazharansari.axion.work.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-8: bounded retries with exponential backoff — the delays the worker and the retry record agree on. */
class RetryPolicyTest {
    @Test fun firstRetryWaitsTenMinutes() {
        assertEquals(10L * 60L * 1000L, RetryPolicy.delayMs(0))
        assertEquals(10L, RetryPolicy.delayMinutes(0))
    }

    @Test fun backoffDoublesEachRetry() {
        assertEquals(20L, RetryPolicy.delayMinutes(1))
        assertEquals(40L, RetryPolicy.delayMinutes(2))
        assertEquals(80L, RetryPolicy.delayMinutes(3))
    }

    @Test fun delayIsCappedAtTwoHours() {
        assertEquals(2L * 60L * 60L * 1000L, RetryPolicy.delayMs(10))
        assertEquals(120L, RetryPolicy.delayMinutes(10))
    }

    @Test fun negativeAttemptCountsAsFirstRetry() {
        assertEquals(RetryPolicy.delayMs(0), RetryPolicy.delayMs(-1))
    }

    @Test fun descriptionIsPlainEnglish() {
        assertEquals("about 10 minutes", RetryPolicy.describe(0))
        assertEquals("about 40 minutes", RetryPolicy.describe(2))
        assertEquals("about 2 hours", RetryPolicy.describe(4))
        assertTrue(RetryPolicy.describe(0).contains("minutes"))
    }
}
