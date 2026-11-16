package com.mazharansari.axion.ui.chat

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Phase 2 — the WhatsApp-style chat list: one row per conversation, like a
 * friend in a messenger. A row is Axion or one bot (its logo, name, last
 * message, time, and a Working mark while its run is still going in the
 * background). Worker transcripts stay out of this list (they live in Bots).
 * Pure row-building logic, no Android dependency — the drawer maps
 * [com.mazharansari.axion.data.ConversationMeta] onto [ChatListMeta].
 * Original Axion code.
 */

/** The fields a chat-list row needs (mapped from ConversationMeta by the caller). */
data class ChatListMeta(
    val id: String,
    val title: String,
    val pinned: Boolean,
    val updatedAt: Long,
    val preview: String = "",
    val section: String = "ask",
    val botId: String = "",
    val botName: String = "",
)

data class ChatListRow(
    val id: String,
    val title: String,
    val preview: String,
    val updatedAt: Long,
    val pinned: Boolean,
    val section: String,
    val working: Boolean,
    val botId: String = "",
    val botName: String = "",
    val botLogo: String = "",
) {
    /** The name shown in the row: the bot's name for a bot chat, else the chat title. */
    val displayName: String get() = botName.ifBlank { title }
    /** True when this row is a chat running as an installed/created bot. */
    val isBotChat: Boolean get() = botId.isNotBlank()
}

/**
 * Build the chat-list rows: worker transcripts excluded, pinned first, then
 * newest first. [workingIds] are the conversation ids with a run in flight
 * (RunRegistry); [botLogos] maps bot id → stored logo string.
 */
fun buildChatRows(
    metas: List<ChatListMeta>,
    workingIds: Set<String>,
    botLogos: Map<String, String> = emptyMap(),
): List<ChatListRow> = metas
    .filter { it.section != "worker" }
    .map { m ->
        ChatListRow(
            id = m.id, title = m.title, preview = m.preview, updatedAt = m.updatedAt,
            pinned = m.pinned, section = m.section, working = workingIds.contains(m.id),
            botId = m.botId, botName = m.botName, botLogo = botLogos[m.botId].orEmpty(),
        )
    }
    .sortedWith(compareByDescending<ChatListRow> { it.pinned }.thenByDescending { it.updatedAt })

/** WhatsApp-style time: "2:45 PM" today, "Yesterday", else "12 Nov". */
fun chatTimeLabel(t: Long, now: Long = System.currentTimeMillis()): String {
    val dayStart = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        t >= dayStart -> SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(t))
        t >= dayStart - 86_400_000L -> "Yesterday"
        else -> SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(t))
    }
}
