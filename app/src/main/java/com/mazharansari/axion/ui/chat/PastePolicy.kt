package com.mazharansari.axion.ui.chat

/**
 * Phase 2 (settings audit): the pure decision behind Settings → Advanced → "Paste as File".
 * Kept dependency-free so the rule is unit-testable without a device.
 *
 * - "Always" (default): any paste over [alwaysLimit] goes in as a .txt attachment.
 * - "Always ask": only a giant paste over [inlineLimit] asks first.
 * - "Never": nothing is ever converted.
 * Anything else (including an unknown stored value) behaves like "Never" is never
 * assumed — it falls through to inline, so a corrupt setting cannot eat a message.
 */
enum class PasteAction { SEND_INLINE, CONVERT_TO_FILE, ASK_USER }

object PastePolicy {
    fun decide(mode: String, length: Int, inlineLimit: Int = 60_000, alwaysLimit: Int = 2_000): PasteAction = when {
        mode == "Always" && length > alwaysLimit -> PasteAction.CONVERT_TO_FILE
        mode == "Always ask" && length > inlineLimit -> PasteAction.ASK_USER
        else -> PasteAction.SEND_INLINE
    }
}
