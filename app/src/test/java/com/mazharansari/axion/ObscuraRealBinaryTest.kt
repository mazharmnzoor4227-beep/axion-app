package com.mazharansari.axion

import com.mazharansari.axion.mcp.StdioRpc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * 0.9.6: the one test that talks to a real Obscura binary — spawns `obscura mcp`,
 * runs initialize + tools/list through the existing StdioRpc helpers, and asserts
 * the browser tools are there. It SKIPS (never fails) when no binary is on PATH:
 * CI runners and this sandbox do not have one, and the sandbox cannot run the
 * aarch64 binary anyway. Set OBSCURA_BIN to point at a binary to run it.
 */
class ObscuraRealBinaryTest {

    /** OBSCURA_BIN wins; otherwise the first `obscura` on PATH. Null when there is no binary. */
    private fun findObscuraBinary(): File? {
        System.getenv("OBSCURA_BIN")?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (file.canExecute()) return file
        }
        for (dir in System.getenv("PATH").orEmpty().split(File.pathSeparator)) {
            val file = File(dir, "obscura")
            if (file.canExecute()) return file
        }
        return null
    }

    @Test(timeout = 90_000)
    fun obscuraMcpListsBrowserTools() {
        val bin = findObscuraBinary()
        assumeTrue("No obscura binary on PATH (set OBSCURA_BIN to run this test)", bin != null)
        val proc = ProcessBuilder(bin!!.absolutePath, "mcp")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val lines = Collections.synchronizedList(ArrayList<String>())
        val pump = Thread {
            runCatching {
                proc.inputStream.bufferedReader().useLines { seq -> seq.forEach { lines += it } }
            }
        }.apply { isDaemon = true; start() }
        try {
            val out = proc.outputStream.bufferedWriter()
            fun sendRaw(json: String) {
                out.write(json); out.newLine(); out.flush()
            }
            fun send(id: Int, method: String, params: JsonObject) = sendRaw(StdioRpc.encode(id, method, params))
            send(1, "initialize", buildJsonObject {
                put("protocolVersion", "2025-06-18")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "Axion"); put("version", "0.1") }
            })
            val init = StdioRpc.waitForResponse(read = { lines.toList() }, id = 1, timeoutMs = 30_000)
            assertNotNull("obscura mcp did not answer initialize", init)
            sendRaw("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            send(2, "tools/list", JsonObject(emptyMap()))
            val listed = StdioRpc.waitForResponse(read = { lines.toList() }, id = 2, timeoutMs = 30_000)
            assertNotNull("obscura mcp did not answer tools/list", listed)
            val names = listed!!["result"]?.jsonObject?.get("tools")?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.orEmpty()
            assertTrue("browser_navigate missing from: $names", "browser_navigate" in names)
            assertTrue("browser_screenshot missing from: $names", "browser_screenshot" in names)
        } finally {
            proc.destroyForcibly()
            pump.join(2_000)
        }
    }
}
