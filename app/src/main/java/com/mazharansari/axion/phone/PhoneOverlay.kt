package com.mazharansari.axion.phone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mazharansari.axion.MainActivity
import com.mazharansari.axion.ui.settings.ThinkingOrbEngine
import com.mazharansari.axion.wakeword.WakeWordService

/**
 * v0.8.1 — the phone-control popup, rebuilt to the owner's reference (2026-11-07): the old card sat
 * in the middle of the screen and covered whatever Axion was driving. Now the control surface is a
 * compact dynamic-bar pill at the top — the owner's own animated orb, one line of live status and a
 * small STOP — that he can DRAG anywhere on screen (it docks to the nearest edge when let go).
 * Tapping the pill opens the full card (status log, a Talk button that opens the Live voice call,
 * the big STOP, and the CONFIRM button); a pending CONFIRM opens the card by itself so it is never
 * hidden. Drawn as an accessibility overlay (no extra "draw over apps" permission); touches outside
 * the pill pass through to the app underneath.
 */
class PhoneOverlay(private val svc: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var card: LinearLayout? = null
    private var pillStatus: TextView? = null
    private var logView: TextView? = null
    private var confirmView: TextView? = null
    private var orbView: OrbView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var expanded = false
    private fun dp(v: Int) = (v * svc.resources.displayMetrics.density).toInt()
    private fun wm() = svc.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

    /** The owner's chosen thinking-orb (wave engine), drawn on a plain canvas for the overlay pill. */
    private inner class OrbView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var startAt = 0L
        private val tick = object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) return
                invalidate()
                main.postDelayed(this, 50)
            }
        }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (startAt == 0L) startAt = System.nanoTime()
            main.removeCallbacks(tick); main.post(tick)
        }
        override fun onDetachedFromWindow() {
            main.removeCallbacks(tick)
            super.onDetachedFromWindow()
        }
        override fun onDraw(canvas: Canvas) {
            val size = minOf(width, height).toFloat()
            if (size <= 0f) return
            val t = (System.nanoTime() - startAt) / 1e9f * 4.388f
            for (d in ThinkingOrbEngine.wave(size, t, 0f)) {
                val w = d.white.coerceIn(0f, 1f)
                // ink -> paper, tinted toward the app's lime so the pill reads as one Axion surface
                val rC = (30 + 190 * w).toInt(); val gC = (90 + 150 * w).toInt(); val bC = (20 + 60 * w).toInt()
                paint.color = Color.argb((d.a * 255).toInt().coerceIn(0, 255), rC, gC, bC)
                canvas.drawCircle(d.x + (width - size) / 2f, d.y + (height - size) / 2f, d.r, paint)
            }
        }
    }

    private fun pillBg() = GradientDrawable().apply { setColor(Color.parseColor("#E6121212")); cornerRadius = dp(999).toFloat(); setStroke(dp(1), Color.parseColor("#44FFFFFF")) }
    private fun cardBg() = GradientDrawable().apply { setColor(Color.parseColor("#F2121212")); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.parseColor("#44FFFFFF")) }

    fun show() = main.post {
        if (root != null) return@post
        expanded = false
        // ---- the pill: orb + one-line status + compact STOP ----------------------------------
        val orb = OrbView(svc)
        val status = TextView(svc).apply {
            setTextColor(Color.WHITE); textSize = 13f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "Starting…"
        }
        val stopSmall = TextView(svc).apply {
            text = "STOP"; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); textSize = 12f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#E5252A")); cornerRadius = dp(999).toFloat() }
            setPadding(dp(12), dp(6), dp(12), dp(6)); contentDescription = "Stop Axion controlling the phone"
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_UP) PhoneController.stop("You pressed STOP")
                true
            }
        }
        val pill = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5)); background = pillBg()
        }
        pill.addView(orb, LinearLayout.LayoutParams(dp(34), dp(34)))
        pill.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8); rightMargin = dp(10) })
        pill.addView(stopSmall)
        status.maxWidth = dp(170)

        // ---- the expanded card: full log + Talk + big STOP + CONFIRM -------------------------
        val title = TextView(svc).apply { text = "Axion is controlling your phone"; setTextColor(Color.parseColor("#FFB3B3B3")); textSize = 11f }
        val log = TextView(svc).apply { setTextColor(Color.WHITE); textSize = 13f; maxLines = 3; minWidth = dp(220); maxWidth = dp(280); text = "Starting…" }
        val talk = TextView(svc).apply {
            text = "Talk to Axion"; setTextColor(Color.parseColor("#0E1A02")); setTypeface(typeface, Typeface.BOLD); textSize = 14f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#B4F224")); cornerRadius = dp(12).toFloat() }
            setPadding(dp(14), dp(10), dp(14), dp(10)); contentDescription = "Open the Live voice call with Axion"
            setOnClickListener {
                val open = Intent(svc, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(WakeWordService.EXTRA_START_CALL, true)
                runCatching { svc.startActivity(open) }
            }
        }
        val stopBig = TextView(svc).apply {
            text = "STOP"; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); textSize = 15f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#E5252A")); cornerRadius = dp(12).toFloat() }
            setPadding(dp(18), dp(10), dp(18), dp(10)); contentDescription = "Stop Axion controlling the phone"
            setOnClickListener { PhoneController.stop("You pressed STOP") }
        }
        val btnRow = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        btnRow.addView(talk, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        btnRow.addView(stopBig)
        val confirm = TextView(svc).apply {
            setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); textSize = 15f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#1F9D4D")); cornerRadius = dp(12).toFloat() }
            setPadding(dp(16), dp(11), dp(16), dp(11)); visibility = View.GONE
            contentDescription = "Confirm the action Axion is waiting to do"
            setOnClickListener { PhoneController.confirmPending() }
        }
        val cardBox = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12)); background = cardBg(); visibility = View.GONE
        }
        cardBox.addView(title)
        cardBox.addView(log, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        cardBox.addView(confirm, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        cardBox.addView(btnRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        val wrap = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(pill)
        wrap.addView(cardBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START; x = dp(120); y = dp(10) }
        // Drag the pill anywhere; a tap (no drag) opens/closes the card.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        pill.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; startX = params.x; startY = params.y; dragged = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt(); val dy = (ev.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(8)) dragged = true
                    if (dragged) {
                        val dm = svc.resources.displayMetrics
                        params.x = (startX + dx).coerceIn(0, maxOf(0, dm.widthPixels - wrap.width))
                        params.y = (startY + dy).coerceIn(0, maxOf(0, dm.heightPixels - wrap.height))
                        runCatching { wm().updateViewLayout(wrap, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) dockToEdge(wrap, params) else { expanded = !expanded; cardBox.visibility = if (expanded) View.VISIBLE else View.GONE }
                    true
                }
                else -> false
            }
        }
        runCatching {
            wm().addView(wrap, params)
            root = wrap; card = cardBox; pillStatus = status; logView = log; confirmView = confirm; orbView = orb; lp = params
            // Start centred like the dynamic bar; once measured, centre on the real pill width.
            wrap.post {
                val dm = svc.resources.displayMetrics
                params.x = ((dm.widthPixels - wrap.width) / 2).coerceAtLeast(0)
                runCatching { wm().updateViewLayout(wrap, params) }
            }
        }
    }

    /** On release, slide the pill to the nearest left/right edge so it rests like a docked bubble. */
    private fun dockToEdge(wrap: View, params: WindowManager.LayoutParams) {
        val dm = svc.resources.displayMetrics
        val target = if (params.x + wrap.width / 2 < dm.widthPixels / 2) 0 else maxOf(0, dm.widthPixels - wrap.width)
        val from = params.x
        if (from == target) return
        val steps = 8
        for (i in 1..steps) {
            main.postDelayed({
                params.x = from + (target - from) * i / steps
                runCatching { wm().updateViewLayout(wrap, params) }
            }, (i * 16).toLong())
        }
    }

    fun update(lines: List<String>, confirmLabel: String? = null) = main.post {
        val latest = lines.lastOrNull()?.takeIf { it.isNotBlank() } ?: "Working…"
        pillStatus?.text = latest
        logView?.text = lines.joinToString("\n").ifBlank { "Working…" }
        val c = confirmView ?: return@post
        if (confirmLabel != null) {
            c.text = "CONFIRM: ${confirmLabel.take(24)}"; c.visibility = View.VISIBLE
            // A waiting CONFIRM must never hide inside a collapsed pill: open the card itself.
            if (!expanded) { expanded = true; card?.visibility = View.VISIBLE }
        } else c.visibility = View.GONE
    }

    fun hide() = main.post {
        val r = root ?: return@post
        root = null; card = null; pillStatus = null; logView = null; confirmView = null; orbView = null; lp = null; expanded = false
        runCatching { wm().removeView(r) }
    }
}
