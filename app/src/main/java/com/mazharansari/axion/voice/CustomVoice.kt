package com.mazharansari.axion.voice

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * P2-6 (voice calls: full parity + the user's own voice): the slot for the user's
 * own voice in Settings. The user's voice clone is a SEPARATE workstream (a test
 * sample was delivered, verdict pending) — this file only HOLDS a user-supplied
 * voice file/model and hands it to an engine when one exists. It never claims a
 * clone is done, never invents a voice, and never generates one.
 *
 * - No voice set: the option shows as "Not set up" and read-aloud uses the picked
 *   catalog voice, exactly as before.
 * - Voice set, no engine installed: read-aloud says so once ("Your own voice is
 *   set up, but no voice engine for it is installed yet") and reads in the picked
 *   voice instead.
 * - Voice set AND an engine registered in [CustomVoiceEngines]: the engine reads
 *   the answer in the user's own voice.
 */
@Serializable
data class CustomVoice(
    val name: String,
    val filePath: String,
    /** "audio" = a reference recording; "model" = a voice model file */
    val kind: String,
    val addedAt: Long,
)

/** A voice engine that can speak in a [CustomVoice]. None ships in the app yet. */
interface CustomVoiceEngine {
    fun canSpeak(voice: CustomVoice): Boolean

    /** Reads [text] aloud in [voice]; true when the whole text was spoken. */
    suspend fun speak(context: Context, voice: CustomVoice, text: String): Boolean
}

object CustomVoiceEngines {
    /** Set by a voice engine when one is installed; null until then. */
    var engine: CustomVoiceEngine? = null
}

class CustomVoiceStore(private val dir: File) {
    private val file = File(dir, "custom-voice.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _current = MutableStateFlow(runCatching { json.decodeFromString<CustomVoice>(file.readText()) }.getOrNull())
    val current: StateFlow<CustomVoice?> = _current.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun set(voice: CustomVoice) {
        _current.value = voice
        io.launch {
            synchronized(file) {
                dir.mkdirs()
                runCatching { file.writeText(json.encodeToString(voice)) }
            }
        }
    }

    /** Removes the slot (and the stored file, unless [deleteFile] is false). */
    fun clear(deleteFile: Boolean = true) {
        val old = _current.value
        _current.value = null
        io.launch {
            runCatching { file.delete() }
            if (deleteFile) runCatching { old?.let { File(it.filePath).delete() } }
        }
    }

    /** The file the slot points at — still on disk and non-empty. */
    fun isUsable(): Boolean = _current.value?.let { v ->
        File(v.filePath).let { it.isFile && it.length() > 0 }
    } == true
}

/** Recordings/models up to 100 MB — a reference recording is a few MB; a full model file is a job for a later engine. */
const val CUSTOM_VOICE_MAX_BYTES = 100L * 1024L * 1024L

/** A file name safe to store under the app's private dir (no separators, no ".."). */
fun sanitizeVoiceFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "my-voice" }
    return base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "my-voice" }
}

/**
 * Copies a user-picked file into the app's private custom-voice dir and registers
 * it in [store]. Returns an error message for the UI, or null on success. The
 * picked file is never read as audio here — that is the (not yet installed)
 * engine's job.
 */
suspend fun installCustomVoice(ctx: Context, uri: Uri, store: CustomVoiceStore): String? = withContext(Dispatchers.IO) {
    val dir = File(ctx.filesDir, "custom-voice")
    dir.mkdirs()
    val rawName = uri.lastPathSegment?.substringAfterLast('/') ?: "my-voice"
    val name = sanitizeVoiceFileName(rawName)
    val dest = File(dir, name)
    val ok = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
        dest.isFile && dest.length() > 0
    }.getOrDefault(false)
    if (!ok) {
        runCatching { dest.delete() }
        return@withContext "Could not read that file."
    }
    if (dest.length() > CUSTOM_VOICE_MAX_BYTES) {
        runCatching { dest.delete() }
        return@withContext "That file is over 100 MB — pick a shorter recording."
    }
    val kind = if (name.endsWith(".mp3", true) || name.endsWith(".wav", true) || name.endsWith(".m4a", true) ||
        name.endsWith(".ogg", true) || name.endsWith(".flac", true)
    ) "audio" else "model"
    store.set(CustomVoice(name = name, filePath = dest.absolutePath, kind = kind, addedAt = System.currentTimeMillis()))
    null
}
