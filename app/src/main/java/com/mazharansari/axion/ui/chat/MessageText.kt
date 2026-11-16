package com.mazharansari.axion.ui.chat

import java.net.URLDecoder

/**
 * Phase 2 — message text hygiene.
 *
 * The user saw a whole assistant reply rendered as raw URL-encoded garbage
 * ("%27%29%0Apdf.ln%285%29…" — a script the model pasted percent-encoded).
 * Nothing in the app encodes message text (the encoding arrives from the model
 * or a tool result), so the render layer decodes it before display. The guard
 * is deliberately strict: plain text — including "100% sure", "C++" and links
 * with a stray %20 — is never touched. Only dense percent-encoded blobs are
 * decoded (up to two passes, for double-encoded text).
 * Original Axion code.
 */
object MessageText {

    private val pctSeq = Regex("%[0-9A-Fa-f]{2}")

    /** True when the text is dense with %XX sequences — i.e. it was URL-encoded. */
    fun looksEncoded(text: String): Boolean {
        if (text.length < 12) return false
        val hits = pctSeq.findAll(text).count()
        if (hits < 4) return false
        // At least ~8% of the characters are %XX sequences (the reported blob is ~60%).
        return hits * 300 / text.length >= 8
    }

    /** Decode a percent-encoded message; return plain text unchanged. */
    fun decodeIfEncoded(text: String): String {
        var cur = text
        repeat(2) {
            if (!looksEncoded(cur)) return cur
            val decoded = runCatching { URLDecoder.decode(cur, "UTF-8") }.getOrNull() ?: return cur
            if (decoded == cur) return cur
            // The decoded text must be readable: printable characters, newlines and tabs only.
            if (!decoded.all { it == '\n' || it == '\t' || it == '\r' || it >= ' ' }) return cur
            cur = decoded
        }
        return cur
    }
}
