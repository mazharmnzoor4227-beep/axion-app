package com.mazharansari.axion

import com.mazharansari.axion.security.SecretScanner
import org.junit.Assert.*
import org.junit.Test

/** P0-14 (ME-2): Gemini AQ. keys, Together tgp_ keys, and short secrets are masked. Fake keys only — never real. Pure Kotlin — no Android needed. */
class SecretScannerTest {

    // New Gemini auth key format (AI Studio, since May 2026): "AQ." + 40–60 base64url chars.
    private val aq = "AQ." + "Ab8Rn6LeVeX2kQ9mZ4pT7wY1sD5fH8jK3nM6qP0rS7t"
    // Together AI project key: "tgp_v1_" + 43 base64url chars.
    private val tgp = "tgp_v1_" + "A1b2C3d4E5f6G7h8J9k0L1m2N3p4Q5r6S7t8U9v0" + "W1x"

    @Test fun fakeKeysHaveTheRightShape() {
        assertEquals(46, aq.length)   // "AQ." + 43
        assertEquals(50, tgp.length)  // "tgp_v1_" + 43
    }

    @Test fun geminiAqKeyIsMasked() {
        assertTrue(SecretScanner.contains("my gemini key is $aq", emptyList()))
        val scrubbed = SecretScanner.scrub("my gemini key is $aq ok", emptyList())
        assertFalse(scrubbed.contains(aq))
        assertEquals("my gemini key is AQ.••••" + aq.takeLast(4) + " ok", scrubbed)
        assertEquals("AQ.••••" + aq.takeLast(4), SecretScanner.mask(aq))
    }

    @Test fun togetherKeyIsMasked() {
        assertTrue(SecretScanner.contains("TOGETHER_API_KEY=$tgp", emptyList()))
        val scrubbed = SecretScanner.scrub("TOGETHER_API_KEY=$tgp", emptyList())
        assertFalse(scrubbed.contains(tgp))
        assertEquals("TOGETHER_API_KEY=tgp_••••" + tgp.takeLast(4), scrubbed)
        assertEquals("tgp_••••" + tgp.takeLast(4), SecretScanner.mask(tgp))
    }

    @Test fun shortKnownSecretIsLiteralMatched() {
        val short = "Ab3dEf9X"   // 8 chars: the old 12-char floor never matched it
        assertEquals(8, short.length)
        assertTrue(SecretScanner.contains("key=$short", listOf(short)))
        assertEquals("key=••••", SecretScanner.scrub("key=$short", listOf(short)))
    }

    @Test fun shortTextIsScrubbedToo() {
        // The old find() ignored any text under 16 chars, so a short paste leaked wholesale.
        val short = "Ab3dEf9X"
        assertTrue(SecretScanner.contains(short, listOf(short)))   // 8-char text
        assertEquals("••••", SecretScanner.scrub(short, listOf(short)))
        assertTrue(SecretScanner.contains("k=$short", listOf(short)))   // 10-char text
    }

    @Test fun underEightCharsIsStillNotMatched() {
        assertFalse(SecretScanner.contains("key=Ab3dEf9", listOf("Ab3dEf9")))   // 7-char known secret
        assertFalse(SecretScanner.contains("Ab3dEf", listOf("Ab3dEf")))         // 6-char text
    }

    @Test fun barePrefixesAreNotSecrets() {
        assertFalse(SecretScanner.contains("AQ. is the new Gemini prefix", emptyList()))
        assertFalse(SecretScanner.contains("tgp_ is a Together prefix", emptyList()))
        assertFalse(SecretScanner.contains("my key starts with AQ.", emptyList()))
    }
}
