package com.mazharansari.axion.wakeword

/**
 * Phase B (v0.7.2): pure matcher for the wake phrase in recognized speech text.
 *
 * The recognizer runs with a grammar limited to wake phrases, so anything it returns is already a
 * wake attempt — this matcher only has to be tolerant of the small model's mishears ("action" for
 * "axion" and friends) without ever matching ordinary speech. Adjacent-pair matching, no fuzzy
 * substrings: "an action movie" must NOT wake the app, "hey action" (a misheard "hey axion") must.
 */
object WakePhraseMatcher {
    private val SECOND_WORDS = setOf("axion", "action", "exion", "aksion", "axian", "akshan")
    private val FIRST_WORDS = setOf("hey", "hay", "a", "ok", "okay")

    fun isWakePhrase(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        if (words.size == 1) return words[0] == "axion"
        return (0 until words.size - 1).any { i ->
            words[i] in FIRST_WORDS && words[i + 1] in SECOND_WORDS
        }
    }
}
