package com.mazharansari.axion.ui.build

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.mazharansari.axion.build.*
import com.mazharansari.axion.data.Project
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import com.mazharansari.axion.ui.chat.AxionSheet
import com.mazharansari.axion.ui.chat.SheetRow
import com.mazharansari.axion.ui.chat.highlight
import com.mazharansari.axion.ui.chat.langOf
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.home.ChatPane
import com.mazharansari.axion.ui.settings.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal val BGreen = Color(0xFF3FB950)
internal val BAmber = Color(0xFFF5A524)
internal val BRed = Color(0xFFF04438)

/** One Build project: Chat (Antigravity) | Files (tree + editor) | Preview (WebView, publish, APK). */
@Composable
fun ProjectWorkspace(vm: ChatViewModel, navigate: (Route) -> Unit) {
    val g = vm.g
    val projects by g.build.store.list.collectAsState()
    val project = projects.firstOrNull { it.id == vm.projectId } ?: return
    var tab by rememberSaveable(project.id) { mutableIntStateOf(vm.debugProjectTab.coerceAtLeast(0)) }
    LaunchedEffect(vm.debugProjectTab) { if (vm.debugProjectTab >= 0) tab = vm.debugProjectTab }
    var menu by remember { mutableStateOf(vm.debugProjectMenu) }
    var openFile by rememberSaveable(project.id) { mutableStateOf(vm.debugOpenFile) }
    var deploySheet by remember { mutableStateOf(vm.debugDeploySheet) }
    var reviewOpen by remember { mutableStateOf(false) }   // axion-p1.2
    LaunchedEffect(vm.reviewRequested) { if (vm.pendingReview?.projectId == project.id) { reviewOpen = true; tab = 0 } }
    Box(Modifier.fillMaxSize().background(P.bg)) {
        Crossfade(tab, label = "ptab") { t ->
            when (t) {
                0 -> ChatPane(vm, navigate, onBuild = {}, build = true, topInset = 46.dp, emptyContent = { ProjectEmpty(project) })
                1 -> Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.statusBarsPadding().height(118.dp))
                    val f = openFile
                    if (f == null) FilesTab(vm, project) { openFile = it }
                    else CodeEditor(vm, project, f, onClose = { openFile = null })
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.statusBarsPadding().height(118.dp))
                    PreviewTab(vm, project, onPublish = { deploySheet = true })
                }
            }
        }
        ProjectHeader(project, tab, onTab = { tab = it }, onBack = { vm.closeProject() }, onMenu = { menu = true })
        vm.pendingReview?.takeIf { it.projectId == project.id }?.let { r ->   // axion-p1.2
            PendingReviewPill(r.set.changes.size, onClick = { reviewOpen = true }, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp))
        }
    }
    if (reviewOpen && vm.pendingReview != null) ReviewChangesSheet(vm, onDismiss = { reviewOpen = false })   // axion-p1.2
    if (menu) ProjectMenuSheet(vm, project, onDismiss = { menu = false }, onPublish = { menu = false; deploySheet = true }, onFiles = { menu = false; tab = 1 })
    if (deploySheet) DeploySheet(vm, project) { deploySheet = false; vm.debugDeploySheet = false }
}

@Composable
private fun ProjectHeader(project: Project, tab: Int, onTab: (Int) -> Unit, onBack: () -> Unit, onMenu: () -> Unit) {
    val p = P
    Box(Modifier.fillMaxWidth().background(p.bg)) {   // v0.5.2: solid, like the Ask | Build bar
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 11.dp, end = 11.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleButton(Gk.chevronLeft, "All projects", onClick = onBack)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(project.name, style = Type.body.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(kindLabel(project.kind) + (project.deploy?.url?.let { " · live" } ?: ""), style = Type.small.copy(fontSize = 13.sp), color = if (project.deploy?.url != null) BGreen else p.text2, maxLines = 1)
                }
                CircleButton(Gk.more, "Project menu", onClick = onMenu)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(50)).background(if (p.isDark) Color(0xFF171717) else Color(0xFFEFEFEF)).padding(3.dp)) {
                listOf("Chat" to Gk.agent, "Files" to Gk.folder, "Preview" to Gk.eye).forEachIndexed { i, (label, icon) ->
                    val sel = i == tab
                    val tap = rememberTap { onTab(i) }
                    Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).background(if (sel) (if (p.isDark) Color(0xFF2E2E2E) else Color.White) else Color.Transparent)
                        .clickable(remember { MutableInteractionSource() }, null, onClick = tap), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        GkIcon(icon, null, 17.dp, if (sel) p.text else p.text2); Spacer(Modifier.width(6.dp))
                        Text(label, style = Type.small.copy(fontSize = 15.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium), color = if (sel) p.text else p.text2)
                    }
                }
            }
        }
    }
}

