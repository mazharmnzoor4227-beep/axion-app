package com.mazharansari.axion

import com.mazharansari.axion.work.RunCheckpoint
import com.mazharansari.axion.work.RunCheckpointPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-8: long runs split across worker invocations — checkpoint, resume, and
 * the bound that stops a run from looping forever.
 */
class RunCheckpointTest {
    private fun checkpoint(invocation: Int, partial: String = "first part") =
        RunCheckpoint("s1", "r1", invocation, 1000L, "do the task", partial, 2, 2000L)

    @Test fun maxSegmentsIsThree() {
        assertEquals(3, RunCheckpointPolicy.MAX_SEGMENTS)
    }

    @Test fun notExhaustedBeforeMaxSegments() {
        assertFalse(RunCheckpointPolicy.isExhausted(null))
        assertFalse(RunCheckpointPolicy.isExhausted(checkpoint(1)))
        assertFalse(RunCheckpointPolicy.isExhausted(checkpoint(2)))
    }

    @Test fun exhaustedAtMaxSegments() {
        assertTrue(RunCheckpointPolicy.isExhausted(checkpoint(3)))
        assertTrue(RunCheckpointPolicy.isExhausted(checkpoint(4)))
    }

    @Test fun shouldSplitAtBudget() {
        assertTrue(RunCheckpointPolicy.shouldSplit(9L * 60L * 1000L, 9L * 60L * 1000L))
        assertTrue(RunCheckpointPolicy.shouldSplit(10L * 60L * 1000L, 9L * 60L * 1000L))
        assertFalse(RunCheckpointPolicy.shouldSplit(8L * 60L * 1000L, 9L * 60L * 1000L))
    }

    @Test fun nextSegmentCountsUpAndMergesText() {
        val next = RunCheckpointPolicy.nextSegment(checkpoint(1), "s1", "r1", 1000L, "do the task", "second part", 3, 3000L)
        assertEquals(2, next.invocation)
        assertEquals("r1", next.runId)
        assertTrue(next.partialText.contains("first part"))
        assertTrue(next.partialText.contains("second part"))
        assertEquals(3, next.stepCount)
    }

    @Test fun firstSegmentStartsAtOne() {
        val first = RunCheckpointPolicy.nextSegment(null, "s1", "r9", 1000L, "do the task", "part one", 1, 2000L)
        assertEquals(1, first.invocation)
        assertEquals("part one", first.partialText)
    }

    @Test fun mergePartialCapsLength() {
        val merged = RunCheckpointPolicy.mergePartial("a".repeat(3000), "b".repeat(3000))
        assertEquals(RunCheckpointPolicy.MAX_PARTIAL_CHARS, merged.length)
        assertTrue(merged.endsWith("b".repeat(100)))
    }

    @Test fun mergePartialWithEmptySides() {
        assertEquals("new", RunCheckpointPolicy.mergePartial("", "new"))
        assertEquals("old", RunCheckpointPolicy.mergePartial("old", ""))
    }

    @Test fun continuationPromptKeepsTaskAndResult() {
        val prompt = RunCheckpointPolicy.continuationPrompt("do the task", checkpoint(1))
        assertTrue(prompt.contains("do the task"))
        assertTrue(prompt.contains("first part"))
        assertTrue(prompt.contains("Do NOT repeat work already done"))
        assertTrue(prompt.contains("part 1 of 3"))
    }

    @Test fun continuationPromptHandlesNoTextYet() {
        val prompt = RunCheckpointPolicy.continuationPrompt("do the task", checkpoint(1, partial = ""))
        assertTrue(prompt.contains("no text was produced yet"))
    }
}
