package com.mazharansari.axion

import com.mazharansari.axion.phone.PhoneSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.8.6 phone-control fixes (owner's WhatsApp / YouTube report, 2026-11-08):
 *  - elements the agent acts on (fields, buttons) are shown BEFORE reading text, so the
 *    WhatsApp compose field / send icon and YouTube's Skip button survive dense screens;
 *  - long message CONTENT quoting secret words no longer marks a whole chat screen sensitive,
 *    while short real PIN/OTP prompts still do.
 */
class PhoneFix86Test {

    private fun node(id: Int, text: String = "", editable: Boolean = false, clickable: Boolean = false, scrollable: Boolean = false) =
        PhoneSafety.Node(id, text = text, editable = editable, clickable = clickable, scrollable = scrollable)

    @Test fun prioritizePutsFieldAndButtonsFirst() {
        val nodes = (1..60).map { node(it, text = "old message $it") } +
            node(61, text = "list", scrollable = true) +
            node(62, text = "Send", clickable = true) +
            node(63, editable = true)
        val shown = PhoneSafety.prioritize(nodes).take(10)
        assertEquals(63, shown[0].id)   // the compose field first
        assertEquals(62, shown[1].id)   // then tappables
        assertEquals(61, shown[2].id)   // then the scrollable list
        assertTrue(shown.drop(3).all { it.id in 1..60 })
    }

    @Test fun prioritizeIsStableInsideGroups() {
        val nodes = listOf(node(5, clickable = true), node(3, clickable = true), node(9, editable = true), node(1, editable = true))
        assertEquals(listOf(9, 1, 5, 3), PhoneSafety.prioritize(nodes).map { it.id })
    }

    @Test fun longChatContentQuotingSecretsIsNotSensitive() {
        val bubble = node(1, text = "Your security code with Ahmed changed. Tap to learn more about how end-to-end encryption works here.")
        val verifyBubble = node(2, text = "Please verify your phone number in the app settings page when you get a chance, it is under account options.")
        val field = node(3, editable = true)
        assertTrue(bubble.text.length > 60 && verifyBubble.text.length > 60)
        assertNull(PhoneSafety.sensitiveScreen("com.whatsapp", listOf(bubble, verifyBubble, field)))
    }

    @Test fun shortRealPromptsStaySensitive() {
        val field = node(1, editable = true)
        val otp = node(2, text = "Enter the 6-digit code")
        assertEquals("the screen asks for a PIN, password, OTP or card details", PhoneSafety.sensitiveScreen("com.example.app", listOf(otp, field)))
        val verify = node(3, text = "Verify your phone number")
        assertEquals("the screen is verifying a number or account with a code", PhoneSafety.sensitiveScreen("com.example.app", listOf(verify, field)))
    }
}
