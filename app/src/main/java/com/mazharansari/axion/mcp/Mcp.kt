package com.mazharansari.axion.mcp

import android.content.Context
import com.mazharansari.axion.agent.*
import com.mazharansari.axion.data.KeyVault
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.Http
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class McpServer(val id: String, val name: String, val url: String, val tokenBlob: String = "", val enabled: Boolean = true)

/** Remote MCP over Streamable HTTP (JSON or SSE answers, Mcp-Session-Id), the same thing Grok's "Custom connector" takes. */
class McpClient(private val server: McpServer) {
    private var session: String? = null
    private val ids = AtomicInteger(1)
    private var initialized = false

    private fun post(payload: JsonObject, expectReply: Boolean = true): JsonObject? {
        val rb = Request.Builder().url(server.url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-06-18")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        KeyVault.decrypt(server.tokenBlob)?.takeIf { it.isNotBlank() }?.let { rb.header("Authorization", "Bearer $it") }
        session?.let { rb.header("Mcp-Session-Id", it) }
        Http.quick.newCall(rb.build()).execute().use { r ->
            r.header("Mcp-Session-Id")?.let { session = it }
            if (!expectReply) return null
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("MCP HTTP ${r.code}: ${text.take(200)}")
            val jsonText = if (r.header("content-type").orEmpty().contains("event-stream"))
                text.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
                    .lastOrNull { it.contains("\"id\"") } ?: error("MCP: empty event stream")
            else text
            val o = Json.parseToJsonElement(jsonText).jsonObject
            o["error"]?.let { error("MCP error: ${it.jsonObject["message"]?.jsonPrimitive?.content ?: it}") }
            return o["result"]?.jsonObject ?: JsonObject(emptyMap())
        }
    }

    private fun rpc(method: String, params: JsonObject = JsonObject(emptyMap())) = post(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", ids.getAndIncrement()); put("method", method); put("params", params)
    })!!

    @Synchronized private fun ensureInit() {
        if (initialized) return
        rpc("initialize", buildJsonObject {
            put("protocolVersion", "2025-06-18")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "Axion"); put("version", "0.1") }
        })
        runCatching { post(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }, expectReply = false) }
        initialized = true
    }

    fun listTools(): List<JsonObject> { ensureInit(); return rpc("tools/list")["tools"]?.jsonArray?.map { it.jsonObject }.orEmpty() }

    fun call(name: String, args: JsonObject): Pair<String, Boolean> {
        ensureInit()
        val res = rpc("tools/call", buildJsonObject { put("name", name); put("arguments", args) })
        val text = res["content"]?.jsonArray?.joinToString("\n") { c ->
            val o = c.jsonObject
            when (o["type"]?.jsonPrimitive?.content) {
                "text" -> o["text"]?.jsonPrimitive?.content.orEmpty()
                "image" -> "[image ${o["mimeType"]?.jsonPrimitive?.content}]"
                "resource" -> o["resource"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "[resource]"
                else -> o.toString()
            }
        } ?: res.toString()
        return text to (res["isError"]?.jsonPrimitive?.booleanOrNull == true)
    }
}

class McpRepo(private val dir: File) {
    private val file = File(dir, "mcp.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _servers = MutableStateFlow(runCatching { json.decodeFromString(ListSerializer(McpServer.serializer()), file.readText()) }.getOrDefault(emptyList()))
    val servers: StateFlow<List<McpServer>> = _servers
    private val clients = HashMap<String, McpClient>()
    private var cache: Pair<Long, List<Tool>>? = null

    private fun save() { file.writeText(json.encodeToString(ListSerializer(McpServer.serializer()), _servers.value)); cache = null }

    /** Adds the server after a successful tools/list; returns the tool count. */
    suspend fun add(name: String, url: String, token: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("https://") || url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) { "Server URL must start with https://" }
            val s = McpServer(java.util.UUID.randomUUID().toString(), name.ifBlank { url.substringAfter("//").substringBefore('/') }, url.trim(),
                if (token.isBlank()) "" else KeyVault.encrypt(token.trim()))
            val n = McpClient(s).listTools().size
            _servers.value = _servers.value + s; save(); n
        }
    }

    fun remove(id: String) { _servers.value = _servers.value.filterNot { it.id == id }; clients.remove(id); save() }

    suspend fun tools(): List<Tool> = withContext(Dispatchers.IO) {
        cache?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return@withContext it.second }
        val out = mutableListOf<Tool>()
        for (s in _servers.value.filter { it.enabled }) {
            val client = clients.getOrPut(s.id) { McpClient(s) }
            runCatching { client.listTools() }.getOrNull()?.forEach { t ->
                val toolName = t["name"]?.jsonPrimitive?.content ?: return@forEach
                out += McpTool(s, client, toolName, t["description"]?.jsonPrimitive?.content.orEmpty(), t["inputSchema"] as? JsonObject)
            }
        }
        cache = System.currentTimeMillis() to out; out
    }
}

class McpTool(private val server: McpServer, private val client: McpClient, private val toolName: String, desc: String, input: JsonObject?) : Tool {
    override val spec = ToolSpec(
        ("mcp_" + server.name + "_" + toolName).replace(Regex("[^A-Za-z0-9_]"), "_").take(64),
        "[${server.name}] $desc".take(1000),
        cleanSchema(input ?: buildJsonObject { put("type", "object"); putJsonObject("properties") {} }),
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.MCP, server.name, toolName)
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        runCatching { client.call(toolName, args) }.fold(
            { (t, err) -> ToolOutput(t.clip(15000), display = t.clip(2000), isError = err) },
            { ToolOutput("MCP call failed: ${it.message}", isError = true) },
        )
    }
}

/** Keeps only the JSON-schema keys every provider (incl. Gemini) accepts. */
fun cleanSchema(e: JsonElement): JsonObject {
    val allowed = setOf("type", "description", "properties", "required", "items", "enum", "format", "nullable", "minimum", "maximum", "anyOf")
    fun clean(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.filterKeys { it in allowed }.mapValues { (k, v) ->
            when (k) {
                "properties" -> JsonObject((v as? JsonObject).orEmpty().mapValues { clean(it.value) })
                "type" -> if (v is JsonArray) v.firstOrNull { it.jsonPrimitive.content != "null" } ?: JsonPrimitive("string") else v
                else -> clean(v)
            }
        })
        is JsonArray -> JsonArray(el.map { clean(it) })
        else -> el
    }
    val o = clean(e) as? JsonObject ?: JsonObject(emptyMap())
    return if (o["type"] == null) JsonObject(o + ("type" to JsonPrimitive("object"))) else o
}
