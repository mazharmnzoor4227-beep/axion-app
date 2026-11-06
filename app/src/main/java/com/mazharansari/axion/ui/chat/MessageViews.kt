package com.mazharansari.axion.ui.chat

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mazharansari.axion.data.*
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Grok user bubble: right-aligned #242424, 22dp corners with a 6dp bottom-right, 12dp from the edge, 16sp text. */
@Composable
fun UserBubble(m: Message, onLongPress: () -> Unit = {}) {
    val p = P
    Column(Modifier.fillMaxWidth().padding(start = 64.dp, end = 12.dp, top = 10.dp, bottom = 6.dp), horizontalAlignment = Alignment.End) {
        if (m.attachments.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.attachments.forEach { AttachmentChip(it) }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (m.text.isNotBlank()) {
            SelectionContainer {
                Text(m.text, style = Type.body, color = p.text,
                    modifier = Modifier.clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp)).background(p.card)
                        .padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
fun AttachmentChip(a: Attachment, onRemove: (() -> Unit)? = null) {
    val p = P
    Box {
        if (a.mime.startsWith("image/")) {
            AsyncImage(a.path, a.name, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(p.card), contentScale = ContentScale.Crop)
        } else {
            Row(Modifier.height(56.dp).widthIn(max = 230.dp).clip(RoundedCornerShape(14.dp)).background(p.card).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                GkIcon(if (a.mime.contains("pdf")) Gk.pdf else if (a.mime.contains("zip")) Gk.folder else Gk.file, null, 22.dp, if (a.mime.contains("pdf")) Color(0xFFFF4245) else p.text2)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(a.name, style = Type.small, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sizeLabel(a.size), style = Type.tiny, color = p.text3)
                }
            }
        }
        if (onRemove != null) Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape).background(p.chip).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            GkIcon(Gk.close, "Remove", 12.dp, p.text)
        }
    }
}

fun sizeLabel(b: Long) = when { b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0); b >= 1024 -> "${b / 1024} KB"; else -> "$b B" }

