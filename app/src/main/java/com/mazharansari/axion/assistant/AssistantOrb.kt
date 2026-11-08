package com.mazharansari.axion.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mazharansari.axion.MainActivity
import com.mazharansari.axion.phone.PhoneController
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Axion's floating character — home-screen behaviour aligned with the assistant the owner
 * asked to mirror (his word, 2026-11-08): a small ball RESTS in a corner of the home screen;
 * it comes forward and grows when called ("Hey Axion"), while a call or phone work runs, or
 * briefly for a message; faces follow what is happening and MORPH smoothly into each other.
 * Tap vocabulary: tap = talk, triple-tap = stop the run, long-press = open Axion, drag = move.
 * All code and art here is Axion's own (Canvas); behaviour only was studied from the reference.
 *
 * Implementation: an accessibility-overlay window. REST = the 64dp ball alone. Any holder
 * (summon / call / run / message) grows it to the full run surface (bubble + STOP/CONFIRM).
 * Left alone at rest for a while, it falls asleep. Faces are parameter sets (colour, eye
 * aperture, brow, smile, gape, blush, pulse) tweened every frame — no hard face switches.
 */
object AssistantOrb {
    enum class Emotion { CALM, HAPPY, LISTENING, SPEAKING, WORKING, SURPRISED, ANGRY, SAD, CRYING, SUCCESS, SLEEPING }

    private val main = Handler(Looper.getMainLooper())
    private var svc: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var face: FaceView? = null
    private var bubble: TextView? = null
    private var stopChip: TextView? = null
    private var confirmChip: TextView? = null
    private var enabledWanted = true

    private val holders = mutableSetOf<String>()
    private var summonTimer: Runnable? = null
    private var messageTimer: Runnable? = null
    private var emotionTimer: Runnable? = null
    private var sleepTimer: Runnable? = null
    private var callReleaseTimer: Runnable? = null
    @Volatile var emotion: Emotion = Emotion.CALM
        private set
    private var overrideUntil = 0L

    // run-surface state
    private var stopOn = false
    private var confirmLabel: String? = null
    private var bubbleText: String? = null

    // tap vocabulary
    private var tapCount = 0
    private var lastTapAt = 0L

    fun setEmotion(e: Emotion) { emotion = e; face?.emotion = e }

    private fun baseEmotion(): Emotion = when {
        "call" in holders -> Emotion.SPEAKING
        "run" in holders -> if (confirmLabel != null) Emotion.SURPRISED else Emotion.WORKING
        "summon" in holders -> Emotion.LISTENING
        "message" in holders -> Emotion.HAPPY
        else -> Emotion.CALM
    }

    /** Show a face for a moment, then fall back to whatever the holders say. */
    fun showEmotion(e: Emotion, holdMs: Long = 1800) {
        emotionTimer?.let { main.removeCallbacks(it) }
        overrideUntil = System.currentTimeMillis() + holdMs
        setEmotion(e)
        emotionTimer = Runnable {
            if (System.currentTimeMillis() >= overrideUntil - 50) setEmotion(baseEmotion())
        }
        main.postDelayed(emotionTimer!!, holdMs)
    }

    fun setEnabled(on: Boolean) {
        main.post {
            enabledWanted = on
            if (!on) teardown() else if (svc != null && root == null) build()
        }
    }

    fun attach(s: AccessibilityService) {
        main.post {
            svc = s
            if (enabledWanted && root == null) build()
        }
    }

    fun detach() {
        main.post {
            teardown(); svc = null
        }
    }

    private fun acquire(holder: String) {
        val wasEmpty = holders.isEmpty()
        holders += holder
        sleepTimer?.let { main.removeCallbacks(it) }
        if (root == null && svc != null) build()
        if (wasEmpty) applyMode()
        if (System.currentTimeMillis() >= overrideUntil) setEmotion(baseEmotion())
    }

