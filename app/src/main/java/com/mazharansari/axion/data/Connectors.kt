package com.mazharansari.axion.data

import android.content.Context
import com.mazharansari.axion.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID

@Serializable
data class Connector(
    val id: String,
    val providerId: String,
    val keyBlob: String,
    val keyHint: String,
    val models: List<String>,
    val fastModels: List<String>,
    val pinnedModel: String? = null,
    val enabled: Boolean = true,
    val addedAt: Long = 0,
    val lastError: String = "",
    /** v0.5 "My server": the OpenAI-compatible base URL (…/v1). "Local model": unused. */
    val baseUrl: String? = null,
    /** v0.5 "Local model": which downloaded GGUF (LocalModel id). */
    val localModel: String? = null,
    /**
     * P1-10 (ME-3): shared-quota group, set by the user on the Quota screen. Gemini keys from the same Google
     * project share ONE quota, so keys with the same non-blank group are counted once in the totals. Blank = this
     * key is its own group. Axion cannot learn the project from the key itself (Google exposes no such lookup for
     * an API key), so the grouping is explicit and user-set, never guessed.
     */
    val quotaGroup: String = "",
) {
    val provider: Provider get() = Providers.byId(providerId)!!
    val own: Boolean get() = providerId == "local" || providerId == "myserver"
    fun key(): String = KeyVault.decrypt(keyBlob).orEmpty()
}

sealed interface DetectResult {
    data class Ok(val provider: Provider, val models: List<ModelRanker.ModelInfo>) : DetectResult
    data class Fail(val message: String) : DetectResult
}

class ConnectorRepo(private val ctx: Context) {
    private val file = File(ctx.filesDir, "connectors.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val _list = MutableStateFlow<List<Connector>>(emptyList())
    val list: StateFlow<List<Connector>> = _list.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** In-memory cool-downs: "connectorId" or "connectorId|model" → epoch ms when usable again. */
    private val cooldown = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        val loaded = runCatching { json.decodeFromString<List<Connector>>(file.readText()) }.getOrDefault(emptyList())
            .filter { it.providerId !in Providers.retired && Providers.byId(it.providerId) != null }
        // v0.5.2 security migration: old hints showed the first 4 characters too ("sk-a…wxyz") → last 4 only; a key saved
        // by the pre-0.5.2 plain-text fallback is re-encrypted with the Keystore.
        val migrated = loaded.map { c ->
            val blob = KeyVault.upgrade(c.keyBlob)
            val hint = if (c.providerId != "local" && !(c.providerId == "myserver" && !c.keyHint.contains('…'))) KeyHint.migrate(c.keyHint) else c.keyHint
            if (blob != c.keyBlob || hint != c.keyHint) c.copy(keyBlob = blob, keyHint = hint) else c
        }
        _list.value = migrated
        if (migrated != loaded) io.launch { synchronized(file) { file.writeText(json.encodeToString(migrated)) } }
    }

    private fun save(l: List<Connector>) {
        _list.value = l
        io.launch { synchronized(file) { file.writeText(json.encodeToString(l)) } }
    }

    suspend fun addKey(rawKey: String, forceProviderId: String? = null): Result<Connector> = withContext(Dispatchers.IO) {
        val key = rawKey.trim().removeSurrounding("\"").trim()
        if (key.length < 16) return@withContext Result.failure(IllegalArgumentException("That doesn't look like an API key."))
        when (val d = KeyDetector.detect(key, forceProviderId?.let { Providers.byId(it) })) {
            is DetectResult.Fail -> Result.failure(IllegalStateException(d.message))
            is DetectResult.Ok -> {
                val c = Connector(
                    id = UUID.randomUUID().toString(),
                    providerId = d.provider.id,
                    keyBlob = KeyVault.encrypt(key),
                    keyHint = KeyHint.of(key),
                    models = ModelRanker.rank(d.provider, d.models, Mode.EXPERT).take(12),
                    fastModels = ModelRanker.rank(d.provider, d.models, Mode.FAST).take(12),
                    addedAt = System.currentTimeMillis(),
                )
                save(_list.value + c)
                Result.success(c)
            }
        }
    }

