package com.mazharansari.axion.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.mazharansari.axion.AxionApp
import com.mazharansari.axion.MainActivity

/**
 * Phase C (v0.8.0) — background work: keeps chat and Build runs alive while the owner is in
 * another screen, another app, or has swiped Axion away.
 *
 * The runs themselves live in ChatViewModel / the app graph (they already outlive any single
 * screen); this service's one job is to keep the PROCESS alive while [RunRegistry] says work
 * is in flight: a foreground notification ("Axion is working — …") plus a partial wake lock.
 * It starts on the registry's 0→1 edge and stops on the 1→0 edge, so it never idles.
 *
 * Honest platform limits: Settings → Force stop ends everything (Android rule, no app can
 * override it), and a phone reboot mid-run does not auto-resume — the partial reply is saved
 * and marked interrupted, as today.
 */
class WorkService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            runCatching { (application as AxionApp).graph.runs.cancelAll() }
            releaseWake()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val titles = currentTitles()
        if (titles.isEmpty()) {
            // Nothing in flight (stale start): do not linger as a foreground service.
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification(titles)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        acquireWake()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWake()
        super.onDestroy()
    }

    private fun currentTitles(): List<String> =
        runCatching { (application as AxionApp).graph.runs.active.value.map { it.title } }.getOrDefault(emptyList())

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "axion:work").apply {
            setReferenceCounted(false)
            // Backstop only: runs unregister as they settle, and the service stops with them;
            // the timeout just guarantees a leaked lock can never drain the battery overnight.
            acquire(3L * 60 * 60 * 1000)
        }
    }

    private fun releaseWake() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun buildNotification(titles: List<String>): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH, "Background work", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAll = PendingIntent.getService(
            this, 1, Intent(this, WorkService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (titles.size == 1) titles[0] else "${titles.size} tasks running"
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Axion is working")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(titles.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop all", stopAll)
            .build()
    }

    companion object {
        private const val CH = "work"
        private const val ID = 4403
        private const val ACTION_STOP_ALL = "axion.work.STOP_ALL"

        /** Re-post the notification with the current run titles (called on registry edges). */
        fun refresh(ctx: Context) {
            runCatching {
                val i = Intent(ctx, WorkService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun start(ctx: Context) = refresh(ctx)

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, WorkService::class.java)) }
        }
    }
}
