package com.mazharansari.axion

import com.mazharansari.axion.browser.ObscuraInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * 0.9.6: the pure parts of ObscuraInstaller — the verified release constants,
 * the version-marker logic, and the hash-mismatch rule (delete the file, fail
 * loudly). No network, no Linux: the real download/install runs on the phone.
 */
class ObscuraInstallerTest {

    @Test fun constantsMatchVerifiedRelease() {
        assertEquals(
            "https://github.com/h4ckf0r0day/obscura/releases/download/v0.2.7/obscura-aarch64-linux.tar.gz",
            ObscuraInstaller.URL,
        )
        assertEquals(69_952_302L, ObscuraInstaller.EXPECTED_BYTES)
        assertEquals(
            "4ee0aeeb38fa8bb2cb004ba104abe1c79f1bdfd02f51672c694e8234a5ae0e5c",
            ObscuraInstaller.EXPECTED_SHA256,
        )
        assertEquals(64, ObscuraInstaller.EXPECTED_SHA256.length)
        assertTrue(ObscuraInstaller.EXPECTED_SHA256.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("0.2.7", ObscuraInstaller.VERSION)
        assertEquals(listOf("obscura", "obscura-worker"), ObscuraInstaller.BINARIES)
        assertTrue(
            "the free-space guard must cover the download plus the unpacked tree",
            ObscuraInstaller.NEEDS_FREE_BYTES > ObscuraInstaller.EXPECTED_BYTES,
        )
    }

    private fun tempRootfs(): File = File.createTempFile("obscura-rootfs", "").apply { delete(); mkdirs() }

    @Test fun markerRoundTrip() {
        val rootfs = tempRootfs()
        try {
            assertNull(ObscuraInstaller.readMarker(rootfs))
            assertFalse(ObscuraInstaller.isInstalled(rootfs))
            ObscuraInstaller.writeMarker(rootfs)
            assertEquals("0.2.7", ObscuraInstaller.readMarker(rootfs))
            assertFalse("binaries missing: not installed", ObscuraInstaller.isInstalled(rootfs))
            File(rootfs, "usr/local/bin").mkdirs()
            File(rootfs, "usr/local/bin/obscura").writeText("x")
            assertFalse("worker missing: not installed", ObscuraInstaller.isInstalled(rootfs))
            File(rootfs, "usr/local/bin/obscura-worker").writeText("x")
            assertTrue(ObscuraInstaller.isInstalled(rootfs))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test fun markerVersionMismatchIsNotInstalled() {
        val rootfs = tempRootfs()
        try {
            File(rootfs, "opt/axion").mkdirs()
            File(rootfs, ObscuraInstaller.MARKER).writeText("0.2.6")
            File(rootfs, "usr/local/bin").mkdirs()
            File(rootfs, "usr/local/bin/obscura").writeText("x")
            File(rootfs, "usr/local/bin/obscura-worker").writeText("x")
            assertFalse(ObscuraInstaller.isInstalled(rootfs))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test fun checkDownloadRejectsWrongSizeAndDeletes() {
        val dir = tempRootfs()
        try {
            val f = File(dir, "obscura.tar.gz").apply { writeText("too small") }
            try {
                ObscuraInstaller.checkDownload(f)
                fail("wrong size must fail")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("incomplete"))
            }
            assertFalse("a bad download must be deleted", f.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun checkDownloadRejectsWrongHashAndDeletes() {
        val dir = tempRootfs()
        try {
            // A sparse file of exactly the release size: instant, no 70 MB of writes.
            val f = File(dir, "obscura.tar.gz")
            RandomAccessFile(f, "rw").use { it.setLength(ObscuraInstaller.EXPECTED_BYTES) }
            assertEquals(ObscuraInstaller.EXPECTED_BYTES, f.length())
            try {
                ObscuraInstaller.checkDownload(f)
                fail("wrong hash must fail")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("checksum"))
            }
            assertFalse("a bad download must be deleted", f.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