    fun remove(id: String) { _list.value.firstOrNull { it.id == id }?.let { KeyVault.forget(it.keyBlob) }; save(_list.value.filterNot { it.id == id }) }

    /** v0.5: one "Local model" connector, pointing at the chosen downloaded model. */
    fun setLocal(modelId: String?, title: String = "") {
        val rest = _list.value.filterNot { it.providerId == "local" }
        if (modelId == null) { save(rest); return }
        save(rest + Connector(UUID.randomUUID().toString(), "local", KeyVault.encrypt("local"), title.ifBlank { modelId }, listOf(modelId), listOf(modelId),
            addedAt = System.currentTimeMillis(), localModel = modelId))
    }

    /** v0.5 "My server": OpenAI-compatible URL + optional key; [models] from the server's /models. */
    fun setServer(url: String, key: String, models: List<String>, pinned: String?) {
        val rest = _list.value.filterNot { it.providerId == "myserver" }
        val k = key.trim()
        save(rest + Connector(UUID.randomUUID().toString(), "myserver", KeyVault.encrypt(k), if (k.isBlank()) MyServer.host(url) else KeyHint.of(k),
            models.ifEmpty { listOfNotNull(pinned) }, models.ifEmpty { listOfNotNull(pinned) }, pinned, addedAt = System.currentTimeMillis(), baseUrl = MyServer.normalize(url)))
    }
    fun local(): Connector? = _list.value.firstOrNull { it.providerId == "local" }
    fun server(): Connector? = _list.value.firstOrNull { it.providerId == "myserver" }

    fun exportEntries(): List<KeyBackup.Entry> = _list.value.map { KeyBackup.Entry(it.providerId, it.key(), it.models, it.fastModels, it.pinnedModel, it.enabled) }

