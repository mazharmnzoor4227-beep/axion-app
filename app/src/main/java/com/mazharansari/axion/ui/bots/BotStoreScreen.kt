package com.mazharansari.axion.ui.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.data.BotCatalog
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.components.Gk
import com.mazharansari.axion.ui.components.GkIcon
import com.mazharansari.axion.ui.settings.SettingsPage
import com.mazharansari.axion.ui.theme.P

/**
 * PHASE 1 SWARM — the Bot Store.
 *
 * A catalog of good open-source bots the user can install. Each entry shows its name, what
 * it does, and its source repo + licence. Tapping Install stores that bot's definition
 * bundle (persona + skills + tool allow-list) in the app's own storage — nothing runs
 * until the user starts a chat with it from the Bots section, and no foreign code is ever
 * executed: every installed bot runs on Axion's own agent loop. All seeds come from
 * verified MIT/Apache sources (see third_party/NOTICE-bot-store.md); never Andee,
 * Locus-AGPL, Aether-GPL or Operit-LGPL code.
 * Original Axion code; no code copied from any other project.
 */
@Composable
fun BotStoreScreen(vm: ChatViewModel, back: () -> Unit) {
    val p = P
    val installed by vm.g.botStore.definitions.collectAsState()
    SettingsPage("Bot Store", back) {
        Text("Install a bot to give it work. A bot is a definition — persona, skills and a tool allow-list — run on Axion's own agent loop. Install never starts a bot; you start it from the Bots section.",
            fontSize = 13.sp, color = p.text2, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp))
        BotCatalog.seeds.forEach { seed ->
            val isIn = installed.any { it.id == seed.id }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
                .clip(RoundedCornerShape(16.dp)).background(p.card).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BotLogoView(seed.logo, seed.name, 44.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(seed.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = p.text)
                        Text(seed.description, fontSize = 13.sp, color = p.text2, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Text("Source: ${seed.sourceName} · ${seed.license}", fontSize = 12.sp, color = p.text3, modifier = Modifier.padding(top = 6.dp))
                if (seed.sourceUrl.isNotBlank()) Text(seed.sourceUrl, fontSize = 12.sp, color = p.text3)
                if (seed.skills.isNotEmpty()) Text("Skills: ${seed.skills.joinToString(", ")}", fontSize = 12.sp, color = p.text3, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isIn) {
                        GkIcon(Gk.check, "Installed", 18.dp, p.accent)
                        Text("Installed", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.accent, modifier = Modifier.padding(start = 6.dp))
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.g.botStore.remove(seed.id) }) { Text("Uninstall", color = p.text2, fontSize = 13.sp) }
                    } else {
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                vm.g.botStore.install(seed)
                                vm.toast = "Installed '${seed.name}' — find it in Bots → Your bots"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
                        ) { Text("Install") }
                    }
                }
            }
        }
        Text("Providers: bots run on the free providers you already use — Gemini, Grok, OpenRouter — through Axion's provider system and failover. Antigravity is the Build engine.",
            fontSize = 12.sp, color = p.text3, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        Spacer(Modifier.height(8.dp))
    }
}
