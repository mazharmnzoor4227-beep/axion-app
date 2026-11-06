package com.mazharansari.axion

import com.mazharansari.axion.ui.settings.ThinkingOrbEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ported thinking-orbs engines (listening = wave, composing = ribbon): frames are well-formed —
 * plausible dot counts at the hero size, every dot on the canvas, far-to-near draw order, and the
 * small chat glyph resolves its 20px preset. Pure math, no Android.
 */
class ThinkingOrbEngineTest {
    @Test fun waveFrameIsWellFormed() {
        val dots = ThinkingOrbEngine.wave(220f, 3.1f, 0.4f)
        assertTrue("wave dot count ${dots.size}", dots.size in 120..420)
        assertTrue(dots.all { it.x in -12f..232f && it.y in -12f..232f && it.r >= 0.3f })
        assertEquals(dots.map { it.z }, dots.map { it.z }.sorted())
    }

    @Test fun ribbonFrameIsWellFormed() {
        val dots = ThinkingOrbEngine.ribbon(220f, 2.2f, 0f)
        assertTrue("ribbon dot count ${dots.size}", dots.size in 400..800)
        assertTrue(dots.all { it.x in -12f..232f && it.y in -12f..232f && it.r >= 0.3f })
        assertEquals(dots.map { it.z }, dots.map { it.z }.sorted())
    }

    @Test fun smallRibbonMatchesThe20PresetShape() {
        val dots = ThinkingOrbEngine.ribbonSmall(18f, 1.0f)
        assertEquals(208, dots.size)   // 8 ghost + 10 lanes x 20 segs at the 20px preset
        assertTrue(dots.all { it.x in -4f..22f && it.y in -4f..22f })
    }

    @Test fun framesMoveWithTime() {
        val a = ThinkingOrbEngine.wave(220f, 1.0f, 0f).map { it.x to it.y }
        val b = ThinkingOrbEngine.wave(220f, 2.0f, 0f).map { it.x to it.y }
        assertTrue("the wave must animate", a != b)
        val c = ThinkingOrbEngine.ribbon(220f, 1.0f, 0f).map { it.x to it.y }
        val d = ThinkingOrbEngine.ribbon(220f, 2.5f, 0f).map { it.x to it.y }
        assertTrue("the ribbon wobble must animate", c != d)
    }
}