    /** Adds the keys from a backup (no network: the saved model lists come with them). Returns how many were new. */
    fun importEntries(entries: List<KeyBackup.Entry>): Int {
        val have = _list.value.map { it.key() }.toSet()
        val fresh = entries.filter { it.key !in have && Providers.byId(it.providerId) != null && it.providerId !in Providers.retired }.map { e ->
            Connector(UUID.randomUUID().toString(), e.providerId, KeyVault.encrypt(e.key), KeyHint.of(e.key), e.models, e.fastModels, e.pinnedModel, e.enabled, System.currentTimeMillis())
        }
        if (fresh.isNotEmpty()) save(_list.value + fresh)
        return fresh.size
    }
    fun setEnabled(id: String, on: Boolean) = save(_list.value.map { if (it.id == id) it.copy(enabled = on) else it })
    /** P1-10 (ME-3): name the shared-quota group this key belongs to (blank = its own quota). */
    fun setQuotaGroup(id: String, group: String) = save(_list.value.map { if (it.id == id) it.copy(quotaGroup = group.trim().take(40)) else it })
    fun pinModel(id: String, model: String?) = save(_list.value.map { if (it.id == id) it.copy(pinnedModel = model) else it })
    fun move(id: String, up: Boolean) {
        val l = _list.value.toMutableList(); val i = l.indexOfFirst { it.id == id }; val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in l.indices) return
        java.util.Collections.swap(l, i, j); save(l)
    }
    fun noteError(id: String, msg: String) = save(_list.value.map { if (it.id == id) it.copy(lastError = com.mazharansari.axion.security.SecretScanner.scrub(msg).take(200)) else it })

    /** Remembers cool-downs across restarts (QuotaRepo's usage.db) so a key known dead until noon stays skipped after a relaunch. */
    @Volatile var persistCooldown: ((String, Long) -> Unit)? = null
    /** Requests left today per connector, when the Quota system knows (keys known to be at 0 go last). */
    @Volatile var leftToday: ((Connector) -> Double?)? = null
    /** Free mode (Developer tools): only free-tier providers are used. */
    @Volatile var freeOnly: () -> Boolean = { false }

    fun restoreCooldowns(m: Map<String, Long>) { cooldown.putAll(m) }
    fun coolingUntil(c: Connector, model: String): Long = maxOf(cooldown[c.id] ?: 0L, cooldown["${c.id}|$model"] ?: 0L)
    private fun cool(k: String, until: Long) { cooldown[k] = until; if (until - System.currentTimeMillis() > 60_000) persistCooldown?.invoke(k, until) }

    /** Ordered (connector, model) attempts for one request: every usable model on a key before the next key (v0.4). */
    fun attempts(mode: Mode): List<Pair<Connector, String>> {
        val list = _list.value.filter { it.enabled && !it.own && it.providerId !in Providers.retired && (!freeOnly() || it.provider.free) }
        return order(list, mode, System.currentTimeMillis(), cooldown, leftToday)
    }

    fun coolDown(c: Connector, model: String, e: LlmException) {
        val now = System.currentTimeMillis()
        val msg = e.message.orEmpty().lowercase()
        when (e.kind) {
            FailKind.QUOTA -> {
                val ms = e.retryAfterMs.coerceIn(30_000, 24 * 3600_000L).let { if (e.retryAfterMs <= 0) 3600_000L else it }
                // v0.4: a limit on one model cools THAT model; the other models on the key are tried next. Only account-wide
                // limits (OpenRouter's free-models-per-day, a billing/credit stop) cool the whole key.
                val accountWide = "free-models-per-day" in msg || "per-day" in msg && c.providerId == "openrouter" || e.code == 402 ||
                    "billing" in msg || "credit" in msg || "balance" in msg || "suspended" in msg || "restricted" in msg
                if (accountWide) cool(c.id, now + ms) else cool("${c.id}|$model", now + ms)
            }
            FailKind.RATE -> cooldown["${c.id}|$model"] = now + (if (e.retryAfterMs > 0) e.retryAfterMs + 500 else 20_000L).coerceIn(3_000, 120_000)   // seconds, never the day
            FailKind.AUTH -> { cool(c.id, now + 3600_000L); noteError(c.id, e.message ?: "auth") }
            FailKind.MODEL_UNAVAILABLE, FailKind.BAD_REQUEST -> cool("${c.id}|$model", now + 3600_000L)
            FailKind.SERVER, FailKind.NETWORK -> cooldown["${c.id}|$model"] = now + 20_000L
        }
    }

    companion object {
        /**
         * Pure, unit-tested rotation order. Fast prefers the fastest providers. Within a key every model that isn't cooling down is
         * tried (up to 12) before the next key; keys known to have 0 requests left today go after the ones that still have some.
         */
        fun order(list: List<Connector>, mode: Mode, now: Long, cooldown: Map<String, Long>, left: ((Connector) -> Double?)?): List<Pair<Connector, String>> {
            val fastOrder = listOf("groq", "cerebras", "sambanova", "gemini")
            val active = list.filter { (cooldown[it.id] ?: 0) < now }
            val byMode = if (mode == Mode.FAST) active.sortedBy { c -> fastOrder.indexOf(c.providerId).let { if (it < 0) 99 else it } } else active
            val ordered = if (left == null) byMode else byMode.withIndex().sortedWith(compareBy({ if (runCatching { left(it.value) }.getOrNull() == 0.0) 1 else 0 }, { it.index })).map { it.value }
            val out = mutableListOf<Pair<Connector, String>>()
            for (c in ordered) {
                buildList {
                    c.pinnedModel?.let { add(it) }
                    addAll(if (mode == Mode.FAST) c.fastModels else c.models)
                    addAll(if (mode == Mode.FAST) c.models else c.fastModels)
                }.distinct().filter { (cooldown["${c.id}|$it"] ?: 0) < now }.take(12).forEach { out += c to it }
            }
            // Everything cooling down: still try the first model of each key rather than giving up.
            if (out.isEmpty()) list.forEach { c -> (c.pinnedModel ?: c.models.firstOrNull())?.let { out += c to it } }
            return out
        }
    }

    fun clientFor(c: Connector): LlmClient = when (c.provider.api) {
        ApiKind.GEMINI -> GeminiClient(c.key(), tag = c.id)
        ApiKind.OPENAI -> OpenAiClient(if (c.providerId == "myserver") MyServer.provider(c.baseUrl.orEmpty()) else c.provider, c.key(), tag = c.id)
        ApiKind.LOCAL -> {
            val m = com.mazharansari.axion.local.LocalModel.byId(c.localModel) ?: throw LlmException("Pick a local model in Connectors → Local model.", FailKind.MODEL_UNAVAILABLE)
            com.mazharansari.axion.local.LocalLlmClient(java.io.File(java.io.File(ctx.filesDir, "models"), m.url.substringAfterLast('/')), m.ctx)
        }
    }

    fun geminiKey(): String? = _list.value.firstOrNull { it.enabled && it.providerId == "gemini" }?.key()
}

