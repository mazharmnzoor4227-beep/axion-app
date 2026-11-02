package com.mazharansari.axion.llm

enum class ApiKind { OPENAI, GEMINI, LOCAL }

data class Provider(
    val id: String,
    val name: String,
    val api: ApiKind,
    val baseUrl: String,
    /** Opens in the browser from the "Get key" button. Checked on 1 Nov 2026 (each answers 200 or its sign-in page). */
    val keyPage: String,
    val prefixes: List<String> = emptyList(),
    val free: Boolean,
    val note: String,
    /** Model-id fragments, best first, for the Auto/Expert slot. */
    val expertPrefs: List<String> = emptyList(),
    /** Model-id fragments, best first, for the Fast slot. */
    val fastPrefs: List<String> = emptyList(),
    val fallbackModel: String,
    val modelsUrl: String = "$baseUrl/models",
    val vision: Boolean = false,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Mistral wants 9-char alphanumeric tool-call ids. */
    val shortToolIds: Boolean = false,
)

object Providers {
    val all: List<Provider> = listOf(
        Provider(
            id = "gemini", name = "Google Gemini", api = ApiKind.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            keyPage = "https://aistudio.google.com/app/apikey",
            prefixes = listOf("AIza", "AQ."), free = true, vision = true,   // AQ. = AI Studio's new "Auth key" format (2026)
            note = "Free tier · Google AI Studio · works in Pakistan · also voice",
            expertPrefs = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3-flash", "gemini-flash-latest", "gemini-2.5-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-flash-lite-latest", "gemma-4-31b"),
            fastPrefs = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-flash-lite-latest", "gemini-3.5-flash", "gemini-flash-latest", "gemini-2.5-flash"),
            fallbackModel = "gemini-flash-latest",
        ),
        Provider(
            id = "groq", name = "Groq", api = ApiKind.OPENAI,
            baseUrl = "https://api.groq.com/openai/v1", keyPage = "https://console.groq.com/keys",
            prefixes = listOf("gsk_"), free = true,
            note = "Free tier · very fast",
            expertPrefs = listOf("openai/gpt-oss-120b", "moonshotai/kimi-k2", "qwen/qwen3", "llama-3.3-70b", "openai/gpt-oss-20b"),
            fastPrefs = listOf("openai/gpt-oss-20b", "llama-3.1-8b-instant", "openai/gpt-oss-120b"),
            fallbackModel = "openai/gpt-oss-120b",
        ),
        Provider(
            id = "cerebras", name = "Cerebras", api = ApiKind.OPENAI,
            baseUrl = "https://api.cerebras.ai/v1", keyPage = "https://cloud.cerebras.ai/platform/",
            prefixes = listOf("csk-"), free = true,
            note = "Free tier · fastest open models",
            expertPrefs = listOf("gpt-oss-120b", "qwen-3", "glm", "llama-3.3-70b", "llama"),
            fastPrefs = listOf("gpt-oss-120b", "llama3.1-8b", "qwen-3"),
            fallbackModel = "gpt-oss-120b",
        ),
        Provider(
            id = "mistral", name = "Mistral", api = ApiKind.OPENAI,
            baseUrl = "https://api.mistral.ai/v1", keyPage = "https://console.mistral.ai/api-keys",
            free = true, shortToolIds = true, vision = true,
            note = "Free Experiment plan",
            expertPrefs = listOf("mistral-medium-latest", "mistral-large-latest", "magistral-medium", "mistral-small-latest"),
            fastPrefs = listOf("mistral-small-latest", "ministral-8b-latest", "mistral-medium-latest"),
            fallbackModel = "mistral-small-latest",
        ),
        Provider(
            id = "openrouter", name = "OpenRouter", api = ApiKind.OPENAI,
            baseUrl = "https://openrouter.ai/api/v1", keyPage = "https://openrouter.ai/settings/keys",
            prefixes = listOf("sk-or-"), free = true,
            note = "Free :free models · one key, many models",
            expertPrefs = listOf(":free"), fastPrefs = listOf(":free"),
            fallbackModel = "openrouter/auto",
            extraHeaders = mapOf("HTTP-Referer" to "https://github.com/mazharmnzoor4227-beep", "X-Title" to "Axion"),
        ),
        Provider(
            id = "huggingface", name = "Hugging Face", api = ApiKind.OPENAI,
            baseUrl = "https://router.huggingface.co/v1", keyPage = "https://huggingface.co/settings/tokens",
            prefixes = listOf("hf_"), free = true,
            note = "Free monthly credits · Inference Providers",
            expertPrefs = listOf("deepseek-ai/deepseek-v4", "moonshotai/kimi", "zai-org/glm-5", "qwen/qwen3.8", "openai/gpt-oss-120b", "meta-llama/llama-3.3-70b"),
            fastPrefs = listOf("openai/gpt-oss-20b", "qwen/qwen3.6-35b-a3b", "meta-llama/llama-3.1-8b"),
            fallbackModel = "openai/gpt-oss-120b",
        ),
        Provider(
            id = "nvidia", name = "NVIDIA NIM", api = ApiKind.OPENAI,
            baseUrl = "https://integrate.api.nvidia.com/v1", keyPage = "https://build.nvidia.com/settings/api-keys",
            prefixes = listOf("nvapi-"), free = true,
            note = "Free developer credits",
            expertPrefs = listOf("moonshotai/kimi", "deepseek-ai/deepseek-v4", "z-ai/glm-5", "openai/gpt-oss-120b", "qwen/qwen3", "meta/llama-3.3-70b"),
            fastPrefs = listOf("openai/gpt-oss-20b", "z-ai/glm-5.3-flash", "meta/llama-3.1-8b"),
            fallbackModel = "openai/gpt-oss-120b",
        ),
        Provider(
            id = "sambanova", name = "SambaNova", api = ApiKind.OPENAI,
            baseUrl = "https://api.sambanova.ai/v1", keyPage = "https://cloud.sambanova.ai/apis",
            free = true,
            note = "Free tier · fast open models",
            expertPrefs = listOf("DeepSeek-V3.2", "DeepSeek-V3.1", "gpt-oss-120b", "Llama-3.3-70B"),
            fastPrefs = listOf("gpt-oss-120b", "MiniMax", "Llama-3.3-70B"),
            fallbackModel = "gpt-oss-120b",
        ),
        Provider(
            id = "cohere", name = "Cohere", api = ApiKind.OPENAI,
            baseUrl = "https://api.cohere.ai/compatibility/v1", keyPage = "https://dashboard.cohere.com/api-keys",
            free = true,
            note = "Free trial key · non-commercial use only",
            expertPrefs = listOf("command-a", "command-r-plus", "command-r"),
            fastPrefs = listOf("command-r7b", "command-r", "command-a"),
            fallbackModel = "command-a-03-2025",
        ),
        Provider(
            id = "together", name = "Together AI", api = ApiKind.OPENAI,
            baseUrl = "https://api.together.xyz/v1", keyPage = "https://api.together.ai/settings/api-keys",
            prefixes = listOf("tgp_"), free = false,
            note = "Paid credits (a few free models)",
            expertPrefs = listOf("-Free", "deepseek-ai/DeepSeek-V", "moonshotai/Kimi", "openai/gpt-oss-120b", "Llama-3.3-70B"),
            fastPrefs = listOf("-Free", "openai/gpt-oss-20b"),
            fallbackModel = "openai/gpt-oss-120b",
        ),
        // Paid providers: optional, same "paste key" flow.
        Provider(
            id = "openai", name = "OpenAI", api = ApiKind.OPENAI,
            baseUrl = "https://api.openai.com/v1", keyPage = "https://platform.openai.com/api-keys",
            prefixes = listOf("sk-proj-", "sk-svcacct-"), free = false, vision = true,
            note = "Paid",
            expertPrefs = listOf("gpt-5", "gpt-4.1", "gpt-4o"), fastPrefs = listOf("gpt-5-mini", "gpt-4.1-mini", "gpt-4o-mini"),
            fallbackModel = "gpt-4.1",
        ),
        Provider(
            id = "anthropic", name = "Anthropic", api = ApiKind.OPENAI,
            baseUrl = "https://api.anthropic.com/v1", keyPage = "https://console.anthropic.com/settings/keys",
            prefixes = listOf("sk-ant-"), free = false, vision = true,
            note = "Paid · OpenAI-compatible endpoint",
            expertPrefs = listOf("claude-sonnet", "claude-opus", "claude-haiku"), fastPrefs = listOf("claude-haiku", "claude-sonnet"),
            fallbackModel = "claude-sonnet-4-5",
            extraHeaders = mapOf("anthropic-version" to "2023-06-01"),
        ),
        Provider(
            id = "deepseek", name = "DeepSeek", api = ApiKind.OPENAI,
            baseUrl = "https://api.deepseek.com", keyPage = "https://platform.deepseek.com/api_keys",
            free = false, note = "Paid · very cheap",
            expertPrefs = listOf("deepseek-chat", "deepseek-reasoner"), fastPrefs = listOf("deepseek-chat"),
            fallbackModel = "deepseek-chat",
        ),
        Provider(
            id = "xai", name = "xAI Grok", api = ApiKind.OPENAI,
            baseUrl = "https://api.x.ai/v1", keyPage = "https://console.x.ai/",
            prefixes = listOf("xai-"), free = false, vision = true, note = "Paid",
            expertPrefs = listOf("grok-4", "grok-3"), fastPrefs = listOf("grok-4-fast", "grok-3-mini"),
            fallbackModel = "grok-4",
        ),
    )

