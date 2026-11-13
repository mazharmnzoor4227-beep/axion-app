package com.mazharansari.axion

import com.mazharansari.axion.agent.ImageGen
import com.mazharansari.axion.livecall.LiveCallProtocol
import com.mazharansari.axion.voice.VoiceCatalog
import com.mazharansari.axion.voice.VoiceFamily
import org.junit.Assert.*
import org.junit.Test

/** P1-11: Edge TTS hedge (a Gemini voice per Natural voice) + verified Gemini model IDs + provenance. */
class P1VoiceHedgeTest {

    @Test fun everyNaturalVoiceHasAGeminiFallback() {
        assertEquals(8, VoiceCatalog.natural.size)
        for (v in VoiceCatalog.natural) {
            val fb = VoiceCatalog.geminiFallbackFor(v)
            assertNotNull("no Gemini fallback for ${v.name}", fb)
            assertEquals(VoiceFamily.GEMINI, fb!!.family)
            assertEquals("fallback gender differs for ${v.name}", v.female, fb.female)
            assertTrue("fallback ${fb.engineVoice} is not a catalog Gemini voice",
                VoiceCatalog.gemini.any { it.engineVoice == fb.engineVoice })
        }
    }

    @Test fun fallbackIsNullForNonNaturalVoices() {
        for (v in VoiceCatalog.kodex + VoiceCatalog.gemini) {
            assertNull("unexpected fallback for ${v.id}", VoiceCatalog.geminiFallbackFor(v))
        }
    }

    @Test fun fallbackChainKeepsEdgePrimary() {
        val ava = VoiceCatalog.byId("edge:ava")
        val withKeys = VoiceCatalog.voiceFallbackChain(ava, hasGeminiKeys = true)
        assertEquals(listOf(ava, VoiceCatalog.byId("gemini:Kore"), VoiceCatalog.kodex.first()), withKeys)
        val noKeys = VoiceCatalog.voiceFallbackChain(ava, hasGeminiKeys = false)
        assertEquals(listOf(ava, VoiceCatalog.kodex.first()), noKeys)
    }

    @Test fun fallbackChainEndsOnRoundedForEveryFamily() {
        val rounded = VoiceCatalog.kodex.first()
        assertEquals(listOf(rounded), VoiceCatalog.voiceFallbackChain(rounded, true))
        val gemini = VoiceCatalog.byId("gemini:Zephyr")
        assertEquals(listOf(gemini, rounded), VoiceCatalog.voiceFallbackChain(gemini, true))
        val kodex = VoiceCatalog.byId("kodex:buttery")
        assertEquals(listOf(kodex, rounded), VoiceCatalog.voiceFallbackChain(kodex, false))
        val swara = VoiceCatalog.byId("edge:swara")
        assertEquals(swara, VoiceCatalog.voiceFallbackChain(swara, true).first())
        assertEquals(VoiceCatalog.byId("gemini:Sulafat"), VoiceCatalog.voiceFallbackChain(swara, true)[1])
        assertEquals(rounded, VoiceCatalog.voiceFallbackChain(swara, true).last())
    }

    // ── model IDs, verified 13 Nov 2026 against Google's AI Studio models + deprecations pages ──

    @Test fun liveModelIdIsVerified() {
        assertEquals("gemini-3.8-live", LiveCallProtocol.MODEL)
    }

    @Test fun liveFallbacksDropTheRetiringPreview() {
        assertFalse(LiveCallProtocol.FALLBACK_MODELS.contains("gemini-3.1-flash-live-preview"))   // retires 17 Nov 2026
        assertTrue(LiveCallProtocol.FALLBACK_MODELS.contains("gemini-3.8-live-extended-thinking"))
        assertFalse(LiveCallProtocol.FALLBACK_MODELS.contains(LiveCallProtocol.MODEL))
    }

    @Test fun geminiImageModelsAreDocumentedIds() {
        assertEquals(
            listOf("gemini-nano-banana-2.1", "gemini-3.1-flash-image", "gemini-3.1-flash-lite-image", "gemini-2.5-flash-image"),
            ImageGen.GEMINI_IMAGE_MODELS,
        )
        assertFalse(ImageGen.GEMINI_IMAGE_MODELS.contains("gemini-3.1-flash-image-preview"))   // retired 25 Jun 2026
    }
}
