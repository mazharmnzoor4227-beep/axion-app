package com.mazharansari.axion

import com.mazharansari.axion.build.BuildDemo
import com.mazharansari.axion.build.DemoState
import com.mazharansari.axion.build.DemoStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-5 (one-tap build demo): the state machine, file naming and capture sizing
 * are pure and tested here. The actual MediaProjection recording needs a real
 * phone (its failure path is honest — the service publishes an error, never a
 * fake video), so it is verified on-device, not in this test.
 */
class P2BuildDemoTest {

    @Test
    fun `capture size downscales a 1080p screen to 720 wide`() {
        val (w, h) = BuildDemo.captureSize(1080, 2400)
        assertEquals(720, w)
        assertEquals(1600, h)
    }

    @Test
    fun `capture size never upscales a small screen`() {
        assertEquals(480 to 800, BuildDemo.captureSize(480, 800))
    }

    @Test
    fun `capture size is always even and at least 2px`() {
        val (w, h) = BuildDemo.captureSize(721, 1001)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
        assertTrue(w <= 720)
        assertTrue(w >= 2 && h >= 2)
    }

    @Test
    fun `max duration is two minutes`() {
        assertEquals(120_000L, BuildDemo.MAX_DURATION_MS)
    }

    @Test
    fun `demo file lives under the shareable cache dir with a unique name`() {
        val dir = File("/tmp/p2-demo-test")
        val a = File(dir, "build-demo-1.mp4")
        val b = File(dir, "build-demo-2.mp4")
        assertTrue(a.name.startsWith("build-demo-"))
        assertTrue(a.name.endsWith(".mp4"))
        assertNotEquals(a.name, b.name)
    }

    @Test
    fun `state machine walks the happy path`() {
        val m = DemoStateMachine()
        assertEquals(DemoState.IDLE, m.state)
        assertTrue(m.requestPermission())
        assertEquals(DemoState.WAITING_PERMISSION, m.state)
        m.started()
        assertEquals(DemoState.RECORDING, m.state)
        val rec = m.stopped(File("/tmp/x.mp4"), 1000L, 5000L)
        assertEquals(DemoState.READY, m.state)
        assertEquals(5000L, rec?.durationMs)
        assertEquals(1000L, rec?.startedAt)
    }

    @Test
    fun `permission denied returns to idle`() {
        val m = DemoStateMachine()
        m.requestPermission()
        m.permissionDenied()
        assertEquals(DemoState.IDLE, m.state)
    }

    @Test
    fun `double record request is refused while recording`() {
        val m = DemoStateMachine()
        m.requestPermission()
        m.started()
        assertFalse(m.requestPermission())
        assertEquals(DemoState.RECORDING, m.state)
    }

    @Test
    fun `stop without a recording returns null`() {
        val m = DemoStateMachine()
        assertNull(m.stopped(File("/tmp/x.mp4"), 0L, 0L))
        assertEquals(DemoState.IDLE, m.state)
    }

    @Test
    fun `failure is honest and resettable`() {
        val m = DemoStateMachine()
        m.failed("Screen recording was not granted.")
        assertEquals(DemoState.ERROR, m.state)
        assertEquals("Screen recording was not granted.", m.error)
        m.reset()
        assertEquals(DemoState.IDLE, m.state)
        assertNull(m.error)
    }

    @Test
    fun `a new recording can start after a finished one`() {
        val m = DemoStateMachine()
        m.requestPermission(); m.started(); m.stopped(File("/tmp/x.mp4"), 0L, 1000L)
        assertTrue(m.requestPermission())
        assertEquals(DemoState.WAITING_PERMISSION, m.state)
    }
}
