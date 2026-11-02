package com.mazharansari.axion.voice

/**
 * Every voice Axion can speak with. v0.3 (mazhar, 1 Nov 2026): ALL earlier voices removed. Two families remain:
 *
 * 1. Kodex voices — the exact six on-device Google voices his Kodex app shows on its Voice settings screen,
 *    with Kodex's friendly names in Kodex's order (read off his screen recording, 1 Nov 2026):
 *      Rounded  en-gb-x-gbb-network  English (United Kingdom)   ← Kodex default (first card)
 *      Glassy   en-au-x-aub-network  English (Australia)
 *      Mellow   en-in-x-ene-network  English (India)
 *      Airy     en-au-x-aud-network  English (Australia)
 *      Buttery  en-us-x-sfg-network  English (United States)
 *      Clear    en-gb-x-gbd-network  English (United Kingdom)
 *    They are Google "Speech Services" voices: free, and the "-network" ones are Google's higher-quality online
 *    rendering; offline the engine uses the same voice's "-local" twin. Pace: Kodex's Slow 0.85 / Normal 1.0 /
 *    Fast 1.18, Normal by default.
 *
 * 3. Gemini voices (added 1 Nov 2026, mazhar: "the call must speak in the SAME voice I selected"): Google's own
 *    Gemini Live voices. Picking one makes the Live call native Gemini audio (lowest latency, barge-in). Read-aloud
 *    in a Gemini voice uses the same Live connection as a read-it-exactly engine (tested: verbatim in English,
 *    Roman Urdu and Urdu script, first audio ≈0.5 s). Needs a Gemini key in Connectors.
 *
 * 2. Natural voices — Microsoft Edge's free neural voices (no key), tested from Tab's box on 1 Nov 2026:
 *    multilingual Ava/Andrew/Emma/Brian, Hindi Swara/Madhur (read Roman Urdu / Hinglish best), Urdu Uzma/Asad
 *    (read Urdu script). With "Smart Urdu" on, a natural voice hands Urdu-script text to the Urdu voice and
 *    Roman Urdu / Hinglish to the Hindi voice of the same gender, so Urdu never comes out in an English accent.
 */
enum class VoiceFamily { KODEX, NATURAL, GEMINI, ELEVENLABS }

data class AxVoice(
    val id: String,
    val name: String,
    val family: VoiceFamily,
    val language: String,
    val tag: String,
    /** Device voice name (Kodex) or Edge voice short name (natural). */
    val engineVoice: String = "",
    val female: Boolean = true,
)

object VoiceCatalog {
    const val DEFAULT_ID = "kodex:rounded"
    const val PREVIEW_TEXT = "Hi, I'm your Axion voice. Ask me anything."
    /** Spoken (not shown) by Hindi/Urdu voices; written in Devanagari so the voice pronounces it naturally. */
    const val PREVIEW_URDU = "नमस्ते! मैं आपका Axion हूँ। बताइए, आज मैं आपकी क्या मदद कर सकता हूँ?"

    val kodex = listOf(
        AxVoice("kodex:rounded", "Rounded", VoiceFamily.KODEX, "English (United Kingdom)", "Default voice", "en-gb-x-gbb-network", false),
        AxVoice("kodex:glassy", "Glassy", VoiceFamily.KODEX, "English (Australia)", "Phone voice", "en-au-x-aub-network", false),
        AxVoice("kodex:mellow", "Mellow", VoiceFamily.KODEX, "English (India)", "Phone voice", "en-in-x-ene-network", false),
        AxVoice("kodex:airy", "Airy", VoiceFamily.KODEX, "English (Australia)", "Phone voice", "en-au-x-aud-network", false),
        AxVoice("kodex:buttery", "Buttery", VoiceFamily.KODEX, "English (United States)", "Phone voice", "en-us-x-sfg-network", true),
        AxVoice("kodex:clear", "Clear", VoiceFamily.KODEX, "English (United Kingdom)", "Phone voice", "en-gb-x-gbd-network", false),
    )

