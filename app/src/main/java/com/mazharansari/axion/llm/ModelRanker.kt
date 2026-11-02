package com.mazharansari.axion.llm

enum class Mode { AUTO, FAST, EXPERT }

/** Picks the best chat + tool-calling models from a provider's live model list. */
object ModelRanker {
    private val exclude = Regex(
        "embed|tts|whisper|transcri|audio|speech|image|imagen|veo|lyria|guard|rerank|moderation|robotics|computer-use|aqa|" +
            "deep-research|antigravity|native-audio|banana|live|vision-only|ocr|clip|bge|e5-|nomic|-instruct-v0\\.1|codestral-embed|tokenizer|dall-e|sora|realtime|search-preview",
        RegexOption.IGNORE_CASE,
    )

    data class ModelInfo(val id: String, val tools: Boolean? = null, val free: Boolean? = null)

    fun rank(p: Provider, models: List<ModelInfo>, mode: Mode): List<String> {
        var pool = models.filter { !exclude.containsMatchIn(it.id) && it.tools != false }
        if (p.id == "openrouter") pool = pool.filter { it.id.endsWith(":free") || it.free == true }.ifEmpty { pool }
        if (pool.isEmpty()) return listOf(p.fallbackModel)
        val prefs = if (mode == Mode.FAST) p.fastPrefs else p.expertPrefs
        fun score(id: String): Double {
            val l = id.lowercase()
            var s = 0.0
            val idx = prefs.indexOfFirst { l.contains(it.lowercase()) }
            if (idx >= 0) s += 1000 - idx * 40
            s += familyBoost(l)
            val size = Regex("(\\d+(?:\\.\\d+)?)b\\b").findAll(l).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull()
            if (size != null) s += if (mode == Mode.FAST) (60 - minOf(size, 300.0) / 5) else minOf(size, 700.0) / 7
            Regex("(?:gemini|gemma|qwen|glm|deepseek-v|kimi-k|llama-)(\\d+(?:\\.\\d+)?)").find(l)?.groupValues?.get(1)?.toDoubleOrNull()?.let { s += it * 8 }
            if ("preview" in l || "exp" in l) s -= 6
            if ("latest" in l) s += 4
            if (mode == Mode.FAST && ("lite" in l || "mini" in l || "flash" in l || "instant" in l || "fast" in l)) s += 40
            if (mode != Mode.FAST && "lite" in l) s -= 30
            if (mode != Mode.FAST && ("pro" in l) && p.id == "gemini") s -= 50 // free tier gives Pro ~0 requests/day
            if ("nano" in l || "8b" in l || "4b" in l || "1b" in l) s -= if (mode == Mode.FAST) 0 else 40
            return s
        }
        return pool.sortedByDescending { score(it.id) }.map { it.id.removePrefix("models/") }.distinct()
    }

    private fun familyBoost(l: String): Double = when {
        "gpt-oss-120b" in l -> 80.0
        "kimi" in l -> 78.0
        "deepseek-v" in l -> 76.0
        "glm-5" in l -> 72.0
        "qwen3" in l || "qwen-3" in l -> 70.0
        "llama-3.3-70b" in l || "llama3.3-70b" in l -> 64.0
        "gemini" in l && "flash" in l -> 75.0
        "mistral-medium" in l || "mistral-large" in l -> 70.0
        "command-a" in l -> 60.0
        "gpt-4.1" in l || "gpt-5" in l || "gpt-4o" in l -> 70.0
        "nemotron" in l -> 55.0
        "gemma" in l -> 40.0
        else -> 0.0
    }
}
