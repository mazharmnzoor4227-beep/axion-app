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
 * v0.8.8 Phase F — Axion's floating character, corrected on the owner's word (2026-11-08):
 *  1. It must NOT live on the screen permanently. It APPEARS when called ("Hey Axion"), while
 *     a call or a phone run is active, or briefly for a message — and then it is GONE.
 *  2. A real character, not two eyes and a smile on a dark ball: a glossy shaded 3D-look
 *     sphere (violet body, specular light, rim light, antenna) with a full set of EMOTIONS —
 *     happy, listening, speaking, focused, surprised, angry, sad, crying — each with its own
 *     brows, eyes, mouth, tint and motion.
 * Original Axion code and art (Canvas), hosted in an accessibility-overlay window like the
 * phone-control strip, so no extra "draw over apps" permission is needed.
 *
 * Visibility is holder-based: summon / call / run / message each hold the window up; when the
 * last holder lets go, the window is removed. Drag + edge positions are remembered.
 */
object AssistantOrb {
    enum class Emotion { HAPPY, LISTENING, SPEAKING, WORKING, SURPRISED, ANGRY, SAD, CRYING }

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
    @Volatile var emotion: Emotion = Emotion.HAPPY
        private set
    @Volatile var shownAtMs: Long = 0L
        private set

    val isShowing: Boolean get() = root != null

    private fun baseEmotion(): Emotion = when {
        "call" in holders -> Emotion.SPEAKING
        "run" in holders -> Emotion.WORKING
        "summon" in holders -> Emotion.LISTENING
        else -> Emotion.HAPPY
    }

    /** A timed emotion (angry at a blocked app, sad on a stop…) that falls back to the base. */
    fun showEmotion(e: Emotion, holdMs: Long = 2600) {
        main.post {
            emotion = e
            emotionTimer?.let { main.removeCallbacks(it) }
            emotionTimer = Runnable { emotion = baseEmotion() }
            main.postDelayed(emotionTimer!!, holdMs)
        }
    }

    fun attach(service: AccessibilityService) {
        svc = service
        enabledWanted = runCatching { (service.application as com.mazharansari.axion.AxionApp).graph.settings.state.value.assistantOrb }.getOrDefault(true)
        // v0.8.8: do NOT build here — the character appears only when something calls it.
    }

    fun detach() = main.post {
        holders.clear()
        removeWindow()
        svc = null
    }

    fun setEnabled(on: Boolean) {
        enabledWanted = on
        main.post { if (!on) { holders.clear(); removeWindow() } }
    }

    // ---- holders ----

    private fun acquire(holder: String) {
        if (!enabledWanted || svc == null) return
        holders += holder
        if (root == null) build()
        if (emotionTimer == null) emotion = baseEmotion()
    }

