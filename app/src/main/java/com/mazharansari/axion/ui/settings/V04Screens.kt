package com.mazharansari.axion.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.antigravity.AgyAccount
import com.mazharansari.axion.antigravity.AgyLoginState
import com.mazharansari.axion.data.KeyBackup
import com.mazharansari.axion.linux.*
import com.mazharansari.axion.quota.*
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import com.mazharansari.axion.ui.chat.AxionSheet
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────────── shared bits ─────────────────────────────

private val Amber = Color(0xFFF5A524)
private val Red = Color(0xFFF04438)
private val Green = Color(0xFF3FB950)

fun mb(kb: Long) = if (kb >= 1024 * 1024) "%.1f GB".format(kb / 1048576.0) else "${(kb / 1024).coerceAtLeast(1)} MB"
fun timeOf(ms: Long): String = SimpleDateFormat(if (ms - System.currentTimeMillis() > 20 * 3600_000L) "EEE h:mm a" else "h:mm a", Locale.ENGLISH).format(Date(ms))
fun num(d: Double?): String = when { d == null -> "—"; d >= 10_000 -> "%,.0f".format(d); d == Math.floor(d) -> "%,.0f".format(d); else -> "%.1f".format(d) }

@Composable
fun TagChip(tag: Tag) {
    if (tag == Tag.ESTIMATE) { Text("≈ estimate", style = Type.tiny.copy(fontSize = 10.sp, fontStyle = FontStyle.Italic), color = P.text3); return }
    val p = P
    val live = tag == Tag.LIVE
    Box(Modifier.border(1.dp, if (live) p.text.copy(alpha = 0.7f) else p.text3.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(tag.label, style = Type.tiny.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp), color = if (live) p.text else p.text2)
    }
}

@Composable
fun QuotaBar(fraction: Double?, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val p = P
    val f = fraction?.coerceIn(0.0, 1.0)
    val fill = when { f == null -> Color.Transparent; f <= 0.0001 -> Red; f < 0.2 -> Amber; else -> p.text.copy(alpha = 0.9f) }
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(if (p.isDark) Color(0xFF2A2A2A) else Color(0xFFE3E3E3))) {
        if (f != null) Box(Modifier.fillMaxWidth(f.toFloat().coerceAtLeast(if (f > 0) 0.015f else 0f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(fill))
    }
}

@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.card).padding(16.dp), content = content)

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) = Text(text, style = Type.small.copy(fontSize = 13.sp), color = P.text2, modifier = modifier)

@Composable
internal fun Badge(text: String, color: Color) =
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = Type.tiny.copy(fontWeight = FontWeight.Medium), color = color, maxLines = 1)
    }

@Composable
internal fun SmallButton(text: String, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = P; val tap = rememberTap(onClick)
    Box(Modifier.height(34.dp).clip(RoundedCornerShape(50)).background(if (filled) p.text else if (p.isDark) Color(0xFF2C2C2C) else Color(0xFFE9E9E9))
        .then(if (enabled) Modifier.clickable(onClick = tap) else Modifier).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = if (filled) p.bg else p.text.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

@Composable
internal fun BigButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = P; val tap = rememberTap(onClick)
    Box(modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(50)).background(if (enabled) p.text else p.text3)
        .then(if (enabled) Modifier.clickable(onClick = tap) else Modifier), contentAlignment = Alignment.Center) {
        Text(text, style = Type.row, color = p.bg)
    }
}

