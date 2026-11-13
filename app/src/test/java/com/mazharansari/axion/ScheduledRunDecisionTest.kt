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
 * P0-9 (HI-7): the scheduled worker's outcome rule, tested without Android.
 * [ScheduledRunWorker.decideOutcome] is pure: every doWork() path maps to one
 * of these decisions, so a run that produced nothing can never be a success.
 */
class ScheduledRunDecisionTest {
    private fun decide(
        entryFound: Boolean = true,
        enabled: Boolean = true,
        hasAttempts: Boolean = true,
        error: String = "",
        hasAnswer: Boolean = true,
        hasSteps: Boolean = false,
        isOnce: Boolean = false,
        runAttemptCount: Int = 0,
    ): ScheduledRunDecision = ScheduledRunWorker.decideOutcome(
        entryFound, enabled, hasAttempts, error, hasAnswer, hasSteps, isOnce, runAttemptCount)

    @Test fun missingEntryIsTerminalFailureWithNoRecord() {
        val d = decide(entryFound = false)
        assertEquals(ScheduledRunResult.FAILURE, d.result)
        assertFalse(d.saveChat); assertNull(d.markRanStatus); assertFalse(d.disableOnce)
    }

    @Test fun disabledEntryIsTerminalFailureWithNoRecord() {
        val d = decide(enabled = false)
        assertEquals(ScheduledRunResult.FAILURE, d.result)
        assertFalse(d.saveChat); assertNull(d.markRanStatus); assertFalse(d.disableOnce)
    }

    @Test fun noKeyRecordsFailureOnceAndDisablesOnce() {
        val d = decide(hasAttempts = false, hasAnswer = false,
            error = "No AI key is connected, so the scheduled task could not run. Add a free key in Connectors.",
            isOnce = true)
        assertEquals(ScheduledRunResult.FAILURE, d.result)
        assertTrue(d.saveChat)
        assertTrue(d.markRanStatus!!.contains("No AI key"))
        assertTrue(d.disableOnce)
    }

    @Test fun noKeyDoesNotDisableRepeatingSchedule() {
        val d = decide(hasAttempts = false, hasAnswer = false, error = "no key", isOnce = false)
        assertEquals(ScheduledRunResult.FAILURE, d.result)
        assertTrue(d.saveChat); assertFalse(d.disableOnce)
    }

    @Test fun agentErrorRetriesOnFirstAttemptWithNoRecord() {
        val d = decide(error = "network down", hasAnswer = false, runAttemptCount = 0, isOnce = true)
        assertEquals(ScheduledRunResult.RETRY, d.result)
        assertFalse(d.saveChat); assertNull(d.markRanStatus); assertFalse(d.disableOnce)
    }

    @Test fun agentErrorRetriesOnSecondAttempt() {
        val d = decide(error = "network down", hasAnswer = false, runAttemptCount = 1)
        assertEquals(ScheduledRunResult.RETRY, d.result)
        assertFalse(d.saveChat); assertFalse(d.disableOnce)
    }

    @Test fun agentErrorRecordsFailureWhenRetriesRunOut() {
        val d = decide(error = "network down", hasAnswer = false, runAttemptCount = 2, isOnce = true)
        assertEquals(ScheduledRunResult.FAILURE, d.result)
        assertTrue(d.saveChat)
        assertEquals("network down", d.markRanStatus)
        assertTrue(d.disableOnce)
    }

    @Test fun emptyRunRetriesThenFails() {
        val retry = decide(hasAnswer = false, hasSteps = false, runAttemptCount = 0)
        assertEquals(ScheduledRunResult.RETRY, retry.result)
        assertFalse(retry.saveChat)
        val done = decide(hasAnswer = false, hasSteps = false, runAttemptCount = 2, isOnce = true)
        assertEquals(ScheduledRunResult.FAILURE, done.result)
        assertTrue(done.saveChat)
        assertEquals(ScheduledRunWorker.EMPTY_RESULT_ERROR, done.markRanStatus)
        assertTrue(done.disableOnce)
    }

    @Test fun successWithAnswerRecordsDoneAndDisablesOnce() {
        val d = decide(hasAnswer = true, isOnce = true)
        assertEquals(ScheduledRunResult.SUCCESS, d.result)
        assertTrue(d.saveChat)
        assertEquals("done", d.markRanStatus)
        assertTrue(d.disableOnce)
    }

    @Test fun successWithStepsOnlyCountsAsSuccess() {
        // A tool-only run with no final text still did real work.
        val d = decide(hasAnswer = false, hasSteps = true)
        assertEquals(ScheduledRunResult.SUCCESS, d.result)
        assertTrue(d.saveChat)
        assertEquals("done", d.markRanStatus)
    }

    @Test fun successDoesNotDisableRepeatingSchedule() {
        val d = decide(hasAnswer = true, isOnce = false)
        assertEquals(ScheduledRunResult.SUCCESS, d.result)
        assertFalse(d.disableOnce)
    }

    @Test fun errorBeatsSteps() {
        // An agent error is a failure even when some steps were recorded;
        // the steps are saved in the failure chat on the final attempt.
        val retry = decide(error = "timed out", hasAnswer = false, hasSteps = true, runAttemptCount = 0)
        assertEquals(ScheduledRunResult.RETRY, retry.result)
        val done = decide(error = "timed out", hasAnswer = false, hasSteps = true, runAttemptCount = 2)
        assertEquals(ScheduledRunResult.FAILURE, done.result)
        assertEquals("timed out", done.markRanStatus)
    }
}