fun kindLabel(kind: String) = when (kind) { "android" -> "Android app"; "web" -> "Website"; else -> "Project" }

@Composable
private fun ProjectEmpty(project: Project) {
    val p = P
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(p.surface), contentAlignment = Alignment.Center) { GkIcon(if (project.kind == "android") Gk.android else Gk.globe, null, 26.dp, p.text) }
        Spacer(Modifier.height(14.dp))
        Text(project.name, style = Type.body.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), color = p.text)
        Text(if (project.kind == "android") "Describe the app. Antigravity writes the code here; build the APK from Preview."
            else "Describe the site. Antigravity plans it, writes the files, runs and checks them, then you preview and publish.",
            style = Type.small, color = p.text2, modifier = Modifier.padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(120.dp))
    }
}

// ───────────────────────────── Files ─────────────────────────────

@Composable
private fun FilesTab(vm: ChatViewModel, project: Project, onOpen: (String) -> Unit) {
    val p = P; val g = vm.g
    val root = remember(project.id) { g.build.folder(project) }
    var expanded by rememberSaveable(project.id) { mutableStateOf(setOf<String>()) }
    var refresh by remember { mutableIntStateOf(0) }
    val running = vm.running
    LaunchedEffect(running) { refresh++ }   // the agent just changed files
    val nodes by produceState(emptyList<ProjectFiles.Node>(), expanded, refresh, project.id) {
        value = withContext(Dispatchers.IO) {
            // auto-open top-level src/app folders the first time so the tree isn't one closed line
            ProjectFiles.tree(root, expanded)
        }
    }
    var newFile by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("/root/projects/${project.slug}", style = Type.mono.copy(fontSize = 12.sp), color = p.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconTap(Gk.refresh, "Refresh", 18.dp, p.text2) { refresh++ }
            IconTap(Gk.plus, "New file", 20.dp, p.text2) { newFile = true }
        }
        if (nodes.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                GkIcon(Gk.folder, null, 34.dp, p.text3)
                Text("No files yet", style = Type.row, color = p.text, modifier = Modifier.padding(top = 10.dp))
                Text("Ask in Chat and Antigravity creates them here.", style = Type.small, color = p.text2)
                Spacer(Modifier.height(100.dp))
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            items(nodes, key = { it.rel }) { n ->
                val tap = rememberTap { if (n.isDir) expanded = if (n.rel in expanded) expanded - n.rel else expanded + n.rel else onOpen(n.rel) }
                Row(Modifier.fillMaxWidth().clickable(onClick = tap).padding(start = (18 + n.depth * 18).dp, end = 18.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (n.isDir) GkIcon(if (n.rel in expanded) Gk.chevronDown else Gk.chevronRight, null, 14.dp, p.text3) else Spacer(Modifier.width(14.dp))
                    Spacer(Modifier.width(6.dp))
                    FileGlyph(n.file.name, n.isDir)
                    Spacer(Modifier.width(10.dp))
                    Text(n.file.name, style = Type.body.copy(fontSize = 15.sp), color = if (n.isDir && n.file.name in ProjectFiles.GENERATED) p.text2 else p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (n.isDir) "${n.childCount}" else sizeLabel(n.size), style = Type.tiny, color = p.text3)
                }
            }
        }
    }
    if (newFile) NameDialog("New file", "path/name.ext", "", onDismiss = { newFile = false }) { name ->
        newFile = false
        runCatching { val f = ProjectFiles.safeChild(root, name); if (name.endsWith("/")) f.mkdirs() else { f.parentFile?.mkdirs(); if (!f.exists()) f.writeText("") }; refresh++; if (!name.endsWith("/")) onOpen(name.trim('/')) }
            .onFailure { vm.toast = it.message }
    }
}

internal fun sizeLabel(b: Long) = when { b >= 1048576 -> "%.1f MB".format(b / 1048576.0); b >= 1024 -> "${b / 1024} KB"; else -> "$b B" }

