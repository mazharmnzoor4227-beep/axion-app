package com.mazharansari.axion.ui.home

import androidx.compose.ui.zIndex
import com.mazharansari.axion.ui.chat.BuildPopover
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.mazharansari.axion.data.*
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import com.mazharansari.axion.ui.chat.*
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * Home = Grok's shell: full-screen drawer that slides over from the left, a transparent top bar
 * (☰ · title · ✎) floating over a bg→transparent fade, and the composer floating over the bottom fade.
 */
@Composable
fun HomeScreen(vm: ChatViewModel, navigate: (Route) -> Unit, initialDrawer: Boolean = false, initialTab: Int = 0) {
    val p = P
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    LaunchedEffect(vm.debugHomeTab) { if (vm.debugHomeTab >= 0) tab = vm.debugHomeTab }
    // v0.4 Ask | Build are separate engines: switching tabs never carries a thread across.
    fun switchTab(to: Int) {
        if (to == 0 && vm.section == com.mazharansari.axion.ui.Section.BUILD) vm.newChat(com.mazharansari.axion.ui.Section.ASK)
        if (to == 2 && vm.section == com.mazharansari.axion.ui.Section.ASK) vm.newChat(com.mazharansari.axion.ui.Section.BUILD)
        tab = to
    }
    // Opening a thread from the menu lands on its own tab.
    LaunchedEffect(vm.conversationId, vm.section) {
        if (vm.conversationId != null) { if (vm.section == com.mazharansari.axion.ui.Section.BUILD) tab = 2 else if (tab == 2) tab = 0 }
    }
    var drawerOpen by remember { mutableStateOf(initialDrawer) }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    val target = if (drawerOpen) 1f else 0f
    val anim by animateFloatAsState(target, tween(280, easing = FastOutSlowInEasing), label = "drawer")
    val frac = if (drag != 0f) ((if (drawerOpen) 1f else 0f) + drag / widthPx).coerceIn(0f, 1f) else anim
    BackHandler(drawerOpen) { drawerOpen = false }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Box(Modifier.fillMaxSize().pointerInput(drawerOpen) {
            if (!drawerOpen) detectHorizontalDragGestures(
                onDragEnd = { if (drag > widthPx * 0.25f) drawerOpen = true; drag = 0f },
                onDragCancel = { drag = 0f },
            ) { ch, d -> if (ch.position.x < widthPx * 0.15f || drag > 0f) { drag = (drag + d).coerceAtLeast(0f) } }
        }) {
            Crossfade(tab, animationSpec = tween(180), label = "tab") { t ->
                when (t) {
                    0 -> ChatPane(vm, navigate, onBuild = { switchTab(2) }, onImagine = { tab = 1 })
                    1 -> Column(Modifier.fillMaxSize()) { Spacer(Modifier.statusBarsPadding().height(64.dp)); ImaginePane(vm) }
                    // v0.4: a Build thread shows as a chat run by Antigravity; otherwise Build's start page (or its setup card)
                    // v0.5: Build = projects. Inside a project: Chat | Files | Preview; otherwise the projects list (or its setup card).
                    else -> if (vm.projectId != null) com.mazharansari.axion.ui.build.ProjectWorkspace(vm, navigate)
                        else if (vm.section == com.mazharansari.axion.ui.Section.BUILD && vm.messages.isNotEmpty()) ChatPane(vm, navigate, onBuild = {}, build = true)
                        else Column(Modifier.fillMaxSize()) { Spacer(Modifier.statusBarsPadding().height(64.dp)); BuildPane(vm, onSendToAsk = { }, navigate = navigate) }
                }
            }
            UpdateBanner(vm, navigate)
            if (!(tab == 2 && vm.projectId != null)) TopBar(tab, empty = vm.messages.isEmpty(), onTab = { switchTab(it) }, onMenu = { drawerOpen = true }, onNew = { if (tab == 2) vm.newChat(com.mazharansari.axion.ui.Section.BUILD) else { vm.newChat(com.mazharansari.axion.ui.Section.ASK); tab = 0 } })
            vm.viewingHtml?.let { a -> com.mazharansari.axion.ui.chat.HtmlPreview(a, onClose = { vm.viewingHtml = null }) }   // axion-p1.3
            vm.viewingPdf?.let { a ->
                val ctx = LocalContext.current
                PdfViewer(a, onClose = { vm.viewingPdf = null; vm.debugViewer = null }, onShare = { shareFile(ctx, a) }, onDownload = { vm.toast = FileActions.download(ctx, a) })
            }
        }
        if (frac > 0f) {
            com.mazharansari.axion.ui.components.DismissKeyboard()   // v0.4: the keyboard closes when the menu opens
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f * frac)).clickable(remember { MutableInteractionSource() }, null) { drawerOpen = false })
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = -widthPx * (1f - frac) }.pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { if (drag < -widthPx * 0.25f) drawerOpen = false; drag = 0f }, onDragCancel = { drag = 0f }) { _, d -> drag = (drag + d).coerceAtMost(0f) }
            }) {
                DrawerContent(vm, onClose = { drawerOpen = false }, onOpen = { vm.open(it); tab = 0; drawerOpen = false },
                    onNew = { vm.newChat(com.mazharansari.axion.ui.Section.ASK); tab = 0; drawerOpen = false }, navigate = { r -> drawerOpen = false; navigate(r) })
            }
        }
    }
}

