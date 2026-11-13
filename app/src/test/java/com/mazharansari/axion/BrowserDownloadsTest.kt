package com.mazharansari.axion

import com.mazharansari.axion.browser.BrowserDownloads
import com.mazharansari.axion.browser.DownloadState
import com.mazharansari.axion.browser.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-7 (ME-9): download file names, the Downloads/Axion folder rule, and progress maths. */
class BrowserDownloadsTest {
    @Test fun downloadFolderIsAxion() {
        assertEquals("Axion", BrowserDownloads.DIR)
    }

    @Test fun progressPercentIsBounded() {
        assertEquals(0, BrowserDownloads.progressPercent(0, 1000))
        assertEquals(50, BrowserDownloads.progressPercent(500, 1000))
        assertEquals(100, BrowserDownloads.progressPercent(1000, 1000))
        assertEquals(100, BrowserDownloads.progressPercent(1500, 1000))
        assertEquals(-1, BrowserDownloads.progressPercent(100, 0))
        assertEquals(-1, BrowserDownloads.progressPercent(100, -1))
    }

    @Test fun downloadStateExposesProgress() {
        assertEquals(25, DownloadState(1, "https://x.example/f.zip", "f.zip", 250, 1000).progressPercent)
        assertEquals(-1, DownloadState(1, "https://x.example/f.zip", "f.zip", 10, -1).progressPercent)
    }

    @Test fun fileNamePrefersContentDisposition() {
        assertEquals(
            "report 2026.pdf",
            BrowserDownloads.fileNameFor("https://x.example/download?id=7", "attachment; filename=\"report 2026.pdf\"", null),
        )
    }

    @Test fun fileNameHandlesRfc5987Disposition() {
        assertEquals(
            "my file.pdf",
            BrowserDownloads.fileNameFor("https://x.example/dl", "attachment; filename*=UTF-8''my%20file.pdf", null),
        )
    }

    @Test fun fileNameFallsBackToUrl() {
        assertEquals("photo.jpg", BrowserDownloads.fileNameFor("https://x.example/img/photo.jpg?size=large", null, null))
        assertEquals("archive.zip", BrowserDownloads.fileNameFor("https://x.example/files/archive.zip#part", null, null))
    }

    @Test fun fileNameFallsBackToMimeThenPlain() {
        assertEquals("download.pdf", BrowserDownloads.fileNameFor("https://x.example/dl", null, "application/pdf"))
        assertEquals("download", BrowserDownloads.fileNameFor("https://x.example/", null, null))
    }

    @Test fun sanitizeRemovesSeparatorsAndTraversal() {
        assertFalse(BrowserDownloads.sanitizeFileName("../../etc/passwd").contains('/'))
        assertFalse(BrowserDownloads.sanitizeFileName("..\\secret.txt").contains('\\'))
        assertEquals("download", BrowserDownloads.sanitizeFileName("..."))
        assertEquals("a_b_c.txt", BrowserDownloads.sanitizeFileName("a/b:c.txt"))
        assertEquals("a_b.txt", BrowserDownloads.sanitizeFileName("a\"b.txt"))
    }

    @Test fun uniqueFileNameNumbersCollisions() {
        val existing = setOf("photo.jpg", "photo (1).jpg")
        assertEquals("other.jpg", BrowserDownloads.uniqueFileName(existing, "other.jpg"))
        assertEquals("photo (2).jpg", BrowserDownloads.uniqueFileName(existing, "photo.jpg"))
        assertEquals("notes (1)", BrowserDownloads.uniqueFileName(setOf("notes"), "notes"))
    }

    @Test fun downloadStatusValuesExist() {
        assertEquals(3, DownloadStatus.values().size)
        assertTrue(DownloadStatus.values().contains(DownloadStatus.COMPLETE))
    }
}