    val natural = listOf(
        AxVoice("edge:ava", "Ava", VoiceFamily.NATURAL, "English · multilingual", "Warm, expressive", "en-US-AvaMultilingualNeural", true),
        AxVoice("edge:andrew", "Andrew", VoiceFamily.NATURAL, "English · multilingual", "Warm, confident", "en-US-AndrewMultilingualNeural", false),
        AxVoice("edge:emma", "Emma", VoiceFamily.NATURAL, "English · multilingual", "Cheerful, clear", "en-US-EmmaMultilingualNeural", true),
        AxVoice("edge:brian", "Brian", VoiceFamily.NATURAL, "English · multilingual", "Easy, casual", "en-US-BrianMultilingualNeural", false),
        AxVoice("edge:swara", "Swara", VoiceFamily.NATURAL, "Hindi · Roman Urdu", "Best for Roman Urdu / Hinglish", "hi-IN-SwaraNeural", true),
        AxVoice("edge:madhur", "Madhur", VoiceFamily.NATURAL, "Hindi · Roman Urdu", "Best for Roman Urdu / Hinglish", "hi-IN-MadhurNeural", false),
        AxVoice("edge:uzma", "Uzma", VoiceFamily.NATURAL, "Urdu (Pakistan)", "Urdu script", "ur-PK-UzmaNeural", true),
        AxVoice("edge:asad", "Asad", VoiceFamily.NATURAL, "Urdu (Pakistan)", "Urdu script", "ur-PK-AsadNeural", false),
    )

    /** Gemini Live voices (Google's names + Google's one-word styles). Samples: voice_samples/geminilive_*.mp3. */
    val gemini = listOf(
        AxVoice("gemini:Kore", "Kore", VoiceFamily.GEMINI, "Gemini · any language", "Firm · Gemini default", "Kore", true),
        AxVoice("gemini:Puck", "Puck", VoiceFamily.GEMINI, "Gemini · any language", "Upbeat", "Puck", false),
        AxVoice("gemini:Aoede", "Aoede", VoiceFamily.GEMINI, "Gemini · any language", "Breezy", "Aoede", true),
        AxVoice("gemini:Charon", "Charon", VoiceFamily.GEMINI, "Gemini · any language", "Informative", "Charon", false),
        AxVoice("gemini:Zephyr", "Zephyr", VoiceFamily.GEMINI, "Gemini · any language", "Bright", "Zephyr", true),
        AxVoice("gemini:Leda", "Leda", VoiceFamily.GEMINI, "Gemini · any language", "Youthful", "Leda", true),
        AxVoice("gemini:Sulafat", "Sulafat", VoiceFamily.GEMINI, "Gemini · any language", "Warm", "Sulafat", true),
        AxVoice("gemini:Achird", "Achird", VoiceFamily.GEMINI, "Gemini · any language", "Friendly", "Achird", false),
    )

    val elevenLabs = AxVoice("elevenlabs", "ElevenLabs", VoiceFamily.ELEVENLABS, "Your own key", "Premium · your voice ID")

    val all = kodex + natural + gemini + elevenLabs

    fun byId(id: String?): AxVoice = all.firstOrNull { it.id == id } ?: kodex.first()

    /** Kodex's pace options. */
    val PACES = listOf("Slow" to 0.85f, "Normal" to 1.0f, "Fast" to 1.18f)
    const val DEFAULT_PACE = 1.0f

    enum class Script { LATIN_ENGLISH, ROMAN_URDU, URDU, DEVANAGARI }

    private val romanUrduWords = setOf(
        "hai", "hain", "hoon", "hun", "ho", "main", "mein", "mai", "aap", "ap", "aapka", "apka", "aapki", "apki", "ka", "ki", "ke", "ko",
        "se", "ne", "nahi", "nahin", "kya", "kyun", "kaise", "kaisa", "yeh", "ye", "woh", "wo", "aur", "bhi", "tha", "thi", "karo", "karna",
        "kar", "karein", "karen", "raha", "rahi", "rahe", "sakta", "sakti", "chahiye", "abhi", "bas", "theek", "thik", "acha", "achha",
        "accha", "yaar", "jee", "ji", "batao", "bataein", "batayein", "dekho", "lekin", "agar", "toh", "to", "kuch", "sab", "bohat", "bahut",
        "zyada", "kam", "wala", "wali", "liye", "hum", "tum", "mera", "meri", "tera", "humara", "apna", "apni", "kab", "kahan", "kitna",
        "shukriya", "salam", "assalam", "alaikum", "inshallah", "mashallah", "matlab", "phir", "pehle", "baad", "din", "raat", "kal", "aaj",
    )