/**
 * ☰ circle · Ask | Build · ✎ circle. v0.4 (mazhar 2:08 PM): he only wanted Imagine out of the top bar — the v0.3
 * segmented tabs come back as "Ask | Build" (Grok's tab style: 18 sp semibold, 27 dp apart, 2.5 dp bar under the
 * current one). Imagine opens from the + menu; there the middle reads "‹ Imagine" and tapping it returns to Ask.
 */
@Composable
private fun TopBar(tab: Int, empty: Boolean, onTab: (Int) -> Unit, onMenu: () -> Unit, onNew: () -> Unit) {
    val p = P
    // v0.5.2 (mazhar's test): solid bar — chat text no longer shows through the Ask | Build switch while scrolling.
    Box(Modifier.fillMaxWidth().background(p.bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 11.dp, end = 11.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Gk.menu, "Menu", onClick = onMenu)
            Spacer(Modifier.weight(1f))
            if (tab == 1) {
                val tap = rememberTap { onTab(0) }
                Column(Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = tap), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GkIcon(Gk.chevronLeft, "Back to Ask", 18.dp, p.text2); Spacer(Modifier.width(2.dp))
                        Text("Imagine", style = Type.body.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold), color = p.text)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.size(width = 16.dp, height = 2.5.dp).clip(RoundedCornerShape(50)).background(p.dim))
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(27.dp), verticalAlignment = Alignment.Top) {
                listOf(0 to "Ask", 2 to "Build").forEach { (i, label) ->
                    val sel = tab == i
                    val tap = rememberTap { onTab(i) }
                    val c by animateColorAsState(if (sel) p.text else p.dim, tween(160), label = "tab")
                    Column(Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = tap), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, style = Type.body.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold), color = c)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.size(width = 16.dp, height = 2.5.dp).clip(RoundedCornerShape(50)).background(if (sel) p.dim else Color.Transparent))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            CircleButton(if (empty && tab == 0) Gk.eyeOff else Gk.newChat, if (empty && tab == 0) "Private chat" else "New chat", onClick = onNew)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatPane(vm: ChatViewModel, navigate: (Route) -> Unit, onBuild: () -> Unit, onImagine: () -> Unit = {}, build: Boolean = false, topInset: androidx.compose.ui.unit.Dp = 0.dp, emptyContent: (@Composable () -> Unit)? = null) {
    val p = P
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val g = vm.g
    val settings by g.settings.state.collectAsState()
    val connectors by g.connectors.list.collectAsState()
    val listening by g.speech.listening.collectAsState()
    val partial by g.speech.partial.collectAsState()
    val dictation by g.speech.state.collectAsState()
    LaunchedEffect(dictation.error) { dictation.error?.let { vm.toast = it } }
    val speakingId by g.speaker.speakingId.collectAsState()
    var plus by remember { mutableStateOf(vm.debugPopover == 1) }
    var model by remember { mutableStateOf(vm.debugPopover == 2) }
    LaunchedEffect(vm.debugPopover) { if (vm.debugPopover == 1) plus = true; if (vm.debugPopover == 2) model = true }   // render hook
    var thoughts by remember { mutableStateOf(vm.debugThoughts) }
    val list = rememberLazyListState()
    val messages = vm.messages
    val haze = remember { HazeState() }
    val density = LocalDensity.current
    var composerH by remember { mutableIntStateOf(0) }
    var composerInnerH by remember { mutableIntStateOf(0) }   // v0.5.0's measurement, for the before/after test renders only
    // v0.3 popover anchoring: measured in root coordinates every layout pass, so the popover follows the composer
    // when the keyboard opens/closes (the old fixed nav-bar offset left a gap above the keyboard and pushed the
    // model list under the top bar).
    var boxBottomPx by remember { mutableFloatStateOf(0f) }
    var boxTopPx by remember { mutableFloatStateOf(0f) }
    var composerTopPx by remember { mutableFloatStateOf(0f) }

    val pickSkill = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.importSkills(uris) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach { vm.addAttachment(it) } }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris -> uris.forEach { vm.addAttachment(it) } }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraFile?.let { vm.addFile(it) } }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) vm.toast = "Mic permission is needed for dictation" }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) vm.toast = "Camera permission is needed" }

    val last = messages.lastOrNull()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list.isScrollInProgress) { if (!list.isScrollInProgress) follow = !list.canScrollForward }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) { follow = true; list.animateScrollToItem(messages.lastIndex) } }
    LaunchedEffect(last?.text?.length, last?.steps?.size, last?.artifacts?.size) {
        if (vm.running && follow && messages.isNotEmpty()) list.scrollToItem(messages.lastIndex, 1_000_000)
    }
    val scope = rememberCoroutineScope()

    // v0.3.1 — Grok's read-aloud pill, pull-up-for-new-chat and in-app PDF viewer
    val ra = com.mazharansari.axion.voice.ReadAloudPlayer
    val imeVisible = WindowInsets.isImeVisible
    val pillVisible = speakingId != null && messages.any { it.id == speakingId } && !imeVisible
    var speedOpen by remember { mutableStateOf(vm.debugSpeedMenu) }
    LaunchedEffect(pillVisible) { if (!pillVisible) speedOpen = false }
    var speedAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    LaunchedEffect(vm.debugViewer) { vm.debugViewer?.let { n -> vm.viewingPdf = messages.flatMap { it.artifacts }.firstOrNull { it.name == n } } }
    val view = androidx.compose.ui.platform.LocalView.current
    val thresholdPx = with(density) { 132.dp.toPx() }   // Grok lifts the chat ≈135 dp before "New chat" arms (recording 0:01–0:02)
    var pullPx by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val debugPull = vm.debugPull
    val pullFrac = if (debugPull > 0f) debugPull else pullPx / thresholdPx
    val pullArmed = if (debugPull > 0f) debugPull >= 1f else armed
    fun tick() { if (settings.haptics) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
    // Only a finger drag lifts the chat (programmatic / accessibility scrolls never start a new chat).
    val dragging by list.interactionSource.collectIsDraggedAsState()
    val draggingNow by rememberUpdatedState(dragging)
    val pullConnection = remember(messages.isEmpty()) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // pulling back down while the chat is lifted: lower it first
                if (pullPx > 0f && available.y > 0f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                    val use = minOf(available.y, pullPx * 2f)
                    pullPx = (pullPx - use / 2f).coerceAtLeast(0f)
                    if (armed && pullPx < thresholdPx) { armed = false }
                    return androidx.compose.ui.geometry.Offset(0f, use)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // dragging up past the end of the chat: lift it with resistance (Grok's rubber band)
                if (available.y < 0f && draggingNow && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && !vm.running) {
                    val resist = 0.62f * (1f - (pullPx / (thresholdPx * 2.4f)).coerceIn(0f, 0.8f))
                    pullPx += -available.y * resist
                    if (!armed && pullPx >= thresholdPx) { armed = true; tick() }
                    return available
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (pullPx <= 0f) return androidx.compose.ui.unit.Velocity.Zero
                val go = armed
                armed = false
                if (go) { vm.newChat() }
                androidx.compose.animation.core.animate(pullPx, 0f, animationSpec = tween(260, easing = FastOutSlowInEasing)) { v, _ -> pullPx = v }
                return available
            }
        }
    }

    CompositionLocalProvider(LocalHaze provides haze) {
        Box(Modifier.fillMaxSize().imePadding().onGloballyPositioned { val b = it.boundsInRoot(); boxBottomPx = b.bottom; boxTopPx = b.top }) {
            // v0.5.1 (mazhar 3:36/3:52 PM: Thinking and the last message sat glued to the composer). Grok's recording
            // (1033) measured 88 px at 480 px wide = ~75 dp from the last message row to the composer, keyboard open
            // or closed. The list's bottom padding holds that space below the last item at all times — streaming,
            // auto-scroll, keyboard open (the Box is imePadding'd, so the composer and this gap move up together).
            val bottomPad = if (ChatGap.debugLegacy) with(density) { composerInnerH.toDp() } + 12.dp else with(density) { composerH.toDp() } + ChatGap.LIST_BOTTOM
            // Grok: when the player pill (or anything) grows the bottom stack while you're at the end, the end stays in view above it.
            LaunchedEffect(composerH) { if (follow && messages.isNotEmpty() && !list.isScrollInProgress) list.scrollToItem(messages.lastIndex, 1_000_000) }
            Box(Modifier.fillMaxSize().haze(haze)) {
                if (messages.isEmpty()) (emptyContent ?: { EmptyHome() })()
                else LazyColumn(state = list, modifier = Modifier.fillMaxSize().nestedScroll(pullConnection).graphicsLayer { translationY = -(if (debugPull > 0f) debugPull * thresholdPx * 0.9f else pullPx) },
                    contentPadding = PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp + topInset, bottom = bottomPad)) {
                    items(messages, key = { it.id }, contentType = { it.role }) { m ->
                        if (m.role == Role.USER) UserBubble(m)
                        else AssistantMessage(
                            m, isLast = m.id == messages.last().id, speaking = speakingId == m.id,
                            onOpenThoughts = { thoughts = m.id },
                            onCopy = { vm.toast = if (com.mazharansari.axion.security.SecureClipboard.copy(ctx, "Axion", m.text)) "Copied — it has a key in it, so the clipboard clears in 45 s" else "Copied" },
                            onSpeak = { vm.speak(m) },
                            onRegenerate = { vm.regenerate() },
                            onShare = { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, m.text), "Share")) },
                            onRate = { vm.rate(m.id, it) },
                            onOpenFile = { a -> if (a.mime == "application/pdf") vm.viewingPdf = a else if (a.mime == "text/html" || a.name.endsWith(".html", true)) vm.viewingHtml = a else openFile(ctx, a) },   // axion-p1.3
                            onDownload = { a -> vm.toast = FileActions.download(ctx, a) },
                        )
                    }
                }
            }
            // bottom fade under the floating composer
            // v0.5.1: the fade lives inside the gap only (it used to dim the last 24 dp of the last message).
            if (ChatGap.debugLegacy) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomPad + 24.dp).background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to p.bg.copy(alpha = 0.85f), 0.62f to p.bg, 1f to p.bg)))
            else Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(density) { composerH.toDp() } + ChatGap.FADE).background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to p.bg, 1f to p.bg)))
            // v0.5.1: measure the whole bottom stack (nav bar + 6 dp included) — measuring inside the padding left the list
            // short by the nav-bar height, which is part of why the last message sat on the composer.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { composerH = it.height }.navigationBarsPadding().padding(bottom = 6.dp).onSizeChanged { composerInnerH = it.height }) {
                // Grok: 48 dp #1F1F1F chevron, 12 dp from the right edge, 10 dp above the player pill (or the composer).
                AnimatedVisibility(messages.isNotEmpty() && list.canScrollForward && !vm.running && pullPx == 0f, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f),
                    modifier = Modifier.align(Alignment.End).padding(end = 12.dp, bottom = 10.dp)) {
                    CircleButton(Gk.chevronDown, "Scroll to bottom", size = 48.dp, iconSize = 22.dp, bg = if (p.isDark) Color(0xFF1F1F1F) else Color(0xFFF2F2F2)) { scope.launch { list.animateScrollToItem(messages.lastIndex, 1_000_000) } }
                }
                AnimatedVisibility(pillVisible, modifier = Modifier.align(Alignment.CenterHorizontally),
                    enter = fadeIn(tween(180)) + slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 2 } + expandVertically(tween(240), expandFrom = Alignment.Bottom),
                    exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 2 } + shrinkVertically(tween(220), shrinkTowards = Alignment.Bottom)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // P1-11: which provider actually produced this voice (Edge / Gemini / phone).
                        ra.provenance?.let { Text("Voice: $it", style = Type.tiny, color = p.text2, modifier = Modifier.padding(bottom = 6.dp)) }
                        ReadAloudPill(
                            paused = ra.paused, elapsedMs = ra.elapsedMs, progress = ra.progress, speed = ra.speed, speedOpen = speedOpen,
                            onPlayPause = { ra.togglePause() }, onBack = { ra.seekBy(-15_000) }, onForward = { ra.seekBy(15_000) },
                            onSpeed = { speedOpen = !speedOpen }, onClose = { speedOpen = false; g.speaker.stop() },
                            onSpeedAnchor = { speedAnchor = it },
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                }
                // v0.12: a long task the app lost mid-way gets a visible way back, above the composer.
                vm.interruptedTask?.let { rec ->
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).border(1.dp, p.border, RoundedCornerShape(16.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        GkIcon(Gk.history, null, 18.dp, p.text2); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("A long task was interrupted", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                            Text(rec.goal.take(90), style = Type.tiny, color = p.text2, maxLines = 1)
                        }
                        TextButton(onClick = { vm.resumeInterruptedTask() }) { Text("Open chat", color = p.accent) }
                        TextButton(onClick = { vm.dismissInterruptedTask() }) { Text("Dismiss", color = p.text2) }
                    }
                }
                // P2-2: measured context usage of the last run — a plain line + thin bar above the composer.
                // When a long session compacts itself the label says how many turns were folded into session notes.
                vm.contextUsage?.let { u ->
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                        Text(u.label(), style = Type.tiny, color = p.text2, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(progress = { u.percent / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp), color = p.accent, trackColor = p.border)
                    }
                }
                // P2-4: measured speed of the last local-model run.
                vm.localSpeed?.let { sp ->
                    Text(sp.label(), style = Type.tiny, color = p.text2, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                }
                // v0.5.2 (mazhar's test): Build never shows the Ask-only key chips — it shows what Build actually needs.
                if (messages.isEmpty() && !plus && !model && build) BuildChips(ready = vm.buildReady(), onAgy = { navigate(Route.Antigravity) }, onTools = { navigate(Route.DevTools) })
                else if (messages.isEmpty() && !plus && !model) SuggestionChips(noKeys = connectors.isEmpty(), onConnect = { navigate(Route.Connectors) }, onSkills = { navigate(Route.Skills) }, onBuild = onBuild, onVoice = { navigate(Route.VoiceCall) })
                // v0.9.4: the 10X tool toggles live in their own line ABOVE the box — inside the box they
                // pushed the mic and the Send button off the right edge (no Send at all). Same chips,
                // same behaviour, only the placement changed. Ask only: Build never shows them.
                if (!build) ToolsRow(
                    researchOn = settings.deepResearch,
                    // P0-6 (HI-4): turning Research ON while the Deep research tool group is off in the
                    // Tools library would silently do nothing (the agent filters the tool out). Re-enable
                    // the group instead, and say so — the toggle then really researches.
                    onResearch = {
                        val s = g.settings.state.value
                        if (!s.deepResearch && s.toolSwitches["research"] == false) {
                            g.settings.update { it.copy(deepResearch = true, toolSwitches = it.toolSwitches + ("research" to true)) }
                            vm.toast = "Deep research turned back on in Tools library."
                        } else {
                            // A tap must say so: a shade change alone left the user unsure the toggle had worked.
                            val on = !s.deepResearch
                            g.settings.update { it.copy(deepResearch = on) }
                            vm.toast = if (on) "Deep research ON — your next message will use deep research." else "Deep research OFF."
                        }
                    },
                    longTaskOn = settings.longTask,
                    onLongTask = {
                        val on = !settings.longTask
                        g.settings.update { it.copy(longTask = on) }
                        vm.toast = if (on) "Long task ON — your next message will run as a long task." else "Long task OFF."
                    },
                    // P1-3 Turbo (server): per-task toggle; off by default, and it only takes
                    // effect when a server is set up in Settings → Turbo (server).
                    turboOn = settings.turbo,
                    onTurbo = {
                        val on = !settings.turbo
                        g.settings.update { it.copy(turbo = on) }
                        vm.toast = if (on) "Turbo ON — your next task runs on your server." else "Turbo OFF."
                    },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Composer(
                    modifier = Modifier.onGloballyPositioned { composerTopPx = it.boundsInRoot().top },
                    text = vm.draft, onText = { vm.draft = it }, placeholder = if (build) "Type to Build…" else "Ask anything",
                    modeLabel = if (build) "Build" else settings.mode.lowercase().replaceFirstChar { it.uppercase() }, running = vm.running,
                    attachments = vm.pending, onRemoveAttachment = { vm.pending.remove(it) },
                    listening = listening, level = { g.speech.level() }, partial = partial,
                    onPlus = { model = false; plus = !plus }, onMode = { plus = false; model = !model },
                    onMic = {
                        if (listening) g.speech.finish { t -> if (t.isNotBlank()) vm.draft = (vm.draft + " " + t).trim() }
                        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micPerm.launch(Manifest.permission.RECORD_AUDIO)
                        else if (!g.speech.available()) vm.toast = "No speech recogniser on this phone"
                        else g.speech.start()
                    },
                    onMicCancel = { g.speech.cancel() },
                    onSend = { vm.send() }, onStop = { vm.stop() }, onVoiceCall = { navigate(Route.VoiceCall) },
                    plusOpen = plus, modeOpen = model, onFocus = { vm.prewarm() },
                )
            }
            // v0.12: the approval gate — a consequential shell command waits for the user's answer.
            vm.pendingApproval?.let { pa ->
                AlertDialog(
                    onDismissRequest = { vm.answerApproval(com.mazharansari.axion.agent.ApprovalDecision.DENY) },
                    containerColor = p.card, shape = RoundedCornerShape(24.dp),
                    title = { Text("Allow this command?", style = Type.title, color = p.text) },
                    text = {
                        Column {
                            Text(pa.req.summary.take(600), style = Type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.text)
                            if (pa.req.hard) {
                                Spacer(Modifier.height(10.dp))
                                Text(pa.req.hardReason + " This kind can never be remembered — Axion will ask every time.", style = Type.small, color = p.danger)
                            } else if (pa.req.pattern.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text("\"Always allow\" remembers this kind of command (${pa.req.pattern}) — revoke it any time in Settings → Approvals.", style = Type.small, color = p.text2)
                            }
                        }
                    },
                    confirmButton = {
                        Row {
                            TextButton(onClick = { vm.answerApproval(com.mazharansari.axion.agent.ApprovalDecision.DENY) }) { Text("Deny", color = p.danger) }
                            if (!pa.req.hard && pa.req.pattern.isNotBlank())
                                TextButton(onClick = { vm.answerApproval(com.mazharansari.axion.agent.ApprovalDecision.ALLOW_REMEMBER) }) { Text("Always allow", color = p.text) }
                            TextButton(onClick = { vm.answerApproval(com.mazharansari.axion.agent.ApprovalDecision.ALLOW_ONCE) }) { Text("Allow once", color = p.accent) }
                        }
                    },
                    dismissButton = {},
                )
            }
            // Grok's pull-up indicator, rising from just above the composer as the chat is lifted.
            NewChatPull(pullFrac, pullArmed, Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { composerH.toDp() } + 18.dp)
                .graphicsLayer { translationY = -(pullFrac.coerceIn(0f, 1.4f) * 52.dp.toPx()) })
            // Speed list standing on the pill, its left edge on the speed capsule's left edge.
            if (pillVisible && speedAnchor != androidx.compose.ui.geometry.Rect.Zero) {
                val pillTopPx = speedAnchor.top - with(density) { 9.dp.toPx() }
                if (speedOpen) Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { speedOpen = false })
                Box(Modifier.align(Alignment.BottomStart)
                    .padding(bottom = with(density) { (boxBottomPx - pillTopPx).coerceAtLeast(0f).toDp() })
                    .offset(x = with(density) { speedAnchor.left.toDp() } - 2.dp)) {
                    SpeedMenu(speedOpen, ra.speed, com.mazharansari.axion.voice.VoicePrefs.SPEEDS, onPick = { v -> ra.setSpeed(ctx, v); speedOpen = false })
                }
            }
            // Popover bottom = 8dp above the composer's top edge; max height = the space between the top bar and that edge.
            val popBottom = with(density) { (boxBottomPx - composerTopPx).coerceAtLeast(0f).toDp() } + 8.dp
            val statusTopPx = WindowInsets.statusBars.getTop(density).toFloat()
            val topLimitPx = maxOf(boxTopPx, statusTopPx + with(density) { 64.dp.toPx() })
            val popMax = (with(density) { (composerTopPx - topLimitPx).coerceAtLeast(0f).toDp() } - 16.dp).coerceAtLeast(120.dp)
            PlusPopover(plus, { plus = false }, bottomGap = popBottom, maxHeight = popMax,
                onCamera = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) camPerm.launch(Manifest.permission.CAMERA)
                    else {
                        val f = File(g.workspace.uploads, "photo_${System.currentTimeMillis()}.jpg"); cameraFile = f
                        takePhoto.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
                    }
                },
                onGallery = { pickImages.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onFiles = { pickFiles.launch(arrayOf("*/*")) },
                onSkills = { navigate(Route.Skills) },
                onConnectors = { navigate(Route.Connectors) },
                onUploadSkill = { pickSkill.launch(arrayOf("*/*")) },
                onImagine = onImagine, onBuild = null)   // v0.4: Build is back in the top bar, not here
            if (build) BuildPopover(model, { model = false }, bottomGap = popBottom, maxHeight = popMax, agy = g.agy,
                onManage = { navigate(Route.Antigravity) })   // v0.4: Build's picker = Antigravity accounts/models only
            else ModelPopover(model, { model = false }, bottomGap = popBottom, maxHeight = popMax, mode = settings.mode, connectors = connectors,
                onPick = { m -> g.settings.update { it.copy(mode = m) } }, onBuild = onBuild, onConnectors = { navigate(Route.Connectors) })
        }
    }
    thoughts?.let { id -> messages.firstOrNull { it.id == id }?.let { ThoughtsSheet(it) { thoughts = null } } }
}