    private fun release(holder: String) {
        holders -= holder
        if (holders.isEmpty()) {
            applyMode()
            setEmotion(Emotion.CALM)
            startSleepTimer()
        } else if (System.currentTimeMillis() >= overrideUntil) {
            setEmotion(baseEmotion())
        }
    }

    private fun startSleepTimer() {
        sleepTimer?.let { main.removeCallbacks(it) }
        sleepTimer = Runnable { if (holders.isEmpty()) setEmotion(Emotion.SLEEPING) }
        main.postDelayed(sleepTimer!!, 45_000)
    }

    /** Wake-word summon: come to the front, listen face, then settle back to the corner. */
    fun summon() {
        main.post {
            if (!enabledWanted || svc == null) return@post
            acquire("summon")
            val l = lp ?: return@post
            val disp = svc!!.resources.displayMetrics
            l.x = disp.widthPixels - dp(96)
            l.y = (disp.heightPixels * 0.30f).toInt()
            runCatching { wm?.updateViewLayout(root, l) }
            summonTimer?.let { main.removeCallbacks(it) }
            summonTimer = Runnable { release("summon"); restorePos() }
            main.postDelayed(summonTimer!!, 9_000)
        }
    }

    /** Live call keeps the character out in its grown form; hanging up gets a happy wave-off. */
    fun callActive(on: Boolean) {
        main.post {
            if (svc == null) return@post
            if (on) {
                callReleaseTimer?.let { main.removeCallbacks(it) }
                acquire("call")
            } else if ("call" in holders) {
                showEmotion(Emotion.SUCCESS, 900)
                callReleaseTimer?.let { main.removeCallbacks(it) }
                callReleaseTimer = Runnable { release("call") }
                main.postDelayed(callReleaseTimer!!, 950)
            }
        }
    }

    /** A new message: grow, hold the line in the bubble, then settle back. */
    fun showMessage(line: String) {
        main.post {
            if (!enabledWanted || svc == null) return@post
            acquire("message")
            bubbleText = line
            refreshSurface()
            messageTimer?.let { main.removeCallbacks(it) }
            messageTimer = Runnable { bubbleText = null; refreshSurface(); release("message") }
            main.postDelayed(messageTimer!!, 6_000)
        }
    }

    /** Reserved hook for failure moments: a brief cry, then back to rest. */
    fun cryBriefly() {
        main.post {
            if (!enabledWanted || svc == null) return@post
            acquire("message")
            showEmotion(Emotion.CRYING, 2600)
            messageTimer?.let { main.removeCallbacks(it) }
            messageTimer = Runnable { release("message") }
            main.postDelayed(messageTimer!!, 2700)
        }
    }

    fun isUp(): Boolean = root != null

    // ------------------------------------------------------------------ run surface (PhoneOverlay)

    /** PhoneOverlay's contract (unchanged since v0.8.7): can the character carry this run? */
    fun runShow(): Boolean {
        if (!enabledWanted || svc == null) return false
        main.post {
            if (svc == null) return@post
            acquire("run")
            stopOn = true
            refreshSurface()
        }
        return true
    }

    fun runStatus(lines: List<String>, confirmLabel: String?) {
        main.post {
            if ("run" !in holders) return@post
            stopOn = true
            this.confirmLabel = confirmLabel
            bubbleText = lines.lastOrNull()
            // A blocked/stopped line flashes the angry face, like being scolded mid-work.
            if (lines.any { it.contains("✋") }) showEmotion(Emotion.ANGRY, 1600)
            else if (System.currentTimeMillis() >= overrideUntil) setEmotion(baseEmotion())
            refreshSurface()
        }
    }

    fun runHide() {
        main.post {
            if ("run" !in holders) return@post
            showEmotion(Emotion.SUCCESS, 1000)
            main.postDelayed({
                stopOn = false
                confirmLabel = null
                bubbleText = null
                refreshSurface()
                release("run")
            }, 1050)
        }
    }

    // ------------------------------------------------------------------ window

    private fun dp(v: Int): Int {
        val d = svc?.resources?.displayMetrics?.density ?: 2.6f
        return (v * d).toInt()
    }

