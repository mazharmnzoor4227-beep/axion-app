package com.mazharansari.axion

import com.mazharansari.axion.agent.AxionPlugin
import com.mazharansari.axion.agent.PluginManifestParser
import com.mazharansari.axion.agent.PluginRegistry
import com.mazharansari.axion.agent.StepLabel
import com.mazharansari.axion.agent.Tool
import com.mazharansari.axion.agent.ToolContext
import com.mazharansari.axion.agent.ToolOutput
import com.mazharansari.axion.agent.Workspace
import com.mazharansari.axion.agent.plugins.SamplePlugin
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.ToolSpec
import com.mazharansari.axion.skills.SkillRepo
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/**
 * P2-9 plugin API pure-logic tests (no model, no network, no Android framework):
 * registry rejection rules, manifest parsing, and the sample plugin's tools.
 */
class P2PluginApiTest {

    private fun fakeTool(name: String): Tool = object : Tool {
        override val spec: ToolSpec = ToolSpec(
            name,
            "Test tool $name",
            buildJsonObject { put("type", "object") },
        )
        override fun label(args: JsonObject): StepLabel = StepLabel(StepKind.OTHER, "Test", "")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput =
            ToolOutput(forModel = "ok")
    }

    private fun fakePlugin(
        id: String,
        tools: List<Tool>,
        name: String = "Test Plugin",
        version: String = "1.0.0",
    ): AxionPlugin = object : AxionPlugin {
        override val id: String = id
        override val name: String = name
        override val version: String = version
        override val description: String = "Test plugin"
        override val tools: List<Tool> = tools
    }

    private fun registry(): PluginRegistry =
        PluginRegistry(builtInNames = setOf("run_command", "web_search", "read_file"))

    private fun testContext(): ToolContext {
        val dir = Files.createTempDirectory("p2-plugin-test").toFile()
        return ToolContext(Workspace(File(dir, "ws")), SkillRepo(null, File(dir, "skills")))
    }

    // ── Registry rejection rules ───────────────────────────────────────────────