@Composable
private fun FileGlyph(name: String, dir: Boolean) {
    val (bg, label) = when {
        dir -> Color(0xFF3A3A3A) to ""
        else -> when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> Color(0xFFE44D26) to "<>"; "css" -> Color(0xFF2965F1) to "#"; "js", "mjs" -> Color(0xFFD9B600) to "JS"; "ts", "tsx" -> Color(0xFF3178C6) to "TS"
            "json" -> Color(0xFF6D6D6D) to "{}"; "md" -> Color(0xFF555555) to "M"; "py" -> Color(0xFF3572A5) to "Py"; "kt", "kts" -> Color(0xFF7F52FF) to "Kt"
            "java" -> Color(0xFFB07219) to "J"; "xml" -> Color(0xFF0060AC) to "<>"; "php" -> Color(0xFF4F5D95) to "php"; "png", "jpg", "jpeg", "webp", "svg", "gif" -> Color(0xFF2E9E6B) to "img"
            else -> Color(0xFF444444) to "·"
        }
    }
    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(bg), contentAlignment = Alignment.Center) {
        if (dir) GkIcon(Gk.folder, null, 14.dp, Color.White) else Text(label, color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ───────────────────────────── Editor ─────────────────────────────

private class HighlightTransformation(private val lang: String) : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(if (text.length > 200_000) text else highlight(text.text, lang), OffsetMapping.Identity)
}

@Composable
private fun CodeEditor(vm: ChatViewModel, project: Project, rel: String, onClose: () -> Unit) {
    val p = P; val g = vm.g; val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val ctxClip2 = LocalContext.current
    val file = remember(rel) { runCatching { ProjectFiles.safeChild(g.build.folder(project), rel) }.getOrNull() }
    var original by remember(rel) { mutableStateOf<String?>(null) }
    var text by remember(rel) { mutableStateOf("") }
    var loaded by remember(rel) { mutableStateOf(false) }
    var editing by remember(rel) { mutableStateOf(vm.debugEditing) }
    LaunchedEffect(rel, vm.running) {
        val t = withContext(Dispatchers.IO) { file?.let { ProjectFiles.read(it) } }
        if (original == null || original == text) { text = t.orEmpty() }
        original = t; loaded = true
    }
    val dirty = original != null && text != original
    val lang = remember(rel) { langOf(rel) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 6.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTap(Gk.chevronLeft, "Back to files", 22.dp, p.text) { onClose() }
            Column(Modifier.weight(1f)) {
                Text(rel.substringAfterLast('/'), style = Type.row, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ProjectFiles.languageOf(rel) + " · " + "${text.lines().size} lines" + if (dirty) " · unsaved" else "", style = Type.tiny, color = if (dirty) BAmber else p.text3)
            }
            IconTap(Gk.copy, "Copy", 18.dp, p.text2) { vm.toast = if (com.mazharansari.axion.security.SecureClipboard.copy(ctxClip2, "File", text)) "Copied — it has a key in it, so the clipboard clears in 45 s" else "Copied" }
            if (original != null) {
                if (editing) SmallButton(if (dirty) "Save" else "Done", filled = dirty) {
                    if (dirty && file != null) scope.launch { withContext(Dispatchers.IO) { ProjectFiles.write(file, text) }; original = text; vm.toast = "Saved" }
                    editing = false
                } else SmallButton("Edit") { editing = true }
            }
        }
        Spacer(Modifier.height(6.dp))
        val shape = RoundedCornerShape(16.dp)
        Box(Modifier.padding(horizontal = 12.dp).fillMaxSize().padding(bottom = 12.dp).navigationBarsPadding().imePadding().clip(shape).background(p.code)
            .border(1.dp, if (p.isDark) Color(0xFF222222) else p.border, shape)) {
            when {
                !loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(26.dp), color = p.text2, strokeWidth = 2.dp)
                original == null -> Text("This file can't be shown (binary or bigger than 512 KB).", style = Type.small, color = p.text2, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                else -> Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // line numbers
                    Text((1..text.lines().size.coerceAtLeast(1)).joinToString("\n"), style = Type.mono.copy(fontSize = 12.5.sp, lineHeight = 19.sp), color = p.text3,
                        modifier = Modifier.padding(start = 10.dp, top = 12.dp, end = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        if (editing) BasicTextField(text, { text = it }, textStyle = Type.mono.copy(fontSize = 12.5.sp, lineHeight = 19.sp, color = p.text), cursorBrush = SolidColor(p.text),
                            visualTransformation = remember(lang) { HighlightTransformation(lang) }, modifier = Modifier.padding(top = 12.dp, end = 16.dp, bottom = 24.dp).widthIn(min = 300.dp))
                        else Text(remember(text, lang) { highlight(text.take(200_000), lang) }, style = Type.mono.copy(fontSize = 12.5.sp, lineHeight = 19.sp), softWrap = false,
                            modifier = Modifier.padding(top = 12.dp, end = 16.dp, bottom = 24.dp))
                    }
                }
            }
        }
    }
}

