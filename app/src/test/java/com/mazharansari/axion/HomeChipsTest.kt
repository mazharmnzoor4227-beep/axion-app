package com.mazharansari.axion

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import com.mazharansari.axion.ui.chat.ToolsRow
import com.mazharansari.axion.ui.home.SuggestionChips
import com.mazharansari.axion.ui.home.showToolsRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.9.6: the empty home has ONE chip row above the composer — the three tool
 * toggles merged into the suggestion row (Research, Long task, Turbo first),
 * and the standalone ToolsRow renders only in an active chat. No network, no
 * Android services: the rows render standalone under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class HomeChipsTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** The visible texts in layout order, depth-first through the semantics tree. */
    private fun orderedTexts(): List<String> {
        fun walk(n: SemanticsNode): List<String> {
            val out = ArrayList<String>()
            n.config.getOrNull(SemanticsProperties.Text)?.let { out += it.joinToString("") }
            n.children.forEach { out += walk(it) }
            return out
        }
        return walk(compose.onRoot().fetchSemanticsNode())
    }

    private fun suggestions(noKeys: Boolean, longTaskOn: Boolean = true) {
        compose.setContent {
            SuggestionChips(
                noKeys = noKeys, researchOn = false, onResearch = {},
                longTaskOn = longTaskOn, onLongTask = {},
                turboOn = false, onTurbo = {},
                onConnect = {}, onSkills = {}, onBuild = {}, onVoice = {},
            )
        }
    }

    @Test fun emptyHomeChipsAreOneMergedRowInOrder() {
        suggestions(noKeys = false)
        assertEquals(
            listOf("Research", "Long task", "Turbo", "Try Connectors", "Skills", "Build an app", "Voice"),
            orderedTexts(),
        )
    }

    @Test fun emptyHomeChipsWithNoKeysLeadWithTheKeyChip() {
        suggestions(noKeys = true)
        assertEquals(
            listOf("Connect a free AI key", "Research", "Long task", "Turbo", "Try Connectors", "Skills", "Build an app", "Voice"),
            orderedTexts(),
        )
    }

    @Test fun toolsRowStillHoldsTheThreeTogglesForActiveChats() {
        compose.setContent {
            ToolsRow(researchOn = true, onResearch = {}, longTaskOn = false, onLongTask = {}, turboOn = false, onTurbo = {})
        }
        assertEquals(listOf("Research", "Long task", "Turbo"), orderedTexts())
    }

    @Test fun toolsRowRendersOnlyInAnActiveChat() {
        assertEquals(false, showToolsRow(messagesEmpty = true, build = false))   // empty home: no second row
        assertEquals(true, showToolsRow(messagesEmpty = false, build = false))  // active chat: the row stays
        assertEquals(false, showToolsRow(messagesEmpty = false, build = true))  // Build: never
        assertEquals(false, showToolsRow(messagesEmpty = true, build = true))
    }
}