    private fun build() {
        val s = svc ?: return
        val w = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val col = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        bubble = TextView(s).apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(0xE61B1B22.toInt())
            visibility = View.GONE
            maxWidth = dp(230)
        }
        col.addView(bubble)
        val row = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stopChip = TextView(s).apply {
            text = "■ STOP"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundColor(0xE6C62828.toInt())
            visibility = View.GONE
            setOnClickListener { PhoneController.stop("You tapped STOP on the character.") }
        }
        row.addView(stopChip)
        val fv = FaceView(s)
        face = fv
        row.addView(fv)
        confirmChip = TextView(s).apply {
            setTextColor(0xFF101408.toInt())
            textSize = 12f
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundColor(0xFFB7F542.toInt())
            visibility = View.GONE
            setOnClickListener { PhoneController.confirmPending() }
        }
        row.addView(confirmChip)
        col.addView(row)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        lp = params
        root = col
        restorePos()
        installTouch(fv)
        runCatching { w.addView(col, params) }
            .onFailure { root = null; face = null }
        applyMode()
        if (holders.isEmpty()) startSleepTimer()
    }

    private fun teardown() {
        summonTimer?.let { main.removeCallbacks(it) }
        messageTimer?.let { main.removeCallbacks(it) }
        sleepTimer?.let { main.removeCallbacks(it) }
        callReleaseTimer?.let { main.removeCallbacks(it) }
        holders.clear()
        stopOn = false
        confirmLabel = null
        bubbleText = null
        val r = root
        if (r != null) runCatching { wm?.removeView(r) }
        root = null; face = null; bubble = null; stopChip = null; confirmChip = null; lp = null
    }

    /** Grow (any holder) or shrink back to the resting ball. */
    private fun applyMode() {
        val fv = face ?: return
        val active = holders.isNotEmpty()
        val size = if (active) dp(84) else dp(64)
        fv.layoutParams = LinearLayout.LayoutParams(size, size)
        refreshSurface()
        // Keep the grown surface on screen if it was resting near the right edge.
        root?.post {
            val l = lp ?: return@post
            val r = root ?: return@post
            val disp = svc?.resources?.displayMetrics ?: return@post
            if (l.x + r.width > disp.widthPixels) l.x = maxOf(0, disp.widthPixels - r.width - dp(4))
            if (l.y + r.height > disp.heightPixels) l.y = maxOf(0, disp.heightPixels - r.height - dp(4))
            runCatching { wm?.updateViewLayout(r, l) }
        }
    }

    private fun refreshSurface() {
        val active = holders.isNotEmpty()
        bubble?.visibility = if (active && !bubbleText.isNullOrBlank()) View.VISIBLE else View.GONE
        if (!bubbleText.isNullOrBlank()) bubble?.text = bubbleText
        stopChip?.visibility = if (active && stopOn) View.VISIBLE else View.GONE
        confirmChip?.visibility = if (active && confirmLabel != null) View.VISIBLE else View.GONE
        if (confirmLabel != null) confirmChip?.text = "✓ " + confirmLabel
    }

    private fun restorePos() {
        val s = svc ?: return
        val l = lp ?: return
        val prefs = s.getSharedPreferences("assistant_orb", Context.MODE_PRIVATE)
        val disp = s.resources.displayMetrics
        l.x = prefs.getInt("x", disp.widthPixels - dp(76))
        l.y = prefs.getInt("y", (disp.heightPixels * 0.55f).toInt())
        runCatching { wm?.updateViewLayout(root, l) }
    }

    private fun savePos() {
        val s = svc ?: return
        val l = lp ?: return
        s.getSharedPreferences("assistant_orb", Context.MODE_PRIVATE).edit()
            .putInt("x", l.x).putInt("y", l.y).apply()
    }

    private fun openAxion(startCall: Boolean) {
        val s = svc ?: return
        val i = Intent(s, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (startCall) i.putExtra(com.mazharansari.axion.wakeword.WakeWordService.EXTRA_START_CALL, true)
        runCatching { s.startActivity(i) }
    }

    private fun onTap() {
        sleepTimer?.let { main.removeCallbacks(it) }
        if (holders.isEmpty()) setEmotion(Emotion.CALM)
        startSleepTimer()
        val now = System.currentTimeMillis()
        tapCount = if (now - lastTapAt < 700) tapCount + 1 else 1
        lastTapAt = now
        if (tapCount >= 3) {
            tapCount = 0
            PhoneController.stop("You tapped the character three times — stopped.")
            showEmotion(Emotion.SURPRISED, 900)
            return
        }
        when {
            "run" in holders -> openAxion(false)
            "call" in holders -> openAxion(false)
            else -> { summon(); openAxion(true) }   // rest: tap = talk
        }
    }

    private fun installTouch(v: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longPressed = false
        var longTimer: Runnable? = null
        v.setOnTouchListener { _, ev ->
            val l = lp ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = l.x; startY = l.y
                    moved = false; longPressed = false
                    longTimer = Runnable {
                        longPressed = true
                        openAxion(false)
                    }
                    main.postDelayed(longTimer!!, 550)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt(); val dy = (ev.rawY - downY).toInt()
                    if (abs(dx) + abs(dy) > 14) {
                        moved = true
                        longTimer?.let { main.removeCallbacks(it) }
                    }
                    if (moved) {
                        val disp = svc?.resources?.displayMetrics
                        val maxX = (disp?.widthPixels ?: 1080) - v.width
                        val maxY = (disp?.heightPixels ?: 1920) - v.height
                        l.x = (startX + dx).coerceIn(0, maxOf(0, maxX))
                        l.y = (startY + dy).coerceIn(0, maxOf(0, maxY))
                        runCatching { wm?.updateViewLayout(root, l) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longTimer?.let { main.removeCallbacks(it) }
                    if (moved) savePos() else if (!longPressed) onTap()
                    true
                }
                else -> false
            }
        }
    }

    // ------------------------------------------------------------------ the face

    /** One face as numbers; the view tweens its current numbers toward the emotion's row. */
    private class FP(
        var r: Float, var g: Float, var b: Float,
        var eye: Float, var arc: Float, var brow: Float,
        var smile: Float, var gape: Float, var blush: Float, var pulse: Float,
    )

    private fun targetFor(e: Emotion): FP = when (e) {
        Emotion.CALM -> FP(0.60f, 0.50f, 0.95f, 0.92f, 0f, 0f, 0.30f, 0f, 0.35f, 1.0f)
        Emotion.HAPPY -> FP(0.68f, 0.56f, 1.00f, 0.80f, 0.75f, -0.15f, 0.80f, 0f, 0.60f, 1.2f)
        Emotion.LISTENING -> FP(0.64f, 0.53f, 0.98f, 1.10f, 0f, -0.10f, 0.25f, 0.25f, 0.40f, 1.0f)
        Emotion.SPEAKING -> FP(0.66f, 0.54f, 0.99f, 0.95f, 0f, 0f, 0.30f, 0.45f, 0.45f, 1.3f)
        Emotion.WORKING -> FP(0.58f, 0.48f, 0.92f, 0.80f, 0f, 0.45f, 0.05f, 0f, 0.25f, 1.5f)
        Emotion.SURPRISED -> FP(0.70f, 0.58f, 1.00f, 1.15f, 0f, -0.60f, 0f, 0.70f, 0.30f, 1.2f)
        Emotion.ANGRY -> FP(0.85f, 0.38f, 0.42f, 0.55f, 0f, 1.00f, -0.55f, 0f, 0.20f, 2.0f)
        Emotion.SAD -> FP(0.50f, 0.47f, 0.80f, 0.50f, 0f, -0.80f, -0.60f, 0f, 0.10f, 0.6f)
        Emotion.CRYING -> FP(0.48f, 0.45f, 0.78f, 0.45f, 0f, -0.85f, -0.80f, 0.10f, 0.10f, 0.7f)
        Emotion.SUCCESS -> FP(0.70f, 0.60f, 1.00f, 0.70f, 1.00f, -0.20f, 1.00f, 0f, 0.80f, 1.6f)
        Emotion.SLEEPING -> FP(0.42f, 0.38f, 0.70f, 0.06f, 0f, 0f, 0.15f, 0f, 0.12f, 0.45f)
    }

    private class FaceView(ctx: Context) : View(ctx) {
        @Volatile var emotion: Emotion = Emotion.CALM
        private val cur = FP(0.60f, 0.50f, 0.95f, 0.92f, 0f, 0f, 0.30f, 0f, 0.35f, 1.0f)
        private val born = System.currentTimeMillis()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = System.currentTimeMillis()
            val t = (now - born) / 1000f
            val tgt = targetFor(emotion)
            val k = 0.16f
            cur.r = lerp(cur.r, tgt.r, k); cur.g = lerp(cur.g, tgt.g, k); cur.b = lerp(cur.b, tgt.b, k)
            cur.eye = lerp(cur.eye, tgt.eye, k); cur.arc = lerp(cur.arc, tgt.arc, k)
            cur.brow = lerp(cur.brow, tgt.brow, k); cur.smile = lerp(cur.smile, tgt.smile, k)
            cur.gape = lerp(cur.gape, tgt.gape, k); cur.blush = lerp(cur.blush, tgt.blush, k)
            cur.pulse = lerp(cur.pulse, tgt.pulse, k)

            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2f; val cy = h / 2f
            val pop = min(1f, (now - born) / 260f)
            val popScale = 0.6f + 0.4f * (1f - (1f - pop) * (1f - pop))
            val bob = sin(t * 1.7f * cur.pulse) * h * 0.012f
            val breathe = 1f + sin(t * 2.2f * cur.pulse) * 0.015f
            val shake = if (emotion == Emotion.ANGRY) sin(t * 40f) * w * 0.008f else 0f
            val rBase = min(w, h) * 0.40f * popScale * breathe
            val ccx = cx + shake; val ccy = cy + bob

            // soft ground shadow + aura
            p.shader = null
            p.color = Color.argb(46, 10, 8, 30)
            canvas.drawOval(RectF(ccx - rBase * 0.82f, ccy + rBase * 0.86f, ccx + rBase * 0.82f, ccy + rBase * 1.06f), p)
            p.color = Color.argb((44 * (0.5f + cur.blush * 0.5f)).toInt(), 150, 120, 255)
            canvas.drawCircle(ccx, ccy, rBase * 1.13f, p)

            // body: glossy shaded sphere, colour from the tweened face row
            val mid = Color.rgb((cur.r * 255).toInt(), (cur.g * 255).toInt(), (cur.b * 255).toInt())
            val light = Color.rgb(
                (lerp(cur.r, 1f, 0.55f) * 255).toInt(),
                (lerp(cur.g, 1f, 0.55f) * 255).toInt(),
                (lerp(cur.b, 1f, 0.55f) * 255).toInt(),
            )
            val dark = Color.rgb((cur.r * 0.42f * 255).toInt(), (cur.g * 0.42f * 255).toInt(), (cur.b * 0.42f * 255).toInt())
            p.shader = RadialGradient(ccx - rBase * 0.38f, ccy - rBase * 0.42f, rBase * 1.45f,
                intArrayOf(light, mid, dark), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(ccx, ccy, rBase, p)
            p.shader = null
            // cyan rim light at the bottom + specular highlight
            p.style = Paint.Style.STROKE; p.strokeWidth = rBase * 0.05f
            p.color = Color.argb(120, 110, 240, 255)
            canvas.drawArc(RectF(ccx - rBase * 0.94f, ccy - rBase * 0.94f, ccx + rBase * 0.94f, ccy + rBase * 0.94f), 35f, 110f, false, p)
            p.style = Paint.Style.FILL
            p.color = Color.argb(165, 255, 255, 255)
            canvas.drawOval(RectF(ccx - rBase * 0.52f, ccy - rBase * 0.72f, ccx - rBase * 0.14f, ccy - rBase * 0.40f), p)

            // WORKING: rotating lime ring
            if (emotion == Emotion.WORKING) {
                p.style = Paint.Style.STROKE; p.strokeWidth = rBase * 0.06f
                p.color = Color.argb(210, 183, 245, 66)
                val a0 = (t * 140f) % 360f
                canvas.drawArc(RectF(ccx - rBase * 1.06f, ccy - rBase * 1.06f, ccx + rBase * 1.06f, ccy + rBase * 1.06f), a0, 100f, false, p)
                p.style = Paint.Style.FILL
            }

            // antenna + glowing bulb (bright while listening)
            val ax = ccx; val ay = ccy - rBase
            p.color = Color.argb(220, 230, 228, 255)
            p.strokeWidth = rBase * 0.045f
            p.style = Paint.Style.STROKE
            canvas.drawLine(ax, ay + rBase * 0.04f, ax, ay - rBase * 0.22f, p)
            p.style = Paint.Style.FILL
            val bulbGlow = if (emotion == Emotion.LISTENING) 0.65f + 0.35f * sin(t * 6f) else 0.35f
            p.color = Color.argb((bulbGlow * 90).toInt().coerceIn(0, 90), 183, 245, 66)
            canvas.drawCircle(ax, ay - rBase * 0.30f, rBase * 0.16f, p)
            p.color = Color.rgb(183, 245, 66)
            canvas.drawCircle(ax, ay - rBase * 0.30f, rBase * 0.085f, p)

            // rosy cheeks (always a little — the resting face must read harmless)
            p.color = Color.argb((cur.blush * 95).toInt().coerceIn(0, 95), 255, 140, 170)
            canvas.drawCircle(ccx - rBase * 0.52f, ccy + rBase * 0.16f, rBase * 0.15f, p)
            canvas.drawCircle(ccx + rBase * 0.52f, ccy + rBase * 0.16f, rBase * 0.15f, p)

            val eyeY = ccy - rBase * 0.10f
            val eyeDx = rBase * 0.36f
            val eyeW = rBase * 0.155f
            val eyeH = rBase * 0.20f
            // blink: a short closed window every ~3.4 s
            val blink = if ((t % 3.4f) > 3.22f) 0.08f else 1f
            // look direction follows the mood
            val lookX = when (emotion) { Emotion.WORKING -> sin(t * 0.9f) * eyeW * 0.35f; else -> sin(t * 0.6f) * eyeW * 0.18f }
            val lookY = when (emotion) { Emotion.SAD, Emotion.CRYING -> eyeH * 0.3f; Emotion.SLEEPING -> 0f; else -> sin(t * 0.8f) * eyeH * 0.12f }

            fun brow(sign: Float, ex: Float) {
                // brow param: +1 angry (inner end DOWN toward the nose), −1 sad/surprised (inner end UP)
                val innerDy = cur.brow * eyeH * 0.55f
                val outerDy = -cur.brow * eyeH * 0.18f
                val y0 = eyeY - eyeH * 1.28f
                p.color = Color.argb(215, 30, 22, 60)
                p.strokeWidth = rBase * 0.055f
                p.style = Paint.Style.STROKE
                canvas.drawLine(ex - sign * eyeW * 1.05f, y0 + innerDy, ex + sign * eyeW * 1.05f, y0 + outerDy, p)
                p.style = Paint.Style.FILL
            }
            brow(-1f, ccx - eyeDx); brow(1f, ccx + eyeDx)

            fun eye(ex: Float) {
                if (cur.arc > 0.5f) {
                    // happy ^^ arc
                    p.color = Color.argb(235, 30, 22, 60)
                    p.strokeWidth = rBase * 0.07f
                    p.style = Paint.Style.STROKE
                    canvas.drawArc(RectF(ex - eyeW, eyeY - eyeH * 0.7f, ex + eyeW, eyeY + eyeH * 0.7f), 180f, 180f, false, p)
                    p.style = Paint.Style.FILL
                    return
                }
                val open = (cur.eye * blink).coerceIn(0.03f, 1.2f)
                p.color = Color.WHITE
                canvas.drawOval(RectF(ex - eyeW, eyeY - eyeH * open, ex + eyeW, eyeY + eyeH * open), p)
                p.color = Color.rgb(38, 30, 84)
                canvas.drawCircle(ex + lookX, eyeY + lookY, eyeW * 0.58f, p)
                p.color = Color.WHITE
                canvas.drawCircle(ex + lookX + eyeW * 0.2f, eyeY + lookY - eyeH * 0.22f, eyeW * 0.2f, p)
                if (emotion == Emotion.SLEEPING) {
                    p.color = Color.argb(200, 30, 22, 60)
                    p.strokeWidth = rBase * 0.04f
                    p.style = Paint.Style.STROKE
                    canvas.drawLine(ex - eyeW, eyeY, ex + eyeW, eyeY, p)
                    p.style = Paint.Style.FILL
                }
            }
            eye(ccx - eyeDx); eye(ccx + eyeDx)

            // CRYING: animated tear drops
            if (emotion == Emotion.CRYING) {
                p.color = Color.argb(215, 130, 200, 255)
                for (s in -1..1 step 2) {
                    val ph = ((t * 0.9f + if (s < 0) 0f else 0.45f) % 1f)
                    val ty = eyeY + eyeH + ph * rBase * 0.55f
                    val a = if (ph > 0.85f) ((1f - ph) / 0.15f * 215).toInt() else 215
                    p.alpha = a.coerceIn(0, 215)
                    canvas.drawCircle(ccx + s * eyeDx, ty, rBase * 0.05f * (0.7f + ph * 0.5f), p)
                }
                p.alpha = 255
            }

            // mouth: gape (open) beats the smile curve; SPEAKING oscillates the gape
            val my = ccy + rBase * 0.34f
            val mw = rBase * 0.34f
            var gape = cur.gape
            if (emotion == Emotion.SPEAKING) gape *= 0.55f + 0.45f * abs(sin(t * 7f))
            if (gape > 0.30f) {
                p.color = Color.argb(235, 40, 26, 66)
                canvas.drawOval(RectF(ccx - mw * 0.42f, my - rBase * 0.10f * gape, ccx + mw * 0.42f, my + rBase * 0.26f * gape), p)
            } else {
                p.color = Color.argb(235, 40, 26, 66)
                p.strokeWidth = rBase * 0.06f
                p.style = Paint.Style.STROKE
                val lift = cur.smile * rBase * 0.20f
                path.reset()
                path.moveTo(ccx - mw, my - lift * 0.35f)
                path.quadTo(ccx, my + lift, ccx + mw, my - lift * 0.35f)
                if (emotion == Emotion.ANGRY) {
                    // zigzag mouth under strong negative smile
                    path.reset()
                    val seg = mw / 3f
                    path.moveTo(ccx - mw, my)
                    for (i in 1..6) {
                        val xx = ccx - mw + seg * i
                        val yy = my + if (i % 2 == 0) 0f else rBase * 0.07f
                        path.lineTo(xx, yy)
                    }
                }
                canvas.drawPath(path, p)
                p.style = Paint.Style.FILL
            }

            // SLEEPING: rising Zzz
            if (emotion == Emotion.SLEEPING) {
                p.color = Color.argb(200, 200, 210, 255)
                p.textSize = rBase * 0.42f
                p.textAlign = Paint.Align.CENTER
                val ph = (t * 0.35f) % 1f
                canvas.drawText("z", ccx + rBase * (0.55f + ph * 0.25f), ccy - rBase * (0.55f + ph * 0.5f), p)
                canvas.drawText("Z", ccx + rBase * (0.75f + ph * 0.3f), ccy - rBase * (0.85f + ph * 0.55f), p)
            }

            postInvalidateOnAnimation()
        }
    }
}
