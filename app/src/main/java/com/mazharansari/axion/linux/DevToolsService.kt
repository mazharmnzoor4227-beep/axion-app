package com.mazharansari.axion.linux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Keeps toolchain installs alive while the user leaves the app; shows "Installing Python… 40 %" in the shade. */
class DevToolsService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopForeground(true); stopSelf(); return START_NOT_STICKY }
        startForeground(ID, build(this, intent?.getStringExtra("text") ?: "Setting up developer tools…", intent?.getIntExtra("pct", -1) ?: -1))
        return START_NOT_STICKY
    }

    companion object {
        private const val CH = "devtools"; private const val ID = 4401; private const val STOP = "stop"
        @Volatile private var running = false
        private var lastUpdate = 0L

        private fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "Developer tools", NotificationManager.IMPORTANCE_LOW))
        }

        fun build(ctx: Context, text: String, pct: Int): Notification {
            channel(ctx)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            return b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Axion · Developer tools").setContentText(text)
                .setOngoing(true).setOnlyAlertOnce(true).apply { if (pct in 0..100) setProgress(100, pct, false) else setProgress(0, 0, true) }.build()
        }

        fun start(ctx: Context) {
            if (running) return
            running = true
            runCatching {
                val i = Intent(ctx, DevToolsService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun update(ctx: Context, text: String, pct: Int) {
            val now = System.currentTimeMillis(); if (now - lastUpdate < 700) return; lastUpdate = now
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, text, pct)) }
        }

        fun stop(ctx: Context) {
            if (!running) return
            running = false
            runCatching { ctx.startService(Intent(ctx, DevToolsService::class.java).setAction(STOP)) }
        }
    }
}
