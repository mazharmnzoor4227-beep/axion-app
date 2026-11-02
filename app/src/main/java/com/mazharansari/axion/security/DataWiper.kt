package com.mazharansari.axion.security

import android.app.ActivityManager
import android.content.Context
import com.mazharansari.axion.data.KeyVault
import java.io.File

/**
 * v0.5.2 "Delete all data": keys, tokens, Antigravity sign-ins, chats, projects, uploads, Linux, tools, models, settings —
 * everything Axion keeps — then Android's own "clear app data", which also closes the app.
 * Step 1 deletes the Keystore key, so even a file that somehow survived can never be decrypted again.
 */
object DataWiper {
    data class Report(val filesDeleted: Int, val bytes: Long, val keystoreWiped: Boolean, val androidCleared: Boolean)

    /** The folders wiped (tests check this list). */
    fun roots(ctx: Context): List<File> = listOfNotNull(
        ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir, ctx.codeCacheDir,
        File(ctx.applicationInfo.dataDir, "databases"), File(ctx.applicationInfo.dataDir, "shared_prefs"),
        ctx.getExternalFilesDir(null), ctx.externalCacheDir,
    ).distinct()

    fun wipe(ctx: Context, clearAndroid: Boolean = true): Report {
        KeyVault.wipe()
        var n = 0; var bytes = 0L
        for (root in roots(ctx)) {
            if (!root.exists()) continue
            // Linux files can be read-only (chmod 555 dirs from packages): make them writable first.
            root.walkTopDown().forEach { f -> if (f.isDirectory) runCatching { f.setWritable(true, true); f.setExecutable(true, true) } }
            root.walkBottomUp().forEach { f ->
                if (f == root) return@forEach
                val len = if (f.isFile) f.length() else 0L
                if (java.nio.file.Files.isSymbolicLink(f.toPath()) || f.isFile) { if (f.delete()) { n++; bytes += len } }
                else f.delete()
            }
        }
        val cleared = clearAndroid && runCatching { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData() }.getOrDefault(false)
        return Report(n, bytes, true, cleared)
    }
}
