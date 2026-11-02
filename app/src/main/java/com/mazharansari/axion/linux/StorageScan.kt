package com.mazharansari.axion.linux

import android.content.Context
import com.mazharansari.axion.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** v0.5.3 Settings → Storage: what takes space on the phone, item by item. */
object StorageScan {
    /** Bytes under [f], never following symlinks (Ubuntu's rootfs has absolute links that point back into itself). */
    fun bytes(f: File): Long {
        val p = f.toPath()
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0
        var total = 0L
        runCatching {
            Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { if (attrs.isRegularFile) total += attrs.size(); return FileVisitResult.CONTINUE }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        return total
    }
    fun bytes(files: List<File>): Long = files.sumOf { bytes(it) }

    /** Inside Ubuntu: caches that are safe to delete any time (apt downloads and lists, pip/npm caches, temp files). */
    val LINUX_CACHE_PATHS = listOf("var/cache/apt/archives", "var/cache/apt/pkgcache.bin", "var/cache/apt/srcpkgcache.bin", "var/lib/apt/lists",
        "root/.cache", "root/.npm/_cacache", "root/.npm/_logs", "var/log/apt")

    /** Pure: "1.4 GB", "512 MB", "820 KB". */
    fun label(b: Long): String = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", b / 1073741824.0)
        b >= 10L shl 20 -> "${b shr 20} MB"
        b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024 -> "${b shr 10} KB"
        b > 0 -> "$b B"
        else -> "0 MB"
    }

    /** Pure: Ubuntu's own share = the whole Linux folder minus the tools measured inside it (never below 0). */
    fun linuxBase(rootfsBytes: Long, tools: Long, cache: Long): Long = (rootfsBytes - tools - cache).coerceAtLeast(0)
}

enum class StorageKind { APP, LINUX, AGY, TOOL, CHATS, FILES, MODEL, CACHE }

data class StorageItem(
    val id: String, val kind: StorageKind, val title: String, val subtitle: String, val bytes: Long,
    /** null = can't be deleted here (with the reason in [subtitle]). */
    val deleteLabel: String? = null,
    val confirm: String? = null,
)

/** Measures every item (slow on a full Linux: run off the main thread) and deletes one on request. */
class StorageRepo(private val ctx: Context, private val g: Graph) {
    private val cacheDirs get() = listOf(ctx.cacheDir, ctx.codeCacheDir, File(ctx.filesDir, "linux/download"))
    private fun linuxCaches() = StorageScan.LINUX_CACHE_PATHS.map { File(g.linux.rootfs, it) }

    suspend fun scan(): List<StorageItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<StorageItem>()
        val ai = ctx.applicationInfo
        val app = (ai.sourceDir?.let { File(it).length() } ?: 0L) + (ai.nativeLibraryDir?.let { StorageScan.bytes(File(it)) } ?: 0L)
        out += StorageItem("app", StorageKind.APP, "Axion app", "The app itself. Remove it from Android Settings to free this.", app)

