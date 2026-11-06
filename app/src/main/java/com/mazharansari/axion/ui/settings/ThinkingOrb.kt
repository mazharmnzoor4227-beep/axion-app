package com.mazharansari.axion.ui.settings

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which of the owner's two chosen orbs the call screen shows (picked from the thinking-orbs preview,
 * 2026-11-03): Listening while he talks, Composing while Axion speaks/thinks, Frozen (a still
 * listening frame) for Connecting / Error / Ended.
 */
enum class OrbMode { Listening, Composing, Frozen }

/** One dot of a computed orb frame, in canvas units: position, depth, radius, shade (0 = ink … 1 = paper), alpha. */
internal class OrbDot(val x: Float, val y: Float, val z: Float, val r: Float, val white: Float, val a: Float)

/**
 * A faithful Kotlin port of the two engines behind the owner's chosen states from
 * Jakubantalik/thinking-orbs (MIT): listening -> its "wave" engine, composing -> its "ribbon"
 * engine — the package's own base profiles, 64px presets, frame math and radius law
 * (size/300)^0.6. Canvas size is in dp; dot counts follow the 176px hero preview he approved
 * (scaled by sqrt(size/176)), radii follow the engine's scale law at the real canvas size.
 */
internal object ThinkingOrbEngine {

    /** JS Math.round (half up), not Kotlin's half-to-even — the presets were tuned with it. */
    private fun jsRound(x: Double): Int = floor(x + 0.5).toInt()

    private fun radiusScale(size: Float): Double = (size / 300.0).pow(0.6)

    /** The engine's yaw/tilt sphere projection (makeProj with scale 1; points arrive pre-scaled by R). */
    private class Proj(yaw: Double, tilt: Double, private val cx: Float, private val cy: Float) {
        private val st = sin(tilt); private val ct = cos(tilt)
        private val sy = sin(yaw); private val cyw = cos(yaw)
        fun p(x: Double, y: Double, z: Double, out: FloatArray) {
            val x1 = x * cyw + z * sy
            val z1 = -x * sy + z * cyw
            val y1 = y * ct - z1 * st
            val z2 = y * st + z1 * ct
            out[0] = (cx + x1).toFloat(); out[1] = (cy - y1).toFloat(); out[2] = z2.toFloat()
        }
    }

    private fun fibDir(i: Int, n: Int, out: DoubleArray) {
        val golden = PI * (3 - sqrt(5.0))
        val y = 1 - 2 * (i + 0.5) / n
        val rad = sqrt(1 - y * y)
        val a = i * golden
        out[0] = rad * cos(a); out[1] = y; out[2] = rad * sin(a)
    }

    /**
     * listening -> "wave": latitude rings on a sphere whose radius ripples with two travelling
     * waves; crest dots grow and dim slightly. Preset 64: speed 4.388, count x0.341, radii x1.
     * Base profile: rings 15, lonDensity 40, rBase 0.6, rDepth 1.7.
     */
    fun wave(size: Float, t: Float, level: Float, breathe: Float = 1f): List<OrbDot> {
        val boost = sqrt(max(size, 1f) / 176.0)
        val rings = max(2, jsRound(15 * sqrt(0.341) * boost))
        val lonDensity = max(2, jsRound(40 * sqrt(0.341) * boost))
        val rFrame = size / 2 * 0.874 * breathe * (1f + level * 0.10f)
        val pt = Proj(t * 0.18, 0.38, size / 2, size / 2)
        val rs = radiusScale(size)
        val dots = ArrayList<OrbDot>(320)
        val tmp = FloatArray(3)
        for (ri in 0..rings) {
            val lat = -PI / 2 + ri.toDouble() / rings * PI
            val cosLat = cos(lat); val sinLat = sin(lat)
            val w = 0.62 * sin(t * 2.1 - ri * 0.52) + 0.38 * sin(t * 1.27 + ri * 0.83)
            val rr = rFrame * (0.88 + 0.105 * w)
            val lonCount = max(1, jsRound(abs(cosLat) * lonDensity))
            for (lj in 0 until lonCount) {
                val lon = lj.toDouble() / lonCount * 2 * PI
                pt.p(cosLat * cos(lon) * rr, sinLat * rr, cosLat * sin(lon) * rr, tmp)
                val depth = (tmp[2] / rFrame + 1) / 2
                val crest = max(0.0, w)
                val r = max(0.3, (0.6 + 1.7 * depth) * (1 + 0.4 * crest) * rs)
                dots += OrbDot(tmp[0], tmp[1], tmp[2], r.toFloat(), (0.66 - 0.56 * depth - 0.1 * crest).toFloat(), 1f)
            }
        }
        return dots.sortedBy { it.z }
    }

