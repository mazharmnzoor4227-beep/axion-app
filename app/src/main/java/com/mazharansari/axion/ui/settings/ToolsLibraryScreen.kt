package com.mazharansari.axion.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.agent.ToolCatalog
import com.mazharansari.axion.agent.ToolGroup
import com.mazharansari.axion.phone.PhoneController
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import com.mazharansari.axion.ui.build.ConfirmDialog
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
                val enabled = ToolCatalog.isEnabled(g, s.toolSwitches) && (g.id != "phone" || s.phoneConsent)
                val change = { v: Boolean ->
                    if (g.id == "phone" && v && !s.phoneConsent) nav(Route.PhoneControl)          // consent first, on its own screen
                    else vm.g.settings.update { it.copy(toolSwitches = it.toolSwitches + (g.id to v)) }
                }
                SettingsRow(iconFor(g), g.title, g.description + if (g.note.isNotBlank()) "\n" + g.note else "", chevron = false,
                    trailing = { Spacer(Modifier.width(14.dp)); GrokSwitch(enabled, change) }, onClick = { change(!enabled) })
            }
        }
        SectionLabel("Phone control")
        SettingsGroup { SettingsRow(Gk.shield, "Permission & safety", subtitle = if (s.phoneConsent) "Allowed — you can withdraw it there" else "Not allowed yet") { nav(Route.PhoneControl) } }
    }
}

/** v0.6 (Phase 3.2) the explicit permission screen for phone control. */
@Composable
fun PhoneControlScreen(vm: ChatViewModel, back: () -> Unit) {
    val s = vm.g.settings.state.collectAsState().value
    val up = PhoneController.serviceUp.collectAsState().value || PhoneController.available
    val ctx = LocalContext.current; val p = P
    var confirm by remember { mutableStateOf(false) }
    SettingsPage("Phone control", back) {
        SettingsGroup {
            SettingsRow(Gk.shield, "Accessibility service", subtitle = if (up) "On — Axion can work other apps when you ask" else "Off — turn on \"Axion phone control\" in Android Accessibility settings",
                value = if (up) "On" else "Off", chevron = false)
            SettingsRow(Gk.arrowUpRight, "Open Accessibility settings", subtitle = "Android Settings → Accessibility → Axion phone control → On") {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        SectionLabel("What Axion can do")
        Para("Open apps, tap, type, scroll and press Back/Home on your phone to finish a task you asked for — for example \"open the browser and search for …\". Every step is shown live in the chat and in a strip on top of the screen.")
        SectionLabel("What it never does")
        Para("• It never opens or operates banking, payment, wallet, crypto, password-manager or authenticator apps (including JazzCash, Easypaisa, HBL, Meezan, UBL, MCB, SadaPay, NayaPay, Google Pay/Wallet, PayPal). If one comes to the front it goes Home and stops.\n" +
            "• It never reads or types passwords, PINs, OTP codes or card numbers. Those screens are blocked.\n" +
            "• It asks you in chat before anything irreversible: sending, deleting, buying, posting.\n" +
            "• It never works in the background: it only acts while a task you just asked for is running, and stops after 15 minutes or 60 actions.")
        SectionLabel("How you stay in control")
        Para("A red STOP button stays on screen the whole time. Tap it — or Stop in the chat — and everything halts at once. You can turn the service off in Android Settings or withdraw permission here at any time.")
        SectionLabel("Permission")
        SettingsGroup {
            if (!s.phoneConsent) {
                SettingsRow(Gk.check, "I understand — allow phone control", subtitle = "Needed once. You can withdraw it any time.", chevron = false, titleColor = Color(0xFF6CB6FF)) { confirm = true }
            } else {
                ToggleRow(Gk.agent, "Phone control", "Let Axion use the phone tools when I ask", ToolCatalog.isEnabled("phone_screen", s.toolSwitches)) { v ->
                    vm.g.settings.update { it.copy(toolSwitches = it.toolSwitches + ("phone" to v)) } }
                SettingsRow(Gk.close, "Withdraw permission", subtitle = "Switches phone control off and asks again next time", chevron = false, titleColor = p.danger) {
                    vm.g.settings.update { it.copy(phoneConsent = false, toolSwitches = it.toolSwitches + ("phone" to false)) }
                }
            }
        }
        Para("Try it: after turning the service on, ask \"Open the browser and search for today's weather\" and watch the strip on top.")
    }
    if (confirm) ConfirmDialog("Allow phone control?",
        "Axion will be able to read the screen and tap, type and scroll in other apps — only while you have asked it to do a task and the red STOP button is showing. Banking, payment and wallet apps and password screens are always blocked.",
        "Allow", onDismiss = { confirm = false }) {
        vm.g.settings.update { it.copy(phoneConsent = true, toolSwitches = it.toolSwitches + ("phone" to true)) }; confirm = false
    }
}

@Composable
private fun Para(text: String) = Text(text, style = Type.small.copy(fontSize = 15.sp, lineHeight = 22.sp), color = P.text2, modifier = Modifier.padding(horizontal = 30.dp, vertical = 2.dp))
