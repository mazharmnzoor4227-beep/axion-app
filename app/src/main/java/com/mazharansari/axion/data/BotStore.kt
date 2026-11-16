package com.mazharansari.axion.data

import com.mazharansari.axion.agent.Attempt
import com.mazharansari.axion.agent.ToolCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * PHASE 1 SWARM — the Bot Store's unit: a bot is a DEFINITION bundle, not code.
 *
 * A bot is a persona + skills + tool allow-list + suggested provider, stored in the app's
 * own storage (bots.json) and run through Axion's OWN agent loop — no foreign code is ever
 * executed. Installing a bot only stores its definition; it never runs until the user
 * starts a chat with it from the Bots section.
 *
 * Any bot can create another bot, and the user (or the creating bot) NAMES it — "CEO" is
 * just one possible name, not a built-in role. There is no special CEO class: the
 * parent/child relationship plus the given name is what makes a bot the parent for its
 * run. The bots in the store seed are definitions written here from the PATTERNS of the
 * verified sources below (Axion's own Kotlin); no source code was copied from any of them.
 * Original Axion code.
 */
@Serializable
data class BotDefinition(
    val id: String,
    val name: String,
    val description: String,
    val persona: String,
    val skills: List<String> = emptyList(),
    /** ToolCatalog group ids this bot may use; empty = no restriction (all groups on). */
    val toolGroups: List<String> = emptyList(),
    /**
     * Preferred FREE provider — a provider NAME, e.g. "Google Gemini", "Groq", "OpenRouter",
     * "xAI Grok". It only reorders Axion's existing failover chain (see [preferProvider]);
     * it never pins a provider or bypasses failover. Null = Auto. Antigravity is the Build
     * engine, not an Ask provider, so it is never offered here. Never "OpenAI".
     */
    val suggestedProvider: String? = null,
    /** Where this bot's pattern comes from — shown in the store, with its licence. */
    val sourceName: String = "",
    val sourceUrl: String = "",
    val license: String = "",
    /**
     * Phase 2: the bot's logo, chosen by the user — "builtin:<id>" or
     * "file:<path>" (see [BotLogo]); "" = the bot's initial. Stored in bots.json.
     */
    val logo: String = "",
) {
    /** The system-prompt addition for a chat run as this bot (Axion's own agent loop). */
    fun promptBlock(): String = buildString {
        append("You are $name — $description. ")
        if (persona.isNotBlank()) append(persona.trim()).append(' ')
        if (skills.isNotEmpty()) append("Your skills: ${skills.joinToString(", ")} — load the matching skill (load_skill) before specialised work. ")
        if (toolGroups.isNotEmpty()) append("You may use only these tool groups: ${toolGroups.joinToString(", ")}. If a task needs anything else, say so instead of working around it. ")
        append("Verify before you deliver: never present a result as done until it is checked. If a check fails, say what failed and what is still unverified.")
    }
}

/** Installed + user-created bot definitions, persisted as bots.json (same pattern as SkillRepo). */
class BotDefinitionStore(private val dir: File) {
    private val index = File(dir, "bots.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _definitions = MutableStateFlow<List<BotDefinition>>(load())
    val definitions: StateFlow<List<BotDefinition>> = _definitions

    private fun load(): List<BotDefinition> = runCatching {
        json.decodeFromString<List<BotDefinition>>(index.readText())
    }.getOrDefault(emptyList())

    fun isInstalled(id: String): Boolean = _definitions.value.any { it.id == id }

    /** Install a store bot: store its definition only. Returns false when already installed. Never runs anything. */
    fun install(def: BotDefinition): Boolean {
        if (isInstalled(def.id)) return false
        _definitions.value = _definitions.value + def
        persist()
        return true
    }

    /** The user creates a bot and names it anything — "CEO" is just the owner's example name. */
    fun create(name: String, description: String, persona: String, suggestedProvider: String?): BotDefinition {
        val def = BotDefinition(
            id = "user-" + UUID.randomUUID(),
            name = name.trim().take(40).ifBlank { "My bot" },
            description = description.trim().take(200),
            persona = persona.trim().take(2000),
            suggestedProvider = suggestedProvider?.takeIf { it.isNotBlank() },
            sourceName = "Created by you",
        )
        _definitions.value = _definitions.value + def
        persist()
        return def
    }

    /** Uninstall (store bot) or delete (user-created bot). */
    fun remove(id: String) {
        _definitions.value = _definitions.value.filterNot { it.id == id }
        persist()
    }

    /** Phase 2: change a bot's logo (a builtin id string or a "file:" path). No-op for an unknown id. */
    fun setLogo(id: String, logo: String) {
        if (_definitions.value.none { it.id == id }) return
        _definitions.value = _definitions.value.map { if (it.id == id) it.copy(logo = logo) else it }
        persist()
    }

    private fun persist() {
        runCatching { dir.mkdirs(); index.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(BotDefinition.serializer()), _definitions.value)) }
    }
}

/**
 * A bot's tool allow-list as the [ToolCatalog] group switches the agent loop already reads.
 * An empty allow-list means unrestricted (every group on) — that is the default for a
 * user-created bot, which the user can narrow by editing its definition later.
 */
fun switchesForBot(def: BotDefinition): Map<String, Boolean> =
    ToolCatalog.groups.associate { it.id to (def.toolGroups.isEmpty() || it.id in def.toolGroups) }

/**
 * Soft provider preference: attempts from the suggested provider go first; the rest keep
 * their ranked order, and the existing failover still applies if the preferred one fails.
 * The free-provider set the owner uses is Gemini, Grok, OpenRouter (Ask) + Antigravity
 * (Build only); never OpenAI.
 */
fun preferProvider(attempts: List<Attempt>, suggestedProvider: String?): List<Attempt> {
    if (suggestedProvider.isNullOrBlank()) return attempts
    return attempts.sortedByDescending { it.provider.equals(suggestedProvider, ignoreCase = true) }
}