@Composable
internal fun Field(value: String, onChange: (String) -> Unit, hint: String, password: Boolean = false, mono: Boolean = false) {
    val p = P
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (p.isDark) Color(0xFF2A2A2A) else Color(0xFFEDEDED)).padding(horizontal = 14.dp, vertical = 13.dp)) {
        if (value.isEmpty()) Text(hint, style = Type.body, color = p.text3)
        BasicTextField(value, onChange, singleLine = true, textStyle = (if (mono) Type.mono.copy(fontSize = 15.sp) else Type.body).copy(color = p.text), cursorBrush = SolidColor(p.text),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun ToolBadge(t: Toolchain, size: Dp = 40.dp) {
    val (bg, label) = when (t) {
        Toolchain.WEB -> Color(0xFF2F6B2F) to "JS"; Toolchain.PYTHON -> Color(0xFF2B5B84) to "Py"; Toolchain.ANDROID -> Color(0xFF3B7D3B) to "Kt"
        Toolchain.CPP -> Color(0xFF51419A) to "C++"; Toolchain.PHP -> Color(0xFF5B5F97) to "php"; Toolchain.BROWSER -> Color(0xFF1F6FB2) to "Cr"; Toolchain.AGY -> Color(0xFF3D4A5C) to "ag"
        Toolchain.ANDROIDSDK -> Color(0xFF2E7D32) to "apk"; Toolchain.GO -> Color(0xFF00897B) to "Go"; Toolchain.RUST -> Color(0xFF8D4E2A) to "Rs"
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = (size.value * 0.33f).sp, fontWeight = FontWeight.Bold, fontFamily = com.mazharansari.axion.ui.theme.GoogleSans)
    }
}

@Composable
fun ProviderLogo(id: String, size: Dp) {
    val p = P
    val res = BrandLogos.res(id)
    Box(Modifier.size(size).clip(CircleShape).background(if (p.isDark) Color(0xFF2C2C2C) else Color(0xFFE6E6E6)), contentAlignment = Alignment.Center) {
        if (res != null) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(res), null, Modifier.size(size * 0.62f))
        else Text(id.take(1).uppercase(), color = p.text, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

// ───────────────────────────── Developer tools ─────────────────────────────

@Composable
fun DevToolsScreen(vm: ChatViewModel, back: () -> Unit, initialSheet: Toolchain? = null) {
    val g = vm.g; val p = P
    val dt = g.devtools
    val linuxState by g.linux.state.collectAsState()
    val records by dt.records.collectAsState()
    val status by dt.status.collectAsState()
    val settings by g.settings.state.collectAsState()
    // render hook (tests): the install-log dialog on its own, as it looks once opened from a tool's sheet
    if (initialSheet == null) vm.debugToolLog?.let { id -> ToolLogDialog(vm, id) { vm.debugToolLog = null } }
    var sheet by remember { mutableStateOf(initialSheet) }
    var confirmRemoveAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // v0.4 tap-latency pass: the rootfs size walk (tens of thousands of files) ran on the main thread on every open.
    val free by produceState(0L) { value = withContext(Dispatchers.IO) { g.linux.freeBytes() } }
    val used by produceState(0L, records) { value = withContext(Dispatchers.IO) { if (g.linux.isInstalled()) g.linux.diskUsageBytes() else 0L } }
    val visible = Toolchain.entries.filter { !it.hidden }
    val updates = visible.count { dt.hasUpdate(it.id) }

    SettingsPage("Developer tools", back) {
        Note("Real Linux on your phone — ${LinuxBase.UBUNTU} in a private sandbox (PRoot). The agent runs its commands here. Install only what you need: nothing is downloaded until you tap Install, and installs keep running in the background.",
            Modifier.padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 12.dp))
        // Linux card
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GkIcon(Gk.terminal, null, 26.dp, p.text)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Linux · ${LinuxBase.UBUNTU}", style = Type.row, color = p.text)
                    val sub = when (val s = linuxState) {
                        LinuxState.Ready -> "Ready in PRoot · uses ${mb(used / 1024)}"
                        is LinuxState.Installing -> s.p.message
                        is LinuxState.Failed -> s.message
                        LinuxState.NotInstalled -> "Not set up yet"
                    }
                    Text(sub, style = Type.small, color = if (linuxState is LinuxState.Failed) Red else p.text2)
                }
                if (linuxState == LinuxState.Ready) Badge("Ready", Green)
            }
            (linuxState as? LinuxState.Installing)?.let { QuotaBar(it.p.fraction.toDouble(), Modifier.padding(top = 12.dp)) }
            if (linuxState !is LinuxState.Ready) {
                Note("First install downloads about ${LinuxBase.FIRST_DOWNLOAD_MB} MB and needs about ${LinuxBase.NEEDS_FREE_BYTES / 1048576} MB free. Free on this phone now: ${mb(free / 1024)}.", Modifier.padding(top = 10.dp))
            } else Note("Free on this phone: ${mb(free / 1024)}", Modifier.padding(top = 8.dp))
        }

        SectionLabel("Toolchains")
        SettingsGroup {
            visible.forEach { t ->
                val rec = records[t.id]; val st = status[t.id] ?: ToolStatus.Idle
                val installed = rec?.version != null && linuxState == LinuxState.Ready
                val tap = rememberTap { sheet = t }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card).clickable(onClick = tap).padding(horizontal = 16.dp, vertical = 13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToolBadge(t)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t.title, style = Type.row, color = p.text)
                                if (installed && dt.hasUpdate(t.id)) { Spacer(Modifier.width(8.dp)); Badge("Update", Amber) }
                            }
                            Text(t.tech, style = Type.small, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val line = when {
                                st is ToolStatus.Working -> "${st.action} · ${st.percent}% · ${st.message}"
                                st == ToolStatus.Queued -> "Waiting…"
                                st is ToolStatus.Failed -> st.message
                                installed -> "Ready in PRoot · ${rec!!.version} · ${mb(rec.sizeKb)}"
                                else -> "Not installed · ~${t.downloadMb} MB download"
                            }
                            Text(line, style = Type.tiny.copy(fontSize = 12.5.sp), color = when { st is ToolStatus.Failed -> Red; installed -> Green; else -> p.text3 }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        when {
                            st is ToolStatus.Working || st == ToolStatus.Queued -> CircularProgressIndicator(Modifier.size(22.dp), color = p.text, strokeWidth = 2.dp)
                            installed -> GkIcon(Gk.chevronRight, null, 20.dp, p.text3)
                            else -> SmallButton("Install") { dt.install(t) }
                        }
                    }
                    if (st is ToolStatus.Working) QuotaBar(st.percent / 100.0, Modifier.padding(top = 10.dp), 4.dp)
                }
            }
        }

        SectionLabel("Updates")
        SettingsGroup {
            SettingsRow(Gk.refresh, "Update all tools now", subtitle = when {
                dt.installedTools().isEmpty() -> "Nothing installed yet"
                updates > 0 -> "$updates update${if (updates > 1) "s" else ""} available"
                else -> "Checks Node, Python, PHP, Kotlin… and installs the newest"
            }, onClick = { scope.launch { dt.checkUpdates(); dt.updateAll() } })
        }

        SectionLabel("Models")
        SettingsGroup {
            ToggleRow(Gk.bolt, "Free Mode", "Ask uses only free API providers and Free (slow)", settings.freeMode) { on -> g.settings.update { it.copy(freeMode = on) } }
        }
        if (linuxState == LinuxState.Ready) {
            Spacer(Modifier.height(18.dp))
            SettingsGroup { SettingsRow(Gk.trash, "Remove Linux and all tools", "Frees ${mb(used / 1024)} · Antigravity sign-ins are kept", titleColor = Red, iconTint = Red) { confirmRemoveAll = true } }
        }
    }

    sheet?.let { t -> ToolSheet(vm, t) { sheet = null } }
    if (confirmRemoveAll) AxionSheet({ confirmRemoveAll = false }) {
        Text("Remove Linux?", style = Type.title, color = p.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Note("Ubuntu and every toolchain will be deleted (${mb(used / 1024)}). You can install again any time.", Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton("Cancel") { confirmRemoveAll = false }
            SmallButton("Remove", filled = true) { confirmRemoveAll = false; scope.launch { g.linux.uninstallAll() } }
        }
    }
}