fun durationLabel(ms: Long): String { val s = (ms / 1000).coerceAtLeast(1); return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s" }

/** One run of same-kind steps, shown the way Grok groups them ("Loaded 1 skill", "Running 2 commands"). */
internal data class StepRun(val kind: StepKind, val steps: List<Step>)

internal fun runsOf(steps: List<Step>): List<StepRun> {
    val out = mutableListOf<StepRun>()
    for (s in steps) {
        val k = if (s.kind == StepKind.PDF || s.kind == StepKind.WRITE) StepKind.WRITE else s.kind
        if (out.isNotEmpty() && out.last().kind == k) out[out.size - 1] = out.last().copy(steps = out.last().steps + s) else out += StepRun(k, listOf(s))
    }
    return out
}

internal fun runLabel(r: StepRun): String {
    val n = r.steps.size; val live = r.steps.any { it.status == StepStatus.RUNNING }
    fun pl(w: String, pw: String = w + "s") = if (n == 1) w else pw
    return when (r.kind) {
        StepKind.SKILL -> if (live) "Loading ${pl("skill")}" else "Loaded $n ${pl("skill")}"
        StepKind.SEARCH -> if (live) "Searching the web" else "Ran $n ${pl("search", "searches")}"
        StepKind.BROWSE -> if (live) "Browsing" else "Browsed $n ${pl("page")}"
        StepKind.COMMAND -> if (live) "Running $n ${pl("command")}" else "Ran $n ${pl("command")}"
        StepKind.READ -> if (live) "Reading" else "Read $n ${pl("file")}"
        StepKind.WRITE -> if (live) "Writing" else "Created $n ${pl("file")}"
        StepKind.ZIP -> "Extracted $n ${pl("archive")}"
        StepKind.PDF -> if (live) "Making PDF" else "Made $n ${pl("PDF")}"
        StepKind.MCP -> "Used $n ${pl("tool")}"
        StepKind.THINK -> "Thinking"
        StepKind.OTHER -> "Used $n ${pl("tool")}"
    }
}

/** Grok assistant turn: no bubble, 16dp margins; Thinking header → grouped steps → Bash cards → answer → actions. */
@Composable
fun AssistantMessage(
    m: Message, isLast: Boolean, speaking: Boolean,
    onOpenThoughts: () -> Unit, onCopy: () -> Unit, onSpeak: () -> Unit, onRegenerate: () -> Unit, onShare: () -> Unit,
    onRate: (Int) -> Unit, onOpenFile: (Artifact) -> Unit, onDownload: (Artifact) -> Unit,
) {
    val p = P
    val streaming = m.status == MsgStatus.STREAMING
    // v0.3.1: Grok's reply column is 12 dp from each screen edge (measured), not 16.
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        if (m.plan.isNotEmpty()) { PlanCard(m.plan); Spacer(Modifier.height(10.dp)) }   // axion-p2
        if (m.steps.isNotEmpty() || m.reasoning.isNotBlank() || streaming) ThoughtsHeader(m, onOpenThoughts)
        if (streaming && m.notice.isNotBlank()) Text(m.notice, style = Type.small, color = p.text2, maxLines = 1, modifier = Modifier.padding(start = 30.dp, top = 2.dp))
        val runs = remember(m.steps) { runsOf(m.steps) }
        runs.forEachIndexed { i, r ->
            if (r.kind == StepKind.COMMAND || r.kind == StepKind.WRITE || streaming) key(r.kind.name + i) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onOpenThoughts)) {
                    GkIcon(stepIcon(r.kind), null, 18.dp, p.text2); Spacer(Modifier.width(12.dp))
                    Text(runLabel(r), style = Type.body.copy(fontSize = 16.sp), color = p.text2)
                }
                Column(Modifier.padding(start = 30.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.kind == StepKind.COMMAND) r.steps.forEach { s -> key(s.id) { BashCard(s) } }
                    else (if (r.kind == StepKind.WRITE) r.steps else r.steps.takeLast(3)).forEach { s -> key(s.id) { ExpandableStep(s, 15) } }
                }
            }
        }
        val pages = m.steps.count { it.kind == StepKind.BROWSE && it.status == StepStatus.DONE }
        if (pages > 0 && !streaming) { Spacer(Modifier.height(10.dp)); SourcesPill(pages, onOpenThoughts) }
        // Grok puts the document card first, then the words about it; images stay under the text.
        val (docs, images) = m.artifacts.partition { !it.mime.startsWith("image/") }
        docs.forEach { a -> Spacer(Modifier.height(12.dp)); ArtifactCard(a, { onOpenFile(a) }, { onDownload(a) }) }
        if (m.text.isNotBlank()) { Spacer(Modifier.height(12.dp)); Markdown(m.text) }
        if (m.usedTools.isNotEmpty()) Text((if (m.usedTools.size == 1) "Used tool: " else "Used tools: ") + m.usedTools.joinToString(", "), style = Type.small.copy(fontSize = 13.sp), color = p.text3, modifier = Modifier.padding(top = 10.dp))   // axion-p3
        images.forEach { a -> Spacer(Modifier.height(14.dp)); ImageArtifact(a, { onOpenFile(a) }, { onDownload(a) }) }
        if (m.status == MsgStatus.INTERRUPTED) Text("Response interrupted", style = Type.body, color = p.text2, modifier = Modifier.padding(top = 10.dp))
        if (m.status == MsgStatus.ERROR) ErrorCard(m.error)
        if (!streaming) MessageActions(m, isLast, speaking, onCopy, onSpeak, onRegenerate, onShare, onRate)
    }
}

/** "⣿ Thinking for 1m 2s ›" while working (3×3 dot grid shimmering), "💡 Thought for 1m 36s ›" after. */
@Composable
private fun ThoughtsHeader(m: Message, onClick: () -> Unit) {
    val p = P
    val streaming = m.status == MsgStatus.STREAMING
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (streaming) LaunchedEffect(m.id) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val elapsed = if (streaming) now - m.thinkingStartedAt else m.thinkingMs
    val label = if (streaming && m.text.isEmpty()) "Thinking for ${durationLabel(elapsed)}" else "Thought for ${durationLabel(elapsed)}"
    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (streaming && m.text.isEmpty()) DotGrid() else GkIcon(Gk.think, null, 18.dp, p.text2)
        Spacer(Modifier.width(12.dp))
        if (streaming && m.text.isEmpty()) ShimmerText(label) else Text(label, style = Type.body.copy(fontSize = 16.sp), color = p.text2, maxLines = 1)
        Spacer(Modifier.width(2.dp))
        GkIcon(Gk.chevronRight, null, 16.dp, p.text2)
    }
}