object KeyDetector {
    private val JSONT = "application/json".toMediaType()

    /**
     * P0-5 (HI-3): probes providers SEQUENTIALLY in ranked order and stops at the first success — a pasted key is
     * never broadcast to every guessed provider in parallel. Ranked order (see [guessesFor]): a provider the key's
     * own prefix names goes first and alone; otherwise Gemini first, then the format heuristics, then the full
     * [Providers.probeOrder] list. A provider later in the order is only contacted when every earlier one refused.
     * [probeFn] is the real network probe by default; tests pass a fake to assert the order without network.
     */
    suspend fun detect(key: String, force: Provider? = null, probeFn: (Provider, String) -> List<ModelRanker.ModelInfo> = { p, k -> probe(p, k) }): DetectResult {
        // P0-3 (HI-1): Anthropic was removed — its API is not OpenAI-compatible (/v1/messages + x-api-key), so an
        // sk-ant- key is refused here, before any provider is probed, instead of being sent around and failing.
        if (key.trim().startsWith("sk-ant-")) return DetectResult.Fail(UNSUPPORTED_ANTHROPIC)
        val guesses = guessesFor(key, force)
        var firstError = ""
        for (p in guesses) {
            val r = runCatching { probeFn(p, key) }
            if (r.isSuccess) return DetectResult.Ok(p, r.getOrThrow())
            if (firstError.isEmpty()) firstError = r.exceptionOrNull()?.message.orEmpty()
        }
        if (guesses.size == 1) {
            val reason = clean(firstError, key)
            return DetectResult.Fail("${guesses[0].name} didn't accept this key" + if (reason.isNotBlank()) " — $reason" else "")
        }
        return DetectResult.Fail(NOT_RECOGNISED)
    }

    /** The ranked probe order for a key: prefix-confident provider alone, else Gemini + heuristics, else [Providers.probeOrder]. */
    internal fun guessesFor(key: String, force: Provider?): List<Provider> {
        val guessed = Providers.guess(key)
        return if (force != null) listOf(force)
            else if (guessed.size <= 1) (guessed + Providers.probeOrder.mapNotNull { Providers.byId(it) }).distinctBy { it.id }.let { all -> if (guessed.isNotEmpty() && guessed[0].prefixes.any { key.startsWith(it) }) guessed else all }
            else guessed
    }

    const val NOT_RECOGNISED = "Key not recognised — pick the provider below and paste it there."

    /** P0-3 (HI-1): Anthropic is not a supported provider in Axion, so its keys are refused, never probed. */
    const val UNSUPPORTED_ANTHROPIC = "Anthropic is not supported in Axion"

    /** Provider errors sometimes echo the key back ("Incorrect API key provided: AQ.Ab8*****mOSw"); never show any of it. */
    fun clean(msg: String, key: String): String {
        var m = msg
        if (key.length >= 6) for (n in listOf(key, key.take(8), key.take(6), key.takeLast(6), key.takeLast(4))) if (n.length >= 4) m = m.replace(n, "")
        m = m.replace(Regex("\\S*\\*{3,}\\S*"), "").replace(Regex("(?i)(key|token)\\s*(provided)?\\s*:\\s*[^\\s.,]+"), "$1")
        m = m.replace(Regex("https?://\\S+"), "").replace(Regex("\\s+"), " ").trim().trimEnd(':', '.', ',')
        return m.take(90)
    }

