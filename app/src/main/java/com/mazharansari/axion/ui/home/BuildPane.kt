package com.mazharansari.axion.ui.home

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import com.mazharansari.axion.ui.chat.Composer
import com.mazharansari.axion.ui.chat.LocalHaze
import com.mazharansari.axion.ui.chat.PlusPopover
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

private data class Template(val icon: Int, val tint: Color, val name: String, val prompt: String)

private val templates = listOf(
    Template(Gk.paintBrush, Color(0xFFFF5A6A), "Photo booth", "a photo booth web app with camera, filters and download"),
    Template(Gk.clipboard, Color(0xFFE356D8), "Kanban board", "a kanban board web app with drag and drop and local storage"),
    Template(Gk.speaker, Color(0xFF9B7BFF), "Audio visualizer", "an audio visualizer web app using the microphone"),
    Template(Gk.cube, Color(0xFF4DA3FF), "3D maze", "a 3D maze game in one HTML file with keyboard and swipe controls"),
    Template(Gk.circleCheck, Color(0xFF3DDC97), "Habit tracker", "a habit tracker web app with streaks and local storage"),
    Template(Gk.globe, Color(0xFF39C6F4), "World map", "an interactive world map web app with country facts"),
    Template(Gk.editFile, Color(0xFFFFC043), "Notes app", "a clean notes web app with search and local storage"),
    Template(Gk.barChart, Color(0xFF7CD4A0), "Revenue dashboard", "a revenue dashboard web app with charts and sample data"),
    Template(Gk.rocket, Color(0xFFFF8A3D), "Endless runner", "an endless runner game in one HTML file"),
    Template(Gk.building, Color(0xFFFF7EB6), "Store landing page", "a premium landing page for a Korean skincare store, mobile-first"),
    Template(Gk.calculator, Color(0xFFB0B0FF), "Calculator", "a beautiful calculator web app"),
)

/**
 * v0.5 Build tab (addendum 5): your projects, "New project", templates, and a composer that starts a new project from a
 * sentence. The first time Build opens, the developer-tools popup offers optional toolchains (addendum 2).
 * Build runs on Antigravity only — never on API keys.
 */