    /**
     * v0.5 (addendum 6): the user's own models. Never auto-detected from a pasted key, never in probe lists; added from
     * Connectors → "Local model" / "My server". Both honestly labelled slower/weaker than API models.
     */
    val LOCAL = Provider(id = "local", name = "Local model", api = ApiKind.LOCAL, baseUrl = "", keyPage = "", free = true,
        note = "On your phone · offline · slower and weaker than API models", fallbackModel = "")
    val SERVER = Provider(id = "myserver", name = "My server", api = ApiKind.OPENAI, baseUrl = "", keyPage = "", free = true,
        note = "Your own Ollama / llama.cpp server · slower than API models", fallbackModel = "qwen2.5-coder:7b")
    val own = listOf(LOCAL, SERVER)

    fun byId(id: String): Provider? = all.firstOrNull { it.id == id } ?: own.firstOrNull { it.id == id }

    /** Which provider a key's own prefix says it belongs to (used to catch a key pasted into the wrong provider's sheet). */
    fun ownerByPrefix(key: String): Provider? = all.firstOrNull { p -> p.prefixes.any { key.trim().startsWith(it) } }

    /** Providers retired upstream; a saved connector for one is dropped on load. GitHub Models: retired 30 Jul 2026. */
    val retired = setOf("github")

