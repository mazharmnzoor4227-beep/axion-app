package com.mazharansari.axion.ui.chat

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.data.*
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.GoogleSansCode
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

/** The screen's blur source; popovers frost whatever is behind them, like Grok's glass menus. */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

private val PopShape = RoundedCornerShape(22.dp)

/**
 * Grok's anchored glass popover. It is NOT a bottom sheet: it grows out of the composer's bottom-left corner
 * (scale 0.92→1 + fade, ~150 ms), sits 12dp from the screen edge, frosts the chat behind it, and a tap outside
 * closes it. [bottomGap] is the distance from the screen bottom (inside the composer's box) to the popover's bottom.
 */
@Composable
fun BoxScope.GlassPopover(visible: Boolean, onDismiss: () -> Unit, bottomGap: Dp, width: Dp, maxHeight: Dp = Dp.Unspecified, content: @Composable ColumnScope.() -> Unit) {
    val p = P
    // v0.4 (mazhar 2:18 PM): a dark scrim behind every popover so the chat doesn't show through, and the keyboard
    // closes (composer loses focus) the moment a popover opens.
    if (visible) com.mazharansari.axion.ui.components.DismissKeyboard()
    val scrim by animateFloatAsState(if (visible) 0.55f else 0f, tween(140), label = "scrim")
    if (scrim > 0f) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = scrim)).then(if (visible) Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss) else Modifier))
    androidx.activity.compose.BackHandler(visible) { onDismiss() }
    AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = bottomGap),
        enter = fadeIn(tween(120)) + scaleIn(tween(160, easing = FastOutSlowInEasing), initialScale = 0.92f, transformOrigin = TransformOrigin(0f, 1f)),
        exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.95f, transformOrigin = TransformOrigin(0f, 1f)),
    ) {
        val haze = LocalHaze.current
        val glass = if (p.isDark) Color(0xFF262626) else Color(0xFFFFFFFF)
        Column(
            Modifier.width(width).then(if (maxHeight != Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier).clip(PopShape)
                .background(glass)   // v0.4: opaque surface (was see-through glass)
                .border(1.dp, if (p.isDark) Color(0x14FFFFFF) else Color(0x14000000), PopShape)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            content = content,
        )
    }
}

@Composable
fun PopRow(icon: Int, title: String, subtitle: String? = null, selected: Boolean = false, dim: Boolean = false, badge: String? = null, onClick: () -> Unit) {
    val p = P; val tap = rememberTap(onClick)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = if (subtitle != null) 57.dp else 48.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) (if (p.isDark) Color(0x1FFFFFFF) else Color(0x0F000000)) else Color.Transparent)
            .clickable(onClick = tap).padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GkIcon(icon, null, 22.dp, if (dim) p.text3 else p.text)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Type.body.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium), color = if (dim) p.text3 else p.text, maxLines = 1)
                if (badge != null) Text(badge, style = Type.tiny.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = p.text2,
                    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(if (p.isDark) Color(0x1FFFFFFF) else Color(0x0F000000)).padding(horizontal = 7.dp, vertical = 1.dp))
            }
            if (subtitle != null) Text(subtitle, style = Type.small.copy(fontSize = 14.sp, lineHeight = 18.sp), color = if (selected) p.text.copy(alpha = 0.8f) else p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) { Spacer(Modifier.width(8.dp)); GkIcon(Gk.check, "Selected", 20.dp, p.text) }
    }
}

