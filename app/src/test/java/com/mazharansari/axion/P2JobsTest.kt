package com.mazharansari.axion.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P2-3: background jobs survive process death — persisted records, reconcile
 * on start, re-run. Pure JVM (the store takes a plain directory), no Android.
 */
class P2JobsTest {

    private fun tmpDir(): File = Files.createTempDirectory("axion-jobs-test").toFile()

    @Test fun startRecordsARunningJob() {
        val store = JobRecordStore(tmpDir())
        store.recordStart(1, "sleep 60", "/tmp/job-1.log")
        val rec = store.get(1)
        assertNotNull(rec)
        assertEquals("sleep 60", rec!!.command)
        assertEquals("/tmp/job-1.log", rec.logPath)
        assertEquals(JobState.RUNNING, rec.state)
        assertNull(rec.exitCode)
        assertTrue(rec.startedAt > 0)
    }

    @Test fun finishMarksDoneOrFailed() {
        val store = JobRecordStore(tmpDir())
        store.recordStart(1, "true", "/tmp/a.log")
        store.recordStart(2, "false", "/tmp/b.log")
        store.recordFinish(1, 0)
        store.recordFinish(2, 3)
        assertEquals(JobState.DONE, store.get(1)!!.state)
        assertEquals(0, store.get(1)!!.exitCode)
        assertEquals(JobState.FAILED, store.get(2)!!.state)
        assertEquals(3, store.get(2)!!.exitCode)
        // A finished record is never re-finished.
        store.recordFinish(1, 5)
        assertEquals(JobState.DONE, store.get(1)!!.state)
        assertEquals(0, store.get(1)!!.exitCode)
    }

    @Test fun reconcileMarksOnlyRunningAsInterrupted() {
        val store = JobRecordStore(tmpDir())
        store.recordStart(1, "sleep 60", "/tmp/a.log")
        store.recordStart(2, "true", "/tmp/b.log")
        store.recordFinish(2, 0)
        val changed = store.markAllRunningInterrupted()
        assertEquals(listOf(1), changed.map { it.id })
        assertEquals(JobState.INTERRUPTED, store.get(1)!!.state)
        assertEquals(JobState.DONE, store.get(2)!!.state)
        // Second reconcile is a no-op.
        assertTrue(store.markAllRunningInterrupted().isEmpty())
        // An interrupted record cannot be re-finished.
        store.recordFinish(1, 0)
        assertEquals(JobState.INTERRUPTED, store.get(1)!!.state)
    }

    @Test fun recordsSurviveANewStoreInstance() {
        val dir = tmpDir()
        JobRecordStore(dir).apply {
            recordStart(7, "make build", "/tmp/job-7.log")
            recordFinish(7, 0)
        }
        val reopened = JobRecordStore(dir)
        val rec = reopened.get(7)
        assertNotNull(rec)
        assertEquals("make build", rec!!.command)
        assertEquals(JobState.DONE, rec.state)
    }

    @Test fun capDropsOldestFinishedFirstNeverRunning() {
        val store = JobRecordStore(tmpDir())
        for (i in 1..105) store.recordStart(i, "cmd $i", "/tmp/job-$i.log")
        for (i in 1..100) store.recordFinish(i, 0)
        store.recordStart(106, "cmd 106", "/tmp/job-106.log")
        assertEquals(100, store.records.value.size)
        assertNull(store.get(1))   // oldest finished, dropped
        assertNull(store.get(6))
        assertNotNull(store.get(7))
        assertNotNull(store.get(101))   // still running, kept
        assertNotNull(store.get(106))
    }

    @Test fun logTailCutsTheBeginningAndSurvivesMissingFiles() {
        val dir = tmpDir()
        val log = File(dir, "job.log").apply { writeText("x".repeat(20000)) }
        val tail = readLogTail(log.absolutePath, 8000)
        assertTrue(tail.startsWith("…[beginning cut]"))
        assertTrue(tail.length < 9000)
        assertEquals("", readLogTail(File(dir, "nope.log").absolutePath))
        assertEquals("short", readLogTail(File(dir, "s.log").apply { writeText("short") }.absolutePath))
    }
}
