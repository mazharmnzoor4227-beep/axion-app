package com.mazharansari.axion.ui.home

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mazharansari.axion.data.Artifact
import java.io.File

object FileActions {
    /** Copies a produced file into the phone's public Downloads folder. Returns a message for the toast. */
    fun download(ctx: Context, a: Artifact): String = runCatching {
        val src = File(a.path)
        com.mazharansari.axion.security.SecretScanner.leaveCheck(src)?.let { return it }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, a.name)
                put(MediaStore.Downloads.MIME_TYPE, a.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Axion")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            "Saved to Downloads/Axion/${a.name}"
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("no storage")
            src.copyTo(File(dir, a.name), overwrite = true)
            "Saved to ${dir.absolutePath}/${a.name}"
        }
    }.getOrElse { "Couldn't save: ${it.message}" }
}
