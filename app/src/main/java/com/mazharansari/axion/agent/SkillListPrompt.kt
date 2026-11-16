package com.mazharansari.axion.agent

/**
 * Phase 2 (token efficiency): the skill list injected into every ACTION system prompt.
 * It used to be unbounded — every enabled skill's name + 100 chars of description, so
 * each skill the user installs from GitHub permanently taxed every prompt. Now the
 * descriptions are short and the list is capped; every skill's NAME stays visible
 * (that is how the model discovers skills — it can load_skill <name> to read the full
 * description), and the count note says how many more exist past the cap.
 *
 * Kept dependency-free so the rule is unit-testable without a device.
 */
object SkillListPrompt {
    const val MAX_ENTRIES = 20
    const val DESC_CHARS = 40

    /** "  - name: short description" per skill, or names only past the cap. */
    fun render(entries: List<Pair<String, String>>): String {
        if (entries.isEmpty()) return ""
        val shown = entries.take(MAX_ENTRIES)
        val lines = shown.joinToString("\n") { (name, desc) ->
            val d = desc.replace("\n", " ").trim().take(DESC_CHARS)
            if (d.isEmpty()) "  - $name" else "  - $name: $d"
        }
        val more = entries.size - shown.size
        return if (more > 0) "$lines\n  (+ $more more skills — load_skill <name> to see one)" else lines
    }
}