    /**
     * composing -> "ribbon": a band of dotted lanes wrapped on a tilted, slowly wobbling ring,
     * over a faint fibonacci ghost sphere. Preset 64: speed 2.34, count x0.25, radii x0.85,
     * spin 0, bandMul 3.9, wobMul 1. Base profile: lanes 5, segs 88, ghostN 150, rBase 1.1, rDepth 1.7.
     */
    fun ribbon(size: Float, t: Float, level: Float): List<OrbDot> =
        ribbonFrame(size, t, level, countScale = 0.25, radiusMul = 0.85, bandMul = 3.9, heroBoost = true)

    /** The chat thinking glyph: the same ribbon engine resolved at the package's 20px preset
     *  (speed 3.12, count x0.051, radii x1.073, bandMul 4.94). */
    fun ribbonSmall(size: Float, t: Float): List<OrbDot> =
        ribbonFrame(size, t, 0f, countScale = 0.051, radiusMul = 1.073, bandMul = 4.94, heroBoost = false)

    private fun ribbonFrame(
        size: Float, t: Float, level: Float,
        countScale: Double, radiusMul: Double, bandMul: Double, heroBoost: Boolean,
    ): List<OrbDot> {
        val boost = if (heroBoost) sqrt(max(size, 1f) / 176.0) else 1.0
        val baseLanes = max(2, jsRound(5 * sqrt(countScale) * boost))
        val segs = max(2, jsRound(88 * sqrt(countScale) * boost))
        val ghostN = max(1, jsRound(150 * countScale * boost))
        val rBase = 1.1 * radiusMul
        val rDepth = 1.7 * radiusMul
        val bigR = size / 2 * 0.78 * (1f + level * 0.08f)
        val spin = 0.0   // the 64px and 20px composing presets both pin spin to 0: the band ripples in place
        val pt = Proj(t * 0.1 * spin, 0.3, size / 2, size / 2)
        val rs = radiusScale(size)
        val dots = ArrayList<OrbDot>(640)
        val tmp = FloatArray(3)
        val dir = DoubleArray(3)
        for (i in 0 until ghostN) {
            fibDir(i, ghostN, dir)
            pt.p(dir[0] * bigR, dir[1] * bigR, dir[2] * bigR, tmp)
            val depth = (tmp[2] / bigR + 1) / 2
            dots += OrbDot(tmp[0], tmp[1], tmp[2], max(0.3, 0.8 * rs * radiusMul).toFloat(), 0.78f, (0.1 + 0.22 * depth).toFloat())
        }
        val ya = t * 0.24 * spin
        val ta = 0.55 + 0.3 * sin(t * 0.18) * spin
        val ux = cos(ya); val uy = 0.0; val uz = sin(ya)
        val vx = -uz * sin(ta); val vy = cos(ta); val vz = ux * sin(ta)
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val lanes = max(1, jsRound(baseLanes * bandMul))
        val mid = (lanes - 1) / 2.0
        for (w in 0 until lanes) {
            val laneOff = (w - mid) * 0.075
            val edge = abs(w - mid) / max(1.0, mid)
            for (k in 0 until segs) {
                val a = k.toDouble() / segs * 2 * PI
                val wob = 0.16 * sin(a * 3 - t * 1.7 + w * 0.22) + 0.07 * sin(a * 5 + t * 1.1)
                val off = laneOff + wob
                var x = ux * cos(a) + vx * sin(a) + nx * off
                var y = uy * cos(a) + vy * sin(a) + ny * off
                var z = uz * cos(a) + vz * sin(a) + nz * off
                val l = sqrt(x * x + y * y + z * z)
                x = x / l * bigR; y = y / l * bigR; z = z / l * bigR
                pt.p(x, y, z, tmp)
                val depth = (tmp[2] / bigR + 1) / 2
                val r = max(0.3, (rBase + rDepth * depth) * (1 - 0.25 * edge) * rs * radiusMul)
                dots += OrbDot(tmp[0], tmp[1], tmp[2], r.toFloat(), (0.52 - 0.44 * depth + 0.18 * edge).toFloat(), (0.4 + 0.6 * depth).toFloat())
            }
        }
        return dots.sortedBy { it.z }
    }
}