// ───────────────────────────── Preview ─────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewTab(vm: ChatViewModel, project: Project, onPublish: () -> Unit) {
    val p = P; val g = vm.g; val ctx = LocalContext.current
    val urls by g.build.previewUrl.collectAsState()
    val jobs by g.build.jobs.collectAsState()
    val url = urls[project.id]
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.previewEpoch) { if (vm.previewEpoch > 0) reload++ }   // axion-p1.2: approved change → preview reloads
    val isAndroid = project.kind == "android" || java.io.File(g.build.folder(project), "settings.gradle.kts").isFile
    val hasSite = remember(project.id, vm.running, reload) { SiteChecker.siteDir(g.build.folder(project)) != null || java.io.File(g.build.folder(project), "package.json").isFile }
    LaunchedEffect(project.id, vm.running, reload) { if (!isAndroid && hasSite && !vm.running && vm.debugProjectTab < 0) runCatching { g.build.startPreview(project) } }
    DisposableEffect(project.id) { onDispose { g.build.stopPreview(project.id) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        if (!isAndroid) {
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(50)).background(p.surface).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GkIcon(Gk.globe, null, 15.dp, p.text3); Spacer(Modifier.width(8.dp))
                Text(url ?: if (hasSite) "Starting preview…" else "Nothing to preview yet", style = Type.small.copy(fontSize = 13.sp), color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconTap(Gk.refresh, "Reload", 17.dp, p.text2) { reload++ }
                IconTap(Gk.arrowUpRight, "Open in browser", 17.dp, p.text2) { url?.let { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(it)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
            }
            Spacer(Modifier.height(10.dp))
            val frame = RoundedCornerShape(22.dp)
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(470.dp).clip(frame).background(Color.White).border(1.dp, p.border, frame)) {
                val shot = java.io.File(g.build.folder(project), ".axion/shot.png")
                if (vm.debugProjectTab >= 0 && shot.isFile) {
                    // screenshot renders (tests): Robolectric's WebView draws nothing, so show Axion's own headless-Chromium screenshot
                    val bmp = remember(shot) { android.graphics.BitmapFactory.decodeFile(shot.path)?.asImageBitmapSafe() }
                    if (bmp != null) androidx.compose.foundation.Image(bmp, "Preview", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth, alignment = Alignment.TopCenter)
                } else if (url != null) AndroidView(factory = { c -> com.mazharansari.axion.security.PreviewWeb.harden(WebView(c)).apply {
                        webChromeClient = WebChromeClient(); if (com.mazharansari.axion.security.PreviewWeb.allowedInside(url)) loadUrl(url)
                    } }, update = { w -> if (w.url != url) w.loadUrl(url) else if (reload > 0) w.reload() }, modifier = Modifier.fillMaxSize())
                else Column(Modifier.fillMaxSize().background(p.card), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    GkIcon(Gk.eye, null, 30.dp, p.text3)
                    Text(if (hasSite) "Starting…" else "Ask in Chat to build the site first", style = Type.small, color = p.text2, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            PublishCard(vm, project, jobs["${project.id}:deploy"], onPublish)
        } else {
            ApkCard(vm, project, jobs["${project.id}:apk"], jobs["${project.id}:apk-release"])
        }
        DemoCard(vm)
        Spacer(Modifier.height(40.dp))
    }
}

private fun android.graphics.Bitmap.asImageBitmapSafe() = runCatching { this.asImageBitmap() }.getOrNull()

/**
 * P2-5 (one-tap build demo): after a Builder run, one tap records a short screen
 * video of the build + preview and shares it. The video is recorded on this phone
 * with MediaProjection — nothing is uploaded and nothing is faked: if the system
 * refuses or the recording fails, the card says so instead of showing a video.
 */
@Composable
private fun DemoCard(vm: ChatViewModel) {
    val p = P; val ctx = LocalContext.current
    var state by remember { mutableStateOf(DemoState.IDLE) }
    var lastFile by remember { mutableStateOf<File?>(null) }
    var seenRecording by remember { mutableStateOf<com.mazharansari.axion.build.DemoRecording?>(null) }
    var seenError by remember { mutableStateOf<String?>(null) }
    val lastRecording by BuildDemo.lastRecording.collectAsState()
    val lastError by BuildDemo.lastError.collectAsState()
    // The service finishes while this card is on screen: offer the share sheet.
    LaunchedEffect(lastRecording) {
        val rec = lastRecording
        if (rec != null && rec != seenRecording) {
            seenRecording = rec; lastFile = rec.file; state = DemoState.READY
        }
    }
    LaunchedEffect(lastError) {
        val err = lastError
        if (err != null && err != seenError) {
            seenError = err; state = DemoState.ERROR; vm.toast = err
        }
    }
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            BuildDemo.startRecording(ctx, result.resultCode, result.data!!)
            state = DemoState.RECORDING
        } else {
            state = DemoState.IDLE
            vm.toast = "Screen recording was not allowed."
        }
    }
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GkIcon(Gk.play, null, 20.dp, p.text); Spacer(Modifier.width(10.dp))
            Text("Build demo video", style = Type.row, color = p.text, modifier = Modifier.weight(1f))
        }
        Text("Record the screen while this app is built and previewed, then share the video. The video is recorded on this phone; nothing is uploaded.", style = Type.small, color = p.text2, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        when (state) {
            DemoState.RECORDING -> {
                BigButton("Stop & share") { BuildDemo.stopRecording(ctx) }
                Text("Recording… tap Stop & share when the demo is done (max 2 minutes).", style = Type.tiny, color = p.text2, modifier = Modifier.padding(top = 8.dp))
            }
            DemoState.READY -> {
                Text("Recorded ${lastFile?.name ?: "video"} — tap Share to send it.", style = Type.small, color = p.text2)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("Share video", modifier = Modifier.weight(1f)) { lastFile?.let { f -> runCatching { ctx.startActivity(android.content.Intent.createChooser(BuildDemo.shareIntent(ctx, f), "Share demo video").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
                    BigButton("Record again", modifier = Modifier.weight(1f)) { state = DemoState.IDLE; runCatching { captureLauncher.launch(BuildDemo.captureIntent(ctx)) }.onFailure { vm.toast = "Screen recording is not available on this phone." } }
                }
            }
            else -> BigButton("Record demo") {
                state = DemoState.WAITING_PERMISSION
                runCatching { captureLauncher.launch(BuildDemo.captureIntent(ctx)) }.onFailure {
                    state = DemoState.IDLE
                    vm.toast = "Screen recording is not available on this phone."
                }
            }
        }
    }
}

@Composable
private fun PublishCard(vm: ChatViewModel, project: Project, job: JobState?, onPublish: () -> Unit) {
    val p = P; val ctx = LocalContext.current; val clip = LocalClipboardManager.current
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GkIcon(Gk.rocket, null, 20.dp, p.text); Spacer(Modifier.width(10.dp))
            Text("Publish", style = Type.row, color = p.text, modifier = Modifier.weight(1f))
            project.deploy?.let { d -> Badge(DeployProvider.byId(d.provider)?.title ?: d.provider, BGreen) }
        }
        when (job) {
            is JobState.Running -> {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = p.text, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                    Text(job.lines.lastOrNull() ?: job.title, style = Type.small, color = p.text2, maxLines = 2)
                }
            }
            is JobState.Failed -> Text(job.message, style = Type.small, color = BRed, modifier = Modifier.padding(top = 8.dp))
            else -> {}
        }
        val live = (job as? JobState.Done)?.result ?: project.deploy?.url
        if (live != null) {
            Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (p.isDark) Color(0xFF15261A) else Color(0xFFE6F6EA)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(BGreen)); Spacer(Modifier.width(8.dp))
                Text(live.removePrefix("https://"), style = Type.small.copy(fontWeight = FontWeight.Medium), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconTap(Gk.copy, "Copy link", 17.dp, p.text2) { clip.setText(AnnotatedString(live)); vm.toast = "Link copied" }
                IconTap(Gk.share, "Share link", 17.dp, p.text2) {
                    runCatching { ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, live), "Share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        } else if (job !is JobState.Running) Note("Put it on the internet with your own free Netlify, Vercel, Cloudflare or GitHub account. You add a token once.", Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        BigButton(if (live != null) "Publish again" else "Publish", enabled = job !is JobState.Running) { onPublish() }
    }
}

@Composable
private fun ApkCard(vm: ChatViewModel, project: Project, job: JobState?, releaseJob: JobState? = null) {
    val p = P; val g = vm.g
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ready = g.build.apkReady()
    // P1-9 release form: the keystore file (picked), alias and passwords. Passwords live only
    // in this form's state until the build starts, then only in the build request's CharArrays
    // (wiped when the build ends) — they are asked again for every release build.
    var releaseForm by remember { mutableStateOf(false) }
    var keystoreFile by remember { mutableStateOf<File?>(null) }
    var keystoreName by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var storePass by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }
    val pickKeystore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val dest = File(ctx.cacheDir, "release-keystore-${System.nanoTime()}.keystore")
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }; dest.isFile && dest.length() > 0 }.getOrDefault(false)
            }
            if (ok) { keystoreFile = dest; keystoreName = uri.lastPathSegment ?: dest.name }
            else { vm.toast = "Could not read that keystore file"; dest.delete() }
        }
    }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GkIcon(Gk.phone, null, 20.dp, p.text); Spacer(Modifier.width(10.dp))
            Text("Android APK", style = Type.row, color = p.text, modifier = Modifier.weight(1f))
            if (!ready) Badge("Needs APK builder", BAmber)
        }
        if (!ready) {
            Note("Building APKs needs the \"Android APK builder\" toolchain: about 210 MB to download, and Gradle adds about 1 GB of libraries on the first build. The first build is slow on a phone (5–15 minutes); later builds take about a minute.", Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            BigButton("Install APK builder") { vm.g.devtools.install(com.mazharansari.axion.linux.Toolchain.ANDROIDSDK) }
            return@Column
        }
        when (job) {
            is JobState.Running -> {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = p.text, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                    Text("Gradle is building…", style = Type.small, color = p.text2)
                }
                LogBox(job.lines)
            }
            is JobState.Failed -> { Text(job.message, style = Type.small, color = BRed, modifier = Modifier.padding(top = 8.dp)); LogBox(job.lines) }
            is JobState.Done -> Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (p.isDark) Color(0xFF15261A) else Color(0xFFE6F6EA)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(BGreen)); Spacer(Modifier.width(8.dp))
                Text(job.result, style = Type.small.copy(fontWeight = FontWeight.Medium), color = p.text, modifier = Modifier.weight(1f))
            }
            else -> Note("Builds a debug APK of this project with Gradle inside Linux, then Android's installer opens.", Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        val apk = (releaseJob as? JobState.Done)?.file ?: (job as? JobState.Done)?.file ?: project.lastApk
        val busy = job is JobState.Running || releaseJob is JobState.Running
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(if (job is JobState.Running) "Building…" else "Build APK", enabled = !busy, modifier = Modifier.weight(1f)) { g.build.buildApk(project) }
            if (apk != null && File(apk).isFile && !busy) {
                Box(Modifier.weight(1f)) { BigButton("Install") { runCatching { g.build.installApk(File(apk)) }.onFailure { vm.toast = it.message } } }
            }
        }
        if (apk != null && File(apk).isFile && !busy) Text("Share APK", style = Type.small.copy(fontWeight = FontWeight.Medium), color = p.text2,
            modifier = Modifier.padding(top = 12.dp).clickable { runCatching { g.build.shareFile(File(apk), "application/vnd.android.package-archive") } })
        // ── P1-9: release APK signed with the user's own keystore ──────────────────────────
        Spacer(Modifier.height(16.dp))
        Text("Release APK", style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = p.text)
        Note("Signed with your own keystore, for sharing outside the Play Store. The keystore and its passwords are asked every time and never stored; the keystore is never copied into the project or the APK.", Modifier.padding(top = 6.dp))
        when (releaseJob) {
            is JobState.Running -> {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = p.text, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                    Text("Gradle is signing the release build…", style = Type.small, color = p.text2)
                }
                LogBox(releaseJob.lines)
            }
            is JobState.Failed -> { Text(releaseJob.message, style = Type.small, color = BRed, modifier = Modifier.padding(top = 8.dp)); LogBox(releaseJob.lines) }
            is JobState.Done -> Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (p.isDark) Color(0xFF15261A) else Color(0xFFE6F6EA)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(BGreen)); Spacer(Modifier.width(8.dp))
                Text(releaseJob.result, style = Type.small.copy(fontWeight = FontWeight.Medium), color = p.text, modifier = Modifier.weight(1f))
            }
            else -> {}
        }
        Spacer(Modifier.height(12.dp))
        if (releaseForm) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButtonGhost(if (keystoreName.isBlank()) "Pick keystore file (.keystore / .jks)" else keystoreName) { pickKeystore.launch(arrayOf("*/*")) }
                ReleaseField("Key alias", alias, { alias = it })
                ReleaseField("Keystore password", storePass, { storePass = it }, secret = true)
                ReleaseField("Key password", keyPass, { keyPass = it }, secret = true)
                BigButton("Build release APK", enabled = !busy && keystoreFile != null && alias.isNotBlank() && storePass.isNotEmpty() && keyPass.isNotEmpty()) {
                    val file = keystoreFile ?: return@BigButton
                    val req = ReleaseSigningRequest(file, alias.trim(), storePass.toCharArray(), keyPass.toCharArray())
                    storePass = ""; keyPass = ""
                    g.build.buildReleaseApk(project, req)
                }
            }
        } else {
            BigButton("Build release APK", enabled = !busy) { releaseForm = true }
        }
    }
}

