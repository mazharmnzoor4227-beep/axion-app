package com.mazharansari.axion.phone

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v0.6 (Phase 3.2) the always-visible control strip while Axion drives the phone: a big red STOP button and the live step log.
 * Drawn as an accessibility overlay (needs no extra "draw over apps" permission); touches outside the strip pass through to the app.
 */
class PhoneOverlay(private val svc: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var logView: TextView? = null
    private var confirmView: TextView? = null
    private fun dp(v: Int) = (v * svc.resources.displayMetrics.density).toInt()

    fun show() = main.post {
        if (root != null) return@post
        val wm = svc.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(Color.parseColor("#E6121212")); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.parseColor("#44FFFFFF")) }
        }
        val stop = TextView(svc).apply {
            text = "STOP"; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); textSize = 17f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#E5252A")); cornerRadius = dp(12).toFloat() }
            setPadding(dp(18), dp(12), dp(18), dp(12)); contentDescription = "Stop Axion controlling the phone"
            setOnClickListener { PhoneController.stop("You pressed STOP") }
        }
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        val title = TextView(svc).apply { text = "Axion is controlling your phone"; setTextColor(Color.parseColor("#FFB3B3B3")); textSize = 11f }
        val log = TextView(svc).apply { setTextColor(Color.WHITE); textSize = 13f; maxLines = 3; minWidth = dp(190); maxWidth = dp(230); text = "Starting…" }
        col.addView(title); col.addView(log); box.addView(stop); box.addView(col)
        // v0.6.1: the user's own CONFIRM button — appears only while an irreversible tap waits for the user's press (see PhoneGate.awaitUserConfirm)
        val confirm = TextView(svc).apply {
            setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); textSize = 15f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#1F9D4D")); cornerRadius = dp(12).toFloat() }
            setPadding(dp(16), dp(11), dp(16), dp(11)); visibility = View.GONE
            contentDescription = "Confirm the action Axion is waiting to do"
            setOnClickListener { PhoneController.confirmPending() }
        }
        val wrap = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(box)
        val clp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        wrap.addView(confirm, clp)
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(44) }
        runCatching { wm.addView(wrap, lp); root = wrap; logView = log; confirmView = confirm }
    }

    fun update(lines: List<String>, confirmLabel: String? = null) = main.post {
        logView?.text = lines.joinToString("\n").ifBlank { "Working…" }
        val c = confirmView ?: return@post
        if (confirmLabel != null) { c.text = "CONFIRM: ${confirmLabel.take(24)}"; c.visibility = View.VISIBLE } else c.visibility = View.GONE
    }

    fun hide() = main.post {
        val r = root ?: return@post; root = null; logView = null; confirmView = null
        runCatching { (svc.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager).removeView(r) }
    }
}
