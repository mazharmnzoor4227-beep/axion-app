package com.mazharansari.axion.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.mazharansari.axion.MainActivity
import com.mazharansari.axion.wakeword.WakeWordService

/**
 * v0.8.4 — the phone-control surface as a SYSTEM notification (owner's correction, 2026-11-07:
 * he asked for Android's own dynamic bar, not a custom-drawn lookalike). No app can draw inside
 * the system's dynamic island — the system renders it from notifications — so the control strip
 * is now an ongoing, high-importance system notification: live status as its text, and STOP /
 * CONFIRM / Talk as its action buttons. Whether his HyperOS build lifts it into the island is
 * the system's decision; the notification itself is the platform's own surface either way.
 *
 * [PhoneOverlay] uses this whenever the app's notifications are enabled, and falls back to its
 * drawn strip only when notifications are off — a phone run must never be left without a STOP.
 */
object PhoneControlNotify {
    private const val CH = "phonecontrol"
    private const val ID = 4404

    fun available(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun show(ctx: Context, lines: List<String>, confirmLabel: String?, alert: Boolean = false) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Phone control", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows while Axion is controlling the phone, with its STOP button"
            })
        }
        val latest = lines.lastOrNull()?.takeIf { it.isNotBlank() } ?: "Working…"
        fun pi(req: Int, intent: Intent, activity: Boolean): PendingIntent =
            if (activity) PendingIntent.getActivity(ctx, req, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            else PendingIntent.getBroadcast(ctx, req, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openApp = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val stopPi = pi(1, Intent(ctx, PhoneActionReceiver::class.java).setAction(PhoneActionReceiver.ACTION_STOP), activity = false)
        val talkPi = pi(2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(WakeWordService.EXTRA_START_CALL, true), activity = true)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH) else @Suppress("DEPRECATION") Notification.Builder(ctx)
        b.setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Axion is controlling your phone")
            .setContentText(latest)
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n").ifBlank { "Working…" }))
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(pi(0, openApp, activity = true))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPi)
            .addAction(android.R.drawable.ic_btn_speak_now, "Talk", talkPi)
        if (confirmLabel != null) {
            val confirmPi = pi(3, Intent(ctx, PhoneActionReceiver::class.java).setAction(PhoneActionReceiver.ACTION_CONFIRM), activity = false)
            b.addAction(android.R.drawable.checkbox_on_background, "CONFIRM: ${confirmLabel.take(18)}", confirmPi)
        }
        runCatching { nm.notify(ID, b.build()) }
    }

    fun hide(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(ID) }
    }
}
