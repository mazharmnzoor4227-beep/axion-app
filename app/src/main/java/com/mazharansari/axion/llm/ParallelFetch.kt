package com.mazharansari.axion.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * One HTTP connection to Axion's hosts is painfully slow on some networks — the owner's
 * 11 MB update crawled for ~10 minutes on a single flow, while the same server answers
 * several flows at full speed (and a phone's route to the server is what it is). This
 * fetcher splits a file into segments and pulls them over parallel ranged connections
 * (the download-manager trick), then assembles the parts in order. Segment files live
 * next to the target, so an interrupted fetch resumes where it stopped.
 *
 * Returns false when the server won't honour Range requests — the caller then falls
 * back to its plain single-stream download. Throws on network failure, keeping the
 * segment files for the next attempt.
 */
object ParallelFetch {

    private class RangeRefusedException : Exception()

    suspend fun fetch(
        client: OkHttpClient,
        url: String,
        target: File,
        expectedTotal: Long = -1L,
        segments: Int = 4,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean {
        var total = expectedTotal
        if (total <= 0L) total = probeTotal(client, url) ?: return false
        if (total < 262_144L) return false   // too small to be worth splitting
        val n = minOf(segments, maxOf(1, ((total + 262_143L) / 262_144L).toInt()))
        val chunk = (total + n - 1) / n
        target.parentFile?.mkdirs()
        val segFiles = (0 until n).map { File(target.parentFile, target.name + ".seg" + it) }
        val done = AtomicLong(segFiles.sumOf { it.length() })
        val lastEmit = AtomicLong(0L)
        fun emit(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || now - lastEmit.get() > 150) { lastEmit.set(now); onProgress(done.get(), total) }
        }
        emit(force = true)
        try {
            coroutineScope {
                val jobs = (0 until n).map { i ->
                    async(Dispatchers.IO) {
                        fetchSegment(client, url, segFiles[i], i * chunk, minOf(total, (i + 1) * chunk) - 1, done) { emit() }
                    }
                }
                jobs.forEach { it.await() }
            }
        } catch (e: RangeRefusedException) {
            segFiles.forEach { it.delete() }
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e)
        }
        // Assemble in order; a size mismatch means a segment lied — start clean next time.
        FileOutputStream(target).use { out ->
            val buf = ByteArray(1 shl 16)
            for (f in segFiles) f.inputStream().use { s -> while (true) { val r = s.read(buf); if (r < 0) break; out.write(buf, 0, r) } }
        }
        if (target.length() != total) {
            target.delete(); segFiles.forEach { it.delete() }
            return false
        }
        segFiles.forEach { it.delete() }
        emit(force = true)
        return true
    }

    /** Total size via a one-byte ranged probe; null when the server won't play. */
    private fun probeTotal(client: OkHttpClient, url: String): Long? = try {
        val req = Request.Builder().url(url).header("User-Agent", Http.UA).header("Range", "bytes=0-0").build()
        client.newCall(req).execute().use { r ->
            if (r.code != 206) null else r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        }
    } catch (e: Exception) { null }

    private fun fetchSegment(client: OkHttpClient, url: String, file: File, start: Long, endInclusive: Long, done: AtomicLong, emit: () -> Unit) {
        val len = endInclusive - start + 1
        var have = file.length()
        if (have > len) { file.delete(); have = 0 }
        if (have == len) return
        var counted = have   // how much of this file is already included in [done]
        var retried416 = false
        while (true) {
            val req = Request.Builder().url(url).header("User-Agent", Http.UA)
                .header("Range", "bytes=${start + have}-$endInclusive").build()
            client.newCall(req).execute().use { r ->
                when {
                    r.code == 206 -> {
                        val src = r.body?.byteStream() ?: throw IOException("The download stream was empty.")
                        FileOutputStream(file, true).use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val rd = src.read(buf); if (rd < 0) break
                                out.write(buf, 0, rd); done.addAndGet(rd.toLong()); counted += rd; emit()
                            }
                        }
                        if (file.length() != len) throw IOException("A download segment ended early.")
                        return
                    }
                    r.code == 416 && !retried416 -> {
                        // Our saved offset is past what the server has: drop the segment and restart it.
                        retried416 = true
                        done.addAndGet(-counted); counted = 0
                        file.delete(); have = 0
                    }
                    r.code == 200 -> throw RangeRefusedException()   // Range ignored: the caller falls back to one stream
                    else -> throw IOException("The download failed (${r.code}).")
                }
            }
        }
    }
}
