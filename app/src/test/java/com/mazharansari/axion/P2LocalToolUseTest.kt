package com.mazharansari.axion

import com.mazharansari.axion.agent.*
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.UserItem
import com.mazharansari.axion.local.*
import com.mazharansari.axion.skills.SkillRepo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P2-4: on-device model tool-use — JSON extraction, prompt shape, the tool loop,
 * the approval gate, and measured tok/s. Pure JVM (fake generator + fake tools),
 * no model, no network, no Android framework.
 */
class P2LocalToolUseTest {

    private fun ctx(): ToolContext {
        val dir = Files.createTempDirectory("axion-local-test").toFile()
        return ToolContext(Workspace(File(dir, "ws")), SkillRepo(null, File(dir, "skills")))
    }

    private class FakeReadTool(var calls: Int = 0) : Tool {
        override val spec = ToolSpec("read_file", "Read a file in the workspace.", schema { str("path", "Path to read") })
        override fun label(args: JsonObject) = StepLabel(StepKind.READ, "Read", args.s("path").orEmpty())
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
            calls++
            return ToolOutput("contents of ${args.s("path")}")
        }
    }

    private class FakeRunTool(var calls: Int = 0) : Tool {
        override val spec = ToolSpec("run_command", "Run a shell command.", schema { str("command", "Command") })
        override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "Run", args.s("command").orEmpty())
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
            calls++
            return ToolOutput("ran: ${args.s("command")}")
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────────────

    @Test fun parsesPlainJsonToolCall() {
        val p = LocalToolUse.parse("""{"tool": "read_file", "arguments": {"path": "notes.txt"}}""", LocalToolUse.ALLOWED_TOOLS)
        assertTrue(p is ParsedCall.Call)
        val c = p as ParsedCall.Call
        assertEquals("read_file", c.tool)
        assertEquals("notes.txt", c.args.s("path"))
    }

    @Test fun parsesFencedJsonAndFinalAnswers() {
        val fenced = LocalToolUse.parse("```json\n{\"tool\": \"list_files\", \"arguments\": {\"path\": \".\"}}\n```", LocalToolUse.ALLOWED_TOOLS)
        assertTrue(fenced is ParsedCall.Call)
        val fin = LocalToolUse.parse("{\"answer\": \"Done — the file says hello.\"}", LocalToolUse.ALLOWED_TOOLS)
        assertTrue(fin is ParsedCall.Final)
        assertEquals("Done — the file says hello.", (fin as ParsedCall.Final).text)
    }

    @Test fun parsesJsonEmbeddedInProse() {
        val p = LocalToolUse.parse("I will read the file now: {\"tool\": \"read_file\", \"arguments\": {\"path\": \"a.txt\"}}", LocalToolUse.ALLOWED_TOOLS)
        assertTrue(p is ParsedCall.Call)
    }

    @Test fun rejectsUnknownToolsBadJsonAndMissingArguments() {
        assertTrue(LocalToolUse.parse("""{"tool": "delete_everything", "arguments": {}}""", LocalToolUse.ALLOWED_TOOLS) is ParsedCall.Invalid)
        assertTrue(LocalToolUse.parse("no json here at all", LocalToolUse.ALLOWED_TOOLS) is ParsedCall.Invalid)
        assertTrue(LocalToolUse.parse("""{"tool": "read_file"}""", LocalToolUse.ALLOWED_TOOLS) is ParsedCall.Invalid)
        assertTrue(LocalToolUse.parse("""{"tool": "read_file", "arguments": "notes.txt"}""", LocalToolUse.ALLOWED_TOOLS) is ParsedCall.Invalid)
        assertTrue(LocalToolUse.parse("""{"tool": "read_file", "arguments": {"path": "x"}}""", setOf("list_files")) is ParsedCall.Invalid)
    }

    @Test fun extractJsonBalancesBracesInsideStrings() {
        val text = """{"tool": "write_file", "arguments": {"path": "a.txt", "content": "a } brace and { another"}}"""
        val p = LocalToolUse.parse(text, LocalToolUse.ALLOWED_TOOLS)
        assertTrue(p is ParsedCall.Call)
        assertEquals("a } brace and { another", (p as ParsedCall.Call).args["content"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull })
    }

    // ── Prompt ───────────────────────────────────────────────────────────────────

    @Test fun promptListsToolsAndBothFormats() {
        val specs = listOf(
            ToolSpec("read_file", "Read a file.", buildJsonObject { put("type", "object") }),
            ToolSpec("run_command", "Run a command.", buildJsonObject { put("type", "object") }),
        )
        val prompt = LocalToolUse.buildToolPrompt(specs)
        assertTrue(prompt.contains("read_file"))
        assertTrue(prompt.contains("run_command"))
        assertTrue(prompt.contains("\"tool\""))
        assertTrue(prompt.contains("\"answer\""))
        assertTrue("prompt must stay short for a 4k model: ${prompt.length}", prompt.length < 4000)
    }

    // ── The loop ─────────────────────────────────────────────────────────────────

    @Test fun loopRunsTheToolThenReturnsTheFinalAnswer() = runBlocking {
        val read = FakeReadTool()
        var n = 0
        val runner = LocalAgentRunner(
            generate = {
                n++
                if (n == 1) LocalGeneration("""{"tool": "read_file", "arguments": {"path": "notes.txt"}}""", 400, 100)
                else LocalGeneration("""{"answer": "The file says hello."}""", 200, 50)
            },
            tools = listOf(read), ctx = ctx(),
        )
        val r = runner.run(emptyList(), UserItem("What does notes.txt say?"))
        assertEquals("The file says hello.", r.answer)
        assertEquals(1, read.calls)
        assertEquals(1, r.steps.size)
        assertEquals("read_file", r.steps.single().tool)
        assertTrue(r.steps.single().ok)
        assertEquals(150, r.totalTokens)
        assertEquals(600L, r.totalMs)
        assertTrue(r.speed.tokPerSec > 0)
    }

    @Test fun invalidJsonGetsOneRetryThenAnHonestAnswer() = runBlocking {
        val read = FakeReadTool()
        var n = 0
        val runner = LocalAgentRunner(
            generate = {
                n++
                if (n == 1) LocalGeneration("not json at all", 100, 10)
                else LocalGeneration("""{"answer": "No tool needed."}""", 100, 10)
            },
            tools = listOf(read), ctx = ctx(),
        )
        val r = runner.run(emptyList(), UserItem("hi"))
        assertEquals("No tool needed.", r.answer)
        assertEquals(0, read.calls)
        assertEquals(2, r.generations.size)
    }

    @Test fun gatedShellWorkIsRefusedNotRun() = runBlocking {
        val run = FakeRunTool()
        val runner = LocalAgentRunner(
            generate = { LocalGeneration("""{"tool": "run_command", "arguments": {"command": "rm -rf /"}}""", 100, 10) },
            tools = listOf(run), ctx = ctx(), approvals = null,
        )
        val r = runner.run(emptyList(), UserItem("delete everything"))
        assertEquals(0, run.calls)   // rm -rf is hard-gated: never runs without the user
        assertTrue(r.answer.contains("approval"))
        assertEquals(1, r.steps.size)
        assertFalse(r.steps.single().ok)
    }

    @Test fun speedIsMeasuredNotInvented() {
        val g = LocalGeneration("x".repeat(400), 1000, LocalToolUse.approxTokens(400))
        assertEquals(100, g.approxTokens)
        assertEquals(100.0, g.tokPerSec, 0.001)
        assertEquals("Local model · 100.0 tok/s · 100 tokens", LocalSpeed(100, 1000).label())
    }
}