fun shareFile(ctx: android.content.Context, a: Artifact) {
    com.mazharansari.axion.security.SecretScanner.leaveCheck(File(a.path))?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show(); return }
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(a.path))
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(a.mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun openFile(ctx: android.content.Context, a: Artifact) {
    com.mazharansari.axion.security.SecretScanner.leaveCheck(File(a.path))?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show(); return }
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(a.path))
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        android.widget.Toast.makeText(ctx, "No app to open ${a.name}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Grok's empty home: just the logo, centred a little above middle, in dim grey (#434343). */
@Composable
private fun EmptyHome() {
    Box(Modifier.fillMaxSize()) {
        AxionMark(64.dp, Modifier.align(Alignment.TopCenter).padding(top = 0.dp).offset(y = (LocalConfiguration.current.screenHeightDp * 0.25f).dp), tint = if (P.isDark) Color(0xFF434343) else Color(0xFFBDBDBD))
    }
}

/** Build's empty-chat row: an Antigravity setup card until Build can run, then the tools shortcut. */
@Composable
private fun BuildChips(ready: Boolean, onAgy: () -> Unit, onTools: () -> Unit) {
    val p = P
    if (!ready) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).clickable(onClick = onAgy).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(p.surface), contentAlignment = Alignment.Center) { GkIcon(Gk.hammer, null, 20.dp, p.text) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Set up Antigravity to build", style = Type.row.copy(fontSize = 16.sp), color = p.text)
                Text("Build runs on your Google Antigravity account inside Linux on this phone. Takes a few minutes, once.", style = Type.tiny, color = p.text2)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF1D9BF0)).padding(horizontal = 14.dp, vertical = 8.dp)) { Text("Set up", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = Color.White) }
        }
    } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(Gk.terminal, "Developer tools", onClick = onTools)
        Chip(Gk.hammer, "Antigravity", onClick = onAgy)
    }
}

