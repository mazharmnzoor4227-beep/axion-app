package com.mazharansari.axion.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase F (v0.8.5) — the assistant's ears: message notifications the listener service has seen.
 * Pure formatting/dedupe lives here so it is unit-testable without Android.
 *
 * The spoken line is in the owner's own words (his Phase F brief, 2026-11-07):
 * "WhatsApp par X ka message aya hai" — app label + sender + text.
 */
data class InboxItem(val pkg: String, val appLabel: String, val title: String, val text: String, val at: Long)

object NotifyInbox {
    /** Message apps whose notifications the assistant announces (chat-class apps only). */
    val MESSAGING: Set<String> = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.telegram.messenger.web",
        "com.facebook.orca", "com.facebook.mlite",
        "com.instagram.android",
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
    )

    fun isMessaging(pkg: String): Boolean = pkg in MESSAGING

    /** "WhatsApp par Ahmed ka message aya hai: kal milte hain" — degrades gracefully when a part is missing. */
    fun spokenLine(appLabel: String, title: String, text: String): String {
        val app = appLabel.ifBlank { "Phone" }
        val t = title.trim()
        val x = text.trim()
        return when {
            t.isNotBlank() && x.isNotBlank() -> "$app par $t ka message aya hai: $x"
            t.isNotBlank() -> "$app par $t ka message aya hai"
            x.isNotBlank() -> "$app par naya message aya hai: $x"
            else -> "$app par naya message aya hai"
        }
    }

    private const val DEDUPE_MS = 15_000L
    private const val MAX_ITEMS = 30

    private val _items = MutableStateFlow<List<InboxItem>>(emptyList())
    val items: StateFlow<List<InboxItem>> = _items.asStateFlow()
    @Volatile var listenerConnected: Boolean = false
    private var lastKey: String = ""
    private var lastAt: Long = 0L

    /**
     * Records a notification; returns false when it is a repeat of the same message inside the
     * dedupe window (chat apps re-post the same notification as it updates).
     */
    @Synchronized
    fun offer(pkg: String, appLabel: String, title: String, text: String, now: Long): Boolean {
        val key = "$pkg|$title|$text"
        if (key == lastKey && now - lastAt < DEDUPE_MS) return false
        lastKey = key
        lastAt = now
        _items.value = (listOf(InboxItem(pkg, appLabel, title, text, now)) + _items.value).take(MAX_ITEMS)
        return true
    }
}
