package com.mazharansari.axion.security

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * v0.5.2: screens where a key, token or password is typed or shown block screenshots, screen recording and the
 * Recents thumbnail (FLAG_SECURE). Counted, so two secure screens on the stack don't clear each other's flag.
 */
object SecureWindow {
    private var count = 0
    @Volatile var debugActive = false; private set   // tests

    fun enter(a: Activity?) { count++; debugActive = true; a?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    fun leave(a: Activity?) {
        count = (count - 1).coerceAtLeast(0)
        if (count == 0) { debugActive = false; a?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.findActivity(); else -> null }

/** Put this at the top of any screen or sheet where a secret is entered or shown. */
@Composable
fun SecureScreen() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val a = ctx.findActivity()
        SecureWindow.enter(a)
        onDispose { SecureWindow.leave(a) }
    }
}

/**
 * Copying a secret: Android 13+ hides it from the clipboard preview (EXTRA_IS_SENSITIVE) and Axion clears the clipboard
 * after [clearAfterMs] if it still holds what we copied. Plain text that happens to contain a key gets the same care.
 */
object SecureClipboard {
    const val CLEAR_AFTER_MS = 45_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun copy(ctx: Context, label: String, text: String, sensitive: Boolean = SecretScanner.contains(text), clearAfterMs: Long = CLEAR_AFTER_MS): Boolean {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
        if (sensitive) main.postDelayed({ clearIfOurs(cm, text) }, clearAfterMs)
        return sensitive
    }

    internal fun clearIfOurs(cm: ClipboardManager, text: String) = runCatching {
        val now = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (now == text) { if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", "")) }
    }
}