    /** What the text mostly is, so a natural voice can hand it to the voice that pronounces it right. */
    fun detect(text: String): Script {
        var arabic = 0; var deva = 0; var latin = 0
        for (ch in text) {
            when (ch.code) {
                in 0x0600..0x06FF, in 0x0750..0x077F, in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> arabic++
                in 0x0900..0x097F -> deva++
                in 0x41..0x5A, in 0x61..0x7A -> latin++
            }
        }
        if (arabic > 0 && arabic >= deva && arabic * 2 >= latin / 3) return Script.URDU
        if (deva > 0 && deva * 2 >= latin / 3) return Script.DEVANAGARI
        val words = text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }
        if (words.size >= 2) {
            val hits = words.count { it in romanUrduWords }
            if (hits * 100 / words.size >= 18) return Script.ROMAN_URDU
        }
        return Script.LATIN_ENGLISH
    }

    /** The Gemini Live voice a live call uses when the picked voice isn't a Gemini one (same gender). */
    fun geminiTwin(voice: AxVoice): AxVoice =
        if (voice.family == VoiceFamily.GEMINI) voice else gemini.first { it.engineVoice == (if (voice.female) "Kore" else "Puck") }

    /** The Edge voice that should read [text] when [voice] is the active natural voice. */
    fun edgeVoiceFor(voice: AxVoice, text: String, smartUrdu: Boolean): String {
        if (!smartUrdu || voice.family != VoiceFamily.NATURAL) return voice.engineVoice
        val urdu = if (voice.female) "ur-PK-UzmaNeural" else "ur-PK-AsadNeural"
        val hindi = if (voice.female) "hi-IN-SwaraNeural" else "hi-IN-MadhurNeural"
        return when (detect(text)) {
            Script.URDU -> urdu
            Script.DEVANAGARI -> hindi
            Script.ROMAN_URDU -> if (voice.engineVoice.startsWith("ur-")) hindi else if (voice.engineVoice.startsWith("en-")) hindi else voice.engineVoice
            Script.LATIN_ENGLISH -> voice.engineVoice
        }
    }
}


/**
 * v0.4 (mazhar, 1 Nov 2:05 PM: "I pick a voice in Settings, but the speaker under an answer plays the default girl's voice").
 *
 * Root cause: Settings' "Test voice" reads an ENGLISH sentence, so it always sounded right. Real answers are Roman Urdu or
 * Urdu/Hindi script, and two silent swaps changed the voice by the text:
 *   1. "Smart Urdu" was ON by default: with a natural voice, every Roman-Urdu answer went to Swara/Madhur (Hindi) and
 *      Urdu script to Uzma/Asad — Ava/Emma became Swara, a different girl's voice.
 *   2. Kodex voices (English-only phone voices) handed Urdu/Hindi-script answers to the phone's own Urdu/Hindi voice,
 *      which on most phones is a female default.
 * Neither said anything after the first time. Now the picked voice reads every answer; the one case it physically
 * can't (an English phone voice and Urdu/Hindi script) goes to a natural Urdu/Hindi voice of the SAME gender, and the
 * player says so. Smart Urdu is opt-in.
 */
object VoiceRouting {
    data class Plan(val voice: AxVoice, val note: String? = null)

    fun plan(selected: AxVoice, text: String): Plan {
        if (selected.family != VoiceFamily.KODEX) return Plan(selected)
        val script = VoiceCatalog.detect(text)
        val stand = when (script) {
            VoiceCatalog.Script.URDU -> if (selected.female) "edge:uzma" else "edge:asad"
            VoiceCatalog.Script.DEVANAGARI -> if (selected.female) "edge:swara" else "edge:madhur"
            else -> return Plan(selected)
        }
        val v = VoiceCatalog.byId(stand)
        val what = if (script == VoiceCatalog.Script.URDU) "Urdu" else "Hindi"
        return Plan(v, "${selected.name} is an English phone voice and can't read $what script — ${v.name} read this one. Pick a natural or Gemini voice to hear one voice for everything.")
    }
}
