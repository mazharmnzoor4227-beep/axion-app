package com.mazharansari.axion.ui.build

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.build.ChangeGate
import com.mazharansari.axion.build.PatchEngine
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.chat.AxionSheet
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type

/**
 * v0.6 (Phase 1.2) Review changes: every file the AI wants to add / edit / delete, as a before/after diff.
 * Nothing is written into the project until Approve; Reject throws the whole change away. A file can be unticked:
 * Approve applies the ticked files only and discards the rest.
 */
@Composable
fun ReviewChangesSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val review = vm.pendingReview ?: run { onDismiss(); return }
    val p = P
    val set = review.set
    val selected = remember(review.projectId, set.createdAt) { mutableStateListOf<String>().apply { addAll(set.changes.map { it.path }) } }
    var open by remember(review.projectId, set.createdAt) { mutableStateOf<String?>(set.changes.singleOrNull()?.path) }
    AxionSheet(onDismiss, skipPartial = true) {
        Text("Review changes", style = Type.title, color = p.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        Text("${set.changes.size} file${if (set.changes.size == 1) "" else "s"} · +${set.totalAdded} −${set.totalRemoved}. Nothing is applied until you approve.",
            style = Type.small, color = p.text2, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp))
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 520.dp)) {
            items(set.changes, key = { it.path }) { c ->
                val isOpen = open == c.path
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(p.card)) {
                    Row(Modifier.fillMaxWidth().clickable { open = if (isOpen) null else c.path }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        val on = c.path in selected
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(if (on) P.text else Color.Transparent)
                            .clickable { if (on) selected.remove(c.path) else selected.add(c.path) }, contentAlignment = Alignment.Center) {
                            if (on) GkIcon(Gk.check, null, 15.dp, P.onAccent)
                            else Box(Modifier.matchParentSize().clip(RoundedCornerShape(7.dp)).background(p.surface2))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.path, style = Type.mono.copy(fontSize = 13.sp), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when { c.binary -> "${statusWord(c.status)} · binary · ${c.size / 1024} KB"; else -> "${statusWord(c.status)} · +${c.added} −${c.removed}" },
                                style = Type.small, color = statusColor(c.status))
                        }
                    }
                    if (isOpen) DiffBody(vm, c)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val rej = rememberTap { vm.rejectReview(); onDismiss() }
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(50)).background(p.surface2).clickable(onClick = rej), contentAlignment = Alignment.Center) {
                Text("Reject", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = BRed)
            }
            val appr = rememberTap { vm.approveReview(selected.toSet()); onDismiss() }
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(50)).background(if (selected.isEmpty()) p.surface2 else p.text)
                .clickable(enabled = selected.isNotEmpty(), onClick = appr), contentAlignment = Alignment.Center) {
                Text(if (selected.size == set.changes.size) "Approve all" else "Approve ${selected.size}", style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = if (selected.isEmpty()) p.text3 else p.onAccent)
            }
        }
    }
}

private fun statusWord(s: ChangeGate.Status) = when (s) { ChangeGate.Status.ADDED -> "New file"; ChangeGate.Status.MODIFIED -> "Edited"; ChangeGate.Status.DELETED -> "Deleted" }
private fun statusColor(s: ChangeGate.Status) = when (s) { ChangeGate.Status.ADDED -> BGreen; ChangeGate.Status.MODIFIED -> Color(0xFFE3B341); ChangeGate.Status.DELETED -> BRed }

@Composable
private fun DiffBody(vm: ChatViewModel, c: ChangeGate.FileChange) {
    val p = P
    val lines by produceState<List<PatchEngine.Line>?>(null, c.path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val (old, new) = vm.reviewTexts(c)
            if (old == null && new == null) emptyList() else PatchEngine.diff(old.orEmpty(), new.orEmpty())
        }
    }
    Column(Modifier.fillMaxWidth().background(if (p.isDark) Color(0xFF0E0E0E) else Color(0xFFF1F1F1)).padding(vertical = 6.dp)) {
        val ls = lines
        when {
            c.binary -> Text("Binary file - cannot be shown as text.", style = Type.small, color = p.text2, modifier = Modifier.padding(12.dp))
            ls == null -> Text("Loading…", style = Type.small, color = p.text2, modifier = Modifier.padding(12.dp))
            ls.isEmpty() -> Text("No text to show.", style = Type.small, color = p.text2, modifier = Modifier.padding(12.dp))
            else -> {
                // only changed lines plus 2 lines of context, so a one-line edit in a 600-line file stays readable
                val keep = BooleanArray(ls.size)
                ls.forEachIndexed { i, l -> if (l.op != PatchEngine.Op.SAME) for (j in (i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(ls.lastIndex)) keep[j] = true }
                val shown = ls.indices.filter { keep[it] }.take(400)
                Column(Modifier.heightIn(max = 360.dp).horizontalScroll(rememberScrollState())) {
                    var prev = -2
                    for (i in shown) {
                        if (prev >= 0 && i != prev + 1) Text("  …", style = Type.mono.copy(fontSize = 11.sp), color = p.text3)
                        prev = i
                        val l = ls[i]
                        val (mark, bg, fg) = when (l.op) {
                            PatchEngine.Op.ADD -> Triple("+", BGreen.copy(alpha = 0.16f), BGreen)
                            PatchEngine.Op.DEL -> Triple("−", BRed.copy(alpha = 0.16f), BRed)
                            else -> Triple(" ", Color.Transparent, p.text2)
                        }
                        Text("$mark ${l.text}", style = Type.mono.copy(fontSize = 11.5.sp, lineHeight = 16.sp), color = fg, softWrap = false,
                            modifier = Modifier.background(bg).padding(horizontal = 10.dp).widthIn(min = 300.dp))
                    }
                    if (shown.size >= 400) Text("  … diff cut after 400 lines", style = Type.mono.copy(fontSize = 11.sp), color = p.text3)
                }
            }
        }
    }
}

/** Small pill above the composer while a change waits for review. */
@Composable
fun PendingReviewPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = P; val tap = rememberTap(onClick)
    Row(modifier.clip(RoundedCornerShape(50)).background(p.text).clickable(onClick = tap).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        GkIcon(Gk.eye, null, 17.dp, p.onAccent); Spacer(Modifier.width(8.dp))
        Text("$count change${if (count == 1) "" else "s"} waiting · Review", style = Type.small.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = p.onAccent)
    }
}
