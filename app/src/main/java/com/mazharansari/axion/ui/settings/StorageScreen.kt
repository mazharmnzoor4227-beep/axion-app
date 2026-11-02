package com.mazharansari.axion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.linux.StorageItem
import com.mazharansari.axion.linux.StorageKind
import com.mazharansari.axion.linux.StorageScan
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.launch

private val kindColor = mapOf(
    StorageKind.APP to Color(0xFF1A5EFF), StorageKind.LINUX to Color(0xFFE95420), StorageKind.AGY to Color(0xFF8E6CFF), StorageKind.TOOL to Color(0xFF2BB673),
    StorageKind.CHATS to Color(0xFF00B8D9), StorageKind.FILES to Color(0xFFFFB020), StorageKind.MODEL to Color(0xFFFF5C8A), StorageKind.CACHE to Color(0xFF9AA0A6),
)

/** v0.5.3 Settings → Storage: MB per item (app, Linux, Antigravity, each tool, chats, files, local models, cache), delete per item, Clear cache. */
@Composable
fun StorageScreen(vm: ChatViewModel, back: () -> Unit, preset: List<StorageItem>? = null, presetFree: Long? = null) {
    val p = P
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(preset) }
    var free by remember { mutableStateOf(presetFree) }
    var ask by remember { mutableStateOf<StorageItem?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        if (preset != null && reload == 0) return@LaunchedEffect
        items = runCatching { vm.g.storage.scan() }.getOrDefault(emptyList())
        free = runCatching { android.os.StatFs(vm.getApplication<android.app.Application>().filesDir.absolutePath).availableBytes }.getOrNull()
    }
    fun doDelete(it: StorageItem) {
        working = it.id
        scope.launch { vm.toast = runCatching { vm.g.storage.delete(it) }.getOrElse { e -> "Couldn't delete: ${e.message}" }; working = null; reload++ }
    }
    SettingsPage("Storage", back) {
        val list = items
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = p.text, strokeWidth = 2.5.dp)
                    Text("Measuring…", style = Type.small, color = p.text2, modifier = Modifier.padding(top = 12.dp))
                }
            }
            return@SettingsPage
        }
        val total = list.sumOf { it.bytes }
        // Summary card: total + one coloured bar split by item
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(18.dp)) {
            Text("Axion uses", style = Type.small.copy(fontSize = 15.sp), color = p.text2)
            Text(StorageScan.label(total), style = Type.title.copy(fontSize = 30.sp, lineHeight = 36.sp), color = p.text)
            free?.let { Text("${StorageScan.label(it)} free on this phone", style = Type.small, color = p.text2) }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(if (p.isDark) Color(0xFF333333) else Color(0xFFE3E3E3))) {
                list.filter { it.bytes > 0 }.forEach { i -> Box(Modifier.weight((i.bytes.toFloat() / total.coerceAtLeast(1)).coerceAtLeast(0.004f)).fillMaxHeight().background(kindColor[i.kind] ?: p.text2)) }
            }
        }
        SectionLabel("Items")
        SettingsGroup {
            list.filter { it.kind != StorageKind.CACHE }.forEach { i -> ItemRow(i, working == i.id) { ask = i } }
        }
        list.firstOrNull { it.kind == StorageKind.CACHE }?.let { c ->
            SectionLabel("Cache")
            SettingsGroup { ItemRow(c, working == c.id, showButton = false) {} }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(50)).background(if (c.bytes > 0 && working == null) p.accent else p.card)
                .clickable(enabled = c.bytes > 0 && working == null) { doDelete(c) }, contentAlignment = Alignment.Center) {
                if (working == c.id) CircularProgressIndicator(Modifier.size(20.dp), color = p.onAccent, strokeWidth = 2.dp)
                else Text(if (c.bytes > 0) "Clear cache (${StorageScan.label(c.bytes)})" else "Cache is empty", style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = if (c.bytes > 0) p.onAccent else p.text2)
            }
        }
        Text("Linux, developer tools, Antigravity and local models download only when you first use them.", style = Type.tiny.copy(fontSize = 12.5.sp), color = p.text3,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 18.dp))
    }
    ask?.let { i ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(i.deleteLabel ?: "Delete") },
            text = { Text(i.confirm ?: "Delete ${i.title}?") },
            confirmButton = { TextButton({ ask = null; doDelete(i) }) { Text(i.deleteLabel ?: "Delete", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton({ ask = null }) { Text("Cancel", color = p.text) } },
            containerColor = p.sheet, titleContentColor = p.text, textContentColor = p.text2,
        )
    }
}

@Composable
private fun ItemRow(i: StorageItem, busy: Boolean, showButton: Boolean = true, onDelete: () -> Unit) {
    val p = P
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(6.dp)).background(p.card).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(kindColor[i.kind] ?: p.text2))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(i.title, style = Type.row.copy(fontSize = 16.sp), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(i.subtitle, style = Type.small.copy(fontSize = 13.sp, lineHeight = 17.sp), color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(StorageScan.label(i.bytes), style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = p.text)
            if (showButton && i.deleteLabel != null) {
                Spacer(Modifier.height(6.dp))
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = p.text, strokeWidth = 2.dp)
                else Text(i.deleteLabel, style = Type.tiny.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = Color(0xFFFF6B6B),
                    modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Color(0x55FF6B6B), RoundedCornerShape(50)).clickable(onClick = onDelete).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}