/**
 * The thinking glyph, redrawn 2026-11-06: the same composing ribbon-orb as the call screen, resolved
 * at the thinking-orbs 20px preset — so "thinking" looks identical everywhere in the app. Same 18dp
 * footprint and call sites as the old 3x3 grid; only the drawing changed.
 */
@Composable
fun DotGrid(size: androidx.compose.ui.unit.Dp = 18.dp) {
    val p = P
    var elapsed by remember { mutableFloatStateOf(0f) }
    if (!com.mazharansari.axion.ui.AnimationGate.inTestRuntime) LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { elapsed = (it - start) / 1_000_000_000f }
    }
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        for (d in com.mazharansari.axion.ui.settings.ThinkingOrbEngine.ribbonSmall(s, elapsed * 3.12f)) {
            val ink = (1f - d.white).coerceIn(0f, 1f)
            val col = androidx.compose.ui.graphics.lerp(p.bg, p.text, ink)
            drawCircle(col.copy(alpha = d.a), d.r, androidx.compose.ui.geometry.Offset(d.x, d.y))
        }
    }
}

@Composable
fun ShimmerText(text: String) {
    val p = P
    if (com.mazharansari.axion.ui.AnimationGate.inTestRuntime) {
        Text(text, style = Type.body.copy(fontSize = 16.sp), color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 300.dp)); return
    }
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-300f, 900f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "x")
    Text(text, style = Type.body.copy(fontSize = 16.sp, brush = Brush.linearGradient(listOf(p.text2, p.text, p.text2),
        start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x + 260f, 0f))),
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 300.dp))
}

@Composable
internal fun PulseDot() {
    if (com.mazharansari.axion.ui.AnimationGate.inTestRuntime) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(P.text)); return
    }
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(8.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(P.text))
}

@Composable
fun StepLine(s: Step, compact: Boolean = false) {
    val p = P
    Row(verticalAlignment = Alignment.CenterVertically) {
        GkIcon(stepIcon(s.kind), null, if (compact) 14.dp else 18.dp, if (s.status == StepStatus.ERROR) p.danger else p.text2)
        Spacer(Modifier.width(10.dp))
        StepText(s, if (compact) 14 else 16)
    }
}

/**
 * Grok's Bash card: hairline-rimmed #111111 card, 14dp corners, "▢ Bash" header with copy at right, then the
 * script in Google Sans Code (shell lines khaki #CACBB2, code/strings salmon #B38F82) clipped to ~6 lines with
 * a fade; tap to expand. The command ran hidden in the sandbox — this card is only the record.
 */