/** One labelled field for the release-signing form (passwords masked). */
@Composable
private fun ReleaseField(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false) {
    val p = P
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = p.text2, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, p.border, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (value.isEmpty()) Text(if (secret) "••••••••" else label, style = Type.body, color = p.text3)
            BasicTextField(value, onChange, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.text),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun LogBox(lines: List<String>) {
    if (lines.isEmpty()) return
    val p = P
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (p.isDark) Color(0xFF0E0E0E) else Color(0xFFF1F1F1)).padding(10.dp)) {
        lines.takeLast(6).forEach { Text("› $it", style = Type.mono.copy(fontSize = 11.5.sp, lineHeight = 16.sp), color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

// ───────────────────────────── Sheets ─────────────────────────────

@Composable
private fun ProjectMenuSheet(vm: ChatViewModel, project: Project, onDismiss: () -> Unit, onPublish: () -> Unit, onFiles: () -> Unit) {
    val p = P
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    AxionSheet(onDismiss) {
        Text(project.name, style = Type.title, color = p.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        Text("${kindLabel(project.kind)} · /root/projects/${project.slug}", style = Type.small, color = p.text2, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp))
        SheetRow(Gk.pencil, "Rename", onClick = { rename = true })
        SheetRow(Gk.folder, "Files", onClick = onFiles)
        if (project.kind != "android") SheetRow(Gk.rocket, "Publish", project.deploy?.url?.removePrefix("https://") ?: "Netlify · Vercel · Cloudflare · GitHub Pages", onClick = onPublish)
        run { val on = vm.g.settings.state.value.reviewBeforeApply   // axion-p1.2
            SheetRow(Gk.eye, "Review changes before applying", if (on) "On: you approve every AI change first" else "Off: the AI edits your files directly", onClick = { vm.g.settings.update { it.copy(reviewBeforeApply = !on) }; onDismiss() })
            SheetRow(Gk.eye, "Allow un-reviewed changes in very large projects", if (vm.g.settings.state.value.allowLargeUngated) "On: projects too large to hold for review are edited directly" else "Off: very large projects stop and ask first", onClick = { vm.g.settings.update { it.copy(allowLargeUngated = !it.allowLargeUngated) }; onDismiss() }) }
        if (vm.undoAvailable) SheetRow(Gk.history, "Undo last Build turn", "Put every file back the way it was", onClick = { vm.undoLastBuild(); onDismiss() })
        SheetRow(Gk.newChat, "New chat in this project", "Same files, fresh conversation", onClick = { vm.newChat(com.mazharansari.axion.ui.Section.BUILD); onDismiss() })
        SheetRow(Gk.trash, "Delete project", "Removes its folder and files", color = BRed, onClick = { confirmDelete = true })
    }
    if (rename) NameDialog("Rename project", "Project name", project.name, onDismiss = { rename = false }) { n -> vm.g.build.store.rename(project.id, n); rename = false }
    if (confirmDelete) ConfirmDialog("Delete \"${project.name}\"?", "Its folder and every file in it are deleted from this phone. A published site stays online.", "Delete",
        onDismiss = { confirmDelete = false }) { val id = project.id; confirmDelete = false; onDismiss(); vm.closeProject(); vm.g.build.stopPreview(id); vm.g.build.store.delete(id) }
}

@Composable
fun NameDialog(title: String, hint: String, initial: String, confirm: String = "Save", onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val p = P
    var v by remember { mutableStateOf(initial) }
    androidx.compose.ui.window.Dialog(onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.page2).padding(20.dp)) {
            Text(title, style = Type.title, color = p.text)
            Spacer(Modifier.height(14.dp))
            Field(v, { v = it }, hint)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton("Cancel") { onDismiss() }
                SmallButton(confirm, filled = true, enabled = v.isNotBlank()) { onDone(v.trim()) }
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val p = P
    androidx.compose.ui.window.Dialog(onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.page2).padding(20.dp)) {
            Text(title, style = Type.title, color = p.text)
            Text(body, style = Type.small, color = p.text2, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton("Cancel") { onDismiss() }
                SmallButton(confirm, filled = true) { onConfirm() }
            }
        }
    }
}

@Composable
fun DeploySheet(vm: ChatViewModel, project: Project, onDismiss: () -> Unit) {
    com.mazharansari.axion.security.SecureScreen()   // v0.5.2: a token is typed here — no screenshots / recording
    val p = P; val g = vm.g; val ctx = LocalContext.current
    val saved by g.build.tokens.saved.collectAsState()
    val jobs by g.build.jobs.collectAsState()
    val job = jobs["${project.id}:deploy"]
    var pick by remember { mutableStateOf(DeployProvider.byId(project.deploy?.provider) ?: DeployProvider.entries.firstOrNull { saved.containsKey(it.id) } ?: DeployProvider.NETLIFY) }
    var token by remember(pick) { mutableStateOf("") }
    AxionSheet(onDismiss) {
        Text("Publish ${project.name}", style = Type.title, color = p.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        Note("Your own free account hosts it. The token is stored encrypted on this phone and only sent to that host.", Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        DeployProvider.entries.forEach { d ->
            val sel = d == pick
            val tap = rememberTap { pick = d }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (sel) p.card else Color.Transparent)
                .clickable(onClick = tap).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (sel) p.text else p.text3, CircleShape), contentAlignment = Alignment.Center) { if (sel) Box(Modifier.size(10.dp).clip(CircleShape).background(p.text)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.title, style = Type.row, color = p.text)
                    Text(d.note, style = Type.tiny, color = p.text2)
                }
                if (saved.containsKey(d.id)) Badge("Token saved", BGreen)
            }
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
            if (!saved.containsKey(pick.id)) {
                Field(token, { token = it }, pick.tokenHint, password = true, mono = true)
                Text("Get a token →", style = Type.small.copy(fontWeight = FontWeight.Medium), color = p.text2, modifier = Modifier.padding(top = 8.dp).clickable {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(pick.tokenPage)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            } else Text("Remove saved token", style = Type.small, color = BRed, modifier = Modifier.clickable { g.build.tokens.remove(pick) })
            when (job) {
                is JobState.Running -> LogBox(job.lines.ifEmpty { listOf(job.title) })
                is JobState.Failed -> Text(job.message, style = Type.small, color = BRed, modifier = Modifier.padding(top = 10.dp))
                is JobState.Done -> Text("Live: ${job.result}", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = BGreen, modifier = Modifier.padding(top = 10.dp))
                else -> {}
            }
            Spacer(Modifier.height(14.dp))
            BigButton(if (job is JobState.Running) "Publishing…" else "Publish to ${pick.title}", enabled = job !is JobState.Running && (saved.containsKey(pick.id) || token.length >= 10)) {
                if (token.isNotBlank()) g.build.tokens.set(pick, token)
                g.build.deploy(g.build.store.get(project.id) ?: project, pick)
            }
        }
    }
}