@Composable
fun ToolSheet(vm: ChatViewModel, t: Toolchain, onDismiss: () -> Unit) {
    val g = vm.g; val p = P; val dt = g.devtools
    val records by dt.records.collectAsState()
    val status by dt.status.collectAsState()
    val logs by dt.log.collectAsState()
    val linuxState by g.linux.state.collectAsState()
    val rec = records[t.id]; val st = status[t.id] ?: ToolStatus.Idle
    val installed = rec?.version != null && linuxState == LinuxState.Ready
    val working = st is ToolStatus.Working || st == ToolStatus.Queued
    val free by produceState(0L) { value = withContext(Dispatchers.IO) { g.linux.freeBytes() } }
    AxionSheet(onDismiss) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolBadge(t, 48.dp); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = Type.title, color = p.text)
                Text(t.tech, style = Type.small, color = p.text2)
            }
            when {
                installed -> Badge("Ready in PRoot", Green)
                working -> Badge("Installing", Amber)
            }
        }
        Note(t.blurb, Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
        // sizes
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fun box(label: String, value: String) = @Composable {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(p.card).padding(12.dp)) {
                    Text(value, style = Type.row.copy(fontSize = 16.sp), color = p.text); Text(label, style = Type.tiny, color = p.text2)
                }
            }
            if (installed) { box("Version", rec!!.version!!)(); box("On phone", mb(rec.sizeKb))() }
            else { box("Download", "~${t.downloadMb + if (linuxState == LinuxState.Ready) 0 else LinuxBase.FIRST_DOWNLOAD_MB} MB")(); box("On phone", "~${t.diskMb} MB")() }
            box("Free now", mb(free / 1024))()
        }
        if (!installed && linuxState != LinuxState.Ready) Note("Includes the one-time Linux download (~${LinuxBase.FIRST_DOWNLOAD_MB} MB).", Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        if (t.needs.isNotEmpty() && !installed) Note("Needs ${t.needs.joinToString { Toolchain.byId(it)?.title ?: it }} — it's added automatically.", Modifier.padding(horizontal = 24.dp, vertical = 2.dp))
        // progress + log
        if (working || st is ToolStatus.Failed || (logs[t.id]?.isNotEmpty() == true && !installed)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (p.isDark) Color(0xFF111111) else Color(0xFFF1F1F1)).padding(14.dp)) {
                if (st is ToolStatus.Working) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${st.action} · ${st.percent}%", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = p.text, modifier = Modifier.weight(1f))
                        Text("runs in background", style = Type.tiny, color = p.text3)
                    }
                    QuotaBar(st.percent / 100.0, Modifier.padding(vertical = 8.dp), 5.dp)
                }
                if (st is ToolStatus.Failed) { Text(st.message, style = Type.small, color = Red); ToolFailedActions(vm, t.id) { dt.install(t) }; Spacer(Modifier.height(6.dp)) }
                (logs[t.id] ?: emptyList()).takeLast(5).forEach { Text("› $it", style = Type.mono.copy(fontSize = 12.sp, lineHeight = 17.sp), color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (installed) ToggleRow(Gk.refresh, "Auto-update", "Install new versions in the background", rec!!.autoUpdate) { dt.setAutoUpdate(t, it) }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            when {
                working -> BigButton("Stop") { dt.cancelCurrent() }
                installed -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton(if (dt.hasUpdate(t.id)) "Update to ${rec!!.latest}" else "Reinstall / update", modifier = Modifier.weight(1f)) { dt.update(t) }
                    Box(Modifier.weight(0.6f)) { BigButtonOutline("Uninstall") { dt.uninstall(t) } }
                }
                else -> BigButton("Install ${t.title}") { dt.install(t) }
            }
        }
    }
}

@Composable
internal fun BigButtonOutline(text: String, onClick: () -> Unit) {
    val p = P; val tap = rememberTap(onClick)
    Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(50)).border(1.dp, p.text3, RoundedCornerShape(50)).clickable(onClick = tap), contentAlignment = Alignment.Center) {
        Text(text, style = Type.row, color = Red)
    }
}

// ───────────────────────────── Antigravity ─────────────────────────────