@Composable
fun BashCard(s: Step) {
    val p = P
    var open by rememberSaveable(s.id) { mutableStateOf(false) }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(p.code).border(1.dp, if (p.isDark) Color(0xFF222222) else p.border, shape)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            GkIcon(Gk.terminal, null, 16.dp, p.text)
            Spacer(Modifier.width(8.dp))
            Text("Bash", style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = p.text, modifier = Modifier.weight(1f))
            // axion-p1.4: explicit result chip — Running… / Done · exit 0 / Failed · exit N / Timed out / Stopped
            val st = remember(s.status, s.exitCode, s.output) { com.mazharansari.axion.agent.CommandStatusLogic.status(s.status, s.exitCode, s.output) }
            val chipColor = when (st.kind) { com.mazharansari.axion.agent.CommandStatusLogic.Kind.OK -> Color(0xFF3FB950); com.mazharansari.axion.agent.CommandStatusLogic.Kind.RUNNING -> p.text2
                com.mazharansari.axion.agent.CommandStatusLogic.Kind.STOPPED -> p.text2; else -> p.danger }
            Row(Modifier.clip(RoundedCornerShape(50)).background(chipColor.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (st.kind == com.mazharansari.axion.agent.CommandStatusLogic.Kind.RUNNING) { PulseDot(); Spacer(Modifier.width(6.dp)) }
                Text(st.label, style = Type.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = chipColor)
            }
            IconTap(Gk.copy, "Copy", 18.dp, p.text2, 36.dp) { clip.setText(androidx.compose.ui.text.AnnotatedString(s.target + if (s.output.isNotBlank()) "\n\n" + com.mazharansari.axion.agent.CommandStatusLogic.body(s.output) else "")) }
        }
        val code = remember(s.target, s.output) {
            buildAnnotatedString {
                s.target.lines().forEachIndexed { li, line ->
                    if (li == 0) withStyle(SpanStyle(color = Color(0xFF6E6E6E))) { append("$ ") }   // axion-p1.4: prompt
                    val shell = Regex("^\\s*(cd|ls|cat|echo|python3?|pip|npm|node|curl|wget|grep|find|mkdir|rm|cp|mv|unzip|zip|git|chmod|sed|awk|head|tail|wc)\\b").containsMatchIn(line)
                    withStyle(SpanStyle(color = if (shell) Color(0xFFCACBB2) else Color(0xFFB38F82))) { append(line) }
                    append('\n')
                }
                if (s.output.isNotBlank()) {   // axion-p1.4: output tailed to the last 200 lines; the chip shows the exit status
                    val (tailed, hidden) = com.mazharansari.axion.agent.CommandStatusLogic.tail(com.mazharansari.axion.agent.CommandStatusLogic.body(s.output).takeLast(12000), 200)
                    if (hidden > 0) withStyle(SpanStyle(color = Color(0xFF6E6E6E))) { append("… $hidden earlier lines hidden\n") }
                    withStyle(SpanStyle(color = Color(0xFF8A8A8A))) { append(tailed) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().animateContentSize(tween(180)).then(if (open) Modifier else Modifier.heightIn(max = 132.dp))
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (!open) drawRect(Brush.verticalGradient(0.7f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }) {
            SelectionContainer {
                Text(code, style = Type.mono.copy(fontSize = 13.sp, lineHeight = 20.sp), softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp))
            }
        }
    }
}

@Composable
private fun SourcesPill(pages: Int, onClick: () -> Unit) {
    val p = P
    Row(Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(p.surface).clickable(onClick = onClick).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(p.chip), contentAlignment = Alignment.Center) { GkIcon(Gk.globe, null, 13.dp, p.text) }
        Spacer(Modifier.width(8.dp))
        Text("$pages page${if (pages > 1) "s" else ""}", style = Type.small.copy(fontSize = 15.sp), color = p.text)
    }
}

/**
 * v0.3.1 — Grok's PDF deliverable (mazhar's 10:33 recording): no header, just page 1 filling the reply width in a 16 dp
 * rounded card, and a dark "PDF" tag (99×60 dp, 15 dp corners, 22 sp) centred 43 dp above the card's bottom edge.
 * Tap → Axion's own viewer (every page, share, download). Other files keep the named card.
 */
@Composable
fun ArtifactCard(a: Artifact, onOpen: () -> Unit, onDownload: () -> Unit) {
    val p = P
    val isPdf = a.mime == "application/pdf"
    if (isPdf) {
        val shape = RoundedCornerShape(16.dp)
        val bmp by produceState<Bitmap?>(null, a.path) { value = withContext(Dispatchers.IO) { pdfPageBitmap(a.path, 0, 900) } }
        Box(Modifier.fillMaxWidth().height(236.dp).clip(shape).background(Color.White).clickable(onClick = onOpen)
            .semantics { contentDescription = "PDF ${a.name}" }) {
            bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()) }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 13.dp).size(99.dp, 60.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xE6191919)),
                contentAlignment = Alignment.Center) {
                Text("PDF", style = Type.body.copy(fontSize = 22.sp, lineHeight = 26.sp), color = Color.White)
            }
        }
        return
    }
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(p.surface).border(1.dp, p.border, shape).clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GkIcon(Gk.file, null, 22.dp, p.text2)
            Spacer(Modifier.width(10.dp))
            Text(a.name, style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconTap(Gk.download, "Download", 20.dp, p.text2, 40.dp, onDownload)
            IconTap(Gk.expand, "Open", 20.dp, p.text2, 40.dp, onOpen)
        }
        run {
            val kind = a.name.substringAfterLast('.', "file").uppercase()
            Text(listOfNotNull(kind, a.pages.takeIf { it > 0 }?.let { "$it page${if (it > 1) "s" else ""}" }, sizeLabel(a.size)).joinToString(" · "),
                style = Type.small, color = p.text2, modifier = Modifier.padding(start = 46.dp, bottom = 12.dp))
        }
    }
}