    private fun get(url: String, auth: Map<String, String>): Pair<Int, String> {
        val rb = Request.Builder().url(url).header("User-Agent", Http.UA)
        auth.forEach { (k, v) -> rb.header(k, v) }
        Http.quick.newCall(rb.build()).execute().use { return it.code to (it.body?.string().orEmpty()) }
    }

    /** Throws unless the key authenticates; returns the live model list. */
    fun probe(p: Provider, key: String): List<ModelRanker.ModelInfo> {
        val bearer = mapOf("Authorization" to "Bearer $key") + p.extraHeaders
        if (p.api == ApiKind.GEMINI) {
            val (code, body) = get("${p.baseUrl}/models?pageSize=1000", mapOf("x-goog-api-key" to key))
            if (code != 200) error(shortErr(code, body))
            return Json.parseToJsonElement(body).jsonObject["models"]?.jsonArray.orEmpty().mapNotNull { m ->
                val o = m.jsonObject
                val methods = o["supportedGenerationMethods"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                if (methods.isNotEmpty() && "generateContent" !in methods) null
                else ModelRanker.ModelInfo(o["name"]!!.jsonPrimitive.content.removePrefix("models/"))
            }
        }
        // Providers whose model list is public need a separate authenticated check.
        when (p.id) {
            "openrouter" -> get("https://openrouter.ai/api/v1/key", bearer).let { (c, b) -> if (c != 200) error(shortErr(c, b)) }
            "huggingface" -> get("https://huggingface.co/api/whoami-v2", bearer).let { (c, b) -> if (c != 200) error(shortErr(c, b)) }
            "sambanova", "nvidia" -> {
                val body = """{"model":"${p.fallbackModel}","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
                val rb = Request.Builder().url("${p.baseUrl}/chat/completions").post(body.toRequestBody(JSONT))
                bearer.forEach { (k, v) -> rb.header(k, v) }
                Http.quick.newCall(rb.build()).execute().use { r -> if (r.code == 401 || r.code == 403) error(shortErr(r.code, r.body?.string().orEmpty())) }
            }
        }
        val (code, body) = get(p.modelsUrl, bearer)
        if (code != 200) error(shortErr(code, body))
        val el = Json.parseToJsonElement(body)
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["data"] ?: el["models"])?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return arr.mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] ?: o["name"])?.jsonPrimitive?.content ?: return@mapNotNull null
            val params = o["supported_parameters"]?.jsonArray?.map { it.jsonPrimitive.content }
            val caps = o["capabilities"]?.let { c -> if (c is JsonArray) c.map { it.jsonPrimitive.content } else null }
            val tools = when {
                params != null -> "tools" in params
                caps != null -> "tool-calling" in caps
                else -> null
            }
            val free = o["pricing"]?.jsonObject?.get("prompt")?.jsonPrimitive?.content?.let { it == "0" }
            ModelRanker.ModelInfo(id, tools, free)
        }
    }

    private fun shortErr(code: Int, body: String) = shortError(body, code)
}

/** v0.5 "My server" (addendum 6): any OpenAI-compatible endpoint — Ollama (`/v1`), llama.cpp server, vLLM, LM Studio. */
/** v0.5.2: how a saved key is shown — never more than its public prefix and the last 4 ("gsk_ ••••wxyz"). */
object KeyHint {
    private val publicPrefix = Regex("""^(sk-or-v1-|sk-proj-|sk-|AIza|gsk_|ghp_|github_pat_|hf_|xai-|nvapi-|csk-|nfp_|AQ\.)""")
    fun of(key: String): String {
        val k = key.trim()
        if (k.length < 8) return "••••"
        return (publicPrefix.find(k)?.value?.let { "$it " } ?: "") + "••••" + k.takeLast(4)
    }
    /** "abcd…wxyz" (pre-0.5.2) → "••••wxyz" (keeps a known public prefix). */
    fun migrate(hint: String): String {
        if (!hint.contains('…')) return hint
        val first = hint.substringBefore('…'); val last = hint.substringAfter('…')
        return (publicPrefix.find(first)?.value?.let { "$it " } ?: "") + "••••" + last
    }
}

object MyServer {
    /** "1.2.3.4:11434" / "https://x.sslip.io" / "…/v1/" → "http(s)://host[:port]/v1". */
    fun normalize(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        u = u.removeSuffix("/chat/completions").removeSuffix("/models").trimEnd('/')
        if (!Regex("/v\\d+$").containsMatchIn(u)) u = "$u/v1"
        return u
    }
    fun host(url: String) = normalize(url).removePrefix("http://").removePrefix("https://").substringBefore('/')
    fun provider(url: String) = Providers.SERVER.copy(baseUrl = normalize(url), modelsUrl = normalize(url) + "/models")

    data class Test(val ok: Boolean, val message: String, val models: List<String> = emptyList(), val ms: Long = 0)

    /** Lists the server's models, then asks the first (or [model]) to say one word, timing it. */
    /** v0.5.2: cleartext is off app-wide (network security config); only this phone itself may be plain http. */
    fun insecure(url: String): Boolean {
        val n = normalize(url)
        if (!n.startsWith("http://")) return false
        val h = host(url).substringBefore(':').trim('[', ']')
        return h != "127.0.0.1" && h != "localhost"
    }
    const val HTTPS_ONLY = "Axion connects to servers over https only, so your key and chats can't be read on the way. Use the free https address from the setup guide (Caddy + sslip.io), e.g. https://1-2-3-4.sslip.io"

    fun test(url: String, key: String, model: String?): Test {
        val base = normalize(url)
        if (insecure(url)) return Test(false, HTTPS_ONLY)
        val t0 = System.currentTimeMillis()
        val models = try {
            val rb = Request.Builder().url("$base/models").header("User-Agent", Http.UA)
            if (key.isNotBlank()) rb.header("Authorization", "Bearer ${key.trim()}")
            Http.quick.newCall(rb.build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.code == 401 || r.code == 403) return Test(false, "The server refused the key (${r.code}).")
                if (!r.isSuccessful) return Test(false, "The server answered ${r.code} at /models.")
                val el = Json.parseToJsonElement(body)
                val arr = (el as? JsonObject)?.let { (it["data"] ?: it["models"]) as? JsonArray } ?: (el as? JsonArray) ?: JsonArray(emptyList())
                arr.mapNotNull { m -> (m as? JsonObject)?.let { o -> (o["id"] ?: o["name"] ?: o["model"])?.jsonPrimitive?.contentOrNull } }
            }
        } catch (e: Exception) { return Test(false, "Can't reach $base — " + (e.message ?: e.javaClass.simpleName).take(120)) }
        if (models.isEmpty()) return Test(false, "Connected, but the server has no models. On the server run: ollama pull qwen2.5-coder:7b")
        val pick = model?.takeIf { it in models } ?: models.first()
        val reply = try {
            val body = """{"model":${JsonPrimitive(pick)},"max_tokens":8,"messages":[{"role":"user","content":"Reply with one word: ready"}]}"""
            val rb = Request.Builder().url("$base/chat/completions").header("User-Agent", Http.UA).post(body.toRequestBody("application/json".toMediaType()))
            if (key.isNotBlank()) rb.header("Authorization", "Bearer ${key.trim()}")
            Http.download.newCall(rb.build()).execute().use { r ->
                val b = r.body?.string().orEmpty()
                if (!r.isSuccessful) return Test(false, "Models found, but $pick answered ${r.code}: " + b.take(120), models)
                Json.parseToJsonElement(b).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        } catch (e: Exception) { return Test(false, "Models found, but $pick didn't answer: " + (e.message ?: "").take(120), models) }
        return Test(true, "Working · $pick answered \"${reply.trim().take(30)}\" in ${(System.currentTimeMillis() - t0) / 100 / 10.0} s", models, System.currentTimeMillis() - t0)
    }
}
