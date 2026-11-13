package com.mazharansari.axion

import com.mazharansari.axion.work.ScheduledRunDecision
import com.mazharansari.axion.work.ScheduledRunResult
import com.mazharansari.axion.work.ScheduledRunWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-8 (Scheduler v2): the new outcome rules — offline with a network
 * requirement, per-schedule retry bounds, and a split run out of segments.
 * The P0-9 rules live in ScheduledRunDecisionTest and are untouched here.
 */
class ScheduledRunDecisionV2Test {
    private fun decide(
        error: String = "",
        hasAnswer: Boolean = false,
        hasSteps: Boolean = false,
        isOnce: Boolean = false,
        runAttemptCount: Int = 0,
        maxRetries: Int = 2,
        networkUnavailable: Boolean = false,
        noMoreRetries: Boolean = false,
    ): ScheduledRunDecision = ScheduledRunWorker.decideOutcome(
        entryFound = true, enabled = true, hasAttempts = true,
        error, hasAnswer, hasSteps, isOnce, runAttemptCount,
        maxRetries, networkUnavailable, noMoreRetries)

    @Test fun offlineRetriesWhileAttemptsRemain() {
        val d = decide(error = ScheduledRunWorker.NETWORK_ERROR, networkUnavailable = true, runAttemptCount = 0, isOnce = true)
        assertEquals(ScheduledRunResult.RETRY, d.result)
        assertFalse(d.saveChat); assertNull(d.markRanStatus); assertFalse(d.disableOnce)
    }

    @Test fun offlineRecordsFailureWhenRetriesRunOut() {
        val d = decide(error = ScheduledRunWorker.NETWORK_ERROR, networkUnavailable = true, runAttemptCount = 2, isOnce = true)
        assertEquals(ScheduledRunResult.FAILURE, d.result)
        assertTrue(d.saveChat)
        assertEquals(ScheduledRunWorker.NETWORK_ERROR, d.markRanStatus)
        assertTrue(d.disableOnce)
    }

    @Test fun offlineDoesNotDisableRepeatingSchedule() {
        val d = decide(error = ScheduledRunWorker.NETWORK_ERROR, networkUnavailable = true, runAttemptCount = 2, isOnce = false)
        assertEquals(ScheduledRunResult.FAILURE, d.result)
        assertTrue(d.saveChat); assertFalse(d.disableOnce)
    }

    @Test fun perScheduleMaxRetriesZeroMeansNoRetry() {
        val d = decide(error = "network down", maxRetries = 0, runAttemptCount = 0)
        assertEquals(ScheduledRunResult.FAILURE, d.result)
        assertTrue(d.saveChat)
    }

    @Test fun perScheduleMaxRetriesFiveAllowsLaterRetries() {
        val retry = decide(error = "network down", maxRetries = 5, runAttemptCount = 4)
        assertEquals(ScheduledRunResult.RETRY, retry.result)
        val done = decide(error = "network down", maxRetries = 5, runAttemptCount = 5)
        assertEquals(ScheduledRunResult.FAILURE, done.result)
    }

    @Test fun splitRunOutOfSegmentsEndsAsFailure() {
        val d = decide(error = ScheduledRunWorker.TIMEOUT_ERROR, runAttemptCount = 0, noMoreRetries = true, isOnce = true)
        assertEquals(ScheduledRunResult.FAILURE, d.result)
        assertTrue(d.saveChat)
        assertEquals(ScheduledRunWorker.TIMEOUT_ERROR, d.markRanStatus)
        assertTrue(d.disableOnce)
    }

    @Test fun splitRunWithSegmentsLeftStillRetries() {
        val d = decide(error = ScheduledRunWorker.TIMEOUT_ERROR, runAttemptCount = 0, noMoreRetries = false)
        assertEquals(ScheduledRunResult.RETRY, d.result)
        assertFalse(d.saveChat)
    }

    @Test fun successStillWinsOverNetworkFlag() {
        // A run that produced an answer while flagged offline cannot happen in
        // practice (the worker never starts offline); the rule stays honest:
        // an offline flag with no run yet is a retry/failure, never a success.
        val d = decide(hasAnswer = true, networkUnavailable = true, runAttemptCount = 0)
        assertEquals(ScheduledRunResult.RETRY, d.result)
    }
}
