package com.mazharansari.axion.livecall

import com.mazharansari.axion.data.Message
import com.mazharansari.axion.data.MsgStatus
import com.mazharansari.axion.data.Role

/**
 * v0.8.2 — Call memory (owner, 2026-11-07): a long call dropped, he called back into the SAME
 * chat, and Axion remembered nothing — because the call's setup only ever carried the static
 * system instruction, never the conversation. The turns were already being saved into the chat
 * (see ChatViewModel.appendCallTurn); they were just never handed back to the next call.
 *
 * This builds the context block a new call is seeded with: the tail of its own conversation —
 * typed messages and earlier voice-call turns alike, since call turns are stored as ordinary
 * messages — formatted as a plain transcript. Pure and capped, so it is unit-testable and the
 * setup stays light.
 */
object CallContext {

    const val HEADER = "CONVERSATION SO FAR — this voice call belongs to the chat below and you remember all of it, " +
        "including anything said in earlier calls in this same chat. If this call is a reconnect after a drop, " +
        "pick up naturally from where you both left off; do not re-introduce yourself or start over."

    fun build(messages: List<Message>, maxMessages: Int = 40, maxChars: Int = 16000): String {
        val usable = messages
            .filter { it.text.isNotBlank() && it.status != MsgStatus.STREAMING }
            .takeLast(maxMessages)
        if (usable.isEmpty()) return ""
        val lines = usable.map { m -> (if (m.role == Role.USER) "User: " else "Axion: ") + m.text.trim() }
        var start = 0
        var joined = lines.joinToString("\n")
        while (joined.length > maxChars && start < lines.size - 1) {
            start++
            joined = lines.subList(start, lines.size).joinToString("\n")
        }
        // A single message longer than the cap: keep its tail end rather than dropping the lot.
        if (joined.length > maxChars) joined = joined.takeLast(maxChars)
        return HEADER + "\n" + joined
    }
}