@Composable
fun AntigravityScreen(vm: ChatViewModel, back: () -> Unit, nav: (Route) -> Unit, forceLogin: AgyLoginState? = null) {
    val g = vm.g; val p = P
    val store by g.agy.store.collectAsState()
    val linuxState by g.linux.state.collectAsState()
    val records by g.devtools.records.collectAsState()
    val toolStatus by g.devtools.status.collectAsState()
    val loginState by g.agyLogin.state.collectAsState()
    val qv by g.quota.version.collectAsState()
    val model by produceState<com.mazharansari.axion.quota.QuotaModel?>(null, qv, store) { value = withContext(Dispatchers.IO) { runCatching { g.quota.model() }.getOrNull() } }   // SQLite off the main thread
    val scope = rememberCoroutineScope()
    var showLogin by remember { mutableStateOf(forceLogin != null) }
    var manage by remember { mutableStateOf<AgyAccount?>(null) }
    val cliReady = linuxState == LinuxState.Ready && records["agy"]?.version != null
    val agyStatus = toolStatus["agy"]

    SettingsPage("Antigravity", back) {
        Note("Google's Antigravity agent, signed in with several Google accounts. When one account hits its limit, Axion switches to the next and continues the same task.",
            Modifier.padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 12.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderLogo("antigravity", 40.dp); Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Antigravity CLI", style = Type.row, color = p.text)
                    Text(when {
                        agyStatus is ToolStatus.Working -> "${agyStatus.action} · ${agyStatus.percent}% · ${agyStatus.message}"
                        agyStatus is ToolStatus.Failed -> agyStatus.message
                        cliReady -> "agy ${records["agy"]?.version} · Ready in PRoot"
                        else -> "Not installed"
                    }, style = Type.small, color = if (agyStatus is ToolStatus.Failed) Red else p.text2, maxLines = 4)
                }
                if (cliReady && agyStatus !is ToolStatus.Failed && agyStatus !is ToolStatus.Working) Badge("Ready", Green)
            }
            if (agyStatus is ToolStatus.Working) QuotaBar(agyStatus.percent / 100.0, Modifier.padding(top = 10.dp), 4.dp)
            if (!cliReady && agyStatus !is ToolStatus.Working && agyStatus !is ToolStatus.Failed) {
                Note("One-time download: Linux ~${LinuxBase.FIRST_DOWNLOAD_MB} MB (if not set up) + Antigravity CLI ~53 MB. Needs about ${LinuxBase.NEEDS_FREE_BYTES / 1048576} MB free.", Modifier.padding(top = 10.dp, bottom = 10.dp))
                SmallButton("Set up Antigravity", filled = true) { g.devtools.install(Toolchain.AGY) }
            }
        }

        // v0.5.2: live setup panel (steps + streaming output), between the CLI card and the accounts
        AgySetupPanel(vm) { g.devtools.install(Toolchain.AGY) }
        SectionLabel("Accounts")
        SettingsGroup {
            val now = System.currentTimeMillis()
            g.agy.accounts().forEachIndexed { i, a ->
                val row = model?.agy?.firstOrNull { it.account.email == a.email }
                val tap = rememberTap { manage = a }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card).clickable(onClick = tap).padding(horizontal = 16.dp, vertical = 13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(a.label.ifBlank { a.email }, 38.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(a.label.ifBlank { "Account ${i + 1}" }, style = Type.row, color = p.text)
                                a.tier?.let { Spacer(Modifier.width(6.dp)); Text(if (it == "pro") "AI Pro" else it.replaceFirstChar { c -> c.uppercase() }, style = Type.tiny, color = p.text3) }
                            }
                            Text(a.email, style = Type.small, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        when {
                            a.ineligibleAt > 0 -> Badge("Not eligible", Red)
                            a.authExpiredAt > 0 -> Badge("Sign in again", Amber)
                            a.cooldownUntil > now -> Badge("Limit · back ${timeOf(a.cooldownUntil)}", Amber)
                            !a.enabled -> Badge("Off", p.text3)
                            a.email.equals(store.activeEmail, true) -> Badge("Active", Green)
                        }
                    }
                    row?.buckets?.take(2)?.forEach { b ->
                        Row(Modifier.padding(start = 52.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(b.label.substringAfterLast(" · ").take(14), style = Type.tiny, color = p.text2, modifier = Modifier.width(70.dp), maxLines = 1)
                            QuotaBar(b.remainingFraction, Modifier.weight(1f), 5.dp)
                            Text(b.remainingFraction?.let { "${(it * 100).toInt()}%" } ?: "—", style = Type.tiny, color = p.text, modifier = Modifier.width(40.dp).padding(start = 8.dp))
                        }
                    }
                }
            }
            SettingsRow(Gk.plus, "Add Antigravity account", if (cliReady) "Sign in with Google — paste the one-time code" else "Sets up Antigravity first") {
                if (!cliReady) g.devtools.install(Toolchain.AGY)
                showLogin = true; scope.launch { if (cliReady) g.agyLogin.begin() }
            }
        }

        SectionLabel("Switching")
        SettingsGroup {
            ToggleRow(Gk.refresh, "Auto-switch", "On a limit, the next account continues the task. The used one rests 60 min, or until the reset time Google gives.", store.autoSwitch) { g.agy.setAutoSwitch(it) }
        }

        // v0.4 (mazhar 2:28 PM): Antigravity is the Build tab's engine only — Ask never uses it, so no "use in chat" switch.
        SectionLabel("Where it runs")
        SettingsGroup { SettingsRow(Gk.hammer, "Build tab", "Every Build project runs on these accounts. Ask uses your API keys only.", chevron = false) {} }
        Note("Antigravity runs its own tools (files, commands) in Linux. Axion's PDF, web search and image tools aren't used inside an Antigravity reply.", Modifier.padding(start = 30.dp, end = 30.dp, top = 10.dp))
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingsRow(Gk.brain, "Model", value = store.model.ifBlank { "Antigravity default" }, onClick = { scope.launch { runCatching { g.agyProbe.models() } } })
            SettingsRow(Gk.usage, "Quota", "Used, left and reset time per account") { nav(Route.Quota) }
        }
    }

    if (showLogin) LoginSheet(vm, forceLogin) { showLogin = false; g.agyLogin.reset() }
    manage?.let { a ->
        AxionSheet({ manage = null }) {
            Text(a.label.ifBlank { a.email }, style = Type.title, color = p.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            Note(a.email, Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            com.mazharansari.axion.ui.chat.SheetRow(Gk.check, "Make active", onClick = { g.agy.setActive(a.email); manage = null })
            com.mazharansari.axion.ui.chat.SheetRow(Gk.refresh, "Check quota now", onClick = { g.agyProbe.probeLater(a); manage = null })
            if (a.authExpiredAt > 0) com.mazharansari.axion.ui.chat.SheetRow(Gk.person, "Sign in again", onClick = { manage = null; showLogin = true; scope.launch { g.agyLogin.begin() } })
            com.mazharansari.axion.ui.chat.SheetRow(if (a.enabled) Gk.eyeOff else Gk.eye, if (a.enabled) "Turn off" else "Turn on", onClick = { g.agy.setEnabled(a.email, !a.enabled); manage = null })
            com.mazharansari.axion.ui.chat.SheetRow(Gk.trash, "Remove account", color = Red, onClick = { g.agy.remove(a.email); manage = null })
        }
    }
}

@Composable
private fun LoginSheet(vm: ChatViewModel, force: AgyLoginState?, onDismiss: () -> Unit) {
    val g = vm.g; val p = P; val ctx = LocalContext.current
    val live by g.agyLogin.state.collectAsState()
    val st = force ?: live
    var code by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AxionSheet({ if (st !is AgyLoginState.AwaitingCode && st !is AgyLoginState.Completing) onDismiss() else { g.agyLogin.cancel(); onDismiss() } }) {
        Text("Add Antigravity account", style = Type.title, color = p.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        Note("Sign in with the Google account you want to add. Axion never sees your password or token — Google gives a one-time code that you paste here.", Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(14.dp))
        @Composable fun Step(n: Int, title: String, active: Boolean, done: Boolean, content: @Composable ColumnScope.() -> Unit = {}) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(if (done) Green else if (active) p.text else p.text3.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    if (done) GkIcon(Gk.check, null, 16.dp, Color.White) else Text("$n", color = if (active) p.bg else p.text2, style = Type.small.copy(fontWeight = FontWeight.Bold))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) { Text(title, style = Type.row.copy(fontSize = 16.sp), color = if (active || done) p.text else p.text3); content() }
            }
        }
        val urlReady = st is AgyLoginState.AwaitingCode || st is AgyLoginState.Completing || st is AgyLoginState.SignedIn
        Step(1, "Open Google sign-in", active = st is AgyLoginState.Starting || st is AgyLoginState.AwaitingCode, done = st is AgyLoginState.Completing || st is AgyLoginState.SignedIn) {
            when (st) {
                is AgyLoginState.Starting -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), color = p.text, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Note(st.message) }
                is AgyLoginState.AwaitingCode -> Column(Modifier.padding(top = 8.dp)) {
                    Text(st.url.take(64) + "…", style = Type.mono.copy(fontSize = 11.5.sp), color = p.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    SmallButton("Open in browser", filled = true) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(st.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }
                else -> {}
            }
        }
        Step(2, "Paste the code Google shows", active = st is AgyLoginState.AwaitingCode, done = st is AgyLoginState.SignedIn || st is AgyLoginState.Completing) {
            if (st is AgyLoginState.AwaitingCode) Column(Modifier.padding(top = 8.dp)) {
                Field(code, { code = it; err = null }, "4/0Ab…", mono = true)
                err?.let { Text(it, style = Type.small, color = Red, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(10.dp))
                SmallButton("Continue", filled = true, enabled = code.isNotBlank()) { runCatching { g.agyLogin.submitCode(code) }.onFailure { err = it.message } }
            }
            if (st is AgyLoginState.Completing) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), color = p.text, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Note(st.message) }
        }
        Step(3, if (st is AgyLoginState.SignedIn) "Connected${st.email?.let { " as $it" } ?: ""}" else "Account added", active = false, done = st is AgyLoginState.SignedIn)
        if (st is AgyLoginState.Error) Text(st.message, style = Type.small, color = Red, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            when (st) {
                is AgyLoginState.SignedIn -> BigButton("Done") { onDismiss() }
                is AgyLoginState.Error, AgyLoginState.Idle -> BigButton(if (st is AgyLoginState.Error) "Try again" else "Start sign-in", enabled = g.devtools.isInstalled("agy")) { kotlinx.coroutines.GlobalScope.launch { g.agyLogin.begin() } }
                else -> {}
            }
            if (!urlReady && !g.devtools.isInstalled("agy")) Note("Antigravity is being set up — sign-in starts when it's ready.", Modifier.padding(top = 8.dp))
        }
    }
}

