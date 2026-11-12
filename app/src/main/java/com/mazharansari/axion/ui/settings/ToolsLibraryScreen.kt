package com.mazharansari.axion.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mazharansari.axion.agent.ToolCatalog
import com.mazharansari.axion.agent.ToolGroup
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type

private fun iconFor(g: ToolGroup) = when (g.id) {
    "web" -> Gk.globe; "files" -> Gk.folder; "documents" -> Gk.pdf; "images" -> Gk.image; "video" -> Gk.play
    "commands" -> Gk.terminal; "skills" -> Gk.skills; "connectors" -> Gk.connectors; else -> Gk.agent
}

/** v0.6 (Phase 3.4) Tools library: every tool Axion's agent can use, in plain words, each with an on/off switch. Original Axion screen. */
@Composable
fun ToolsLibraryScreen(vm: ChatViewModel, nav: (Route) -> Unit, back: () -> Unit) {
    val s = vm.g.settings.state.collectAsState().value
    val p = P
    SettingsPage("Tools library", back) {
        Text("Axion picks the right tool for each task and tells you which one it used. Switch a tool off and Axion will politely say it is off instead of doing that kind of task.",
            style = Type.small, color = p.text2, modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
        val on = ToolCatalog.groups.count { ToolCatalog.isEnabled(it, s.toolSwitches) }
        SectionLabel("$on of ${ToolCatalog.groups.size} tools on")
        SettingsGroup {
            ToolCatalog.groups.forEach { g ->
                val enabled = ToolCatalog.isEnabled(g, s.toolSwitches)
                val change = { v: Boolean -> vm.g.settings.update { it.copy(toolSwitches = it.toolSwitches + (g.id to v)) } }
                SettingsRow(iconFor(g), g.title, g.description + if (g.note.isNotBlank()) "\n" + g.note else "", chevron = false,
                    trailing = { Spacer(Modifier.width(14.dp)); GrokSwitch(enabled, change) }, onClick = { change(!enabled) })
            }
        }
    }
}
