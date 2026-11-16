package com.mazharansari.axion.data

/**
 * Phase 2 — a bot's logo, chosen by the user.
 *
 * Stored as one string on [BotDefinition.logo]:
 * - "builtin:<id>" — one of [BotLogo.BUILTINS] (the UI maps the id to its glyph).
 * - "file:<absolute path>" — a gallery image the user picked, copied into the
 *   app's own storage so it survives the gallery permission being revoked.
 * - "" — no logo yet; the UI shows the bot's initial instead.
 * Original Axion code.
 */
sealed interface BotLogo {
    data class Builtin(val id: String) : BotLogo
    data class Custom(val path: String) : BotLogo
    data object None : BotLogo

    companion object {
        const val PREFIX_BUILTIN = "builtin:"
        const val PREFIX_CUSTOM = "file:"

        fun parse(raw: String): BotLogo = when {
            raw.startsWith(PREFIX_BUILTIN) -> {
                val id = raw.removePrefix(PREFIX_BUILTIN).trim()
                if (id.isEmpty()) None else Builtin(id)
            }
            raw.startsWith(PREFIX_CUSTOM) -> {
                val path = raw.removePrefix(PREFIX_CUSTOM).trim()
                if (path.isEmpty()) None else Custom(path)
            }
            raw.isBlank() -> None
            else -> Builtin(raw.trim()) // legacy: a bare builtin id
        }

        fun builtin(id: String): String = "$PREFIX_BUILTIN$id"
        fun custom(path: String): String = "$PREFIX_CUSTOM$path"

        /** Built-in logo ids — the UI maps each id to its glyph. */
        val BUILTINS: List<BuiltinDef> = listOf(
            BuiltinDef("brain", "Brain"),
            BuiltinDef("agent", "Agent"),
            BuiltinDef("rocket", "Rocket"),
            BuiltinDef("sparkles", "Sparkles"),
            BuiltinDef("bolt", "Bolt"),
            BuiltinDef("hammer", "Hammer"),
            BuiltinDef("globe", "Globe"),
            BuiltinDef("person", "Person"),
            BuiltinDef("gem", "Gem"),
            BuiltinDef("shield", "Shield"),
            BuiltinDef("wand", "Wand"),
            BuiltinDef("cube", "Cube"),
        )

        fun isKnownBuiltin(id: String): Boolean = BUILTINS.any { it.id == id }
    }
}

data class BuiltinDef(val id: String, val label: String)