    @Test fun registryRejectsDuplicatePluginId() {
        val r = registry()
        assertTrue(r.register(fakePlugin("my-plugin", listOf(fakeTool("my_tool")))).isSuccess)
        val second = r.register(fakePlugin("my-plugin", listOf(fakeTool("other_tool"))))
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()?.message?.contains("already registered") == true)
        assertEquals(listOf("my_tool"), r.toolNames().toList())
    }

    @Test fun registryRejectsToolNameCollidingWithBuiltIn() {
        val r = registry()
        val result = r.register(fakePlugin("bad-plugin", listOf(fakeTool("run_command"))))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("built-in") == true)
        assertTrue(r.plugins().isEmpty())
        assertTrue(r.toolNames().isEmpty())
    }

    @Test fun registryRejectsMcpPrefixedToolName() {
        val r = registry()
        val result = r.register(fakePlugin("mcp-plugin", listOf(fakeTool("mcp_gmail"))))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("mcp_") == true)
        assertTrue(r.plugins().isEmpty())
    }

    @Test fun registryRejectsSameToolNameAcrossTwoPlugins() {
        val r = registry()
        assertTrue(r.register(fakePlugin("plugin-a", listOf(fakeTool("shared_tool")))).isSuccess)
        val second = r.register(fakePlugin("plugin-b", listOf(fakeTool("shared_tool"))))
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()?.message?.contains("already exposed") == true)
        assertEquals(1, r.plugins().size)
    }

    @Test fun registryRejectsDuplicateToolNameWithinOnePlugin() {
        val r = registry()
        val result = r.register(fakePlugin("dup-plugin", listOf(fakeTool("same_tool"), fakeTool("same_tool"))))
        assertTrue(result.isFailure)
        assertTrue(r.plugins().isEmpty())
    }

    @Test fun registryRejectsBlankNameAndInvalidId() {
        val r = registry()
        assertTrue(r.register(fakePlugin("ok-id", listOf(fakeTool("t1")), name = " ")).isFailure)
        assertTrue(r.register(fakePlugin("Bad_ID", listOf(fakeTool("t2")))).isFailure)
        assertTrue(r.plugins().isEmpty())
    }

    @Test fun unregisterRemovesPluginAndFreesToolNames() {
        val r = registry()
        assertTrue(r.register(fakePlugin("temp-plugin", listOf(fakeTool("temp_tool")))).isSuccess)
        assertTrue(r.unregister("temp-plugin"))
        assertFalse(r.unregister("temp-plugin"))
        assertTrue(r.toolNames().isEmpty())
        assertTrue(r.register(fakePlugin("other-plugin", listOf(fakeTool("temp_tool")))).isSuccess)
    }

    @Test fun allToolsFollowRegistrationOrder() {
        val r = registry()
        assertTrue(r.register(fakePlugin("p-one", listOf(fakeTool("tool_a"), fakeTool("tool_b")))).isSuccess)
        assertTrue(r.register(SamplePlugin()).isSuccess)
        assertEquals(listOf("tool_a", "tool_b", "text_stats", "unit_convert"), r.allTools().map { it.spec.name })
        assertEquals(listOf("p-one", "sample-tools"), r.plugins().map { it.id })
    }

    // ── Manifest parser ────────────────────────────────────────────────────────

    @Test fun manifestParserAcceptsValidManifest() {
        val json = """
            {
              "id": "sample-tools",
              "name": "Sample Tools",
              "version": "1.0.0",
              "description": "Example plugin: text statistics and unit conversion.",
              "toolNames": ["text_stats", "unit_convert"]
            }
        """.trimIndent()
        val manifest = PluginManifestParser.parse(json)
        assertNotNull(manifest)
        assertEquals("sample-tools", manifest!!.id)
        assertEquals("Sample Tools", manifest.name)
        assertEquals("1.0.0", manifest.version)
        assertEquals(listOf("text_stats", "unit_convert"), manifest.toolNames)
    }

    @Test fun manifestParserReturnsNullForMalformedJson() {
        assertNull(PluginManifestParser.parse("{ not json"))
        assertNull(PluginManifestParser.parse(""))
        assertNull(PluginManifestParser.parse("[]"))
    }

    @Test fun manifestParserReturnsNullForMissingId() {
        val json = """
            {"name": "No Id", "version": "1.0.0", "description": "Missing id.", "toolNames": ["x"]}
        """.trimIndent()
        assertNull(PluginManifestParser.parse(json))
    }

    @Test fun manifestParserReturnsNullForBadIdAndMissingFields() {
        val badId = """
            {"id": "Bad_ID", "name": "Bad", "version": "1.0.0", "description": "Bad id.", "toolNames": []}
        """.trimIndent()
        assertNull(PluginManifestParser.parse(badId))
        val missingVersion = """
            {"id": "ok-id", "name": "No Version", "description": "Missing version.", "toolNames": []}
        """.trimIndent()
        assertNull(PluginManifestParser.parse(missingVersion))
    }

    // ── Sample plugin tools ────────────────────────────────────────────────────

    private fun sampleTool(name: String): Tool =
        SamplePlugin().tools.single { it.spec.name == name }

    @Test fun samplePluginTextStatsReturnsCorrectCounts() = runBlocking {
        val out = sampleTool("text_stats").run(
            buildJsonObject { put("text", "Hello world\nsecond line") },
            testContext(),
        )
        assertFalse(out.isError)
        assertEquals("Words: 4, Characters: 23, Lines: 2", out.forModel)
    }

    @Test fun samplePluginTextStatsRejectsMissingText() = runBlocking {
        val out = sampleTool("text_stats").run(buildJsonObject { }, testContext())
        assertTrue(out.isError)
        assertTrue(out.forModel.contains("text"))
    }

    @Test fun samplePluginUnitConvertConvertsKmToMiles() = runBlocking {
        val out = sampleTool("unit_convert").run(
            buildJsonObject { put("value", 1.0); put("from", "km"); put("to", "miles") },
            testContext(),
        )
        assertFalse(out.isError)
        val converted = Regex("= ([0-9.]+)").find(out.forModel)?.groupValues?.get(1)?.toDoubleOrNull()
        assertNotNull(converted)
        assertEquals(0.621371, converted!!, 0.0001)
    }

    @Test fun samplePluginUnitConvertReturnsErrorForUnknownUnit() = runBlocking {
        val out = sampleTool("unit_convert").run(
            buildJsonObject { put("value", 1.0); put("from", "km"); put("to", "parsecs") },
            testContext(),
        )
        assertTrue(out.isError)
        assertTrue(out.forModel.contains("parsecs"))
    }

    @Test fun samplePluginUnitConvertCelsiusToFahrenheit() = runBlocking {
        val out = sampleTool("unit_convert").run(
            buildJsonObject { put("value", 0.0); put("from", "C"); put("to", "F") },
            testContext(),
        )
        assertFalse(out.isError)
        assertTrue(out.forModel.contains("32"))
    }
}