// ───────────────────────────── Quota dashboard ─────────────────────────────

@Composable
fun QuotaScreen(vm: ChatViewModel, back: () -> Unit, nav: (Route) -> Unit) {
    val g = vm.g; val p = P
    val v by g.quota.version.collectAsState()
    val store by g.agy.store.collectAsState()
    val keysList by g.connectors.list.collectAsState()
    val probing by g.agyProbe.running.collectAsState()
    val m0 by produceState<com.mazharansari.axion.quota.QuotaModel?>(null, v, store, keysList) { value = withContext(Dispatchers.IO) { runCatching { g.quota.model() }.getOrNull() } }
    val m = m0 ?: remember { com.mazharansari.axion.quota.QuotaModel.empty() }
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<KeyRow?>(null) }
    var rawFor by remember { mutableStateOf<AgyRow?>(null) }
    LaunchedEffect(Unit) { runCatching { g.quota.refreshOpenRouter() } }

    Column(Modifier.fillMaxSize().background(p.page2)) {
        ScreenHeader("Quota", back) {
            if (probing.isNotEmpty()) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), color = p.text, strokeWidth = 2.dp)
            IconTap(Gk.refresh, "Refresh", 22.dp, p.text, 44.dp) { scope.launch { runCatching { g.quota.refreshOpenRouter() }; g.agyProbe.probeAllLater() } }
        }
        Column(Modifier.weight(1f).verticalScrollCompat().navigationBarsPadding().padding(bottom = 28.dp)) {
            Text("Updated ${timeOf(m.updatedAt)}", style = Type.small, color = p.text3, modifier = Modifier.padding(start = 30.dp, bottom = 4.dp))

            // ── Antigravity ──
            Row(Modifier.padding(start = 30.dp, end = 22.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ANTIGRAVITY", style = Type.small.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, fontSize = 13.sp), color = p.text2, modifier = Modifier.weight(1f))
                Text("Auto", style = Type.small, color = p.text2); Spacer(Modifier.width(8.dp))
                GrokSwitch(store.autoSwitch) { g.agy.setAutoSwitch(it) }
            }
            if (m.agy.isEmpty()) Card {
                Text("No Antigravity account yet", style = Type.row, color = p.text)
                Note("Antigravity needs a one-time download: ~${LinuxBase.FIRST_DOWNLOAD_MB} MB (Linux) + ~53 MB (Antigravity CLI).", Modifier.padding(vertical = 8.dp))
                SmallButton("Set up", filled = true) { nav(Route.Antigravity) }
            } else {
                Card {
                    val total = m.agy.count { it.account.enabled }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Combined", style = Type.small, color = p.text2, modifier = Modifier.weight(1f))
                        TagChip(Tag.LIVE)
                    }
                    Text("${num(m.agyCombined)} of $total account${if (total != 1) "s" else ""} left", style = Type.title.copy(fontSize = 22.sp), color = p.text, modifier = Modifier.padding(top = 2.dp))
                    QuotaBar(if (total > 0) m.agyCombined / total else null, Modifier.padding(vertical = 10.dp), 8.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Today: ${m.agyRunsToday} runs · ${tokens(m.agyTokensToday)} tokens", style = Type.small, color = p.text, modifier = Modifier.weight(1f)); TagChip(Tag.COUNTED)
                    }
                    Text(m.agyMoreRuns?.let { "≈ ${it.toInt()} more runs like your recent ones" } ?: "≈ more-runs estimate: not enough data yet (needs 3 measured runs)", style = Type.small, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                    if (m.agyUnknown > 0) Note("${m.agyUnknown} account(s) not checked yet — tap ⟳", Modifier.padding(top = 4.dp))
                    Note("Antigravity resets every 5 hours (AI Pro) or every week (free) — there is no daily limit.", Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(10.dp))
                SettingsGroup {
                    m.agy.forEach { r -> AgyQuotaRow(r, store.activeEmail, probing.contains(r.account.email)) { rawFor = r } }
                    SettingsRow(Gk.plus, "Add Antigravity account") { nav(Route.Antigravity) }
                }
            }

            // ── API keys ──
            Text("API KEYS", style = Type.small.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, fontSize = 13.sp), color = p.text2, modifier = Modifier.padding(start = 30.dp, top = 26.dp, bottom = 10.dp))
            Card {
                val t = m.totals
                // P1-10: every number carries its source — the chip is the weakest source among the keys feeding the total, never a guess.
                Row(verticalAlignment = Alignment.Bottom) { Text("Today (all keys)", style = Type.small, color = p.text2, modifier = Modifier.weight(1f)); TagChip(t.usedTag) }
                if (t.counted > 0) {
                    Text("${num(t.used)} of ${num(t.limit)} used", style = Type.title.copy(fontSize = 22.sp), color = p.text, modifier = Modifier.padding(top = 2.dp))
                    QuotaBar(if (t.limit > 0) t.left / t.limit else null, Modifier.padding(vertical = 10.dp), 8.dp)
                    Text("${num(t.left)} left · ≈ ${num(t.left)} more messages${m.callsPerChat?.let { " · ≈ ${num(Math.floor(t.left / it))} chats" } ?: ""}", style = Type.small, color = p.text)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("used", style = Type.tiny, color = p.text3); TagChip(t.usedTag)
                        Text("limit", style = Type.tiny, color = p.text3); TagChip(t.limitTag)
                        Text("left", style = Type.tiny, color = p.text3); TagChip(t.leftTag)
                    }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (t.tokensTag == Tag.COUNTED) "${tokens(t.tokens)} tokens today" else "Tokens not reported by the provider yet", style = Type.small, color = p.text)
                        TagChip(t.tokensTag)
                    }
                    Text(when (val r = m.runOut) {
                        is QuotaMath.RunOut.Before -> "At this pace: runs out in ~${QuotaMath.shortDuration(r.ms)}"
                        is QuotaMath.RunOut.LastsPastReset -> "At this pace: lasts past reset${r.resetAt?.let { " (${timeOf(it)})" } ?: ""}"
                        QuotaMath.RunOut.NotEnoughData -> "Run-out estimate after a few more messages"
                    }, style = Type.small, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                } else Text(if (m.keys.isEmpty()) "No keys yet" else "Limits not known yet", style = Type.title.copy(fontSize = 20.sp), color = p.text, modifier = Modifier.padding(top = 2.dp))
                if (t.unknown > 0) Note("${t.counted} key${if (t.counted != 1) "s" else ""} in the total · ${t.unknown} key${if (t.unknown != 1) "s" else ""}: limit not shared by provider", Modifier.padding(top = 6.dp))
                val sharedGroups = m.keys.filter { it.providerId == "gemini" && it.quotaGroup.isNotBlank() }.groupBy { it.quotaGroup }.filter { it.value.size > 1 }.keys
                if (sharedGroups.isNotEmpty()) Note("Shared Google-project quota counted once (group${if (sharedGroups.size != 1) "s" else ""}: ${sharedGroups.joinToString(", ")}).", Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                m.keys.forEach { k -> KeyQuotaRow(k) { detail = k } }
                val freeCalls by produceState(0, v) { value = withContext(Dispatchers.IO) { runCatching { g.quota.store.count("free", System.currentTimeMillis() - 86_400_000L) }.getOrDefault(0) } }
                FreeRow(freeCalls)
                SettingsRow(Gk.key, "Export / import keys", "Encrypted file with your own password") { nav(Route.KeyBackup) }
            }
            if (m.keys.any { it.providerId == "gemini" }) Note("Gemini keys from the same Google project share one quota — tap a Gemini key and give same-project keys the same group name so the total counts them once. More quota = keys from different projects.", Modifier.padding(start = 30.dp, end = 30.dp, top = 12.dp))
        }
    }
    detail?.let { k -> KeyDetailSheet(k, onGroup = { g.connectors.setQuotaGroup(k.connectorId, it) }) { detail = null } }
    rawFor?.let { r ->
        AxionSheet({ rawFor = null }) {
            Text(r.account.label.ifBlank { r.account.email }, style = Type.title, color = p.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            Note("Where this comes from: agy's own /usage answer (last 3). Shown raw when it couldn't be read.", Modifier.padding(horizontal = 24.dp))
            Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (p.isDark) Color(0xFF111111) else Color(0xFFF1F1F1)).padding(12.dp)) {
                Text(r.raw.firstOrNull()?.take(1800) ?: "Not checked yet.", style = Type.mono.copy(fontSize = 11.5.sp, lineHeight = 16.sp), color = p.text2)
            }
        }
    }
}