@Composable
fun PopDivider() = Box(Modifier.padding(horizontal = 22.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(if (P.isDark) Color(0x1AFFFFFF) else Color(0x14000000)))

/** + menu: Camera · Gallery · Files │ Skills · Connectors — 174dp wide, 48dp rows, divider after Files (Grok frame 1:43). */
@Composable
fun BoxScope.PlusPopover(visible: Boolean, onDismiss: () -> Unit, bottomGap: Dp, maxHeight: Dp = Dp.Unspecified, onCamera: () -> Unit, onGallery: () -> Unit, onFiles: () -> Unit, onSkills: () -> Unit, onConnectors: () -> Unit, onUploadSkill: (() -> Unit)? = null, onImagine: (() -> Unit)? = null, onBuild: (() -> Unit)? = null) {
    GlassPopover(visible, onDismiss, bottomGap, 196.dp, maxHeight) {
        PopRow(Gk.camera, "Camera") { onDismiss(); onCamera() }
        PopRow(Gk.image, "Gallery") { onDismiss(); onGallery() }
        PopRow(Gk.file, "Files") { onDismiss(); onFiles() }
        PopDivider()
        // v0.3.1: Imagine left the top bar — it lives here now (and the agent makes images when asked).
        if (onImagine != null) PopRow(Gk.imagine, "Imagine") { onDismiss(); onImagine() }
        if (onBuild != null) PopRow(Gk.hammer, "Build") { onDismiss(); onBuild() }
        if (onImagine != null || onBuild != null) PopDivider()
        PopRow(Gk.skills, "Skills") { onDismiss(); onSkills() }
        if (onUploadSkill != null) PopRow(Gk.file, "Upload skill") { onDismiss(); onUploadSkill() }
        PopRow(Gk.connectors, "Connectors") { onDismiss(); onConnectors() }
    }
}

/**
 * Model picker: the same glass popover, 283dp wide, rows "title / grey description" with the chosen mode on a
 * lighter pill and a check at the right (Grok frame 1:39). Grok's top block is a Premium upsell; Axion puts the
 * providers it will use there instead (same layout: title, one grey line, a white pill button).
 */
@Composable
fun BoxScope.ModelPopover(visible: Boolean, onDismiss: () -> Unit, bottomGap: Dp, maxHeight: Dp = Dp.Unspecified, mode: String, connectors: List<Connector>, onPick: (String) -> Unit, onBuild: () -> Unit, onConnectors: () -> Unit) {
    val p = P
    GlassPopover(visible, onDismiss, bottomGap, 283.dp, maxHeight) {
        val on = connectors.filter { it.enabled }
        // Grok's Premium block (icon + title, one grey line, white "Save 50%" pill) → Axion's Providers block
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GkIcon(Gk.connectors, null, 22.dp, p.text); Spacer(Modifier.width(8.dp))
                Text("Providers", style = Type.body.copy(fontSize = 19.sp, fontWeight = FontWeight.Medium), color = p.text)
            }
            Text(if (on.isEmpty()) "Add a free API key to start" else "${on.size} key${if (on.size > 1) "s" else ""} · auto-switch on limits",
                style = Type.small.copy(fontSize = 13.sp), color = p.text2, maxLines = 1, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            Box(Modifier.height(38.dp).clip(RoundedCornerShape(50)).background(p.accent).clickable { onDismiss(); onConnectors() }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(if (on.isEmpty()) "Add key" else "Manage keys", style = Type.small.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), color = p.onAccent)
            }
        }
        PopDivider()
        PopRow(Gk.hammer, "Build", "Build apps and sites", badge = "Beta") { onDismiss(); onBuild() }
        PopRow(Gk.think, "Expert", "Thinks hard", selected = mode == "EXPERT") { onPick("EXPERT"); onDismiss() }
        PopRow(Gk.bolt, "Fast", "Quick responses", selected = mode == "FAST") { onPick("FAST"); onDismiss() }
        PopRow(Gk.rocket, "Auto", "Chooses Fast or Expert", selected = mode == "AUTO") { onPick("AUTO"); onDismiss() }
        // v0.5 (addendum 6): the user's own models, only when set up
        val local = connectors.firstOrNull { it.providerId == "local" && it.enabled }
        val server = connectors.firstOrNull { it.providerId == "myserver" && it.enabled }
        if (local != null || server != null) PopDivider()
        if (local != null) PopRow(Gk.phone, "Local model", "${local.keyHint} · offline, slower", selected = mode == "LOCAL") { onPick("LOCAL"); onDismiss() }
        if (server != null) PopRow(Gk.database, "My server", "${server.pinnedModel ?: server.keyHint} · slower", selected = mode == "SERVER") { onPick("SERVER"); onDismiss() }
        // v0.5.2 (mazhar's test): the built-in no-key model, by name — Auto also falls back to it when no key works.
        PopDivider()
        PopRow(Gk.globe, "Free (slow)", "No key needed · about 1 reply every 45 s", selected = mode == "FREE") { onPick("FREE"); onDismiss() }
    }
}

