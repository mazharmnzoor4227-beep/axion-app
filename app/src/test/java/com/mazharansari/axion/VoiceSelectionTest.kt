package com.mazharansari.axion

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.mazharansari.axion.voice.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.4 voice bug (mazhar 2:05 PM): pick voice X in Settings → the read-aloud under an answer must request voice X,
 * for every family, for the text his answers actually contain (English, Roman Urdu, Urdu script).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoiceSelectionTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val english = "Here is the summary of your project and the next steps."
    private val roman = "Main theek thaak hoon, aap kaise ho? Ye kaam abhi ho jayega, bas thoda wait karo."
    private val urdu = "میں ٹھیک ہوں، آپ کیسے ہیں؟ یہ کام ابھی ہو جائے گا۔"

    /** What the player would actually ask the engine for: the routed voice, then (natural) the Edge voice name. */
    private fun requested(text: String): String {
        val active = VoicePrefs.activeVoice(ctx)
        val v = VoiceRouting.plan(active, text).voice
        return if (v.family == VoiceFamily.NATURAL) VoiceCatalog.edgeVoiceFor(v, text, VoicePrefs.smartUrdu(ctx)) else v.engineVoice
    }

    @Test fun pickedVoiceIsWhatReadAloudRequests_everyFamily_englishAndRomanUrdu() {
        VoicePrefs.migrate(ctx)
        val families = VoiceCatalog.kodex + VoiceCatalog.natural + VoiceCatalog.gemini
        for (v in families) {
            VoicePrefs.setActive(ctx, v.id)              // what Settings → Voice does on tap
            assertEquals(v.id, VoicePrefs.activeVoice(ctx).id)
            assertEquals("${v.name} english", v.engineVoice, requested(english))
            assertEquals("${v.name} roman urdu", v.engineVoice, requested(roman))
        }
    }

    @Test fun urduScript_naturalAndGeminiKeepThePick_kodexGetsSameGenderStandIn() {
        VoicePrefs.migrate(ctx)
        for (v in VoiceCatalog.natural + VoiceCatalog.gemini) {
            VoicePrefs.setActive(ctx, v.id)
            assertEquals(v.name, v.engineVoice, requested(urdu))
        }
        VoicePrefs.setActive(ctx, "kodex:rounded")       // English phone voice: can't read Urdu script at all
        val p = VoiceRouting.plan(VoicePrefs.activeVoice(ctx), urdu)
        assertEquals("ur-PK-AsadNeural", p.voice.engineVoice)   // same gender as Rounded, not the phone's female default
        assertNotNull(p.note)
        VoicePrefs.setActive(ctx, "kodex:buttery")
        assertEquals("ur-PK-UzmaNeural", VoiceRouting.plan(VoicePrefs.activeVoice(ctx), urdu).voice.engineVoice)
    }

    @Test fun smartUrduIsOffAfterMigration_evenForOldInstalls() {
        val p = VoicePrefs.prefs(ctx); p.edit().clear().putInt("schema", 3).commit()   // a v0.3.1 install that never touched it
        VoicePrefs.migrate(ctx)
        assertFalse(VoicePrefs.smartUrdu(ctx))
        VoicePrefs.setActive(ctx, "edge:ava")
        assertEquals("en-US-AvaMultilingualNeural", requested(roman))   // v0.3.1 sent this to hi-IN-SwaraNeural
    }

    @Test fun changeTakesEffectOnTheNextPlay_noRestart() {
        VoicePrefs.setActive(ctx, "gemini:Puck"); assertEquals("Puck", requested(roman))
        VoicePrefs.setActive(ctx, "edge:brian"); assertEquals("en-US-BrianMultilingualNeural", requested(roman))
    }

    @Test fun liveCallUsesTheSameSelection() {
        VoicePrefs.setActive(ctx, "gemini:Charon"); assertEquals("Charon", VoiceCatalog.geminiTwin(VoicePrefs.activeVoice(ctx)).engineVoice)
        VoicePrefs.setActive(ctx, "edge:ava"); assertEquals("Kore", VoiceCatalog.geminiTwin(VoicePrefs.activeVoice(ctx)).engineVoice)
        VoicePrefs.setActive(ctx, "kodex:rounded"); assertEquals("Puck", VoiceCatalog.geminiTwin(VoicePrefs.activeVoice(ctx)).engineVoice)
        assertFalse(VoicePrefs.callOwnVoice(ctx))   // default: native live audio
    }
}
