package com.mazharansari.axion.agent

import com.mazharansari.axion.llm.AssistantItem
import com.mazharansari.axion.llm.ChatItem
import com.mazharansari.axion.llm.UserItem

/**
 * v0.5.1 (mazhar 3:36 PM): after an answer in Urdu script, "dusra do" (Roman Urdu) must get a Roman Urdu reply.
 * The system prompt already says "follow the script of the latest message"; when the conversation so far is in a
 * different script, or the message is a short follow-up, we add one explicit line so the model can't drift.
 */
object ScriptHint {
    enum class Script { LATIN, ARABIC, DEVANAGARI, OTHER }

    fun of(text: String): Script {
        var lat = 0; var ar = 0; var dev = 0
        for (c in text) when {
            c in 'a'..'z' || c in 'A'..'Z' -> lat++
            c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' || c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF' -> ar++
            c in '\u0900'..'\u097F' -> dev++
        }
        val max = maxOf(lat, ar, dev)
        return when { max == 0 -> Script.OTHER; max == ar -> Script.ARABIC; max == dev -> Script.DEVANAGARI; else -> Script.LATIN }
    }

    private fun name(s: Script) = when (s) {
        Script.LATIN -> "English letters (Roman Urdu if they write Urdu words in English letters, e.g. \"dusra do\")"
        Script.ARABIC -> "Urdu script (اردو)"
        Script.DEVANAGARI -> "Devanagari"
        Script.OTHER -> "the user's script"
    }

    fun note(latest: String, history: List<ChatItem>, followUp: Boolean): String {
        val script = of(latest)
        val recent = history.takeLast(6).joinToString(" ") { when (it) { is AssistantItem -> it.text.take(600); is UserItem -> it.text.take(300); else -> "" } }
        val prior = if (recent.isBlank()) script else of(recent)
        val sb = StringBuilder()
        if (followUp && history.any { it is AssistantItem }) {
            sb.append("\nFOLLOW-UP: the user's latest message (\"").append(latest.trim().take(40)).append("\") is a short follow-up to the conversation above. ")
            sb.append("Do what they asked last time again or continue it (e.g. \"dusra do\" / \"ek aur\" = give another one of the same kind, \"aur\" = continue), at full length — not a one-line reply.")
        }
        if (script != Script.OTHER && (prior != script || followUp)) {
            sb.append("\nSCRIPT NOW: the latest message is written in ").append(name(script)).append(". Write your whole reply in that script, even if earlier messages or your earlier answers used another script.")
        }
        return sb.toString()
    }
}