/** Grok's bottom sheet: #181818, 28dp top corners, 36×4dp grey handle, dim scrim. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AxionSheet(onDismiss: () -> Unit, skipPartial: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val p = P
    com.mazharansari.axion.ui.components.DismissKeyboard()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartial),
        containerColor = p.page2, contentColor = p.text, scrimColor = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(if (p.isDark) Color(0xFF4D4D4D) else Color(0xFFCCCCCC))) },
    ) { Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp), content = content) }
}

@Composable
fun SheetRow(icon: Int, title: String, subtitle: String? = null, color: Color = P.text, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val p = P; val tap = rememberTap(onClick)
    Row(Modifier.fillMaxWidth().clickable(onClick = tap).padding(horizontal = 24.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        GkIcon(icon, null, 22.dp, color)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.row, color = color)
            if (subtitle != null) Text(subtitle, style = Type.small, color = p.text2)
        }
        trailing()
    }
}

fun stepIcon(k: StepKind) = when (k) {
    StepKind.SEARCH -> Gk.search; StepKind.BROWSE -> Gk.globe; StepKind.COMMAND -> Gk.terminal; StepKind.READ -> Gk.readFile
    StepKind.WRITE -> Gk.writeFile; StepKind.SKILL -> Gk.skills; StepKind.PDF -> Gk.writeFile; StepKind.ZIP -> Gk.folder
    StepKind.MCP -> Gk.connectors; StepKind.THINK -> Gk.think; StepKind.OTHER -> Gk.tool
}

/** "Ran 2 searches" / "Browsed claworabeauty.store" — grey verb, white target (filenames in Google Sans Code). */
@Composable
fun StepText(s: Step, size: Int = 16) {
    val p = P
    val mono = s.kind == StepKind.READ || s.kind == StepKind.WRITE || s.kind == StepKind.PDF || s.kind == StepKind.SKILL || s.kind == StepKind.ZIP
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = p.text2)) { append(s.title) }
        if (s.target.isNotBlank()) {
            append("  ")
            withStyle(SpanStyle(color = p.text, fontFamily = if (mono) GoogleSansCode else null, fontSize = if (mono) (size - 1).sp else size.sp)) { append(s.target.take(90)) }
        }
    }, style = Type.body.copy(fontSize = size.sp, lineHeight = (size + 6).sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Grok's Thoughts sheet (frame 1:30): hugs its content; one 17sp line per step group — grey verb, white target (mono for files) — with the reasoning summary as bulb lines. */
@Composable
fun ThoughtsSheet(m: Message, onDismiss: () -> Unit) {
    val p = P
    // v0.3: the WHOLE run — full thinking text, then every step; tap a step to open its code / output.
    var full by remember { mutableStateOf(true) }
    AxionSheet(onDismiss) {
        Text("Thoughts", style = Type.header.copy(fontSize = 18.sp), color = p.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp))
        val runs = remember(m.steps) { runsOf(m.steps) }
        val firstThought = remember(m.reasoning) { m.reasoning.lineSequence().map { it.trim().trimStart('*', '#', ' ').trimEnd('*') }.firstOrNull { it.length > 3 }?.substringBefore(". ")?.take(70) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            if (firstThought != null) item(key = "r0") {
                Column(Modifier.clickable { full = !full }) {
                    ThoughtLine(Gk.think) { Text(if (full) "Thinking" else firstThought, style = ThoughtStyle, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (full) Text(m.reasoning.takeLast(20_000), style = Type.body.copy(fontSize = 15.sp, lineHeight = 22.sp), color = p.text2, modifier = Modifier.padding(start = 30.dp, top = 8.dp))
                }
            }
            items(runs.size, key = { "run$it" }) { i ->
                val r = runs[i]
                Column {
                    ThoughtLine(stepIcon(r.kind)) { Text(runLabel(r), style = ThoughtStyle, color = p.text2, maxLines = 1) }
                    Column(Modifier.padding(start = 30.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        r.steps.forEach { s -> key(s.id) { if (r.kind == StepKind.COMMAND) BashCard(s) else ExpandableStep(s, 15) } }
                    }
                }
            }
        }
    }
}

private val ThoughtStyle @Composable get() = Type.body.copy(fontSize = 17.sp, lineHeight = 24.sp)

@Composable
private fun ThoughtLine(icon: Int, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        GkIcon(icon, null, 19.dp, P.text2); Spacer(Modifier.width(10.dp)); content()
    }
}


/**
 * v0.4 Build's picker (mazhar 2:28 PM): Antigravity accounts and models only — no API keys here. Tapping an account
 * makes it the one Build starts on; auto-switch still moves on when it hits a limit.
 */
@Composable
fun BoxScope.BuildPopover(visible: Boolean, onDismiss: () -> Unit, bottomGap: Dp, maxHeight: Dp = Dp.Unspecified, agy: com.mazharansari.axion.antigravity.AgyAccounts, onManage: () -> Unit) {
    val p = P
    val store by agy.store.collectAsState()
    GlassPopover(visible, onDismiss, bottomGap, 283.dp, maxHeight) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.mazharansari.axion.ui.settings.ProviderLogo("antigravity", 22.dp); Spacer(Modifier.width(8.dp))
                Text("Antigravity", style = Type.body.copy(fontSize = 19.sp, fontWeight = FontWeight.Medium), color = p.text)
            }
            Text(if (store.autoSwitch) "Auto-switch on limits · model: ${store.model.ifBlank { "default" }}" else "Model: ${store.model.ifBlank { "default" }}",
                style = Type.small.copy(fontSize = 13.sp), color = p.text2, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        }
        PopDivider()
        val now = System.currentTimeMillis()
        store.accounts.sortedBy { it.order }.forEach { a ->
            val sub = when {
                a.authExpiredAt > 0 -> "Signed out — sign in again"
                a.cooldownUntil > now -> "Resting · back ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(a.cooldownUntil))}"
                !a.enabled -> "Off"
                else -> when (a.tier) { "pro" -> "AI Pro"; "ultra" -> "Ultra"; "free" -> "Free"; else -> "Ready" }
            }
            PopRow(Gk.person, a.email.substringBefore('@'), sub, selected = a.email.equals(store.activeEmail, true), dim = a.authExpiredAt > 0 || !a.enabled) { agy.setActive(a.email); onDismiss() }
        }
        if (store.models.isNotEmpty()) {
            PopDivider()
            store.models.take(6).forEach { m -> PopRow(Gk.brain, m, null, selected = m == store.model) { agy.setModel(m); onDismiss() } }
        }
        PopDivider()
        PopRow(Gk.settings, "Manage accounts", "Add, sign in, quota") { onDismiss(); onManage() }
    }
}
