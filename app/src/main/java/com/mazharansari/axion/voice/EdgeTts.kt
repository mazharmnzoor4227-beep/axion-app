package com.mazharansari.axion.voice

import com.mazharansari.axion.llm.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Microsoft Edge "Read aloud" neural voices — the same free online voices the Edge browser reads pages with
 * (Ava, Andrew, Emma, Brian multilingual; Swara/Madhur hi-IN; Uzma/Asad ur-PK). No key, no account.
 *
 * Wire protocol and the Sec-MS-GEC v2 token are a 1:1 port of the open-source `edge-tts` Python package
 * (v7, constants as of Chromium 143): a WebSocket to speech.platform.bing.com, one `speech.config` text frame,
 * one SSML frame, then binary `Path:audio` frames (24 kHz mono MP3) until `Path:turn.end`.
 * Verified from Tab's box on 2026-11-01: the v1 token is refused (handshake 403), v2 connects and returns audio.
 * This is an unofficial endpoint: Microsoft can change it; every caller falls back to the phone voice on failure.
 */
object EdgeTts {
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_FULL = "143.0.3650.75"
    private const val CHROMIUM_MAJOR = "143"
    private const val GEC_VERSION = "2-$CHROMIUM_FULL"
    private const val WSS = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"

    /** Seconds to add to the phone clock (learned from the server's Date header after a 403). */
    @Volatile var clockSkewSeconds: Double = 0.0

    class EdgeError(msg: String) : Exception(msg)

    /**
     * Synthesises [text] (plain, at most a few thousand characters) with [voice] and returns MP3 bytes.
     * [ratePercent] e.g. -15 / 0 / +18 (Pace), [pitchHz] usually 0.
     */
    suspend fun synthesize(text: String, voice: String, ratePercent: Int = 0, pitchHz: Int = 0, timeoutMs: Long = 25_000): ByteArray {
        return try { once(text, voice, ratePercent, pitchHz, timeoutMs) } catch (e: SkewException) {
            clockSkewSeconds += e.skew
            once(text, voice, ratePercent, pitchHz, timeoutMs)
        }
    }

    private class SkewException(val skew: Double) : Exception("clock skew")