        val linuxOn = g.linux.isInstalled()
        val records = g.devtools.records.value
        val cache = StorageScan.bytes(cacheDirs) + (if (linuxOn) StorageScan.bytes(linuxCaches()) else 0L)
        var toolsTotal = 0L
        if (linuxOn) {
            val agyFile = File(g.linux.rootfs, "root/.local/bin/agy")
            val agy = StorageScan.bytes(agyFile)
            if (agy > 0 || g.devtools.isInstalled("agy")) {
                toolsTotal += agy
                out += StorageItem("agy", StorageKind.AGY, "Antigravity CLI", "agy ${records["agy"]?.version ?: ""}".trim() + " · your Antigravity sign-ins are kept", agy,
                    "Delete", "Delete the Antigravity CLI (${StorageScan.label(agy)})? Your Antigravity accounts stay saved; Axion downloads the CLI again the next time it's needed.")
            }
            Toolchain.entries.filter { it != Toolchain.AGY && g.devtools.isInstalled(it.id) }.forEach { t ->
                val b = (records[t.id]?.sizeKb ?: 0L) * 1024
                toolsTotal += b
                out += StorageItem("tool:${t.id}", StorageKind.TOOL, t.title, records[t.id]?.version ?: t.tech, b, "Delete",
                    "Delete ${t.title} (${StorageScan.label(b)})? You can install it again from Developer tools.")
            }
            val root = StorageScan.bytes(g.linux.rootfs)
            val base = StorageScan.linuxBase(root, toolsTotal, StorageScan.bytes(linuxCaches()))
            out.add(1, StorageItem("linux", StorageKind.LINUX, "Linux (Ubuntu)", "Ubuntu 24.04 and its base tools", base,
                "Delete", "Delete Linux and every developer tool in it (${StorageScan.label(root)})? Antigravity accounts, chats and projects stay. Linux downloads again when a tool needs it."))
        }
        val chats = StorageScan.bytes(File(ctx.filesDir, "chats"))
        out += StorageItem("chats", StorageKind.CHATS, "Chats", "${g.chats.index.value.size} chats", chats, if (chats > 0) "Delete all" else null,
            "Delete all chats? This can't be undone.")
        val files = StorageScan.bytes(listOf(File(ctx.filesDir, "workspace"), File(ctx.filesDir, "projects")))
        out += StorageItem("files", StorageKind.FILES, "Files and projects", "Uploads, created files and Build projects. Manage them in Library and Build.", files)
        com.mazharansari.axion.local.LocalModel.entries.forEach { m ->
            val f = g.localModels.file(m)
            val b = StorageScan.bytes(f) + StorageScan.bytes(File(f.path + ".part"))
            if (b > 0) out += StorageItem("model:${m.id}", StorageKind.MODEL, m.title, "Local model · ${m.params}", b, "Delete",
                "Delete ${m.title} (${StorageScan.label(b)})? You can download it again in Local models.")
        }
        out += StorageItem("cache", StorageKind.CACHE, "Cache", "Temporary files, downloads and Linux package caches. Safe to clear.", cache, if (cache > 0) "Clear cache" else null)
        out
    }

    /** Deletes one item. Returns a short message for the user. */
    suspend fun delete(item: StorageItem): String = withContext(Dispatchers.IO) {
        when (item.kind) {
            StorageKind.AGY -> { File(g.linux.rootfs, "root/.local/bin/agy").delete(); File(g.linux.rootfs, "usr/local/bin/agy").delete(); g.devtools.forget("agy"); "Antigravity CLI deleted" }
            StorageKind.TOOL -> { Toolchain.byId(item.id.removePrefix("tool:"))?.let { g.devtools.uninstall(it) }; "Removing ${item.title}… (Developer tools shows progress)" }
            StorageKind.LINUX -> { g.linux.uninstallAll(); "Linux deleted" }
            StorageKind.CHATS -> { g.chats.deleteAll(); "All chats deleted" }
            StorageKind.MODEL -> { com.mazharansari.axion.local.LocalModel.entries.firstOrNull { "model:${it.id}" == item.id }?.let { g.localModels.delete(it) }; "${item.title} deleted" }
            StorageKind.CACHE -> { clearCache(); "Cache cleared" }
            else -> "Can't delete this here"
        }
    }

    /** Clears app caches and Linux package caches (not while a tool install is running: apt may be using them). */
    fun clearCache() {
        cacheDirs.forEach { d -> d.listFiles()?.forEach { LinuxSlim.deleteNoFollow(it) } }
        if (g.linux.isInstalled() && !g.devtools.busy.value) linuxCaches().forEach { f ->
            if (Files.isDirectory(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) f.listFiles()?.forEach { c -> if (c.name != "partial" && c.name != "lock") LinuxSlim.deleteNoFollow(c) }
            else f.delete()
        }
        runCatching { File(g.linux.rootfs, "tmp").setWritable(true, false) }
    }
}
