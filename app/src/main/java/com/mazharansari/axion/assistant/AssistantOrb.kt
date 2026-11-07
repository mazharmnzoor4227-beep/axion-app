package com.mazharansari.axion.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
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

/**
 * v0.8.7 Phase F — Axion's floating character (the owner's Andee-style brief, 2026-11-08:
 * a character that LIVES on the home screen instead of controls hiding in the notification
 * bar). Original Axion code and art: a glossy orb character drawn on Canvas — breathing,
 * blinking, with a lime brand glow — hosted in an accessibility-overlay window (the same
 * route as the phone-control strip, so no extra "draw over apps" permission).
 *
 * Behaviour:
 *  - drag it anywhere; on release it docks to the nearest edge and remembers the spot;
 *  - "Hey Axion" summons it to the front (see [summon]) while the Live call opens;
 *  - during a phone-control run it carries the run: status bubble + STOP (+ CONFIRM when the
 *    gate is waiting) — [PhoneOverlay] prefers this surface over the system notification;
 *  - new message announcements pop out of it as a bubble (tap opens Axion).
 */
object AssistantOrb {
    enum class Mood { IDLE, SUMMONED, WORKING, CONFIRM }

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
    @Volatile var mood: Mood = Mood.IDLE
        private set
    private var homeX = -1
    private var homeY = -1
    private var bubbleHide: Runnable? = null
    private var summonBack: Runnable? = null

    val isShowing: Boolean get() = root != null

    fun attach(service: AccessibilityService) {
        svc = service
        enabledWanted = runCatching { (service.application as com.mazharansari.axion.AxionApp).graph.settings.state.value.assistantOrb }.getOrDefault(true)
        if (enabledWanted) main.post { build() }
    }

    fun detach() = main.post {
        removeWindow()
        svc = null
    }

    /** Settings toggle: remember the wish; build or remove right away when the service is up. */
    fun setEnabled(on: Boolean) {
        enabledWanted = on
        main.post { if (on) build() else removeWindow() }
    }

    // ---- window ----

    private fun dp(v: Int): Int = ((svc?.resources?.displayMetrics?.density ?: 2f) * v).toInt()

