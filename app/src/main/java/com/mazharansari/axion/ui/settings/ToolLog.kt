package com.mazharansari.axion.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.5.2 (mazhar's phone: "Could not install: …" and nothing else to go on): under a failed install, "View log" shows the
 * real apt / Linux output — the last 200 lines, selectable, with Copy and Share — and "Retry" runs the install again.
 * The log is scrubbed of keys and tokens before it is kept (SecretScanner).
 */
@Composable
fun ToolFailedActions(vm: ChatViewModel, toolId: String, onRetry: () -> Unit) {
    var open by remember { mutableStateOf(vm.debugToolLog == toolId) }
    val ctx = LocalContext.current
    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("View full log") { open = true }
        SmallButton("Copy") { vm.toast = copyLog(ctx, vm, toolId) }
        SmallButton("Share") { vm.toast = shareLog(ctx, vm, toolId) }
        SmallButton("Retry", filled = true) { onRetry() }
    }
    if (open) ToolLogDialog(vm, toolId) { open = false; vm.debugToolLog = null }
}

private fun copyLog(ctx: android.content.Context, vm: ChatViewModel, toolId: String): String {
    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
    cm?.setPrimaryClip(android.content.ClipData.newPlainText("Axion install log", vm.g.devtools.logText(toolId, 200)))
    return "Log copied"
}

private fun shareLog(ctx: android.content.Context, vm: ChatViewModel, toolId: String): String? = runCatching {
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    val f = File(dir, "axion-$toolId-install-log.txt").apply { writeText(vm.g.devtools.logText(toolId, 2000)) }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share install log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    null
}.getOrElse { "Couldn't share the log" }

/**
 * v0.5.2 (mazhar 5:26 PM): the live Antigravity setup panel — every step as it happens (✓ done, % on the one running,
 * ✗ where it failed) with the command output streaming underneath. On success it closes by itself and shows
 * "Antigravity ready"; on failure it stays open with the error and View full log / Copy / Share / Retry.
 */
@Composable
fun AgySetupPanel(vm: ChatViewModel, onRetry: () -> Unit) {
    val p = P; val dt = vm.g.devtools
    val status by dt.status.collectAsState()
    val plans by dt.steps.collectAsState()
    val current by dt.step.collectAsState()
    val live by dt.live.collectAsState()
    val st = status["agy"]
    val plan = plans["agy"].orEmpty()
    var wasWorking by remember { mutableStateOf(false) }
    var justDone by remember { mutableStateOf(false) }
    LaunchedEffect(st) {
        if (st is com.mazharansari.axion.linux.ToolStatus.Working) wasWorking = true
        else if (wasWorking && (st == null || st == com.mazharansari.axion.linux.ToolStatus.Idle)) { wasWorking = false; justDone = true; kotlinx.coroutines.delay(4000); justDone = false }
    }
    val working = st is com.mazharansari.axion.linux.ToolStatus.Working
    val failed = st is com.mazharansari.axion.linux.ToolStatus.Failed
    androidx.compose.animation.AnimatedVisibility(visible = working || failed || justDone) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(16.dp)) {
            if (justDone && !working && !failed) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    com.mazharansari.axion.ui.components.GkIcon(com.mazharansari.axion.ui.components.Gk.circleCheck, null, 22.dp, Color(0xFF3DDC84))
                    Spacer(Modifier.width(10.dp)); Text("Antigravity ready", style = Type.row, color = p.text)
                }
                return@Column
            }
            Text(if (failed) "Setup stopped" else "Setting up Antigravity", style = Type.row, color = if (failed) Color(0xFFFF6B6B) else p.text)
            Spacer(Modifier.height(10.dp))
            val cur = current["agy"]
            val curIdx = plan.indexOf(cur)
            plan.forEachIndexed { i, s ->
                val state = when { i < curIdx -> 2; i == curIdx -> if (failed) -1 else 1; else -> 0 }
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        when (state) {
                            2 -> Text("✓", color = Color(0xFF3DDC84), style = Type.row)
                            1 -> androidx.compose.material3.CircularProgressIndicator(progress = { ((st as? com.mazharansari.axion.linux.ToolStatus.Working)?.percent ?: 0).coerceAtLeast(8) / 100f }, modifier = Modifier.size(17.dp), strokeWidth = 2.5.dp, color = Color(0xFF1D9BF0), trackColor = p.text3.copy(alpha = 0.3f))
                            -1 -> Text("✕", color = Color(0xFFFF6B6B), style = Type.row)
                            else -> Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(p.text3))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(s.title + if (state == 1 && st is com.mazharansari.axion.linux.ToolStatus.Working) "… ${st.percent}%" else if (state == 2) " ✓".takeIf { false }.orEmpty() else "",
                        style = Type.body.copy(fontSize = 15.sp), color = when (state) { 0 -> p.text3; -1 -> Color(0xFFFF6B6B); else -> p.text })
                }
                if (state == 1 && st is com.mazharansari.axion.linux.ToolStatus.Working) Text(st.message, style = Type.tiny, color = p.text2, maxLines = 1, modifier = Modifier.padding(start = 32.dp))
            }
            if (failed) Text((st as com.mazharansari.axion.linux.ToolStatus.Failed).message, style = Type.small, color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 8.dp))
            // live output, newest at the bottom
            val lines = live["agy"].orEmpty()
            val scroll = rememberScrollState()
            LaunchedEffect(lines.size, lines.lastOrNull()) { scroll.scrollTo(scroll.maxValue) }
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(190.dp).clip(RoundedCornerShape(12.dp)).background(if (p.isDark) Color(0xFF0E0E0E) else Color(0xFFF1F1F1)).padding(10.dp)) {
                SelectionContainer {
                    Text(lines.joinToString("\n").ifBlank { "Waiting for output…" }, style = Type.mono.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = p.text2,
                        modifier = Modifier.fillMaxWidth().verticalScroll(scroll))
                }
            }
            if (failed) ToolFailedActions(vm, "agy", onRetry)
        }
    }
}

@Composable
fun ToolLogDialog(vm: ChatViewModel, toolId: String, onDismiss: () -> Unit) {
    val p = P; val ctx = LocalContext.current
    val text by produceState("Loading…", toolId) { value = withContext(Dispatchers.IO) { vm.g.devtools.logText(toolId, 200).ifBlank { "No log yet. Tap Retry to run the install again." } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.page2,
        title = { Text("Install log", style = Type.title, color = p.text) },
        text = {
            Column {
                Text("Last 200 lines of the last run. Keys and tokens are hidden. Share it with the developer if setup keeps failing.", style = Type.tiny, color = p.text2)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(12.dp)).background(if (p.isDark) Color(0xFF0E0E0E) else Color(0xFFF1F1F1)).padding(10.dp)) {
                    SelectionContainer {
                        Text(text, style = Type.mono.copy(fontSize = 11.sp, lineHeight = 15.sp), color = p.text,
                            modifier = Modifier.verticalScroll(rememberScrollState(Int.MAX_VALUE)).horizontalScroll(rememberScrollState()))
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { vm.toast = shareLog(ctx, vm, toolId) }) { Text("Share", color = p.text) }
                TextButton(onClick = { vm.toast = copyLog(ctx, vm, toolId) }) { Text("Copy", color = p.text) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = p.text2) } },
    )
}
