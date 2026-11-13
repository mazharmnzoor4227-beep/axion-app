package com.mazharansari.axion.agent.plugins

import com.mazharansari.axion.agent.StepLabel
import com.mazharansari.axion.agent.Tool
import com.mazharansari.axion.agent.ToolContext
import com.mazharansari.axion.agent.ToolOutput
import com.mazharansari.axion.agent.s
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * P2-9 sample plugin — developer documentation in code form.
 *
 * This is the reference implementation plugin authors copy: two small,
 * deterministic, offline tools built on the existing [Tool] interface and
 * [ToolSpec]. It shows the whole shape — spec with a JSON-schema parameter
 * object, a [StepLabel], input validation that returns
 * [ToolOutput.isError] instead of throwing, and a plain-text result the
 * model can read.
 *
 * This plugin is NOT auto-registered anywhere: there is no registration call
 * in this file, and merely having the class on the classpath adds no tools to
 * the agent. The coordinator (or the app's startup code) registers it
 * explicitly with `PluginRegistry.register(SamplePlugin())` when — and only
 * when — it wants these example tools available.
 *
 * Security note for plugin authors: tools run with the same [ToolContext] as
 * built-ins. These two tools touch nothing outside their arguments (no
 * files, no network, no shell). A tool that runs shell commands must go
 * through the agent's approval gate ([com.mazharansari.axion.agent.ApprovalGate])
 * exactly like the built-in shell tools — do not run commands on your own
 * authority.
 */
class SamplePlugin : com.mazharansari.axion.agent.AxionPlugin {
    override val id: String = "sample-tools"
    override val name: String = "Sample Tools"
    override val version: String = "1.0.0"
    override val description: String = "Example plugin: text statistics and unit conversion."
    override val tools: List<Tool> = listOf(TextStatsTool(), UnitConvertTool())
}

/** Counts words, characters and lines in a piece of text. */
private class TextStatsTool : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "text_stats",
        description = "Count the words, characters and lines in a piece of text.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "The text to count.")
                }
            }
            putJsonArray("required") { add("text") }
        },
    )

    override fun label(args: JsonObject): StepLabel =
        StepLabel(StepKind.OTHER, "Text stats", "")

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val text = args.s("text") ?: return ToolOutput(
            forModel = "text_stats needs a \"text\" argument.",
            isError = true,
        )
        val words = if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
        val characters = text.length
        val lines = if (text.isEmpty()) 0 else text.lines().size
        return ToolOutput(
            forModel = "Words: $words, Characters: $characters, Lines: $lines",
            doneTitle = "Counted text stats",
        )
    }
}

/** Converts a value between km/miles, kg/lb, m/ft and C/F. */
private class UnitConvertTool : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "unit_convert",
        description = "Convert a value between units: km/miles, kg/lb, m/ft, C/F.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("value") {
                    put("type", "number")
                    put("description", "The value to convert.")
                }
                putJsonObject("from") {
                    put("type", "string")
                    put("description", "The unit to convert from: km, miles, kg, lb, m, ft, C or F.")
                }
                putJsonObject("to") {
                    put("type", "string")
                    put("description", "The unit to convert to: km, miles, kg, lb, m, ft, C or F.")
                }
            }
            putJsonArray("required") { add("value"); add("from"); add("to") }
        },
    )

    override fun label(args: JsonObject): StepLabel =
        StepLabel(StepKind.OTHER, "Unit conversion", "${args.s("from").orEmpty()} to ${args.s("to").orEmpty()}")

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val value = args.num("value") ?: return ToolOutput(
            forModel = "unit_convert needs a numeric \"value\" argument.",
            isError = true,
        )
        val from = args.s("from")?.let(::normaliseUnit)
            ?: return ToolOutput(forModel = "unit_convert needs a \"from\" unit.", isError = true)
        val to = args.s("to")?.let(::normaliseUnit)
            ?: return ToolOutput(forModel = "unit_convert needs a \"to\" unit.", isError = true)
        val converted = convert(value, from, to) ?: return ToolOutput(
            forModel = "Unknown or incompatible units: \"$from\" to \"$to\". " +
                "Supported units: km, miles, kg, lb, m, ft, C, F.",
            isError = true,
        )
        return ToolOutput(
            forModel = "${formatNumber(value)} $from = ${formatNumber(converted)} $to",
            doneTitle = "Converted $from to $to",
        )
    }

    /** Accepts "c"/"f" in lowercase; every other unit is case-sensitive as documented. */
    private fun normaliseUnit(raw: String): String = when (raw) {
        "c" -> "C"
        "f" -> "F"
        else -> raw
    }

    /**
     * Converts [value] from [from] to [to], or null when either unit is unknown
     * or the two units measure different things (length vs mass vs temperature).
     */
    private fun convert(value: Double, from: String, to: String): Double? {
        if (from == to) return value
        if (from in TEMPERATURE || to in TEMPERATURE) {
            if (from !in TEMPERATURE || to !in TEMPERATURE) return null
            return when {
                from == "C" && to == "F" -> value * 9.0 / 5.0 + 32.0
                from == "F" && to == "C" -> (value - 32.0) * 5.0 / 9.0
                else -> null
            }
        }
        val fromMetres = LENGTH_IN_METRES[from] ?: MASS_IN_KG[from]?.let { return convertMass(value, from, to) }
        val toMetres = LENGTH_IN_METRES[to] ?: return null
        if (fromMetres == null) return null
        return value * fromMetres / toMetres
    }

    private fun convertMass(value: Double, from: String, to: String): Double? {
        val fromKg = MASS_IN_KG[from] ?: return null
        val toKg = MASS_IN_KG[to] ?: return null
        return value * fromKg / toKg
    }

    private fun formatNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    companion object {
        private val LENGTH_IN_METRES = mapOf("km" to 1000.0, "miles" to 1609.344, "m" to 1.0, "ft" to 0.3048)
        private val MASS_IN_KG = mapOf("kg" to 1.0, "lb" to 0.45359237)
        private val TEMPERATURE = setOf("C", "F")
    }
}

/** Reads a JSON number argument, accepting both `1.5` and `"1.5"`. */
private fun JsonObject.num(key: String): Double? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
}
