package com.mazharansari.axion.livecall

/** One Gemini key for a Live call (from Axion's connectors). */
data class ApiKeyCredential(val id: String, val name: String, val secret: String, val isActive: Boolean)

/** Failure classes, same wording rules as Kodex's ApiKeyFailoverCoordinator. */
object LiveKeys {
    fun isKeyFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "api key" in value || "authentication" in value || "user not found" in value ||
            "http 401" in value || "http 403" in value || "http 429" in value ||
            "expired" in value || "quota" in value || "rate limit" in value ||
            "ratelimit" in value || "limit exceeded" in value || "out of credits" in value ||
            "too many requests" in value || "insufficient" in value
    }

    fun isQuotaFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "quota" in value || "rate limit" in value || "ratelimit" in value ||
            "http 429" in value || "limit exceeded" in value || "out of credits" in value ||
            "too many requests" in value || "insufficient" in value ||
            "usage limit" in value || "plan limit" in value
    }
}