/** Page [index] of a PDF: Axion's own page image when Axion made it, else the phone's PDF renderer. */
fun pdfPageBitmap(path: String, index: Int, width: Int): Bitmap? {
    val f = File(path)
    val pre = File(com.mazharansari.axion.agent.PdfWriter.previewDir(f), "page_%03d.png".format(index + 1))
    if (pre.exists()) runCatching { android.graphics.BitmapFactory.decodeFile(pre.path) }.getOrNull()?.let { return it }
    return runCatching {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(index).use { pg ->
                    val h = (width * pg.height / pg.width.toFloat()).toInt()
                    Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.WHITE); pg.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                }
            }
        }
    }.getOrNull()
}

fun pdfPageCount(path: String): Int {
    val f = File(path)
    val dir = com.mazharansari.axion.agent.PdfWriter.previewDir(f)
    dir.listFiles { x -> x.name.startsWith("page_") }?.size?.takeIf { it > 0 }?.let { return it }
    return runCatching { ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } } }.getOrDefault(1)
}

@Composable
fun ImageArtifact(a: Artifact, onOpen: () -> Unit, onDownload: () -> Unit) {
    Box(Modifier.fillMaxWidth(0.82f)) {
        AsyncImage(a.path, a.name, contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.surface).clickable(onClick = onOpen))
        Row(Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))) {
            IconTap(Gk.download, "Save", tint = Color.White, onClick = onDownload)
        }
    }
}

@Composable
private fun ErrorCard(msg: String) {
    val p = P
    // v0.3: one friendly line; the provider's own words only behind "Details" (format: "line\u0001details").
    val line = msg.substringBefore('\u0001'); val details = msg.substringAfter('\u0001', "")
    var open by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, p.border, RoundedCornerShape(14.dp)).padding(12.dp)) {
        GkIcon(Gk.info, null, 18.dp, p.danger)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(line, style = Type.small, color = p.text2)
            if (details.isNotBlank()) {
                Text(if (open) "Hide details" else "Details", style = Type.small, color = p.text, modifier = Modifier.padding(top = 6.dp).clickable { open = !open })
                if (open) Text(details, style = Type.small.copy(fontSize = 12.sp), color = p.text3, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * Grok action row (v0.3.1, measured): copy · share · 👍 · 👎 · read-aloud · regenerate — 18 dp grey glyphs on a 48 dp
 * pitch, the first glyph centred 24 dp from the screen edge (so the row starts 12 dp left of the text).
 */
@Composable
private fun MessageActions(m: Message, isLast: Boolean, speaking: Boolean, onCopy: () -> Unit, onSpeak: () -> Unit, onRegenerate: () -> Unit, onShare: () -> Unit, onRate: (Int) -> Unit) {
    val p = P
    Row(Modifier.padding(top = 6.dp).offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
        if (m.text.isNotBlank()) IconTap(Gk.copy, "Copy", 18.dp, p.text2, 48.dp, onCopy)
        if (m.text.isNotBlank()) IconTap(Gk.share, "Share", 18.dp, p.text2, 48.dp, onShare)
        IconTap(Gk.thumbsUp, "Like", 18.dp, if (m.rating == 1) p.text else p.text2, 48.dp) { onRate(1) }
        IconTap(Gk.thumbsDown, "Dislike", 18.dp, if (m.rating == -1) p.text else p.text2, 48.dp) { onRate(-1) }
        if (m.text.isNotBlank()) IconTap(Gk.speaker, if (speaking) "Stop reading" else "Read aloud", 18.dp, if (speaking) p.text else p.text2, 48.dp, onSpeak)
        if (isLast) IconTap(Gk.refresh, "Regenerate", 18.dp, p.text2, 48.dp, onRegenerate)
        if (m.model.isNotBlank()) Text(m.model.substringAfter("· ") + (if (m.firstWordMs > 0) " · " + "%.1f s".format(java.util.Locale.ENGLISH, m.firstWordMs / 1000.0) else ""), style = Type.tiny, color = p.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp).weight(1f, fill = false))
    }
}
