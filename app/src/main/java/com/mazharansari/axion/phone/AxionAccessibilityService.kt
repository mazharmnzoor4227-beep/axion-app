package com.mazharansari.axion.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v0.6 (Phase 3.2) PHONE CONTROL through Android's official Accessibility API. Original Axion code (design idea only from Mika, Apache-2.0 — see
 * third_party/NOTICE-mika.md). The user turns this service on themselves in Android Settings; it does nothing by itself: it acts only when
 * [PhoneController]'s run token is active (a chat task the user started and is watching, with the STOP overlay showing).
 * Enforcement lives here too: if a banking/payment/wallet app comes to the front during a run, the service presses Home and stops the run.
 */
class AxionAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: AxionAccessibilityService? = null
        val connected get() = instance != null
    }
    private val nodes = HashMap<Int, AccessibilityNodeInfo>()
    private lateinit var overlay: PhoneOverlay

    override fun onServiceConnected() {
        instance = this; overlay = PhoneOverlay(this); PhoneController.attach(this, overlay)
    }
    override fun onUnbind(intent: Intent?): Boolean {
        PhoneController.stop("The Accessibility service was turned off"); PhoneController.detach(); instance = null; return super.onUnbind(intent)
    }
    override fun onDestroy() { PhoneController.detach(); instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        if (!PhoneController.active) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = e.packageName?.toString()
            if (pkg != null && pkg != packageName && (PhoneSafety.isDeniedPackage(pkg) || PhoneSafety.isDeniedLabel(labelOf(pkg)))) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                PhoneController.gate.log("✋ A banking / payment app opened — Home")
                PhoneController.stop("A banking, payment or wallet app came to the front. Axion never operates those, so it stopped")
            }
        }
    }

    // ---- reading ----
    fun labelOf(pkg: String): String = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    fun readScreen(): PhoneScreen? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        nodes.clear(); val out = ArrayList<PhoneSafety.Node>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            n ?: return
            if (out.size >= 150 || depth > 40) return
            if (n.isVisibleToUser) {
                val text = n.text?.toString().orEmpty(); val desc = n.contentDescription?.toString().orEmpty()
                val interesting = text.isNotBlank() || desc.isNotBlank() || n.isClickable || n.isEditable || n.isScrollable
                if (interesting) {
                    val id = out.size + 1; nodes[id] = n
                    val r = Rect(); n.getBoundsInScreen(r)
                    out += PhoneSafety.Node(id, text, desc, n.viewIdResourceName.orEmpty(), n.isClickable, n.isEditable, n.isPassword, n.isScrollable,
                        if (n.isCheckable) n.isChecked else null, n.className?.toString().orEmpty(), r.left, r.top, r.right, r.bottom)
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return PhoneScreen(pkg, labelOf(pkg), out)
    }

    // ---- acting ----
    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? { var c: AccessibilityNodeInfo? = n; var d = 0; while (c != null && d < 7) { if (c.isClickable) return c; c = c.parent; d++ }; return null }

    suspend fun clickNode(id: Int): Boolean {
        val n = nodes[id] ?: return false
        n.refresh()
        val target = clickable(n)
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect(); n.getBoundsInScreen(r); if (r.isEmpty) return false
        return gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 60)
    }

    suspend fun typeInto(id: Int, text: String, submit: Boolean): Boolean {
        val n = nodes[id] ?: return false
        n.refresh(); if (n.isPassword) return false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS); n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        if (submit) {
            if (Build.VERSION.SDK_INT >= 30) n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            else rootInActiveWindow?.let { root -> findSubmitButton(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        }
        return true
    }
    private fun findSubmitButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (w in listOf("search", "go", "enter", "done")) root.findAccessibilityNodeInfosByText(w).firstOrNull { it.isClickable && (it.text ?: it.contentDescription)?.toString()?.equals(w, true) == true }?.let { return it }
        return null
    }

    suspend fun scrollScreen(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var best: AccessibilityNodeInfo? = null; var bestArea = 0
        fun walk(n: AccessibilityNodeInfo?) { n ?: return; if (n.isScrollable && n.isVisibleToUser) { val r = Rect(); n.getBoundsInScreen(r); val a = r.width() * r.height(); if (a > bestArea) { best = n; bestArea = a } }; for (i in 0 until n.childCount) walk(n.getChild(i)) }
        walk(root)
        val fwd = direction == "down" || direction == "right"
        best?.let { if (it.performAction(if (fwd) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true }
        val r = Rect(); root.getBoundsInScreen(r); if (r.isEmpty) return false
        val cx = r.exactCenterX(); val cy = r.exactCenterY(); val dx = r.width() * 0.3f; val dy = r.height() * 0.3f
        val p = Path().apply { when (direction) { "down" -> { moveTo(cx, cy + dy); lineTo(cx, cy - dy) }; "up" -> { moveTo(cx, cy - dy); lineTo(cx, cy + dy) }; "right" -> { moveTo(cx + dx, cy); lineTo(cx - dx, cy) }; else -> { moveTo(cx - dx, cy); lineTo(cx + dx, cy) } } }
        return gesture(p, 300)
    }

    private suspend fun gesture(path: Path, ms: Long): Boolean {
        val done = CompletableDeferred<Boolean>()
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done.complete(true) }
            override fun onCancelled(d: GestureDescription?) { done.complete(false) }
        }, null)
        if (!ok) return false
        return withTimeoutOrNull(3000) { done.await() } ?: false
    }

    fun findApps(query: String): List<AppRef> {
        val pm = packageManager; val q = query.trim().lowercase()
        if (q in setOf("browser", "web browser", "internet", "web")) {
            val def = pm.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            if (def != null && def != "android") return listOf(AppRef(labelOf(def), def))
        }
        val launcher = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { AppRef(it.loadLabel(pm).toString(), it.activityInfo.packageName) }.distinctBy { it.pkg }
        val exact = launcher.filter { it.label.equals(query.trim(), true) || it.pkg.equals(query.trim(), true) }
        if (exact.isNotEmpty()) return exact
        return launcher.filter { it.label.lowercase().startsWith(q) } + launcher.filter { !it.label.lowercase().startsWith(q) && it.label.lowercase().contains(q) }
    }

    fun launchApp(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { startActivity(i); true }.getOrDefault(false)
    }
}

/** The real backend for [PhoneActions]. */
object ServiceBackend : PhoneBackend {
    private val s get() = AxionAccessibilityService.instance
    override val connected get() = s != null
    override suspend fun snapshot() = s?.readScreen()
    override suspend fun click(id: Int) = s?.clickNode(id) ?: false
    override suspend fun type(id: Int, text: String, submit: Boolean) = s?.typeInto(id, text, submit) ?: false
    override suspend fun scroll(direction: String) = s?.scrollScreen(direction) ?: false
    override suspend fun back() = s?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
    override suspend fun home() = s?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
    override fun findApps(query: String) = s?.findApps(query) ?: emptyList()
    override suspend fun launch(pkg: String) = s?.launchApp(pkg) ?: false
}
