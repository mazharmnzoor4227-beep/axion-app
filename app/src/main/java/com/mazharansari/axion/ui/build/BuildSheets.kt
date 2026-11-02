package com.mazharansari.axion.ui.build

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.linux.LinuxBase
import com.mazharansari.axion.linux.LinuxState
import com.mazharansari.axion.linux.Toolchain
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.chat.AxionSheet
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.settings.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type

/** "New project": the name the user types + what it is. */
@Composable
fun NewProjectSheet(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    val p = P
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("web") }
    AxionSheet(onDismiss) {
        Text("New project", style = Type.title, color = p.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
            Field(name, { name = it }, "Project name, e.g. Clawora store")
            Spacer(Modifier.height(16.dp))
            Text("What are you making?", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = p.text2)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("web", "Website", Gk.globe), Triple("android", "Android app", Gk.android), Triple("other", "Other", Gk.codeBlock)).forEach { (k, label, icon) ->
                    val sel = kind == k
                    val tap = rememberTap { kind = k }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (sel) p.card else Color.Transparent).border(1.5.dp, if (sel) p.text else p.border, RoundedCornerShape(16.dp))
                        .clickable(onClick = tap).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        GkIcon(icon, null, 22.dp, if (sel) p.text else p.text2)
                        Text(label, style = Type.small.copy(fontWeight = FontWeight.Medium), color = if (sel) p.text else p.text2, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            Text(when (kind) {
                "android" -> "Starts from a small Android project (Kotlin). Building the APK needs the Android APK builder toolchain."
                "web" -> "Antigravity writes the site; you preview it here and publish it with one tap."
                else -> "An empty folder for scripts, tools or anything else."
            }, style = Type.small, color = p.text2, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(18.dp))
            BigButton("Create project", enabled = name.isNotBlank()) { onCreate(name.trim(), kind) }
        }
    }
}

/**
 * Addendum 2: the first time Build opens — optional toolchains as checkboxes, with sizes. Nothing downloads unless ticked;
 * installs run one after another in the background (notification), and Settings → Developer tools manages them later.
 */
@Composable
fun BuildToolsPopup(vm: ChatViewModel, onDismiss: () -> Unit) {
    val p = P; val g = vm.g
    val records by g.devtools.records.collectAsState()
    val linux by g.linux.state.collectAsState()
    val offered = listOf(Toolchain.WEB, Toolchain.PYTHON, Toolchain.BROWSER, Toolchain.ANDROID, Toolchain.ANDROIDSDK, Toolchain.CPP, Toolchain.PHP, Toolchain.GO, Toolchain.RUST)
    fun installed(t: Toolchain) = records[t.id]?.version != null && linux == LinuxState.Ready
    var picked by remember { mutableStateOf(emptySet<String>()) }   // v0.5.2: nothing pre-ticked — "Nothing downloads unless you tick it" is now true
    // ticking something that needs another toolchain ticks that one too (Browser → Web, APK builder → Android)
    fun toggle(t: Toolchain) {
        picked = if (t.id in picked) picked - t.id - offered.filter { o -> t.id in o.needs }.map { it.id }.toSet() else picked + t.id + t.needs.filterNot { installed(Toolchain.byId(it)!!) }
    }
    val chosen = offered.filter { it.id in picked }
    val dl = chosen.sumOf { it.downloadMb } + if (chosen.isNotEmpty() && linux != LinuxState.Ready) LinuxBase.FIRST_DOWNLOAD_MB else 0
    val disk = chosen.sumOf { it.diskMb }
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(horizontal = 18.dp).fillMaxWidth().heightIn(max = 760.dp).clip(RoundedCornerShape(28.dp)).background(p.page2).padding(top = 22.dp, bottom = 16.dp)) {
            Box(Modifier.padding(horizontal = 22.dp).size(46.dp).clip(CircleShape).background(p.surface), contentAlignment = Alignment.Center) { GkIcon(Gk.hammer, null, 22.dp, p.text) }
            Text("Install developer tools for better app and website building", style = Type.title.copy(fontSize = 20.sp, lineHeight = 26.sp), color = p.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
            Text("All optional. Antigravity can run, test and check what it builds with these. Nothing downloads unless you tick it.", style = Type.small, color = p.text2, modifier = Modifier.padding(horizontal = 22.dp))
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                offered.forEach { t ->
                    val done = installed(t); val on = t.id in picked
                    val tap = rememberTap { if (!done) toggle(t) }
                    Row(Modifier.fillMaxWidth().clickable(enabled = !done, onClick = tap).padding(horizontal = 22.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on || done) p.text else Color.Transparent).border(2.dp, if (on || done) p.text else p.text3, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                            if (on || done) GkIcon(Gk.check, null, 15.dp, p.bg)
                        }
                        Spacer(Modifier.width(14.dp))
                        ToolBadge(t, 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = Type.row.copy(fontSize = 16.sp), color = if (done) p.text2 else p.text)
                            Text(t.tech, style = Type.tiny, color = p.text2, maxLines = 1)
                        }
                        Text(if (done) "Installed" else "~${t.downloadMb} MB", style = Type.tiny, color = if (done) BGreen else p.text2)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(if (chosen.isEmpty()) "Nothing selected — you can add tools any time in Settings → Developer tools."
                else "Download ~$dl MB · uses ~${if (disk >= 1024) "%.1f GB".format(disk / 1024.0) else "$disk MB"} on the phone" + (if (linux != LinuxState.Ready) " (includes the one-time Linux base)" else "") + ". Installs in the background.",
                style = Type.small.copy(fontSize = 13.sp), color = p.text2, modifier = Modifier.padding(horizontal = 22.dp))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { BigButtonGhost("Not now") { onDismiss() } }
                Box(Modifier.weight(1.3f)) { BigButton(if (chosen.isEmpty()) "Done" else "Install ${chosen.size}") { chosen.forEach { g.devtools.install(it) }; onDismiss() } }
            }
        }
    }
}

@Composable
internal fun BigButtonGhost(text: String, onClick: () -> Unit) {
    val p = P; val tap = rememberTap(onClick)
    Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(50)).background(if (p.isDark) Color(0xFF2A2A2A) else Color(0xFFE9E9E9)).clickable(onClick = tap), contentAlignment = Alignment.Center) {
        Text(text, style = Type.row, color = p.text)
    }
}
