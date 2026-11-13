package com.mazharansari.axion.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * P2-9: Plugin API for community tools.
 *
 * A plugin is a Kotlin class compiled into the app (or supplied as source and
 * compiled with it) that exposes extra [Tool]s to the agent. Plugins are
 * registered once at startup; the coordinator wires the registry's tools into
 * the agent alongside the built-ins.
 *
 * ## Security rule
 *
 * Plugin tools run with exactly the same [ToolContext] as built-in tools —
 * the same workspace, skills, memory, schedules and model access. There is no
 * sandbox and no separate privilege level for plugins. A plugin tool that runs
 * shell commands MUST go through the same approval gate as the built-in shell
 * tools ([ApprovalGate], which lives in the agent, not here). This file
 * deliberately does NOT implement a second gate: one gate, in one place, is
 * the rule — a plugin must never run a command, touch a file outside the
 * workspace, or reach the network on its own authority.
 *
 * ## Out of scope
 *
 * External code loading is explicitly OUT of scope: Axion never loads plugin
 * APKs, DEX files, or any other code at runtime. A plugin is a Kotlin class
 * in the app's own source tree (see [com.mazharansari.axion.agent.plugins.SamplePlugin]
 * for the documented example). Anything that would need downloading and
 * executing foreign code must be reviewed as a full app release, not shipped
 * through this API.
 */

/**
 * A community plugin: a named, versioned bundle of [Tool]s.
 *
 * Security: a plugin's tools run with the same [ToolContext] as built-in
 * tools, so a plugin is trusted code — it is compiled into the app, never
 * loaded from an external APK/DEX (see the file-level note above). A plugin
 * tool that runs shell commands must go through the agent's approval gate
 * ([ApprovalGate]) exactly like the built-in shell tools do.
 */
interface AxionPlugin {
    /** Unique id: lowercase letters, digits and dashes only (e.g. "sample-tools"). */
    val id: String
    /** Human-readable name shown in the tools library. Must not be blank. */
    val name: String
    /** Version string, e.g. "1.0.0". Must not be blank. */
    val version: String
    /** One-line description of what the plugin adds. */
    val description: String
    /** The tools this plugin contributes. Tool names must be unique (see [PluginRegistry]). */
    val tools: List<Tool>
}

/** Plugin ids are lowercase letters, digits and dashes only, never starting or ending with a dash. */
private val PLUGIN_ID_REGEX = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

/** Returns true when [id] is a valid plugin id. */
fun isValidPluginId(id: String): Boolean = PLUGIN_ID_REGEX.matches(id)

/**
 * The manifest a plugin ships with (for example in its README or a
 * `plugin.json` next to its source). It is descriptive only: the manifest
 * never loads code, it just declares what a plugin contains so the tools
 * library can list it before the class is wired in.
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val toolNames: List<String>,
)

/**
 * Parses a [PluginManifest] from its JSON form. Returns null — never throws —
 * for malformed JSON, missing fields, a bad plugin id, or a `toolNames`
 * entry that is not a list of strings.
 *
 * Expected shape:
 * ```json
 * {
 *   "id": "sample-tools",
 *   "name": "Sample Tools",
 *   "version": "1.0.0",
 *   "description": "Example plugin: text statistics and unit conversion.",
 *   "toolNames": ["text_stats", "unit_convert"]
 * }
 * ```
 */
object PluginManifestParser {
    fun parse(json: String): PluginManifest? {
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            val id = obj.string("id") ?: return null
            if (!isValidPluginId(id)) return null
            val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: return null
            val version = obj.string("version")?.takeIf { it.isNotBlank() } ?: return null
            val description = obj.string("description") ?: return null
            val toolNamesElement = obj["toolNames"] as? JsonArray ?: return null
            val toolNames = toolNamesElement.map { (it as? JsonPrimitive)?.contentOrNull ?: return null }
            if (toolNames.any { it.isBlank() }) return null
            PluginManifest(id, name, version, description, toolNames)
        } catch (_: Exception) {
        null
    }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * Holds the plugins registered for this run of the app, and answers the two
 * questions the agent asks: which plugins exist, and which tools do they add.
 *
 * Registration is single-threaded at startup (a simple `synchronized` block
 * guards the maps), which is all this registry needs — plugins are not
 * registered while a chat is running.
 *
 * Rejection rules in [register] (a failure is a [Result.failure] with a plain
 * message, never an exception):
 * - duplicate plugin id (a plugin can only be registered once);
 * - blank plugin name or version, or an invalid plugin id;
 * - a tool name that is blank, repeated inside one plugin, already exposed by
 *   another registered plugin, or colliding with a built-in tool name
 *   ([builtInNames] — pass the names from [ToolCatalog] so a plugin can never
 *   shadow or replace a built-in, e.g. `run_command`);
 * - a tool name starting with "mcp_" (reserved for connector tools).
 *
 * Security: registration only checks names. It does not (and cannot) audit
 * what a plugin tool does at runtime — plugin code is trusted, compiled-in
 * code, and a tool that runs shell commands is still subject to the agent's
 * approval gate ([ApprovalGate]) at call time.
 */
class PluginRegistry(private val builtInNames: Set<String>) {
    private val lock = Any()
    private val pluginsById = linkedMapOf<String, AxionPlugin>()
    private val toolOwner = mutableMapOf<String, String>()

    /**
     * Registers [plugin], or returns a failure explaining why it was rejected
     * (duplicate id, duplicate or colliding tool name, blank name/version,
     * invalid id). A failed registration changes nothing.
     */
    fun register(plugin: AxionPlugin): Result<Unit> = runCatching {
        synchronized(lock) {
            require(isValidPluginId(plugin.id)) { "Invalid plugin id: \"${plugin.id}\"" }
            require(plugin.name.isNotBlank()) { "Plugin \"${plugin.id}\" has a blank name" }
            require(plugin.version.isNotBlank()) { "Plugin \"${plugin.id}\" has a blank version" }
            require(plugin.id !in pluginsById) { "Plugin \"${plugin.id}\" is already registered" }
            val seenInPlugin = mutableSetOf<String>()
            for (tool in plugin.tools) {
                val name = tool.spec.name
                require(name.isNotBlank()) { "Plugin \"${plugin.id}\" has a tool with a blank name" }
                require(seenInPlugin.add(name)) { "Plugin \"${plugin.id}\" exposes tool \"$name\" twice" }
                require(name !in builtInNames) { "Tool \"$name\" collides with a built-in tool" }
                require(!name.startsWith("mcp_")) { "Tool \"$name\" uses the reserved \"mcp_\" prefix (connector tools)" }
                val owner = toolOwner[name]
                require(owner == null) { "Tool \"$name\" is already exposed by plugin \"$owner\"" }
            }
            pluginsById[plugin.id] = plugin
            for (tool in plugin.tools) toolOwner[tool.spec.name] = plugin.id
        }
    }

    /** Removes the plugin with [id]. Returns true when a plugin was actually removed. */
    fun unregister(id: String): Boolean = synchronized(lock) {
        val plugin = pluginsById.remove(id) ?: return false
        for (tool in plugin.tools) toolOwner.remove(tool.spec.name, id)
        true
    }

    /** All registered plugins, in registration order. */
    fun plugins(): List<AxionPlugin> = synchronized(lock) { pluginsById.values.toList() }

    /** Every registered plugin's tools, in registration order (plugins first-registered first). */
    fun allTools(): List<Tool> = synchronized(lock) { pluginsById.values.flatMap { it.tools } }

    /** The names of every registered plugin tool. */
    fun toolNames(): Set<String> = synchronized(lock) { toolOwner.keys.toSet() }
}
