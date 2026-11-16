package com.mazharansari.axion.data

/**
 * Phase 2 — one input draft per conversation.
 *
 * The composer draft used to live in a single view-model field, so switching
 * chats carried the half-typed text into the wrong chat (and New chat wiped
 * it). Drafts are now kept per conversation id: leaving a chat saves what was
 * typed, opening it again restores it. Pure logic, no Android dependency.
 * Original Axion code.
 */
class ConversationDrafts {
    private val drafts = HashMap<String, String>()

    /** Save the draft for [id]; a blank draft clears the saved one. */
    fun save(id: String?, text: String) {
        if (id == null) return
        if (text.isBlank()) drafts.remove(id) else drafts[id] = text
    }

    /** The saved draft for [id], or "" when there is none. */
    fun restore(id: String?): String = if (id == null) "" else drafts[id].orEmpty()

    /** Drop the draft for [id] (after it was sent). */
    fun clear(id: String?) {
        if (id != null) drafts.remove(id)
    }

    fun count(): Int = drafts.size
}