    /** Prefix guesses, most specific first. Empty → caller probes every provider without a prefix. */
    fun guess(key: String): List<Provider> {
        val k = key.trim()
        val byPrefix = all.filter { p -> p.prefixes.any { k.startsWith(it) } }
        if (byPrefix.isNotEmpty()) return byPrefix
        val out = mutableListOf<Provider>()
        val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        when {
            k.startsWith("sk-") && Regex("^sk-[0-9a-f]{32}$").matches(k) -> out += listOf(byId("deepseek")!!, byId("openai")!!)
            k.startsWith("sk-") -> out += listOf(byId("openai")!!, byId("deepseek")!!)
            uuid.matches(k) -> out += byId("sambanova")!!
            Regex("^[A-Za-z0-9]{32}$").matches(k) -> out += listOf(byId("mistral")!!, byId("cohere")!!)
            Regex("^[A-Za-z0-9]{40}$").matches(k) -> out += listOf(byId("cohere")!!, byId("mistral")!!)
            Regex("^[0-9a-f]{64}$").matches(k) -> out += byId("together")!!
        }
        // Unknown format: Gemini first (AI Studio keys change format — AIza → AQ. in 2026), then the heuristics.
        return (listOf(byId("gemini")!!) + out).distinctBy { it.id }
    }

    /** Providers that have no recognisable prefix and are probed one by one when nothing else matched. */
    val probeOrder: List<String> = listOf("gemini", "mistral", "cohere", "sambanova", "together", "deepseek", "openai")
}
