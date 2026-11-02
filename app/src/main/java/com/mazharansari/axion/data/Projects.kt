package com.mazharansari.axion.data

import kotlinx.serialization.Serializable

/**
 * Build mode projects (addendum 5, v0.5 stage 2). Each project has its own folder on the phone
 * (files/projects/<slug>), seen inside Linux as /root/projects/<slug>, its own Build chat and its own Antigravity conversation.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val slug: String,                    // [a-z0-9-]{1,63}; folder name, never changes after creation (agy conversations point at it)
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val kind: String = "web",            // web | android | other
    val engine: String = "antigravity",  // v0.4/v0.5 rule: Build = Antigravity only
    val deploy: DeployTarget? = null,
    /** Axion's chat thread for this project (ConversationStore id). */
    val conversationId: String? = null,
    /** agy's own conversation id, so the next message continues the same Antigravity session. */
    val agyConversation: String? = null,
    val agyAccount: String? = null,
    val lastApk: String? = null,
)

@Serializable
data class DeployTarget(val provider: String, val siteId: String? = null, val url: String? = null, val name: String? = null, val at: Long = 0)

object ProjectSlug {
    fun of(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifBlank { "project" }
}
