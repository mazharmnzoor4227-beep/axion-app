package com.mazharansari.axion

import com.mazharansari.axion.agent.ApprovalGate
import com.mazharansari.axion.agent.ToolContext
import com.mazharansari.axion.agent.Workspace
import com.mazharansari.axion.mcp.McpAutoDisable
import com.mazharansari.axion.mcp.McpCatalogue
import com.mazharansari.axion.mcp.McpGate
import com.mazharansari.axion.mcp.McpRpc
import com.mazharansari.axion.mcp.McpServer
import com.mazharansari.axion.mcp.McpTool
import com.mazharansari.axion.mcp.StdioRpc
import com.mazharansari.axion.skills.SkillRepo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * P1-2 (§6.3): MCP stdio transport + catalogue — pure-logic tests. No network,
 * no Linux, no Android. The fake token below is a random string, not a real key.
 */
class P1McpTest {

    private val fakeToken = "ghp_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8"

    // ── stdio framing ───────────────────────────────────────────────────────────────────────────────────────────

    @Test fun stdioEncodeRoundTrip() {
        val line = StdioRpc.encode(7, "tools/list", buildJsonObject { put("cursor", "x") })
        val o = Json.parseToJsonElement(line).jsonObject
        assertEquals("2.0", o["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(7, o["id"]?.jsonPrimitive?.content?.toInt())
        assertEquals("tools/list", o["method"]?.jsonPrimitive?.content)
        assertEquals("x", o["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content)
    }

    @Test fun stdioFindResponseSkipsNoise() {
        val lines = listOf(
            "server starting up",                                  // stderr-style log line
            """{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}""",  // another request's reply
            """{"jsonrpc":"2.0","method":"notifications/message"}""", // no id at all
            """{"jsonrpc":"2.0","id":7,"result":{"tools":[{"name":"read"}]}}""",
            "not json at all",
        )
        val res = StdioRpc.findResponse(lines, 7)
        assertNotNull(res)
        assertEquals("read", res!!["result"]?.jsonObject?.get("tools")?.jsonArray?.get(0)?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertNull(StdioRpc.findResponse(lines, 99))
        assertNull(StdioRpc.findResponse(listOf("garbage", "{bad json"), 7))
    }

    @Test fun stdioFindResponseReturnsErrorsToo() {
        val err = StdioRpc.findResponse(listOf("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"no such method"}}"""), 1)
        assertNotNull(err)
        assertEquals("no such method", err!!["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content)
    }

    @Test fun stdioWaitForResponsePollsThenTimesOut() {
        var calls = 0
        val got = StdioRpc.waitForResponse(read = {
            calls++
            if (calls >= 3) listOf("""{"jsonrpc":"2.0","id":5,"result":{"ok":true}}""") else emptyList()
        }, id = 5, timeoutMs = 5_000, sleepMs = 1)
        assertNotNull(got)
        assertTrue(calls >= 3)
        val missed = StdioRpc.waitForResponse(read = { emptyList() }, id = 5, timeoutMs = 50, sleepMs = 5)
        assertNull("no reply within the timeout must be null", missed)
    }

    // ── MCP_WRITE gate ────────────────────────────────────────────────────────────────────────────────────────

    @Test fun mcpWriteClassification() {
        for (write in listOf(
            "mcp_gmail_send_message", "mcp_filesystem_delete_file", "mcp_github_create_issue",
            "mcp_notion_update_page", "mcp_x_pay_invoice", "mcp_x_write_file", "mcp_x_move_item",
            "mcp_x_publish_post", "mcp_x_save_draft", "mcp_x_trash_note",
        )) assertTrue("$write must be MCP_WRITE", McpGate.isWrite(write))
        for (read in listOf(
            "mcp_gmail_list_messages", "mcp_gmail_get_message", "mcp_filesystem_read_file",
            "mcp_github_get_repo", "mcp_x_search_code", "mcp_x_list_items", "mcp_resources",
            "mcp_read_resource", "mcp_prompts", "mcp_get_prompt",
        )) assertFalse("$read must run free", McpGate.isWrite(read))
    }

    @Test fun approvalGateRoutesMcpTools() {
        val write = ApprovalGate.requestFor("mcp_gmail_send_message", buildJsonObject {})
        assertNotNull(write)
        assertFalse("MCP_WRITE asks but can be remembered per tool", write!!.hard)
        assertEquals("mcp_gmail_send_message", write.pattern)
        assertTrue(write.summary.isNotBlank())
        assertNull("read-only MCP calls run free", ApprovalGate.requestFor("mcp_gmail_list_messages", buildJsonObject {}))
        assertNull("resource reads run free", ApprovalGate.requestFor("mcp_read_resource", buildJsonObject { put("uri", "x") }))
        assertNull("shell tools are unaffected", ApprovalGate.requestFor("run_command", buildJsonObject { put("command", "ls") }))
    }

    // ── catalogue ───────────────────────────────────────────────────────────────────────────────────────────

    @Test fun cataloguePresets() {
        val presets = McpCatalogue.presets
        assertEquals(3, presets.size)
        assertEquals(presets.map { it.id }.distinct().size, presets.size)
        val gmail = McpCatalogue.byId("gmail")!!
        assertEquals("remote", gmail.transport)
        assertTrue("Gmail uses OAuth, never a password", gmail.needsOAuth && gmail.note.contains("never asks for your email password"))
        assertTrue(gmail.capabilities.any { it.contains("Send email") })
        val fs = McpCatalogue.byId("filesystem")!!
        assertEquals("stdio", fs.transport)
        assertTrue("Filesystem is sandboxed to /workspace", fs.command.contains("/workspace"))
        assertFalse(fs.command.contains(".."))
        assertTrue(fs.capabilities.any { it.contains("Read files") })
        val gh = McpCatalogue.byId("github")!!
        assertEquals("stdio", gh.transport)
        assertTrue(gh.needsGitHubToken)
        assertTrue(gh.command.contains("server-github"))
        assertFalse("no token is ever written into the GitHub preset command", gh.command.contains("ghp_") || gh.command.contains("token"))
        for (p in presets) assertTrue("${p.id} shows what it can do before it is enabled", p.capabilities.isNotEmpty() && p.note.isNotBlank())
    }

    // ── auto-disable ────────────────────────────────────────────────────────────────────────────────────────

    @Test fun autoDisableAfterThreeFailures() {
        assertEquals(1, McpAutoDisable.nextFailures(0, false))
        assertEquals(3, McpAutoDisable.nextFailures(2, false))
        assertFalse(McpAutoDisable.shouldDisable(2))
        assertTrue(McpAutoDisable.shouldDisable(3))
        assertTrue(McpAutoDisable.shouldDisable(4))
        assertEquals("one success resets the streak", 0, McpAutoDisable.nextFailures(2, true))
        assertEquals(3, McpAutoDisable.MAX_FAILURES)
    }

    // ── output scrubbing ────────────────────────────────────────────────────────────────────────────────────

    private class FakeRpc(val reply: String) : McpRpc {
        override fun listTools(): List<JsonObject> = emptyList()
        override fun call(name: String, args: JsonObject) = reply to false
        override fun listResources(): List<JsonObject> = emptyList()
        override fun readResource(uri: String) = ""
        override fun listPrompts(): List<JsonObject> = emptyList()
        override fun getPrompt(name: String, args: JsonObject) = ""
    }

    @Test fun mcpToolOutputIsScrubbed() = runBlocking {
        val dir = File.createTempFile("p1-mcp", "").apply { delete(); mkdirs() }
        try {
            val ctx = ToolContext(Workspace(File(dir, "ws")), SkillRepo(null, File(dir, "skills")))
            val server = McpServer("s1", "Test Server", "")
            val tool = McpTool(server, FakeRpc("here is the key: $fakeToken — done"), "send_message", "Send a message", null)
            assertEquals("mcp_Test_Server_send_message", tool.spec.name)
            val out = tool.run(buildJsonObject {}, ctx)
            assertFalse("the token must not reach the model", out.forModel.contains(fakeToken))
            assertFalse("the token must not reach the chat", out.display.contains(fakeToken))
            assertTrue("the masked form stays so the user knows which key it was", out.forModel.contains("••••"))
            assertFalse(out.isError)
        } finally {
            dir.deleteRecursively()
        }
    }
}
