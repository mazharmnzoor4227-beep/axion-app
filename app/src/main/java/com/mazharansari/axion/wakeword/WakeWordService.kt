package com.mazharansari.axion.wakeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.mazharansari.axion.MainActivity
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Phase B — "Hey Axion" wake word.
 *
 * A microphone foreground service that listens ONLY for the wake phrase (Vosk offline grammar
 * spotting, no audio ever leaves the device) while the owner has the Settings toggle on. When
 * [WakePhraseMatcher] accepts a recognition result, the app opens straight into the Live voice
 * call — the same route the in-app mic button takes — and this service steps aside (the call
 * owns the microphone from there).
 *
 * Honest platform facts (also told to the owner): Android requires a persistent notification
 * while the microphone is open, so "Listening for Hey Axion" stays in the shade the whole time
 * the toggle is on, and there is a real battery cost. Keeping the service alive across a
 * swipe-away is best-effort (START_STICKY); a force-stop from Settings ends it like any app.
 *
 * The speech model is NOT bundled (41 MB): it downloads once, on first enable, into app
 * storage. Until the model is ready the service shows "Preparing voice model…" and retries
 * the download in the background.
 */
class WakeWordService : Service(), RecognitionListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val retryHandler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    @Volatile private var preparing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWith(if (modelReady()) "Listening for “Hey Axion”" else "Preparing voice model…")
        ensureListening()
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        retryHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        running = false
        super.onDestroy()
    }

    // ---- Vosk -----------------------------------------------------------------------------

    private fun modelDir(): File = File(filesDir, "vosk-model")
    private fun modelReady(): Boolean = modelDir().let { it.isDirectory && File(it, "am").exists() }

    private fun ensureListening() {
        if (speech != null) return
        // v0.8.1: without the microphone permission the recognizer dies silently (v0.7.2's
        // onError swallowed it) while the notification kept claiming "Listening" — say so honestly.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startForegroundWith("Microphone permission needed — open Axion to allow it")
            return
        }
        if (!modelReady()) {
            downloadModel()
            return
        }
        runCatching {
            if (model == null) {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                model = Model(modelDir().absolutePath)
            }
            val grammar = JSONArray(WAKE_GRAMMAR).toString()
            val rec = Recognizer(model, 16000f, grammar)
            speech = SpeechService(rec, 16000f).also { it.startListening(this) }
            startForegroundWith("Listening for “Hey Axion”")
        }.onFailure {
            // Corrupt or partial model: drop it and fetch a fresh copy on the next pass.
            runCatching { modelDir().deleteRecursively() }
            downloadModel()
        }
    }

    private fun downloadModel() {
        if (preparing) return
        preparing = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(cacheDir, "vosk-model.zip")
                    // v0.8.0: a single connection is very slow on some networks (the same crawl
                    // the app update had) — fetch over parallel ranged connections when the
                    // host allows it, else the plain stream.
                    val fetched = runCatching { com.mazharansari.axion.llm.ParallelFetch.fetch(com.mazharansari.axion.llm.Http.download, MODEL_URL, tmp) }.getOrDefault(false)
                    if (!fetched) URL(MODEL_URL).openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    val dest = modelDir()
                    dest.deleteRecursively()
                    dest.mkdirs()
                    var root: File? = null
                    ZipInputStream(tmp.inputStream().buffered()).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            val out = File(dest, e.name)
                            if (e.isDirectory) out.mkdirs() else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zip.copyTo(it) }
                                if (root == null && e.name.endsWith("/am/final.mdl")) root = out.parentFile?.parentFile
                            }
                            zip.closeEntry()
                        }
                    }
                    tmp.delete()
                    // The zip nests one top-level folder; flatten it so modelDir() IS the model.
                    val nested = dest.listFiles()?.singleOrNull { it.isDirectory }
                    if (nested != null && root == nested) {
                        nested.listFiles()?.forEach { it.renameTo(File(dest, it.name)) }
                        nested.deleteRecursively()
                    }
                    modelReady()
                }.getOrDefault(false)
            }
            preparing = false
            if (ok) ensureListening() else {
                startForegroundWith("Voice model download failed — retrying")
                // v0.8.1: the retry is real now — v0.7.2 said "will retry" but nothing ever did.
                retryHandler.removeCallbacksAndMessages(null)
                retryHandler.postDelayed({ downloadModel() }, 60_000)
            }
        }
    }

    private fun stopListening() {
        runCatching { speech?.stop() }
        runCatching { speech?.shutdown() }
        speech = null
    }

    // ---- RecognitionListener ---------------------------------------------------------------

    override fun onPartialResult(hypothesis: String?) {
        // v0.8.1 BUG FIX: partial hypotheses carry the phrase in "partial", not "text" — v0.7.2
        // read "text" for every callback, so live partial wakes NEVER matched and only a paused,
        // finalized utterance could ever trigger. That is the main reason "Hey Axion" felt dead.
        if (hypothesis != null && WakePhraseMatcher.isWakePhrase(extractField(hypothesis, "partial"))) onWake()
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis != null && WakePhraseMatcher.isWakePhrase(extractField(hypothesis, "text"))) onWake()
    }

    override fun onFinalResult(hypothesis: String?) {
        if (hypothesis != null && WakePhraseMatcher.isWakePhrase(extractField(hypothesis, "text"))) onWake()
    }

    override fun onError(exception: Exception?) {
        // v0.8.1: never die silently under a "Listening" notification — stop and say what happened.
        stopListening()
        startForegroundWith("Wake word stopped — reopen Axion to restart it")
    }

    override fun onTimeout() {}

    private fun extractField(json: String, field: String): String =
        runCatching { org.json.JSONObject(json).optString(field) }.getOrDefault("")

    private fun onWake() {
        stopListening()
        com.mazharansari.axion.assistant.AssistantOrb.summon()   // v0.8.7: the character comes to the front when called
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_START_CALL, true)
        runCatching { startActivity(open) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Foreground notification -----------------------------------------------------------

    private fun startForegroundWith(text: String) {
        val n = buildNotification(text)
        // v0.8.1: a sticky restart in the background can be refused the microphone type on
        // Android 14+ — never let that refusal crash the process; fall back, then give up cleanly.
        val typed = runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID, n)
        }.isSuccess
        if (!typed && runCatching { startForeground(ID, n) }.isFailure) stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH, "Wake word", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Axion · Wake word")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop listening", stop)
            .build()
    }

    companion object {
        /** MainActivity reads this and routes straight to the Live voice call. */
        const val EXTRA_START_CALL = "axion.start_call"
        private const val CH = "wakeword"
        private const val ID = 4402
        private const val ACTION_STOP = "axion.wakeword.STOP"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

        /** Grammar: only wake attempts are ever recognized, so stray speech costs nothing. */
        private val WAKE_GRAMMAR = listOf(
            "hey axion", "hey action", "hay axion", "a axion", "ok axion", "okay axion", "axion", "[unk]",
        )

        /** Live state for the Settings toggle: is the service instance up right now. */
        @Volatile var running: Boolean = false
            private set

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, WakeWordService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(ACTION_STOP)) }
        }
    }

    init {
        running = true
    }
}
