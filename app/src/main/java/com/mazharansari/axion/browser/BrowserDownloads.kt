package com.mazharansari.axion.browser

import java.net.URLDecoder

/**
 * P1-7 — real browser downloads, pure logic (no Android calls, unit-tested in
 * BrowserDownloadsTest). The [BrowserController] does the actual saving with
 * DownloadManager; everything here is the part that can be wrong quietly:
 * the file name, the folder, and the progress maths.
 *
 * Downloads save into the public Downloads folder, under Downloads/Axion, so
 * the user finds them in any file manager and can open or share them outside
 * Axion. Nothing is ever written outside that folder.
 */
enum class DownloadStatus { DOWNLOADING, COMPLETE, FAILED }

data class DownloadState(
    val id: Long,
    val url: String,
    val fileName: String,
    val bytesReceived: Long = 0,
    val totalBytes: Long = -1,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
) {
    /** 0..100, or -1 while the total size is still unknown. */
    val progressPercent: Int get() = BrowserDownloads.progressPercent(bytesReceived, totalBytes)
}

object BrowserDownloads {
    /** Subfolder of the public Downloads folder. */
    const val DIR = "Axion"

    /** 0..100, or -1 while [total] is unknown (<= 0). Never over 100, never negative. */
    fun progressPercent(received: Long, total: Long): Int {
        if (total <= 0L) return -1
        return ((received.coerceAtLeast(0L) * 100L) / total).coerceIn(0L, 100L).toInt()
    }

    /** The file name from a Content-Disposition header, if it carries one. */
    fun fileNameFromContentDisposition(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        // RFC 5987 first: filename*=UTF-8''name%20with%20spaces.ext
        Regex("""filename\*\s*=\s*[^']*'[^']*'([^;\s]+)""", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1)?.let { raw ->
                runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        // Plain: filename="name with spaces.ext" (quoted — spaces allowed) or filename=name.ext.
        // The quoted form must be tried first: [^";\s]+ stops at the space inside "report 2026.pdf".
        Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        Regex("""filename\s*=\s*([^";\s]+)""", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    /** The file name from the URL's last path segment (query and fragment stripped). */
    fun fileNameFromUrl(url: String): String? {
        val path = runCatching { java.net.URI(url.trim()).rawPath }.getOrNull() ?: return null
        val last = path.substringAfterLast('/').trim()
        if (last.isBlank()) return null
        // A bare path segment with no extension ("/dl", "/download") is a route, not a
        // file name — return null so the MIME/plain fallback names it ("download.pdf").
        if ('.' !in last || last.startsWith('.') || last.endsWith('.')) return null
        return runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last).trim().takeIf { it.isNotBlank() }
    }

    /**
     * The file name to save as, in priority order: Content-Disposition, URL,
     * then a name from the MIME type, then a plain fallback. The result is
     * always a safe single file name (see [sanitizeFileName]).
     */
    fun fileNameFor(url: String, contentDisposition: String?, mimeType: String?): String {
        val raw = fileNameFromContentDisposition(contentDisposition)
            ?: fileNameFromUrl(url)
            ?: mimeType?.let { extensionForMime(it)?.let { ext -> "download.$ext" } }
            ?: "download"
        return sanitizeFileName(raw)
    }

    /** A MIME type's usual extension, for the fallback name only. */
    fun extensionForMime(mimeType: String?): String? = when (mimeType?.lowercase()?.substringBefore(';')?.trim()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "video/mp4" -> "mp4"
        "audio/mpeg" -> "mp3"
        "application/pdf" -> "pdf"
        "application/zip" -> "zip"
        "text/plain" -> "txt"
        "text/html" -> "html"
        else -> null
    }

    /**
     * Makes [raw] safe as one file name inside Downloads/Axion: path separators,
     * drive letters and control characters go, leading dots go (no hidden files),
     * and an empty result becomes "download". The name never contains a slash,
     * so it can never escape the folder.
     */
    fun sanitizeFileName(raw: String): String {
        var name = raw.trim()
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .trim('.', ' ', '_')
        if (name.isBlank()) name = "download"
        if (name.length > 120) {
            val dot = name.lastIndexOf('.')
            name = if (dot in 1..110) name.take(110 - (name.length - dot)) + name.substring(dot) else name.take(120)
        }
        return name
    }

    /**
     * A name that does not collide with [existing] (the files already in
     * Downloads/Axion): "photo.jpg" becomes "photo (1).jpg", then " (2)", …
     */
    fun uniqueFileName(existing: Set<String>, name: String): String {
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while ("$base ($n)$ext" in existing) n++
        return "$base ($n)$ext"
    }
}
