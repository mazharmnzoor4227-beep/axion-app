package com.mazharansari.axion.build

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class DemoState { IDLE, WAITING_PERMISSION, RECORDING, READY, ERROR }

data class DemoRecording(val file: File, val startedAt: Long, val durationMs: Long)

/** Pure state machine for the demo flow (unit-tested without Android). */
class DemoStateMachine {
    var state: DemoState = DemoState.IDLE
        private set
    var error: String? = null
        private set

    /** The user tapped "Record demo": ask the system for screen-capture permission. */
    fun requestPermission(): Boolean {
        if (state != DemoState.IDLE && state != DemoState.READY && state != DemoState.ERROR) return false
        state = DemoState.WAITING_PERMISSION
        error = null
        return true
    }

    fun permissionDenied() {
        if (state == DemoState.WAITING_PERMISSION) state = DemoState.IDLE
    }

    fun started() {
        if (state == DemoState.WAITING_PERMISSION) state = DemoState.RECORDING
    }

    fun stopped(file: File, startedAt: Long, durationMs: Long): DemoRecording? {
        if (state != DemoState.RECORDING) return null
        state = DemoState.READY
        return DemoRecording(file, startedAt, durationMs)
    }

    fun failed(msg: String) {
        state = DemoState.ERROR
        error = msg
    }

    fun reset() {
        state = DemoState.IDLE
        error = null
    }
}

/**
 * P2-5 (one-tap build demo): after a Builder run, one tap records a short screen
 * video of the app being built on the phone + the preview, then shares it.
 * The video is recorded on the user's phone with MediaProjection — nothing is
 * uploaded, and no video is ever faked: if recording fails, the UI says so.
 */
object BuildDemo {
    /** A demo is at most 2 minutes — the service stops itself there. */
    const val MAX_DURATION_MS = 120_000L
    /** Capture is downscaled to at most 720 px wide (small file, still sharp). */
    const val MAX_WIDTH = 720

    /** Under cacheDir/share/ so the FileProvider ("share" path) can hand it to other apps. */
    fun demosDir(ctx: Context): File = File(ctx.cacheDir, "share/demos").apply { mkdirs() }

    fun newFile(ctx: Context, at: Long = System.currentTimeMillis()): File =
        File(demosDir(ctx), "build-demo-$at.mp4")

    /** The latest finished recording, set by DemoRecordService when it stops. */
    private val _lastRecording = MutableStateFlow<DemoRecording?>(null)
    val lastRecording: StateFlow<DemoRecording?> = _lastRecording.asStateFlow()
    fun publish(rec: DemoRecording) {
        _lastRecording.value = rec
    }

    /** The latest recording failure, set by DemoRecordService (the UI shows it once). */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    fun publishError(msg: String) {
        _lastError.value = msg
    }

    fun captureIntent(ctx: Context): Intent =
        (ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()

    fun startRecording(ctx: Context, resultCode: Int, data: Intent) {
        DemoRecordService.start(ctx, resultCode, data)
    }

    fun stopRecording(ctx: Context) {
        DemoRecordService.stop(ctx)
    }

    /** Share sheet for a finished demo video (FileProvider URI, read-only for the receiver). */
    fun shareIntent(ctx: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        return Intent(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Capture size for the virtual display: the real screen, downscaled to at most 720 wide (even numbers — the encoder needs them). */
    fun captureSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = (MAX_WIDTH.toDouble() / width).coerceAtMost(1.0)
        val w = ((width * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val h = ((height * scale).toInt() / 2 * 2).coerceAtLeast(2)
        return w to h
    }
}
