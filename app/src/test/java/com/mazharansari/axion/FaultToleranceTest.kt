package com.mazharansari.axion.agent

import com.mazharansari.axion.agent.FaultPolicy.Kind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FaultToleranceTest {
    private fun ok(t: String) = ToolOutput(t)
    private fun bad(t: String, code: Int? = null) = ToolOutput(t, isError = true, exitCode = code)

    @Test fun successIsPassedThrough() = runBlocking {
        val o = FaultPolicy(retryPauseMs = 1).execute("t", "{}", 1000) { ok("fine") }
        assertEquals(Kind.OK, o.kind); assertEquals(1, o.attempts); assertEquals("fine", o.forModel); assertFalse(o.mustReport)
    }
    @Test fun failsOnceThenRetrySucceeds() = runBlocking {
        var n = 0; val retried = mutableListOf<String>()
        val o = FaultPolicy(retryPauseMs = 1).execute("t", "{}", 1000, { retried += it }) { if (++n == 1) bad("network blip") else ok("worked") }
        assertEquals(Kind.RETRIED_OK, o.kind); assertEquals(2, n); assertEquals(listOf("Retrying…"), retried); assertEquals("worked", o.forModel)
    }
    @Test fun failsTwiceThenModelIsToldToTryAnAlternative() = runBlocking {
        var n = 0
        val o = FaultPolicy(retryPauseMs = 1).execute("web_search", "{q}", 1000) { n++; bad("503 upstream") }
        assertEquals(Kind.FAILED, o.kind); assertEquals(2, n); assertTrue(o.forModel.contains("DIFFERENT approach")); assertTrue(o.forModel.contains("alternative 1 of 2")); assertFalse(o.mustReport)
    }
    @Test fun afterAlternativesRunOutTheFailureIsReportedWithItsReason() = runBlocking {
        val p = FaultPolicy(maxAlternatives = 1, retryPauseMs = 1)
        val a = p.execute("a", "{1}", 1000) { bad("boom A") }; assertFalse(a.mustReport)
        val b = p.execute("b", "{2}", 1000) { bad("boom B") }
        assertTrue(b.mustReport); assertTrue(b.forModel.contains("STOP calling tools")); assertTrue(b.forModel.contains("boom B"))
    }
    @Test fun successResetsTheFailureStreak() = runBlocking {
        val p = FaultPolicy(retryPauseMs = 1)
        p.execute("a", "{1}", 1000) { bad("x") }; assertEquals(1, p.consecutiveFailures)
        p.execute("b", "{2}", 1000) { ok("y") }; assertEquals(0, p.consecutiveFailures)
    }
    @Test fun sameFailingCallIsNeverRunAThirdTime() = runBlocking {
        var n = 0; val p = FaultPolicy(retryPauseMs = 1)
        p.execute("t", "{same}", 1000) { n++; bad("nope") }
        val o = p.execute("t", "{same}", 1000) { n++; ok("should not run") }
        assertEquals(2, n); assertEquals(Kind.SKIPPED_REPEAT, o.kind); assertTrue(o.forModel.contains("Do NOT repeat"))
    }
    @Test fun aHangingToolTimesOutInsteadOfHanging() = runBlocking {
        val t0 = System.currentTimeMillis()
        val o = FaultPolicy(retryPauseMs = 1).execute("slow", "{}", 150) { delay(60_000); ok("never") }
        assertTrue(System.currentTimeMillis() - t0 < 3000); assertEquals(Kind.TIMED_OUT, o.kind); assertTrue(o.forModel.contains("Timed out"))
    }
    @Test fun exceptionsBecomeFailuresNotCrashes() = runBlocking {
        val o = FaultPolicy(retryPauseMs = 1).execute("t", "{}", 1000) { throw IllegalStateException("kaboom") }
        assertEquals(Kind.FAILED, o.kind); assertTrue(o.forModel.contains("kaboom"))
    }
    @Test fun cancellationStillCancels() {
        val r = runCatching { runBlocking { kotlinx.coroutines.withTimeout(100) { FaultPolicy(retryPauseMs = 1).execute("t", "{}", 60_000) { delay(60_000); ok("x") } } } }
        assertTrue(r.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
    }
    @Test fun commandThatRanAndExitedNonZeroIsNotBlindlyRerun() = runBlocking {
        var n = 0
        val o = FaultPolicy(retryPauseMs = 1).execute("run_command", "{ls}", 1000) { n++; bad("[exit code 2]\nls: cannot access", 2) }
        assertEquals(1, n); assertEquals(Kind.FAILED, o.kind); assertTrue(o.forModel.contains("exit 2"))
    }
    @Test fun toolCallBudgetStopsRunawayLoops() = runBlocking {
        val p = FaultPolicy(maxToolCalls = 3, retryPauseMs = 1); repeat(3) { i -> p.execute("t", "{$i}", 1000) { ok("a") } }
        val o = p.execute("t", "{9}", 1000) { ok("b") }; assertEquals(Kind.BUDGET, o.kind); assertTrue(o.mustReport)
    }
    @Test fun safetyRefusalsGetNoAlternativePressureAndNoFailureStreak() = runBlocking {
        // exit 126 (a safety refusal) is final, not a fault
        val p = FaultPolicy(retryPauseMs = 1); var n = 0
        val o = p.execute("run_command", "{}", 1000) { n++; bad("Refused: blocked by the safety rules", 126) }
        assertEquals(1, n)                                            // never retried
        assertFalse(o.forModel.contains("DIFFERENT approach"))        // no "find a way around it" pressure
        assertFalse(o.mustReport); assertEquals(0, p.consecutiveFailures); assertEquals(0, p.alternativesUsed)
    }
}
