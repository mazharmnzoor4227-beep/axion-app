package com.mazharansari.axion

import com.mazharansari.axion.work.RunRegistry
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.8.0 Phase C: the background-work ledger — parallel handles, owner lookup, first/last edges, stop-all. */
class RunRegistryTest {
    private fun job(): CompletableJob = Job()

    @Test fun askAndBuildRunsCoexist() {
        val r = RunRegistry()
        val ask = job(); val build = job()
        r.register("a1", RunRegistry.Kind.ASK, "chat-1", "Ask", ask)
        r.register("b1", RunRegistry.Kind.BUILD, "proj-1", "Project", build)
        assertEquals(2, r.active.value.size)
        assertTrue(r.anyActive(RunRegistry.Kind.ASK))
        assertTrue(r.anyActive(RunRegistry.Kind.BUILD, "proj-1"))
        assertFalse(r.anyActive(RunRegistry.Kind.BUILD, "proj-2"))
        assertEquals("b1", r.forOwner("proj-1")?.id)
        ask.complete()
        assertEquals(1, r.active.value.size)
        assertEquals("b1", r.active.value[0].id)
    }

    @Test fun firstAndLastEdgesFireOnceEach() {
        val r = RunRegistry()
        var firsts = 0; var lasts = 0
        r.onFirstRunStarted = { firsts++ }; r.onLastRunFinished = { lasts++ }
        val j1 = job(); val j2 = job()
        r.register("a1", RunRegistry.Kind.ASK, null, "A", j1)
        r.register("a2", RunRegistry.Kind.ASK, null, "B", j2)
        assertEquals(1, firsts); assertEquals(0, lasts)
        j1.complete()
        assertEquals(0, lasts)
        j2.complete()
        assertEquals(1, lasts)
        assertTrue(r.active.value.isEmpty())
    }

    @Test fun cancelAllCancelsEveryJob() {
        val r = RunRegistry()
        val j1 = job(); val j2 = job()
        r.register("a1", RunRegistry.Kind.ASK, null, "A", j1)
        r.register("b1", RunRegistry.Kind.BUILD, "p", "B", j2)
        r.cancelAll()
        assertTrue(j1.isCancelled && j2.isCancelled)
        assertTrue(r.active.value.isEmpty())
    }

    @Test fun unregisterIsIdempotent() {
        val r = RunRegistry()
        val j = job()
        r.register("a1", RunRegistry.Kind.ASK, null, "A", j)
        r.unregister("a1"); r.unregister("a1"); r.unregister("nope")
        assertTrue(r.active.value.isEmpty())
        assertFalse(r.isActive("a1"))
    }
}