    private fun release(holder: String) {
        holders -= holder
        emotion = baseEmotion()
        if (holders.isEmpty()) removeWindow()
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
                    setStroke(dp(1), 0xFFB8F53D.toInt())
                }
                setOnClickListener { openAxion() }
            }
            col.addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
            val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            stopChip = chip(s, "STOP", 0xFFD43D2A.toInt()) { PhoneController.stop("You pressed STOP") }
            confirmChip = chip(s, "CONFIRM", 0xFFB8F53D.toInt()) { PhoneController.confirmPending() }
            face = FaceView(s)
            row.addView(stopChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
            row.addView(face, LinearLayout.LayoutParams(dp(84), dp(84)))
            row.addView(confirmChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
            col.addView(row)
            val disp = s.resources.displayMetrics
            lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("x", disp.widthPixels - dp(100))
                y = prefs.getInt("y", (disp.heightPixels * 0.5f).toInt())
            }
            attachDrag(col)
            wm!!.addView(col, lp)
            root = col
            shownAtMs = System.currentTimeMillis()
            face!!.start()
        }
    }

    private fun chip(s: Context, label: String, bg: Int, onTap: () -> Unit): TextView = TextView(s).apply {
        text = label; setTextColor(if (bg == 0xFFB8F53D.toInt()) Color.BLACK else Color.WHITE)
        textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(10), dp(6), dp(10), dp(6)); visibility = View.GONE
        background = android.graphics.drawable.GradientDrawable().apply { setColor(bg); cornerRadius = dp(12).toFloat() }
        setOnClickListener { onTap() }
    }

    private fun removeWindow() {
        summonTimer?.let { main.removeCallbacks(it) }; summonTimer = null
        messageTimer?.let { main.removeCallbacks(it) }; messageTimer = null
        emotionTimer?.let { main.removeCallbacks(it) }; emotionTimer = null
        face?.stop()
        root?.let { r -> runCatching { wm?.removeView(r) } }
        root = null; face = null; bubble = null; stopChip = null; confirmChip = null; lp = null
        emotion = Emotion.HAPPY
    }

    private fun openAxion() {
        val s = svc ?: return
        runCatching { s.startActivity(Intent(s, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    }

    // ---- drag (position is remembered; there is no permanent dock any more) ----

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
                    if (moved) savePos() else openAxion()
                    true
                }
                else -> false
            }
        }
    }

    private fun savePos() {
        val s = svc ?: return
        val p = lp ?: return
        s.getSharedPreferences("assistant_orb", Context.MODE_PRIVATE).edit().putInt("x", p.x).putInt("y", p.y).apply()
    }

    // ---- public behaviours ----

    /** Wake word heard: appear at the front, listening; drift away again if nothing follows. */
    fun summon() = main.post {
        acquire("summon")
        val col = root ?: return@post
        val p = lp ?: return@post
        val s = svc ?: return@post
        val w = s.resources.displayMetrics.widthPixels
        val h = s.resources.displayMetrics.heightPixels
        p.x = w - (col.width.takeIf { it > 0 } ?: dp(84)) - dp(12)
        p.y = (h * 0.36f).toInt()
        runCatching { wm?.updateViewLayout(col, p) }
        emotion = Emotion.LISTENING
        summonTimer?.let { main.removeCallbacks(it) }
        summonTimer = Runnable {
            release("summon")
            // back to the remembered spot for the next appearance
            val pp = lp; val cc = root
            if (pp != null && cc != null) {
                val prefs = s.getSharedPreferences("assistant_orb", Context.MODE_PRIVATE)
                pp.x = prefs.getInt("x", pp.x); pp.y = prefs.getInt("y", pp.y)
                runCatching { wm?.updateViewLayout(cc, pp) }
            }
        }
        main.postDelayed(summonTimer!!, 9000)
    }

    /** A Live call is being held (even minimized): stay on screen, speaking. */
    fun callActive(on: Boolean) = main.post {
        if (on) {
            summonTimer?.let { main.removeCallbacks(it) }; summonTimer = null
            acquire("call")
        } else {
            release("call")
        }
    }

    /** A message announcement pops out of the character — briefly, then it goes again. */
    fun showMessage(line: String) = main.post {
        acquire("message")
        bubble?.let { it.text = line; it.visibility = View.VISIBLE }
        showEmotion(Emotion.HAPPY, 2200)
        messageTimer?.let { main.removeCallbacks(it) }
        messageTimer = Runnable { bubble?.visibility = View.GONE; release("message") }
        main.postDelayed(messageTimer!!, 6000)
    }

    /** Phone-run surface: returns false when the character is disabled (caller falls back). */
    fun runShow(): Boolean {
        if (!enabledWanted || svc == null) return false
        main.post {
            acquire("run")
            stopChip?.visibility = View.VISIBLE
            emotion = Emotion.WORKING
        }
        return true
    }

    fun runStatus(lines: List<String>, confirmLabel: String?) {
        main.post {
            if ("run" !in holders) return@post
            val latest = lines.lastOrNull()?.takeIf { it.isNotBlank() } ?: "Working…"
            bubble?.let { it.text = latest; it.visibility = View.VISIBLE }
            if (lines.any { it.contains("✋") }) showEmotion(Emotion.ANGRY, 2400)
            if (confirmLabel != null) {
                confirmChip?.visibility = View.VISIBLE
                showEmotion(Emotion.SURPRISED, 2000)
            } else {
                confirmChip?.visibility = View.GONE
            }
        }
    }

    fun runHide() {
        main.post {
            stopChip?.visibility = View.GONE
            confirmChip?.visibility = View.GONE
            bubble?.visibility = View.GONE
            showEmotion(Emotion.HAPPY, 800)
            main.postDelayed({ release("run") }, 850)
        }
    }

    /** Something went wrong mid-task (a dropped call, a failed run): a short cry, then gone. */
    fun cryBriefly() = main.post {
        acquire("message")
        showEmotion(Emotion.CRYING, 2600)
        messageTimer?.let { main.removeCallbacks(it) }
        messageTimer = Runnable { release("message") }
        main.postDelayed(messageTimer!!, 2800)
    }

    // ---- the character (original Canvas art) ----

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

        private val body = Paint(Paint.ANTI_ALIAS_FLAG)
        private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sclera = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val iris = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A1E5C.toInt() }
        private val catch1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val brow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF241A4D.toInt(); strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
        private val mouth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF241A4D.toInt(); strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
        private val mouthFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF241A4D.toInt() }
        private val blush = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FF8FAB }
        private val tear = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6EC6FF.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; color = 0xFFB8F53D.toInt() }
        private val bulb = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val em = emotion
            val age = (System.currentTimeMillis() - shownAtMs).coerceAtLeast(0)
            // pop-in: a quick springy scale when the character appears
            val popT = min(1f, age / 300f)
            val pop = 0.6f + 0.4f * (1f - (1f - popT) * (1f - popT)) + if (popT < 1f) 0.06f * sin(popT * 9f) * (1f - popT) else 0f
            val bob = when (em) {
                Emotion.HAPPY -> sin(phase * 1.4).toFloat() * 3.2f
                Emotion.LISTENING -> sin(phase * 2.6).toFloat() * 2.2f
                Emotion.SPEAKING -> sin(phase * 3.4).toFloat() * 2.6f
                Emotion.SAD, Emotion.CRYING -> sin(phase * 0.9).toFloat() * 1.4f
                else -> sin(phase * 1.1).toFloat() * 2f
            }
            val shakeX = if (em == Emotion.ANGRY) sin(phase * 34f).toFloat() * 2.4f else 0f
            val cx = w / 2f + shakeX
            val cy = h / 2f + bob
            val breathe = 1f + 0.022f * sin(phase).toFloat()
            val r = min(w, h) / 2f * 0.86f * pop * breathe

            // ground shadow (gives the floating 3D feel)
            shadow.color = 0x38000000
            canvas.drawOval(cx - r * 0.72f, h * 0.88f, cx + r * 0.72f, h * 0.97f, shadow)
            // aura glow (red flush when angry, lime when listening)
            val aura = when (em) { Emotion.ANGRY -> 0x66FF5A4D; Emotion.LISTENING -> 0x66B8F53D; Emotion.CRYING -> 0x556EC6FF; else -> 0x447C63F2 }
            glow.shader = RadialGradient(cx, cy, r * 1.45f, intArrayOf(aura, 0x00000000), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 1.45f, glow)
            glow.shader = null
            // working ring
            if (em == Emotion.WORKING) {
                val rr = RectF(cx - r * 1.14f, cy - r * 1.14f, cx + r * 1.14f, cy + r * 1.14f)
                canvas.drawArc(rr, (phase * 130f) % 360f, 105f, false, ring)
                canvas.drawArc(rr, (phase * 130f + 180f) % 360f, 60f, false, ring)
            }
            // body: layered violet sphere, lit from the top-left
            body.shader = RadialGradient(cx - r * 0.38f, cy - r * 0.42f, r * 1.3f,
                intArrayOf(0xFFC9B8FF.toInt(), 0xFF8A6FF0.toInt(), 0xFF5B3EDB.toInt(), 0xFF2E1F6E.toInt()),
                floatArrayOf(0f, 0.38f, 0.72f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, body)
            body.shader = null
            // angry flush
            if (em == Emotion.ANGRY) {
                body.color = 0x3DFF4B33
                canvas.drawCircle(cx, cy, r, body)
                body.color = Color.WHITE
            }
            // bottom rim light (cyan bounce)
            rim.shader = RadialGradient(cx, cy + r * 0.95f, r * 0.9f, intArrayOf(0x6657E6D9, 0x00000000), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, rim)
            rim.shader = null
            // specular highlight
            spec.color = 0x8CFFFFFF.toInt()
            canvas.drawOval(cx - r * 0.52f, cy - r * 0.72f, cx - r * 0.08f, cy - r * 0.34f, spec)
            spec.color = 0x30FFFFFF
            canvas.drawOval(cx - r * 0.6f, cy - r * 0.8f, cx + r * 0.05f, cy - r * 0.25f, spec)
            // antenna with a glowing bulb (glows hardest while listening)
            val ax = cx + r * 0.34f; val ayTop = cy - r * 0.94f
            brow.style = Paint.Style.STROKE
            canvas.drawLine(cx + r * 0.18f, cy - r * 0.82f, ax, ayTop + r * 0.1f, brow)
            val bulbPulse = if (em == Emotion.LISTENING) 0.5f + 0.5f * sin(phase * 6f).toFloat() else 0.35f
            bulb.color = 0xFFB8F53D.toInt()
            bulb.alpha = (120 + 135 * bulbPulse).toInt()
            canvas.drawCircle(ax, ayTop, r * 0.13f * (1f + 0.25f * bulbPulse), bulb)
            bulb.alpha = 255; bulb.color = 0xFFE9FFB0.toInt()
            canvas.drawCircle(ax, ayTop, r * 0.07f, bulb)

            // ---- face geometry per emotion ----
            val eyeY = cy - r * 0.1f
            val eyeDX = r * 0.36f
            val eyeR = r * 0.155f
            val blink = ((phase * 20f).toInt() % 72 == 0)
            val openScale = when {
                blink -> 0.1f
                em == Emotion.WORKING -> 0.78f
                em == Emotion.ANGRY -> 0.62f
                em == Emotion.SAD || em == Emotion.CRYING -> 0.7f
                em == Emotion.SURPRISED -> 1.28f
                em == Emotion.LISTENING -> 1.15f
                else -> 1f
            }
            val lookX = when (em) { Emotion.WORKING -> sin(phase * 0.7).toFloat() * eyeR * 0.3f; Emotion.SAD, Emotion.CRYING -> 0f; else -> sin(phase * 0.45).toFloat() * eyeR * 0.22f }
            val lookY = when (em) { Emotion.LISTENING, Emotion.SURPRISED -> -eyeR * 0.3f; Emotion.SAD, Emotion.CRYING -> eyeR * 0.34f; else -> 0f }
            // cheeks
            if (em == Emotion.HAPPY || em == Emotion.SPEAKING || em == Emotion.LISTENING) {
                canvas.drawCircle(cx - r * 0.52f, cy + r * 0.16f, r * 0.13f, blush)
                canvas.drawCircle(cx + r * 0.52f, cy + r * 0.16f, r * 0.13f, blush)
            }
            // eyes
            for (sx in listOf(-1f, 1f)) {
                val ex = cx + sx * eyeDX
                canvas.drawOval(ex - eyeR, eyeY - eyeR * openScale, ex + eyeR, eyeY + eyeR * openScale, sclera)
                if (openScale > 0.4f) {
                    val px = ex + lookX; val py = eyeY + lookY
                    canvas.drawCircle(px, py, eyeR * 0.58f, iris)
                    canvas.drawCircle(px, py, eyeR * 0.3f, Paint().apply { color = 0xFF120B2E.toInt() })
                    canvas.drawCircle(px - eyeR * 0.18f, py - eyeR * 0.2f, eyeR * 0.16f, catch1)
                }
            }
            // tears (crying): drops fall from both eyes on a loop
            if (em == Emotion.CRYING) {
                for (sx in listOf(-1f, 1f)) {
                    val ex = cx + sx * eyeDX
                    val fall = ((phase * 1.6f + if (sx > 0) 0.5f else 0f) % 1f)
                    val ty = eyeY + eyeR * 0.5f + fall * r * 0.62f
                    tear.alpha = (255 * (1f - fall)).toInt()
                    val tp = Path()
                    tp.moveTo(ex, ty - r * 0.09f)
                    tp.quadTo(ex + r * 0.075f, ty + r * 0.03f, ex, ty + r * 0.1f)
                    tp.quadTo(ex - r * 0.075f, ty + r * 0.03f, ex, ty - r * 0.09f)
                    canvas.drawPath(tp, tear)
                }
                tear.alpha = 255
            }
            // brows: angry/working = inner end DOWN (a V), sad/crying = inner end UP (worried),
            // surprised/listening = lifted flat, happy = nearly flat.
            val browY = eyeY - eyeR * 1.55f
            val browLift = when (em) { Emotion.SURPRISED, Emotion.LISTENING -> -eyeR * 0.5f; Emotion.SAD, Emotion.CRYING -> -eyeR * 0.15f; else -> 0f }
            val innerDy = when (em) {
                Emotion.ANGRY -> eyeR * 0.62f
                Emotion.WORKING -> eyeR * 0.38f
                Emotion.SAD, Emotion.CRYING -> -eyeR * 0.5f
                else -> eyeR * 0.06f
            }
            for (sx in listOf(-1f, 1f)) {
                val bx = cx + sx * eyeDX
                val inner = bx - sx * eyeR * 1.05f
                val outer = bx + sx * eyeR * 1.05f
                canvas.drawLine(outer, browY + browLift - innerDy * 0.45f, inner, browY + browLift + innerDy, brow)
            }
            // mouth
            val my = cy + r * 0.34f
            when (em) {
                Emotion.HAPPY -> canvas.drawArc(cx - r * 0.3f, my - r * 0.2f, cx + r * 0.3f, my + r * 0.24f, 20f, 140f, false, mouth)
                Emotion.LISTENING -> canvas.drawCircle(cx, my + r * 0.04f, r * 0.09f, mouthFill)
                Emotion.SPEAKING -> {
                    val openH = r * (0.1f + 0.09f * (0.5f + 0.5f * sin(phase * 7f).toFloat()))
                    canvas.drawOval(cx - r * 0.13f, my - openH / 2, cx + r * 0.13f, my + openH, mouthFill)
                }
                Emotion.WORKING -> canvas.drawLine(cx - r * 0.16f, my + r * 0.05f, cx + r * 0.16f, my + r * 0.05f, mouth)
                Emotion.SURPRISED -> canvas.drawCircle(cx, my + r * 0.05f, r * 0.12f, mouthFill)
                Emotion.ANGRY -> {
                    val p = Path()
                    val seg = r * 0.11f
                    p.moveTo(cx - seg * 2, my + r * 0.06f)
                    p.lineTo(cx - seg, my - r * 0.02f); p.lineTo(cx, my + r * 0.06f); p.lineTo(cx + seg, my - r * 0.02f); p.lineTo(cx + seg * 2, my + r * 0.06f)
                    canvas.drawPath(p, mouth)
                }
                Emotion.SAD, Emotion.CRYING -> canvas.drawArc(cx - r * 0.26f, my + r * 0.02f, cx + r * 0.26f, my + r * 0.4f, 200f, 140f, false, mouth)
            }
        }
    }
}