@Composable
fun BuildPane(vm: ChatViewModel, onSendToAsk: () -> Unit, navigate: (Route) -> Unit = {}) {
    val p = P
    var text by remember { mutableStateOf("") }
    var plus by remember { mutableStateOf(false) }
    val haze = remember { HazeState() }
    val settings by vm.g.settings.state.collectAsState()
    val projects by vm.g.build.store.list.collectAsState()
    val ready = remember(vm.g.agy.store.collectAsState().value, vm.g.devtools.records.collectAsState().value, vm.g.linux.state.collectAsState().value) { vm.buildReady() }
    var newProject by remember { mutableStateOf(vm.debugNewProject) }
    var intro by remember { mutableStateOf(vm.debugBuildIntro || !settings.buildIntroShown) }
    fun go(name: String, prompt: String) {
        if (!ready) { vm.toast = "Set up Antigravity first (the two steps above)."; return }
        vm.createProject(name, if (Regex("(?i)\\b(android|apk)\\b").containsMatchIn(prompt)) "android" else "web",
            "Build $prompt. Make it polished and mobile-first, run and check it, then tell me how to open it.")
    }
    CompositionLocalProvider(LocalHaze provides haze) {
        var boxBottomPx by remember { mutableFloatStateOf(0f) }
        var composerTopPx by remember { mutableFloatStateOf(0f) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        Box(Modifier.fillMaxSize().imePadding().onGloballyPositioned { boxBottomPx = it.boundsInRoot().bottom }) {
            Column(Modifier.fillMaxSize().haze(haze)) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (projects.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(58.dp).clip(CircleShape).background(p.surface), contentAlignment = Alignment.Center) { GkIcon(Gk.hammer, null, 26.dp, p.text) }
                            Spacer(Modifier.height(14.dp))
                            Text("Build apps and sites", style = Type.body.copy(fontSize = 17.sp), color = p.text)
                            Text("Each project gets its own folder, files, chat and preview.", style = Type.small, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                        }
                    } else Spacer(Modifier.height(4.dp))
                    if (!ready) BuildSetupCard(vm, navigate)
                    Row(Modifier.padding(start = 18.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Projects", style = Type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = p.text, modifier = Modifier.weight(1f))
                        val tap = rememberTap { newProject = true }
                        Row(Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(p.text).clickable(onClick = tap).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            GkIcon(Gk.plus, null, 16.dp, p.bg); Spacer(Modifier.width(6.dp))
                            Text("New project", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = p.bg)
                        }
                    }
                    if (projects.isEmpty()) Text("No projects yet. Start one, or pick a template below.", style = Type.small, color = p.text2, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
                    projects.forEach { pr -> ProjectRow(vm, pr) { vm.openProject(pr.id) } }
                    Text("Start from a template", style = Type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = p.text, modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        templates.forEach { t ->
                            val tap = rememberTap { go(t.name, t.prompt) }
                            Row(Modifier.height(50.dp).clip(RoundedCornerShape(50)).background(p.surface).clickable(onClick = tap).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                GkIcon(t.icon, null, 20.dp, t.tint); Spacer(Modifier.width(10.dp))
                                Text(t.name, style = Type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = p.text)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Spacer(Modifier.height(6.dp))
                Composer(
                    text = text, onText = { text = it }, placeholder = if (ready) "Describe a new project…" else "Set up Antigravity to Build", modeLabel = "Build", running = vm.running,
                    attachments = vm.pending, onRemoveAttachment = { vm.pending.remove(it) }, listening = false, level = { 0f }, partial = "",
                    onPlus = { plus = !plus }, onMode = {}, onMic = {},
                    onSend = { val t = text.trim(); if (t.isNotEmpty()) { text = ""; go(t.lineSequence().first().take(36).replaceFirstChar { it.uppercase() }, t) } },
                    onStop = { vm.stop() }, onVoiceCall = {},
                    modifier = Modifier.navigationBarsPadding().padding(bottom = 6.dp).onGloballyPositioned { composerTopPx = it.boundsInRoot().top }, plusOpen = plus, compact = true,
                )
            }
            PlusPopover(plus, { plus = false }, bottomGap = with(density) { (boxBottomPx - composerTopPx).coerceAtLeast(0f).toDp() } + 8.dp,
                onCamera = { navigate(Route.Files) }, onGallery = { navigate(Route.Files) }, onFiles = { navigate(Route.Files) },
                onSkills = { navigate(Route.Skills) }, onConnectors = { navigate(Route.Connectors) })
        }
    }
    if (newProject) com.mazharansari.axion.ui.build.NewProjectSheet(onDismiss = { newProject = false; vm.debugNewProject = false }) { name, kind -> newProject = false; vm.createProject(name, kind) }
    if (intro) com.mazharansari.axion.ui.build.BuildToolsPopup(vm, onDismiss = { intro = false; vm.debugBuildIntro = false; vm.g.settings.update { it.copy(buildIntroShown = true) } })
}

@Composable
private fun ProjectRow(vm: ChatViewModel, pr: com.mazharansari.axion.data.Project, onClick: () -> Unit) {
    val p = P
    val tap = rememberTap(onClick)
    val files by produceState(-1, pr.id, pr.updatedAt) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.mazharansari.axion.build.ProjectFiles.fileCount(vm.g.build.folder(pr)) } }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surface).clickable(onClick = tap).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (pr.kind == "android") Color(0xFF1E3A24) else Color(0xFF1C2B3F)), contentAlignment = Alignment.Center) {
            GkIcon(if (pr.kind == "android") Gk.android else Gk.globe, null, 21.dp, if (pr.kind == "android") Color(0xFF7CD4A0) else Color(0xFF6FB4FF))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(pr.name, style = Type.row, color = p.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val ago = if (System.currentTimeMillis() - pr.updatedAt < 60_000) "just now" else android.text.format.DateUtils.getRelativeTimeSpanString(pr.updatedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
            Text(com.mazharansari.axion.ui.build.kindLabel(pr.kind) + (if (files >= 0) " · $files file${if (files == 1) "" else "s"}" else "") + " · $ago", style = Type.small, color = p.text2, maxLines = 1)
            // v0.8.0 (Phase C): the owner asked for "Working" under a project whose turn is still
            // running after he leaves it — the RunRegistry knows, even in the background.
            val working = vm.g.runs.active.collectAsState().value.any { it.kind == com.mazharansari.axion.work.RunRegistry.Kind.BUILD && it.ownerId == pr.id }
            if (working) Text("Working", style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFF3FB950))
        }
        if (pr.deploy?.url != null) Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0x293FB950)).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text("Live", style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFF3FB950))
        }
        Spacer(Modifier.width(6.dp))
        GkIcon(Gk.chevronRight, null, 18.dp, p.text3)
    }
}

/**
 * Shown instead of the templates when Build can't run yet (no Linux / no agy / no signed-in account): it says exactly
 * what's missing, in his words, and takes him there. Build never falls back to API keys.
 */
@Composable
private fun BuildSetupCard(vm: ChatViewModel, navigate: (Route) -> Unit) {
    val p = P; val g = vm.g
    val linuxOk = g.linux.state.collectAsState().value == com.mazharansari.axion.linux.LinuxState.Ready
    val agyOk = g.devtools.records.collectAsState().value["agy"]?.version != null
    val accounts = g.agy.store.collectAsState().value.accounts.count { it.enabled && g.agy.hasToken(it) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.surface).padding(18.dp)) {
        Text("Build runs on Antigravity", style = Type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = p.text)
        Text("Set up these two things first, then build apps and websites here.", style = Type.small, color = p.text2, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        @Composable fun step(n: Int, done: Boolean, title: String, sub: String, onClick: () -> Unit) {
            val tap = rememberTap(onClick)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = tap).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(if (done) Color(0xFF3DDC97) else p.chip), contentAlignment = Alignment.Center) {
                    if (done) GkIcon(Gk.check, null, 16.dp, Color.Black) else Text("$n", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = Type.row, color = p.text)
                    Text(sub, style = Type.small, color = p.text2)
                }
                if (!done) GkIcon(Gk.chevronRight, null, 18.dp, p.text2)
            }
        }
        step(1, linuxOk && agyOk, "Install Developer tools", if (linuxOk && agyOk) "Linux + Antigravity CLI ready" else "Linux (≈80 MB) + Antigravity CLI (≈53 MB), one time") { navigate(Route.DevTools) }
        step(2, accounts > 0, "Add an Antigravity account", if (accounts > 0) "$accounts account${if (accounts > 1) "s" else ""} signed in" else "Sign in with Google — one or more accounts") { navigate(Route.Antigravity) }
    }
}