    private suspend fun once(text: String, voice: String, ratePercent: Int, pitchHz: Int, timeoutMs: Long): ByteArray {
        val unix = System.currentTimeMillis() / 1000.0 + clockSkewSeconds
        val url = "$WSS&ConnectionId=${connectId()}&Sec-MS-GEC=${secMsGec(unix)}&Sec-MS-GEC-Version=$GEC_VERSION"
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"
        val req = Request.Builder().url(url)
            .header("Pragma", "no-cache").header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", ua).header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
            .build()
        val audio = ByteArrayOutputStream()
        val done = CompletableDeferred<ByteArray>()
        val ws = Http.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ts = jsDate()
                webSocket.send(
                    "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n",
                )
                webSocket.send(
                    "X-RequestId:${connectId()}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n" +
                        ssml(voice, escape(clean(text)), ratePercent, pitchHz),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val head = text.substringBefore("\r\n\r\n")
                if (head.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    if (audio.size() > 0) done.complete(audio.toByteArray()) else done.completeExceptionally(EdgeError("no audio received"))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val hl = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                if (hl + 2 > b.size) return
                val headers = String(b, 2, hl, Charsets.UTF_8)
                if (!headers.contains("Path:audio")) return
                val start = 2 + hl
                if (b.size > start) synchronized(audio) { audio.write(b, start, b.size - start) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                if (code == 403) {
                    val serverDate = response.header("Date")?.let { runCatching { httpDate().parse(it)?.time }.getOrNull() }
                    if (serverDate != null) {
                        val skew = serverDate / 1000.0 - System.currentTimeMillis() / 1000.0 - clockSkewSeconds
                        if (kotlin.math.abs(skew) > 60) { done.completeExceptionally(SkewException(skew)); return }
                    }
                }
                done.completeExceptionally(EdgeError(if (code != null) "HTTP $code" else (t.message ?: "connection failed")))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!done.isCompleted) {
                    if (audio.size() > 0) done.complete(audio.toByteArray()) else done.completeExceptionally(EdgeError("closed ($code) $reason"))
                }
            }
        })
        return try { withTimeout(timeoutMs) { done.await() } } finally { ws.cancel() }
    }

    internal fun ssml(voice: String, escapedText: String, ratePercent: Int, pitchHz: Int): String {
        val rate = (if (ratePercent >= 0) "+" else "") + "$ratePercent%"
        val pitch = (if (pitchHz >= 0) "+" else "") + "${pitchHz}Hz"
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voice'><prosody pitch='$pitch' rate='$rate' volume='+0%'>$escapedText</prosody></voice></speak>"
    }

    internal fun clean(s: String): String = buildString(s.length) {
        for (ch in s) { val c = ch.code; append(if ((c in 0..8) || (c in 11..12) || (c in 14..31)) ' ' else ch) }
    }

    internal fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun connectId() = UUID.randomUUID().toString().replace("-", "")

    private fun jsDate(): String = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun httpDate() = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

    // ------------------------------------------------------------------------------------------------
    // Sec-MS-GEC v2 (port of edge_tts/drm.py). All arithmetic is mod 2^32, so Kotlin Int wraps exactly
    // like the Python code's "& 0xFFFFFFFF" masks.
    // ------------------------------------------------------------------------------------------------

    fun secMsGec(unixSeconds: Double): String {
        var ticks = kotlin.math.floor(unixSeconds).toLong() + 11_644_473_600L
        ticks -= ticks % 300
        ticks *= 10_000_000L
        val data = "$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val (c1, c2) = cs64(data, digest)
        val cs = ByteArray(8).also { le(it, 0, c1); le(it, 4, c2) }
        return hex(cs) + "-" + hex(digest)
    }

    private fun le(b: ByteArray, off: Int, v: Int) { b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte(); b[off + 2] = (v ushr 16).toByte(); b[off + 3] = (v ushr 24).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private val INITIAL = intArrayOf(0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(), 0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19)
    private val STATE = intArrayOf(0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(), 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4.toInt(), 0x03EFFBD2)
    private val SCHEDULE = longArrayOf(0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564).map { it.toInt() }.toIntArray()
    private val K = longArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
    ).map { it.toInt() }.toIntArray()
    private const val SHA_ADJ = 0xC9DC06A7.toInt()
    private val KBLOCK = K.copyOf().also { it[14] = SHA_ADJ }
    private const val SIGMA0_ADJ = 0x059C0B80
    private const val SIGMA1_ADJ = 0x000B92C0
    private val CS64_COEF = longArrayOf(0xE82E91B1, 0x44DE24C6, 0x3BEB65BC, 0xC89C5933, 0x8D1EC211, 0x314EF5D5, 0xE92491B1, 0xF0A5B9D5, 0x06064CF3, 0xDA79FA60, 0xBED20560, 0x89F9C9F8, 0x35FA38C6, 0xCB49E3C6).map { it.toInt() }
    private val CS64_OFF = longArrayOf(0xB32700D0, 0x88DFF51D, 0x29514038, 0xB328CBDF, 0xD405B4D5, 0x26E55F3C, 0x607801C6, 0x82B2C1FB, 0xFA51A3DA, 0x207CC464, 0x3C3C74F3, 0xD2D14B61, 0xBB04A2C1, 0xC5626167).map { it.toInt() }
    private const val CS64_FACTOR = 0x82893933.toInt()
    private const val CS64_ADJ0 = 0xF2F90B1A.toInt()
    private const val CS64_ADJ1 = 0x0B1A0000

    private fun s0(v: Int) = Integer.rotateRight(v, 7) xor Integer.rotateRight(v, 18) xor (v ushr 3)
    private fun s1(v: Int) = Integer.rotateRight(v, 17) xor Integer.rotateRight(v, 19) xor (v ushr 10)

    private fun rounds(state: IntArray, w: IntArray, first: Int): IntArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        val k = if (first == 14) KBLOCK else K
        for (i in first until 64) {
            val t1 = h + (Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)) + (g xor (e and (f xor g))) + k[i] + w[i]
            val t2 = (Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)) + ((a and b) or (c and (a or b)))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expand(w: IntArray, from: Int) { for (i in from until 64) w[i] = s1(w[i - 2]) + w[i - 7] + s0(w[i - 15]) + w[i - 16] }

    private fun beInt(b: ByteArray, off: Int, len: Int): Int { var v = 0; for (i in 0 until len) v = (v shl 8) or (b[off + i].toInt() and 0xFF); return v }

    private fun shaInit(d7: ByteArray): IntArray {
        val w = IntArray(64)
        w[14] = beInt(d7, 0, 3); w[15] = beInt(d7, 3, 4)
        for (i in 16 until 30) {
            var v = SCHEDULE[i - 16]
            if (i - 2 >= 14) v += if (i == 16) (s1(w[i - 2]) xor SIGMA1_ADJ) else s1(w[i - 2])
            if (i - 7 >= 14) v += w[i - 7]
            if (i - 15 >= 14) v += if (i == 29) (s0(w[i - 15]) xor SIGMA0_ADJ) else s0(w[i - 15])
            w[i] = v
        }
        w[30] = s1(w[28]) + w[23] + s0(w[15]) + w[14] + SHA_ADJ - K[14]
        expand(w, 31)
        val r = rounds(STATE, w, 14)
        return IntArray(8) { INITIAL[it] + r[it] }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        val length = 57 + data.size
        val tail = data.copyOfRange(7, data.size)
        val zeros = Math.floorMod(55 - length, 64)
        val padded = ByteArray(tail.size + 1 + zeros + 8)
        tail.copyInto(padded); padded[tail.size] = 0x80.toByte()
        val bits = length.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
        var state = shaInit(data.copyOfRange(0, 7))
        var off = 0
        while (off < padded.size) {
            val w = IntArray(64); for (i in 0 until 16) w[i] = beInt(padded, off + 4 * i, 4)
            expand(w, 16)
            val r = rounds(state, w, 0)
            state = IntArray(8) { state[it] + r[it] }
            off += 64
        }
        val out = ByteArray(32); for (i in 0 until 8) { out[4 * i] = (state[i] ushr 24).toByte(); out[4 * i + 1] = (state[i] ushr 16).toByte(); out[4 * i + 2] = (state[i] ushr 8).toByte(); out[4 * i + 3] = state[i].toByte() }
        return out
    }

    private fun rol16(v: Int) = Integer.rotateLeft(v, 16)

    private fun cs64(data: ByteArray, digest: ByteArray): Pair<Int, Int> {
        fun leInt(b: ByteArray, off: Int) = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
        val keyA = leInt(digest, 0) or 1
        val keyB = leInt(digest, 4) or 1
        val factors = intArrayOf(keyA * CS64_FACTOR, keyB * CS64_FACTOR)
        val terms = ArrayList<Int>(); val offsets = ArrayList<Int>()
        CS64_COEF.forEachIndexed { i, c -> terms += c * factors[i % 2] }
        offsets += CS64_OFF
        // _words_le4_even(b"\x00" + data)
        val src = ByteArray(1 + data.size).also { data.copyInto(it, 1) }
        val padded = src.copyOf(src.size + Math.floorMod(-src.size, 4))
        val words = ArrayList<Int>(); var o = 0; while (o < padded.size) { words += leInt(padded, o); o += 4 }
        if (words.size % 2 == 1) words += 0
        words.forEachIndexed { index, word ->
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (index == 0) { term += CS64_ADJ0 * factors[0]; offset += CS64_ADJ1 }
            terms += term; offsets += offset
        }
        var swapAcc = 0; var swapLast = 0; var state = 0; var acc = 0
        var i = 0
        while (i < terms.size) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rol16(offsets[i] + x)
            var q = t * 0x76F50F34
            swapAcc += rol16(q) * 0xC3EFEA97.toInt() + q
            t = rol16(offsets[i + 1] + y)
            q = t * 0x1881CE3C
            swapLast = rol16(q) * 0x35BD1EC9 + q
            swapAcc += swapLast
            state = state * keyA + x
            state = rol16(state) * 0xCF98B111.toInt()
            state = rol16(state) * 0x87085B9F.toInt()
            state = rol16(state) * 0x12CEB96D
            state = rol16(state) * 0x257E1D83
            acc += state
            t = state * keyB + y
            t = rol16(t) * 0xA27416F5.toInt()
            t = rol16(t) * 0xD38396FF.toInt()
            state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69.toInt()
            acc += state
            i += 2
        }
        return Pair(swapLast xor state, swapAcc xor acc)
    }
}