    private fun build() {
        val s = svc ?: return
        if (root != null || !enabledWanted) return
        runCatching {
            wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val prefs = s.getSharedPreferences("assistant_orb", Context.MODE_PRIVATE)
            val col = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            bubble = TextView(s).apply {
                setTextColor(Color.WHITE); textSize = 12.5f; maxWidth = dp(250)
                setPadding(dp(12), dp(8), dp(12), dp(8)); visibility = View.GONE
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xE61B2018.toInt()); cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), 0xFF9BE15D.toInt())
                }
                setOnClickListener { openAxion() }
            }
            col.addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
            val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            stopChip = chip(s, "STOP", 0xFFD43D2A.toInt()) { PhoneController.stop("You pressed STOP") }
            confirmChip = chip(s, "CONFIRM", 0xFF9BE15D.toInt()) { PhoneController.confirmPending() }
            face = FaceView(s)
            row.addView(stopChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
            row.addView(face, LinearLayout.LayoutParams(dp(76), dp(76)))
            row.addView(confirmChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            col.addView(row)
            val disp = s.resources.displayMetrics
            val startX = prefs.getInt("x", disp.widthPixels - dp(92))
            val startY = prefs.getInt("y", (disp.heightPixels * 0.55f).toInt())
            lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; x = startX; y = startY }
            attachDrag(col)
            wm!!.addView(col, lp)
            root = col
            face!!.start()
        }
    }

    private fun chip(s: Context, label: String, bg: Int, onTap: () -> Unit): TextView = TextView(s).apply {
        text = label; setTextColor(if (bg == 0xFF9BE15D.toInt()) Color.BLACK else Color.WHITE)
        textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(10), dp(6), dp(10), dp(6)); visibility = View.GONE
        background = android.graphics.drawable.GradientDrawable().apply { setColor(bg); cornerRadius = dp(12).toFloat() }
        setOnClickListener { onTap() }
    }

    private fun removeWindow() {
        bubbleHide?.let { main.removeCallbacks(it) }
        summonBack?.let { main.removeCallbacks(it) }
        face?.stop()
        root?.let { r -> runCatching { wm?.removeView(r) } }
        root = null; face = null; bubble = null; stopChip = null; confirmChip = null; lp = null
        mood = Mood.IDLE
    }

    private fun openAxion() {
        val s = svc ?: return
        runCatching { s.startActivity(Intent(s, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    }

    // ---- drag + dock ----

    private fun attachDrag(col: LinearLayout) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        col.setOnTouchListener { _, e ->
            val p = lp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (abs(dx) + abs(dy) > 12) moved = true
                    if (moved) { p.x = startX + dx; p.y = startY + dy; runCatching { wm?.updateViewLayout(col, p) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) dock(col) else openAxion()
                    true
                }
                else -> false
            }
        }
    }

    private fun dock(col: LinearLayout) {
        val s = svc ?: return
        val p = lp ?: return
        val w = s.resources.displayMetrics.widthPixels
        val myW = col.width.takeIf { it > 0 } ?: dp(76)
        p.x = if (p.x + myW / 2 < w / 2) dp(6) else w - myW - dp(6)
        runCatching { wm?.updateViewLayout(col, p) }
        s.getSharedPreferences("assistant_orb", Context.MODE_PRIVATE).edit().putInt("x", p.x).putInt("y", p.y).apply()
    }

    // ---- public behaviours ----

    /** Wake word heard: come to the front for a few seconds, then drift back to the dock. */
    fun summon() = main.post {
        val col = root ?: return@post
        val p = lp ?: return@post
        if (homeX < 0) { homeX = p.x; homeY = p.y }
        val s = svc ?: return@post
        val w = s.resources.displayMetrics.widthPixels
        val h = s.resources.displayMetrics.heightPixels
        p.x = w - (col.width.takeIf { it > 0 } ?: dp(76)) - dp(14)
        p.y = (h * 0.38f).toInt()
        runCatching { wm?.updateViewLayout(col, p) }
        mood = Mood.SUMMONED
        summonBack?.let { main.removeCallbacks(it) }
        summonBack = Runnable {
            val pp = lp ?: return@Runnable
            if (homeX >= 0) { pp.x = homeX; pp.y = homeY; runCatching { wm?.updateViewLayout(col, pp) } }
            if (mood == Mood.SUMMONED) mood = Mood.IDLE
        }
        main.postDelayed(summonBack!!, 3200)
    }

    /** A message announcement pops out of the character. */
    fun showMessage(line: String) = main.post {
        val b = bubble ?: return@post
        b.text = line
        b.visibility = View.VISIBLE
        bubbleHide?.let { main.removeCallbacks(it) }
        bubbleHide = Runnable { bubble?.visibility = View.GONE }
        main.postDelayed(bubbleHide!!, 6000)
    }

    /** Phone-run surface: returns false when the character is not up (caller falls back). */
    fun runShow(): Boolean {
        if (root == null) return false
        main.post {
            mood = Mood.WORKING
            stopChip?.visibility = View.VISIBLE
        }
        return true
    }

    fun runStatus(lines: List<String>, confirmLabel: String?) {
        if (root == null) return
        main.post {
            val latest = lines.lastOrNull()?.takeIf { it.isNotBlank() } ?: "Working…"
            bubble?.let { it.text = latest; it.visibility = View.VISIBLE }
            if (confirmLabel != null) {
                mood = Mood.CONFIRM
                confirmChip?.let { it.text = "CONFIRM"; it.visibility = View.VISIBLE }
            } else {
                if (mood == Mood.CONFIRM) mood = Mood.WORKING
                confirmChip?.visibility = View.GONE
            }
        }
    }

    fun runHide() {
        if (root == null) return
        main.post {
            mood = Mood.IDLE
            stopChip?.visibility = View.GONE
            confirmChip?.visibility = View.GONE
            bubble?.visibility = View.GONE
        }
    }

    // ---- the character's face (original Canvas art) ----

    private class FaceView(ctx: Context) : View(ctx) {
        private val handler = Handler(Looper.getMainLooper())
        private var running = false
        private var phase = 0f
        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                phase += 0.055f
                invalidate()
                handler.postDelayed(this, 50)
            }
        }
        fun start() { if (!running) { running = true; handler.post(tick) } }
        fun stop() { running = false; handler.removeCallbacks(tick) }

        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xFF9BE15D.toInt() }
        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF101410.toInt() }
        private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4.5f; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2f; val cy = h / 2f
            val moodNow = mood
            val pulse = when (moodNow) {
                Mood.SUMMONED -> 1f + 0.07f * kotlin.math.sin(phase * 2.4).toFloat()
                Mood.WORKING -> 1f + 0.045f * kotlin.math.sin(phase * 3.2).toFloat()
                Mood.CONFIRM -> 1f + 0.06f * kotlin.math.sin(phase * 5.0).toFloat()
                Mood.IDLE -> 1f + 0.028f * kotlin.math.sin(phase).toFloat()
            }
            val r = min(w, h) / 2f * 0.92f * pulse
            // soft outer glow
            glowPaint.shader = RadialGradient(cx, cy, r * 1.35f, intArrayOf(if (moodNow == Mood.CONFIRM) 0x55FFB020 else 0x559BE15D, 0x00000000), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 1.35f, glowPaint)
            // body: glossy dark sphere, light from the top-left
            bodyPaint.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.25f, intArrayOf(0xFF4A5A48.toInt(), 0xFF232B22.toInt(), 0xFF12160F.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, bodyPaint)
            canvas.drawCircle(cx, cy, r - 2.5f, rimPaint.apply { color = if (moodNow == Mood.CONFIRM) 0xFFFFB020.toInt() else 0xFF9BE15D.toInt() })
            // blink: eyes close briefly every ~3.4 s
            val blinkCycle = (phase * 20f).toInt() % 68
            val eyeOpen = if (blinkCycle == 0) 0.12f else 1f
            val lookUp = if (moodNow == Mood.SUMMONED || moodNow == Mood.WORKING) -r * 0.03f else 0f
            val eyeY = cy - r * 0.12f + lookUp
            val eyeDX = r * 0.34f
            val eyeR = r * 0.135f
            for (sx in listOf(-1f, 1f)) {
                val ex = cx + sx * eyeDX
                canvas.drawOval(ex - eyeR, eyeY - eyeR * eyeOpen, ex + eyeR, eyeY + eyeR * eyeOpen, eyePaint)
                if (eyeOpen > 0.5f) canvas.drawCircle(ex, eyeY, eyeR * 0.52f, pupilPaint)
            }
            // mouth: gentle smile idle; small open oval while working; flat line on confirm
            when (moodNow) {
                Mood.IDLE, Mood.SUMMONED -> canvas.drawArc(cx - r * 0.26f, cy + r * 0.08f, cx + r * 0.26f, cy + r * 0.44f, 15f, 150f, false, mouthPaint)
                Mood.WORKING -> canvas.drawOval(cx - r * 0.1f, cy + r * 0.18f, cx + r * 0.1f, cy + r * 0.36f, mouthPaint.apply { style = Paint.Style.FILL })
                Mood.CONFIRM -> canvas.drawLine(cx - r * 0.2f, cy + r * 0.28f, cx + r * 0.2f, cy + r * 0.28f, mouthPaint)
            }
            mouthPaint.style = Paint.Style.STROKE
        }
    }
}