/** The chip row above the composer on an empty chat ("Save 50% on Premium" · "Try Connectors" in Grok). */
@Composable
private fun SuggestionChips(noKeys: Boolean, onConnect: () -> Unit, onSkills: () -> Unit, onBuild: () -> Unit, onVoice: () -> Unit) {
    val p = P
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (noKeys) Chip(Gk.key, "Connect a free AI key", blue = true, onClick = onConnect)
        Chip(Gk.connectors, "Try Connectors", onClick = onConnect)
        Chip(Gk.skills, "Skills", onClick = onSkills)
        Chip(Gk.hammer, "Build an app", onClick = onBuild)
        Chip(Gk.voice, "Voice", onClick = onVoice)
    }
}

@Composable
private fun Chip(icon: Int, label: String, blue: Boolean = false, onClick: () -> Unit) {
    val p = P; val tap = rememberTap(onClick)
    val bg = if (blue) Color(0xFF0E2A63) else p.card
    val fg = if (blue) Color(0xFF5B9BFF) else p.text
    Row(Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(bg).clickable(onClick = tap).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        GkIcon(icon, null, 18.dp, fg); Spacer(Modifier.width(8.dp))
        Text(label, style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = fg, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerContent(vm: ChatViewModel, onClose: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit, navigate: (Route) -> Unit) {
    val p = P
    val index by vm.g.chats.index.collectAsState()
    val settings by vm.g.settings.state.collectAsState()
    val connectors by vm.g.connectors.list.collectAsState()
    var q by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ConversationMeta?>(null) }
    var renaming by remember { mutableStateOf<ConversationMeta?>(null) }
    val activeRuns by vm.g.runs.active.collectAsState()   // v0.8.0: chats still working in the background get a "Working" mark
    var collapsed by remember { mutableStateOf(false) }
    val filtered = remember(index, q) { index.filter { q.isBlank() || it.title.contains(q, true) || it.preview.contains(q, true) } }
    val pinned = filtered.filter { it.pinned }; val rest = filtered.filterNot { it.pinned }
    Box(Modifier.fillMaxSize().background(p.page2)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), bottom = 120.dp)) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(settings.userName.ifBlank { "You" }, 46.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(settings.userName.ifBlank { "You" }, style = Type.row.copy(fontSize = 18.sp), color = p.text, modifier = Modifier.weight(1f).clickable { navigate(Route.Settings) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    CircleButton(Gk.arrowsRight, "Close", bg = p.card, onClick = onClose)
                }
            }
            item(key = "nav") {
                SettingsGroup {
                    DrawerTile(Gk.voice, "Voice") { navigate(Route.VoiceCall) }
                    DrawerTile(Gk.library, "Library") { navigate(Route.Files) }
                    DrawerTile(Gk.skills, "Skills") { navigate(Route.Skills) }
                    DrawerTile(Gk.connectors, "Connectors") { navigate(Route.Connectors) }
                }
            }
            if (connectors.isEmpty()) item(key = "banner") {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp).fillMaxWidth().height(60.dp).clip(RoundedCornerShape(50)).background(p.blue).clickable { navigate(Route.Connectors) }.padding(start = 18.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    GkIcon(Gk.key, null, 20.dp, Color.White); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Connect a free AI key", style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
                        Text("Gemini, Groq, OpenRouter and more", style = Type.tiny, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                    }
                    Box(Modifier.height(38.dp).clip(RoundedCornerShape(50)).border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text("Connect", style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
                    }
                }
            }
            if (pinned.isNotEmpty()) {
                item(key = "h_pinned") { DrawerHeader("Pinned", null) {} }
                items(pinned, key = { "p" + it.id }) { c -> ConvRow(c, c.id == vm.conversationId, activeRuns.any { it.ownerId == c.id }, { onOpen(c.id) }, { menuFor = c }) }
            }
            item(key = "h_conv") { DrawerHeader("Conversations", collapsed) { collapsed = !collapsed } }
            if (!collapsed) items(rest, key = { it.id }) { c -> ConvRow(c, c.id == vm.conversationId, activeRuns.any { it.ownerId == c.id }, { onOpen(c.id) }, { menuFor = c }) }
            if (index.isEmpty()) item { Text("Your conversations will appear here", style = Type.small.copy(fontSize = 15.sp), color = p.text3, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) }
        }
        // bottom bar over a fade: Search pill · settings · new chat
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to p.page2))) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(50)).background(p.card).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    GkIcon(Gk.search, null, 22.dp, p.text2); Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (q.isEmpty()) Text("Search", style = Type.body.copy(fontSize = 17.sp), color = p.text2)
                        BasicTextField(q, { q = it }, singleLine = true, textStyle = Type.body.copy(fontSize = 17.sp, color = p.text), cursorBrush = SolidColor(p.text), modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.width(10.dp))
                CircleButton(Gk.settings, "Settings", bg = p.card) { navigate(Route.Settings) }
                Spacer(Modifier.width(10.dp))
                CircleButton(Gk.newChat, "New chat", bg = p.card, onClick = onNew)
            }
        }
    }
    menuFor?.let { c ->
        AxionSheet({ menuFor = null }) {
            SheetRow(Gk.trash, "Delete", color = p.danger, onClick = { vm.g.chats.delete(c.id); if (vm.conversationId == c.id) vm.newChat(); menuFor = null })
            SheetRow(Gk.pencil, "Rename", onClick = { renaming = c; menuFor = null })
            SheetRow(Gk.pin, if (c.pinned) "Unpin" else "Pin", onClick = { vm.g.chats.pin(c.id, !c.pinned); menuFor = null })
            SheetRow(Gk.share, "Share", onClick = { menuFor = null; vm.shareConversation(c.id) })
        }
    }
    renaming?.let { c ->
        var t by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(onDismissRequest = { renaming = null }, containerColor = p.card, shape = RoundedCornerShape(24.dp),
            title = { Text("Rename", style = Type.title, color = p.text) },
            text = {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.page2).padding(14.dp)) {
                    BasicTextField(t, { t = it }, singleLine = true, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.text), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton({ vm.g.chats.rename(c.id, t.trim().ifBlank { c.title }); if (vm.conversationId == c.id) vm.title = t; renaming = null }) { Text("Save", color = p.text, style = Type.label) } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel", color = p.text2, style = Type.label) } })
    }
}

