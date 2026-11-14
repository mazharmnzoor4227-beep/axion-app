package com.mazharansari.axion.browser

import android.system.Os
import com.mazharansari.axion.linux.LinuxRuntime
import com.mazharansari.axion.linux.LinuxSlim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * Installs the Obscura browser engine into on-device Linux, lazily, on first
 * Obscura use. The 70 MB release tarball is downloaded (resumable), its SHA-256
 * is checked, and only then is it unpacked into the rootfs and linked onto PATH.
 * Nothing is ever bundled into the APK: the download happens on the phone, once,
 * and a version marker skips the whole install afterwards.
 *
 * Verified release (checked firsthand on the Hermes server, 2026-11-14):
 * obscura v0.2.7, aarch64 Linux, 69,952,302 bytes. The tarball unpacks to two
 * binaries — `obscura` (the MCP server) and `obscura-worker` (the render
 * engine) — and both must be on PATH.
 */
object ObscuraInstaller {
    const val VERSION = "0.2.7"
    const val URL = "https://github.com/h4ckf0r0day/obscura/releases/download/v0.2.7/obscura-aarch64-linux.tar.gz"
    const val EXPECTED_BYTES = 69_952_302L
    const val EXPECTED_SHA256 = "4ee0aeeb38fa8bb2cb004ba104abe1c79f1bdfd02f51672c694e8234a5ae0e5c"
    /** The version marker inside the rootfs. */
    const val MARKER = "opt/axion/.obscura-version"
    /**
     * Free space required before installing. The peak is the 70 MB download plus
     * the ~153 MB unpacked tree, so 250 MB leaves headroom instead of filling
     * the phone mid-install.
     */
    const val NEEDS_FREE_BYTES = 250L * 1024 * 1024
    val BINARIES = listOf("obscura", "obscura-worker")

    fun markerFile(rootfs: File): File = File(rootfs, MARKER)

    fun readMarker(rootfs: File): String? =
        runCatching { markerFile(rootfs).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()

    /** True when the marker matches this version and both binaries are on PATH in the rootfs. */
    fun isInstalled(rootfs: File): Boolean {
        if (readMarker(rootfs) != VERSION) return false
        return BINARIES.all { File(rootfs, "usr/local/bin/$it").exists() }
    }

    fun writeMarker(rootfs: File) {
        markerFile(rootfs).apply { parentFile?.mkdirs(); writeText(VERSION) }
    }

    /**
     * The downloaded tarball is exactly the release, byte for byte: right size
     * and right SHA-256. A mismatch deletes the file and fails loudly, so a
     * damaged download can never be installed and the next try starts clean.
     */
    fun checkDownload(file: File) {
        if (!file.isFile || file.length() != EXPECTED_BYTES) {
            file.delete()
            error("The Obscura download is incomplete (expected $EXPECTED_BYTES bytes). Try again — it resumes.")
        }
        if (!LinuxRuntime.sha256(file).equals(EXPECTED_SHA256, ignoreCase = true)) {
            file.delete()
            error("The Obscura download was damaged (checksum mismatch). The file was deleted — try again.")
        }
    }

    /** Installs Obscura when it is not already installed; returns at once when the marker matches. */
    suspend fun ensureInstalled(linux: LinuxRuntime, onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (isInstalled(linux.rootfs)) return@withContext
        check(linux.isInstalled()) { "Linux is not installed yet — install it in Developer tools first." }
        val free = linux.freeBytes()
        check(free >= NEEDS_FREE_BYTES) {
            "Not enough space: Obscura needs about ${NEEDS_FREE_BYTES / 1048576} MB free, the phone has ${free / 1048576} MB."
        }
        val dest = File(linux.home, "download/obscura.tar.gz")
        LinuxRuntime.downloadResumable(URL, dest, EXPECTED_BYTES) { done, total ->
            onProgress("Downloading Obscura… ${done / 1048576} of ${total / 1048576} MB")
        }
        onProgress("Checking the Obscura download…")
        checkDownload(dest)
        onProgress("Unpacking Obscura…")
        // Unpack into a staging dir first, then move it into place — a failed
        // unpack never leaves a half-installed engine behind. deleteNoFollow
        // never follows a symlink out of the tree (the rootfs is full of them).
        val staging = File(linux.home, "obscura.staging").apply { LinuxSlim.deleteNoFollow(this); mkdirs() }
        LinuxRuntime.extractTarGz(dest, staging)
        val target = File(linux.rootfs, "opt/axion/obscura")
        LinuxSlim.deleteNoFollow(target)
        target.parentFile?.mkdirs()
        check(staging.renameTo(target)) { "Could not move Obscura into place." }
        val bin = File(linux.rootfs, "usr/local/bin").apply { mkdirs() }
        BINARIES.forEach { name ->
            val src = File(target, name)
            runCatching { Os.chmod(src.absolutePath, "755".toInt(8)) }
            val link = File(bin, name)
            if (!link.exists() && !Files.isSymbolicLink(link.toPath())) {
                runCatching { Os.symlink("/opt/axion/obscura/$name", link.absolutePath) }
            }
        }
        dest.delete()
        writeMarker(linux.rootfs)
        onProgress("Obscura is ready")
    }
}
