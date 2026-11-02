package com.mazharansari.axion.ui

/**
 * The engines behind the app's sections (v0.4, mazhar 1 Nov 2:28 PM — kept fully separate):
 *  - ASK: API-key providers (Groq, Gemini, OpenRouter, …) + "Free (slow)". Never Antigravity.
 *  - BUILD: Antigravity only (agy CLI in Linux, multi-account auto-switch). Never API keys.
 * Extension point: a future AGY_CHAT ("chat with Antigravity outside Build") is one more entry here plus its
 * attempts() branch in ChatViewModel — deliberately not built yet.
 */
enum class Section(val id: String) {
    ASK("ask"),
    BUILD("build");

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: ASK }
}