@Composable
private fun DrawerTile(icon: Int, label: String, onClick: () -> Unit) {
    val p = P; val tap = rememberTap(onClick)
    Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(6.dp)).background(p.card).clickable(onClick = tap).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        GkIcon(icon, null, 22.dp, p.text)
        Spacer(Modifier.width(16.dp))
        Text(label, style = Type.row, color = p.text)
    }
}

@Composable
private fun DrawerHeader(label: String, collapsed: Boolean?, onToggle: () -> Unit) {
    val p = P
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp).background(if (p.isDark) Color(0xFF1F1F1F) else Color(0xFFEDEDED)).clickable(enabled = collapsed != null, onClick = onToggle).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = p.text2, modifier = Modifier.weight(1f))
        if (collapsed != null) GkIcon(if (collapsed) Gk.chevronDown else Gk.chevronDown, null, 18.dp, p.text2, Modifier.graphicsLayer { rotationZ = if (collapsed) 0f else 180f })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConvRow(c: ConversationMeta, selected: Boolean, working: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    val p = P
    Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) p.card else Color.Transparent)
        .combinedClickable(onClick = onOpen, onLongClick = onMenu).padding(start = 13.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.title, style = Type.body.copy(fontSize = 17.sp, lineHeight = 22.sp), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dateLabel(c.updatedAt), style = Type.small.copy(fontSize = 14.sp), color = p.text2, modifier = Modifier.padding(top = 2.dp))
            // v0.8.0 (Phase C): this chat's turn is still running in the background.
            if (working) Text("Working", style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFF3FB950), modifier = Modifier.padding(top = 2.dp))
        }
        IconTap(Gk.more, "More", 18.dp, p.text2, 40.dp, onMenu)
    }
}

