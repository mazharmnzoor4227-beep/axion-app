package com.mazharansari.axion.build

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * P2-5 (one-tap build demo): records the screen while the user demos a build.
 * A foreground service of type mediaProjection — the capture keeps running while
 * the user switches between the Chat and Preview tabs, and it stops itself at
 * [BuildDemo.MAX_DURATION_MS]. No video is ever faked: if the projection or the
 * encoder fails, the service publishes an error and the UI says so.
 */
class DemoRecordService : Service() {

    companion object {
        const val ACTION_START = "com.mazharansari.axion.build.DEMO_START"
        const val ACTION_STOP = "com.mazharansari.axion.build.DEMO_STOP"
        private const val EXTRA_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 41
        private const val CHANNEL = "axion-demo"

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, DemoRecordService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DemoRecordService::class.java).setAction(ACTION_STOP))
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var startedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopRecording() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (data == null) {
                    BuildDemo.publishError("Screen recording could not start (no permission result).")
                    stopSelf()
                } else startRecording(code, data)
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        if (projection != null) return // already recording — a second tap does nothing
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mgr.getMediaProjection(resultCode, data)
                ?: run { BuildDemo.publishError("Screen recording was not granted."); stopSelf(); return }
            projection = proj
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            val (w, h) = BuildDemo.captureSize(metrics.widthPixels, metrics.heightPixels)
            val file = BuildDemo.newFile(this)
            val rec = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(w, h)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(4_000_000)
                setOutputFile(file.absolutePath)
                prepare()
            }
            recorder = rec
            outFile = file
            display = proj.createVirtualDisplay(
                "axion-demo", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null,
            )
            startForeground(NOTIF_ID, notification())
            rec.start()
            startedAt = System.currentTimeMillis()
            handler.postDelayed(autoStop, BuildDemo.MAX_DURATION_MS)
        } catch (e: Throwable) {
            cleanup()
            BuildDemo.publishError("Screen recording failed: ${e.message ?: "unknown error"}")
            stopSelf()
        }
    }

    private fun stopRecording() {
        handler.removeCallbacks(autoStop)
        val file = outFile
        val started = startedAt
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { display?.release() }
        runCatching { projection?.stop() }
        cleanup()
        if (file != null && file.isFile && file.length() > 0) {
            BuildDemo.publish(DemoRecording(file, started, System.currentTimeMillis() - started))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        recorder = null
        display = null
        projection = null
        outFile = null
        startedAt = 0L
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Build demo recording", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Recording build demo")
            .setContentText("Axion is recording the screen. Stop it from the Preview tab to share the video.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
