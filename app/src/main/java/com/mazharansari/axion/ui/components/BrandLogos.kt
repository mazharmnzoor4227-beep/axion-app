package com.mazharansari.axion.ui.components

import com.mazharansari.axion.R

/**
 * Official brand marks for provider / connector rows (res/drawable/brand_<id>.xml).
 * Sources: lobe-icons static SVGs (colour variant where it exists; black mono marks recoloured white
 * for Axion's dark #1E1E1E tiles). Returns null for unknown ids so callers can fall back to the letter badge.
 */
object BrandLogos {
    private val map: Map<String, Int> = mapOf(
        "gemini" to R.drawable.brand_gemini,
        "groq" to R.drawable.brand_groq,
        "xai" to R.drawable.brand_xai,
        "cerebras" to R.drawable.brand_cerebras,
        "openrouter" to R.drawable.brand_openrouter,
        "mistral" to R.drawable.brand_mistral,
        "huggingface" to R.drawable.brand_huggingface,
        "nvidia" to R.drawable.brand_nvidia,
        "sambanova" to R.drawable.brand_sambanova,
        "cohere" to R.drawable.brand_cohere,
        "together" to R.drawable.brand_together,
        "deepseek" to R.drawable.brand_deepseek,
        "openai" to R.drawable.brand_openai,
        "anthropic" to R.drawable.brand_anthropic,
        "github" to R.drawable.brand_github,
        "github-models" to R.drawable.brand_github,
        "antigravity" to R.drawable.brand_antigravity,
    )

    private val aliases: Map<String, String> = mapOf(
        "grok" to "xai", "x-ai" to "xai", "x.ai" to "xai",
        "google" to "gemini", "google-gemini" to "gemini",
        "hf" to "huggingface", "hugging-face" to "huggingface", "hugging_face" to "huggingface",
        "github_models" to "github-models", "githubmodels" to "github-models",
        "together-ai" to "together", "togetherai" to "together",
        "claude" to "anthropic", "nvidia-nim" to "nvidia", "nim" to "nvidia",
        "google-antigravity" to "antigravity", "agy" to "antigravity",
    )

    fun res(id: String): Int? {
        val key = id.trim().lowercase()
        return map[key] ?: aliases[key]?.let { map[it] }
    }
}
