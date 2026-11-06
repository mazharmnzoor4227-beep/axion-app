
package com.mazharansari.axion

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.5 rule: every piece of app UI text is plain English — no Roman Urdu in strings.xml, Compose text, toasts, errors,
 * dialogs, guides, setup cards or the voice/call screens. (AI replies still follow the user's language; that lives in
 * the prompts, which are excluded below, as is the intent matcher that reads what the user typed.)
 */
class EnglishUiTest {
    private val banned = Regex("\\b(karo|hai|nahi|ke|mein|kar|aap|wala)\\b", RegexOption.IGNORE_CASE)
    private val literal = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
    private val tripleQuoted = Regex("\"\"\"([\\s\\S]*?)\"\"\"")
    /** Model prompts and the user-input matcher — not UI. */
    private val notUi = setOf("agent/Intent.kt", "agent/Agent.kt", "build/BuildPrompts.kt", "agent/HistoryCompressor.kt", "agent/Planning.kt", "phone/PhoneSafety.kt")   // pattern lists that recognise Roman-Urdu user input (or on-screen phone text), not UI text

    private fun root(): File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test fun noRomanUrduInUiStrings() {
        val main = root(); val src = File(main, "java/com/mazharansari/axion")
        val hits = mutableListOf<String>()
        src.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val rel = f.relativeTo(src).path.replace('\\', '/')
            if (rel in notUi) return@forEach
            val text = f.readText()
            tripleQuoted.findAll(text).forEach { m -> if (banned.containsMatchIn(m.groupValues[1])) hits += "$rel: \"\"\"${m.groupValues[1].take(60)}…" }
            text.replace(tripleQuoted, "").lineSequence().forEachIndexed { i, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                val lits = literal.findAll(line).map { it.groupValues[1] }.toList()
                // a line of many one-word literals is a word list (the script detector), not UI text
                val wordList = lits.count { !it.contains(' ') } > 3
                lits.forEach { v ->
                    if (wordList && !v.contains(' ')) return@forEach
                    if (banned.containsMatchIn(v)) hits += "$rel:${i + 1}: \"$v\""
                }
            }
        }
        File(main, "res").walkTopDown().filter { it.isFile && it.name.endsWith(".xml") && it.parentFile.name.startsWith("values") }.forEach { f ->
            Regex(">([^<]+)<").findAll(f.readText()).forEach { m -> if (banned.containsMatchIn(m.groupValues[1])) hits += "${f.name}: ${m.groupValues[1]}" }
        }
        assertTrue("Roman Urdu in UI strings (use English):\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test fun checkerCatchesIt() {
        assertTrue(banned.containsMatchIn("Connectors mein add karo"))
        assertTrue(!banned.containsMatchIn("Kept the keyboard open; marker, karaoke, hair, Akers"))
    }
}
