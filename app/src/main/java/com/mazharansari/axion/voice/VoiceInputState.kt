package com.mazharansari.axion.voice

/** UI state for the inline voice input panel. Pure data — no Android dependency. */
data class VoiceInputState(
    val listening: Boolean = false,
    /** Segments the recogniser has already finalised. */
    val committed: String = "",
    /** Live words from the current, still-open segment. */
    val partial: String = "",
    /** Fatal problem, human wording first (permission, unavailable). Pauses are not errors. */
    val error: String? = null,
    /** Raw SpeechRecognizer error code behind [error], for support. */
    val errorCode: Int? = null,
    /** True while a dropped voice service is being reconnected automatically. */
    val reconnecting: Boolean = false,
    /** Human line shown while [reconnecting] (e.g. "Voice service dropped — reconnecting…"). */
    val reconnectMessage: String? = null,
    /** Soft, non-fatal line (e.g. "Didn't catch that") — a pause the user may want to see. */
    val softMessage: String? = null,
    /**
     * Phase 2: true between the user tapping Send and the final words coming
     * back. While finishing, the mic ignores taps — the old flow let a second
     * tap re-enter finish() and drop the text the first tap was collecting.
     */
    val finishing: Boolean = false,
) {
    val fullText: String
        get() = listOf(committed, partial).filter { it.isNotBlank() }.joinToString(" ").trim()
}