private fun dateLabel(t: Long): String {
    val now = System.currentTimeMillis()
    val cal = java.util.Calendar.getInstance()
    val today = cal.apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
    return if (t >= today) java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(t))
    else java.text.SimpleDateFormat("dd MMM", java.util.Locale.ENGLISH).format(java.util.Date(t))
}


/** v0.5: a small "Update available" pill under the top bar after the daily check finds a newer Axion. */
@Composable
private fun BoxScope.UpdateBanner(vm: ChatViewModel, navigate: (Route) -> Unit) {
    val st by vm.g.updater.state.collectAsState()
    val info = (st as? com.mazharansari.axion.update.UpdateState.Available)?.info ?: return
    if (vm.g.updater.dismissedCode == info.versionCode) return
    var hidden by remember(info.versionCode) { mutableStateOf(false) }
    if (hidden) return
    val p = P
    Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp).then(Modifier.zIndex(3f)).clip(RoundedCornerShape(50)).background(Color(0xFF1E3A24))
        .clickable { navigate(Route.About) }.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        GkIcon(Gk.download, null, 15.dp, Color(0xFF7CD4A0)); Spacer(Modifier.width(8.dp))
        Text("Update available · ${info.versionName}", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFD6F5E0))
        Spacer(Modifier.width(4.dp))
        IconTap(Gk.close, "Dismiss", 15.dp, Color(0xFF7CD4A0)) { hidden = true; vm.g.updater.dismissedCode = info.versionCode }
    }
}

/** v0.5.1: space between the chat and the composer (Ask chat and Build project chat share [ChatPane]). */
internal object ChatGap {
    /** Below the last list item, on top of the composer's own height. Item padding (6 dp) adds to it on screen. */
    val LIST_BOTTOM = 52.dp
    /** Height of the fade above the composer top — always smaller than the gap, so resting messages are never dimmed. */
    val FADE = 28.dp
    /** Test renders only: draw the v0.5.0 spacing (12 dp + a fade over the last message) for before/after compares. */
    @Volatile var debugLegacy = false
}
