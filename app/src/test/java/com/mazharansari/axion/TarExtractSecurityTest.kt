package com.mazharansari.axion

import com.mazharansari.axion.linux.LinuxRuntime
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * P0-8 (HI-6): extractTarGz used to exempt symlinks from its path-escape check
 * (`require(… || e.isSymbolicLink)`) and never validated the symlink TARGET, so a malicious
 * tar could plant a link pointing outside the destination and a later entry written through
 * it would escape the sandbox. These tests build malicious tars the same way an attacker
 * would and assert extraction refuses them — and that a legitimate in-destination symlink
 * still works.
 */
class TarExtractSecurityTest {

    private fun buildTar(tgz: File, add: TarArchiveOutputStream.() -> Unit) {
        TarArchiveOutputStream(GZIPOutputStream(tgz.outputStream())).use { it.add() }
    }

    private fun TarArchiveOutputStream.file(name: String, data: ByteArray) {
        val e = TarArchiveEntry(name).apply { size = data.size.toLong(); mode = "100644".toInt(8) }
        putArchiveEntry(e); write(data); closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.symlink(name: String, target: String) {
        val e = TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { setLinkName(target) }
        putArchiveEntry(e); closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.hardlink(name: String, target: String) {
        val e = TarArchiveEntry(name, TarConstants.LF_LINK).apply { setLinkName(target) }
        putArchiveEntry(e); closeArchiveEntry()
    }

    private fun assertRefused(tgz: File, dest: File, what: String) {
        try {
            LinuxRuntime.extractTarGz(tgz, dest)
            fail("extraction must refuse: $what")
        } catch (_: IllegalArgumentException) {
            // expected: the require() in extractTarGz
        }
    }

    @Test fun absoluteSymlinkEscapeRefused() {
        val work = Files.createTempDirectory("p0-8-").toFile()
        try {
            val dest = File(work, "dest").apply { mkdirs() }
            val outside = File(work, "outside").apply { mkdirs() }
            val tgz = File(work, "evil.tgz")
            // the attack from the audit: a symlink to /data/data/… (here: a sibling dir outside
            // dest) plus a file written through it
            buildTar(tgz) { symlink("evil", outside.absolutePath); file("evil/pwned.txt", "pwned".toByteArray()) }
            assertRefused(tgz, dest, "symlink to an absolute path outside the destination")
            assertFalse("nothing may be written through the refused link", File(outside, "pwned.txt").exists())
            assertFalse("the malicious link itself must not be planted", File(dest, "evil").exists())
        } finally { work.deleteRecursively() }
    }

    @Test fun relativeSymlinkEscapeRefused() {
        val work = Files.createTempDirectory("p0-8-").toFile()
        try {
            val dest = File(work, "dest").apply { mkdirs() }
            val outside = File(work, "outside").apply { mkdirs() }
            val tgz = File(work, "evil.tgz")
            buildTar(tgz) { symlink("sub/link", "../../outside"); file("sub/link/pwned.txt", "pwned".toByteArray()) }
            assertRefused(tgz, dest, "relative symlink whose resolved target escapes the destination")
            assertFalse(File(outside, "pwned.txt").exists())
        } finally { work.deleteRecursively() }
    }

    @Test fun dotdotSymlinkEntryRefused() {
        val work = Files.createTempDirectory("p0-8-").toFile()
        try {
            val dest = File(work, "dest").apply { mkdirs() }
            val tgz = File(work, "evil.tgz")
            // the entry's own path escapes — the old code waved symlinks through unchecked
            buildTar(tgz) { symlink("../evil", "x") }
            assertRefused(tgz, dest, "symlink entry named ../evil")
            assertFalse(File(work, "evil").exists())
        } finally { work.deleteRecursively() }
    }

    @Test fun absoluteHardLinkEscapeRefused() {
        val work = Files.createTempDirectory("p0-8-").toFile()
        try {
            val dest = File(work, "dest").apply { mkdirs() }
            val tgz = File(work, "evil.tgz")
            buildTar(tgz) { file("real.txt", "real".toByteArray()); hardlink("bad", "/etc/passwd") }
            assertRefused(tgz, dest, "hard link whose target is an absolute path outside the destination")
        } finally { work.deleteRecursively() }
    }

    @Test fun legitSymlinkInsideDestStillWorks() {
        val work = Files.createTempDirectory("p0-8-").toFile()
        try {
            val dest = File(work, "dest").apply { mkdirs() }
            val tgz = File(work, "ok.tgz")
            buildTar(tgz) { file("real.txt", "hello".toByteArray()); symlink("link", "real.txt"); hardlink("hard", "real.txt") }
            LinuxRuntime.extractTarGz(tgz, dest)
            assertTrue(Files.isSymbolicLink(File(dest, "link").toPath()))
            assertEquals("hello", File(dest, "link").readText())
            assertEquals("hello", File(dest, "hard").readText())
            assertEquals("hello", File(dest, "real.txt").readText())
        } finally { work.deleteRecursively() }
    }
}