private fun tokens(n: Long) = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 1000 -> "%.1fk".format(n / 1e3); else -> "$n" }

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

@Composable
private fun AgyQuotaRow(r: AgyRow, active: String?, probing: Boolean, onRaw: () -> Unit) {
    val p = P; val a = r.account; val now = System.currentTimeMillis()
    val tap = rememberTap(onRaw)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card).clickable(onClick = tap).padding(horizontal = 16.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (a.email.equals(active, true)) Green else Color.Transparent).border(1.5.dp, if (a.email.equals(active, true)) Green else p.text3, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(a.label.ifBlank { a.email.substringBefore('@') }, style = Type.row.copy(fontSize = 16.sp), color = p.text)
            Spacer(Modifier.width(8.dp))
            Text(a.email, style = Type.small.copy(fontSize = 13.sp), color = p.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (probing) CircularProgressIndicator(Modifier.size(14.dp), color = p.text, strokeWidth = 1.5.dp)
            else when {
                a.authExpiredAt > 0 -> Badge("Sign-in expired", Amber)
                a.ineligibleAt > 0 -> Badge("Not eligible", Red)
                a.email.equals(active, true) -> Text("Active", style = Type.tiny, color = Green)
            }
        }
        val plan = when (a.tier) { "pro" -> "AI Pro · every 5 h + weekly cap"; "ultra" -> "Ultra · every 5 h"; "free" -> "Free · resets weekly"; else -> null }
        plan?.let { Text(it, style = Type.tiny, color = p.text3, modifier = Modifier.padding(start = 19.dp, top = 2.dp)) }
        if (r.buckets.isEmpty()) {
            Text(if (a.cooldownUntil > now) "Limit hit · back at ${timeOf(a.cooldownUntil)}" else if (r.raw.isNotEmpty()) "Couldn't read agy's answer — tap to see it" else "Not checked yet · tap ⟳",
                style = Type.small.copy(fontSize = 13.sp), color = if (a.cooldownUntil > now) Amber else p.text2, modifier = Modifier.padding(start = 19.dp, top = 6.dp))
        }
        r.buckets.forEach { b ->
            Row(Modifier.padding(start = 19.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(b.label.substringAfterLast(" · ").ifBlank { "Quota" }, style = Type.small.copy(fontSize = 13.sp), color = if (b.disabled) p.text3 else p.text, modifier = Modifier.width(78.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                QuotaBar(if (b.disabled) null else b.remainingFraction, Modifier.weight(1f), 6.dp)
                Text(if (b.disabled) "Off" else b.remainingFraction?.let { "${(it * 100).toInt()}%" } ?: "—", style = Type.small.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = p.text, modifier = Modifier.width(44.dp).padding(start = 8.dp))
            }
            val reset = b.resetAt?.let { (if ((b.remainingFraction ?: 1.0) <= 0.0001) "back at " else "resets ") + timeOf(it) } ?: ""
            val win = when (b.window) { "5h" -> "5-hour window"; "week" -> "weekly"; else -> "" }
            // P1-10: every bucket percentage carries its source — agy's own live /usage answer.
            Row(Modifier.padding(start = 97.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(listOf(win, reset).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Live from agy /usage" }, style = Type.tiny, color = p.text3)
                TagChip(Tag.LIVE)
            }
        }
    }
}

@Composable
private fun KeyQuotaRow(k: KeyRow, onClick: () -> Unit) {
    val p = P; val tap = rememberTap(onClick)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card).clickable(onClick = tap).padding(horizontal = 16.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo(k.providerId, 28.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(k.providerName, style = Type.row.copy(fontSize = 16.sp), color = p.text)
                    Spacer(Modifier.width(6.dp)); Text(k.keyHint.takeLast(5), style = Type.small.copy(fontSize = 13.sp), color = p.text3)
                }
                Text(if (k.models > 1) "${k.models} models · each has its own daily bucket" else k.model, style = Type.tiny, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val d = k.day
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            QuotaBar(d.limit.value?.let { l -> if (l > 0) (d.left.value ?: (l - (d.used.value ?: 0.0))) / l else null }, Modifier.weight(1f), 6.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (d.limit.value != null) "${num(d.used.value)}/${num(d.limit.value)}" else "${num(d.used.value)} today", style = Type.small.copy(fontWeight = FontWeight.Medium), color = p.text)
        }
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TagChip(d.used.tag)
            if (d.limit.tag != d.used.tag) { Text("limit", style = Type.tiny, color = p.text3); TagChip(d.limit.tag) }
            Text(buildString {
                if (d.left.value != null) append("${num(d.left.value)} left")
                d.resetAt?.let { if (isNotEmpty()) append(" · "); append(if ((d.left.value ?: 1.0) <= 0.0) "back at ${timeOf(it)}" else "resets ${timeOf(it)}") }
                k.minute?.let { r -> if (r.unit == "tok" && r.limit != null) { if (isNotEmpty()) append(" · "); append("TPM ${tokens(((r.limit - (r.remaining ?: 0.0))).toLong())}/${tokens(r.limit.toLong())}") } }
            }, style = Type.tiny, color = p.text2, maxLines = 1)
            // P1-10: the per-minute figure comes from the provider's live headers.
            if (k.minute?.unit == "tok" && k.minute?.limit != null) TagChip(Tag.LIVE)
        }
        // P1-10 (ME-4): real counted tokens for this key; UNKNOWN when the provider never reported usage — never shown as 0.
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (d.tokens.value != null) "${tokens(d.tokens.value!!.toLong())} tokens today" else "Tokens not reported by the provider yet", style = Type.tiny, color = p.text2)
            TagChip(d.tokens.tag)
        }
        if (k.quotaGroup.isNotBlank()) Text("Shares one quota with group “${k.quotaGroup}”", style = Type.tiny, color = p.text2, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun FreeRow(calls: Int) {
    val p = P
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(if (p.isDark) Color(0xFF2C2C2C) else Color(0xFFE6E6E6)), contentAlignment = Alignment.Center) { GkIcon(Gk.sparkles, null, 16.dp, p.text) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Free (slow)", style = Type.row.copy(fontSize = 16.sp), color = p.text)
                Text("Built in, no key · only when nothing else is left", style = Type.tiny, color = p.text2)
            }
            Text("$calls today", style = Type.small.copy(fontWeight = FontWeight.Medium), color = p.text)
        }
        Row(Modifier.padding(top = 6.dp, start = 40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TagChip(Tag.COUNTED); Text("limit ~1 reply / 45 s (measured 1 Nov)", style = Type.tiny, color = p.text2)
        }
    }
}

@Composable
private fun KeyDetailSheet(k: KeyRow, onGroup: (String) -> Unit = {}, onDismiss: () -> Unit) {
    val p = P
    AxionSheet(onDismiss) {
        Text("${k.providerName} ${k.keyHint}", style = Type.title, color = p.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        Note(k.note, Modifier.padding(horizontal = 24.dp))
        if (k.providerId == "gemini") {
            // P1-10 (ME-3): Gemini keys from the same Google project share one quota — same group name = counted once in the totals.
            var group by remember(k.connectorId) { mutableStateOf(k.quotaGroup) }
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("Shared quota group", style = Type.small.copy(fontWeight = FontWeight.Medium), color = p.text)
                Spacer(Modifier.height(6.dp))
                Field(group, { group = it; onGroup(it) }, "Same Google project? Same name, e.g. project-a")
                Text("Keys with the same name share one quota and are counted once. Leave blank when this key is on its own project.", style = Type.tiny, color = p.text2, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        val rows = if (k.perModel.isNotEmpty()) k.perModel else listOf(k.model to k.day)
        rows.forEach { (model, d) ->
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(model, style = Type.small.copy(fontWeight = FontWeight.Medium), color = p.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (d.limit.value != null) "${num(d.used.value)}/${num(d.limit.value)}" else "${num(d.used.value)} today", style = Type.small, color = p.text)
                }
                QuotaBar(d.limit.value?.let { l -> if (l > 0) (d.left.value ?: 0.0) / l else null }, Modifier.padding(vertical = 6.dp), 5.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("used", style = Type.tiny, color = p.text3); TagChip(d.used.tag); Text("limit", style = Type.tiny, color = p.text3); TagChip(d.limit.tag)
                    d.resetAt?.let { Text("resets ${timeOf(it)}", style = Type.tiny, color = p.text2) }
                }
                // P1-10 (ME-4): real counted tokens for this model; UNKNOWN when the provider never reported usage.
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("tokens", style = Type.tiny, color = p.text3); TagChip(d.tokens.tag)
                    Text(if (d.tokens.value != null) "${tokens(d.tokens.value!!.toLong())} today" else "not reported by the provider", style = Type.tiny, color = p.text2)
                }
            }
        }
        k.minute?.raw?.takeIf { it.isNotBlank() }?.let {
            Text("Where this comes from — last headers:", style = Type.small, color = p.text2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            Text(it.take(900), style = Type.mono.copy(fontSize = 11.sp, lineHeight = 15.sp), color = p.text3, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}

// ───────────────────────────── Key export / import ─────────────────────────────

@Composable
fun KeyBackupScreen(vm: ChatViewModel, back: () -> Unit) {
    val g = vm.g; val p = P; val ctx = LocalContext.current
    val keys by g.connectors.list.collectAsState()
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }; var pw2 by remember { mutableStateOf("") }
    var ipw by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var warn by remember { mutableStateOf(vm.debugExportWarning) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) { busy = true; scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val r = runCatching { val text = KeyBackup.export(g.connectors.exportEntries(), pw); ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }; "Saved ${keys.size} keys. Keep the password safe — without it the file can't be opened." }.getOrElse { it.message }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { msg = r; pw = ""; pw2 = ""; busy = false }
        } }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // v0.5.2 bug hunt: picking a huge file read it all into memory on the main thread (ANR / out of memory).
            val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { s -> val b = s.readNBytesCompat(2 * 1024 * 1024 + 1); if (b.size > 2 * 1024 * 1024) null else b.decodeToString() } }.getOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { if (text == null) msg = "This isn't an Axion keys file" else pendingImport = text }
        }
    }
    SettingsPage("Export / import keys", back) {
        Note("Move your API keys to a new phone or a fresh install. The file is locked with a password you choose (AES-256). Antigravity sign-ins are not included — sign in again on the new phone.",
            Modifier.padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 12.dp))
        SectionLabel("Export")
        Card {
            Text("${keys.size} key${if (keys.size != 1) "s" else ""}: " + keys.groupBy { it.provider.name }.entries.joinToString { "${it.key}${if (it.value.size > 1) " ×${it.value.size}" else ""}" }.ifBlank { "none yet" },
                style = Type.small, color = p.text2, modifier = Modifier.padding(bottom = 12.dp))
            Field(pw, { pw = it; msg = null }, "Password (${KeyBackup.MIN_PASSWORD}+ characters)", password = true)
            Spacer(Modifier.height(8.dp))
            Field(pw2, { pw2 = it; msg = null }, "Repeat password", password = true)
            if (pw2.isNotEmpty() && pw != pw2) Text("Passwords don't match", style = Type.small, color = Red, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            SmallButton(if (busy) "Encrypting…" else "Save encrypted file", filled = true, enabled = !busy && keys.isNotEmpty() && pw.length >= KeyBackup.MIN_PASSWORD && pw == pw2) { warn = true }
        }
        SectionLabel("Import")
        Card {
            if (pendingImport == null) {
                Note("Pick an axion-keys file, then enter its password.", Modifier.padding(bottom = 12.dp))
                SmallButton("Choose file") { open.launch(arrayOf("application/json", "*/*")) }
            } else {
                Text("File selected", style = Type.row, color = p.text, modifier = Modifier.padding(bottom = 10.dp))
                Field(ipw, { ipw = it; msg = null }, "File password", password = true)
                Spacer(Modifier.height(12.dp))
                SmallButton(if (busy) "Unlocking…" else "Import keys", filled = true, enabled = ipw.isNotEmpty() && !busy) {
                    val file = pendingImport ?: return@SmallButton
                    busy = true
                    scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        val r = runCatching { KeyBackup.import(file, ipw) }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            msg = r.map { val n = g.connectors.importEntries(it); pendingImport = null; ipw = ""; if (n == 0) "Those keys are already here." else "Added $n key${if (n != 1) "s" else ""}." }.getOrElse { it.message }
                            busy = false
                        }
                    }
                }
            }
        }
        msg?.let { Text(it, style = Type.small, color = if (it.startsWith("Saved") || it.startsWith("Added")) Green else Red, modifier = Modifier.padding(horizontal = 30.dp, vertical = 14.dp)) }
        Note("How it's locked: AES-256-GCM with a key made from your password (PBKDF2, 600,000 rounds, random salt). Without the password the file is just noise, and a wrong password reveals nothing.",
            Modifier.padding(start = 30.dp, end = 30.dp, top = 6.dp, bottom = 24.dp))
    }
    // v0.5.2: say what this file is before it exists — it's the user's keys, and anyone with it AND the password can use them.
    if (warn) AlertDialog(onDismissRequest = { warn = false }, containerColor = p.card, shape = RoundedCornerShape(24.dp),
        title = { Text("Before you save this file", style = Type.title, color = p.text) },
        text = { Text("This file holds all ${keys.size} of your API keys, locked with your password.\n\n• Keep it for yourself (your Google Drive, a USB stick).\n• Don't post it or send it to anyone. Whoever has the file AND the password can use your keys.\n• Never send the password together with the file.\n• If it ever leaks, delete the keys at Google, Groq, etc. and make new ones.",
            style = Type.body, color = p.text2) },
        confirmButton = { TextButton({ warn = false; save.launch("axion-keys-${SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date())}.json") }) { Text("I understand, save", style = Type.label, color = p.text) } },
        dismissButton = { TextButton({ warn = false }) { Text("Cancel", style = Type.label, color = p.text2) } })
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0
    while (total < max) { val n = read(buf, 0, minOf(buf.size, max - total)); if (n < 0) break; out.write(buf, 0, n); total += n }
    return out.toByteArray()
}
