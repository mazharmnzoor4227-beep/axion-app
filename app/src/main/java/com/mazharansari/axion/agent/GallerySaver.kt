package com.mazharansari.axion.agent

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** v0.6 (Phase 1.6): puts a finished video / audio / image where the phone's Gallery and music apps find it (Movies/Axion, Music/Axion, Pictures/Axion). */
object GallerySaver {
    data class Where(val folder: String, val label: String)

    fun target(mime: String): Where? = when {
        mime.startsWith("video/") -> Where("Movies", "Gallery · Movies/Axion")
        mime.startsWith("audio/") -> Where("Music", "Music · Music/Axion")
        mime.startsWith("image/") -> Where("Pictures", "Gallery · Pictures/Axion")
        else -> null
    }

    /** Returns a short place label on success, null when the file is not media or saving failed. */
    fun save(ctx: Context, src: File, mime: String, name: String = src.name): String? {
        val t = target(mime) ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val collection = when { mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI; mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI }
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, t.folder + "/Axion"); put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(collection, v) ?: error("no uri")
                ctx.contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                t.label
            } else {
                // Android 8–9 (this app targets SDK 28, no storage permission asked): the app's own media folder, scanned so players can see it.
                val dir = ctx.getExternalFilesDir(if (mime.startsWith("video/")) Environment.DIRECTORY_MOVIES else if (mime.startsWith("audio/")) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_PICTURES) ?: error("no storage")
                val out = File(dir, name); src.copyTo(out, overwrite = true)
                MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), arrayOf(mime), null)
                "Saved in ${out.parentFile?.absolutePath}"
            }
        }.getOrNull()
    }
}
