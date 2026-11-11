package com.mazharansari.axion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.data.MemoryStore
import com.mazharansari.axion.data.MemoryTarget
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type

/**
 * v0.10 (Hermes learning loop): everything Axion has saved about the user, in the open.
 * The agent writes these entries with its memory tools and reads them at the start of every
 * chat; here the user can add, edit and delete them — nothing is remembered that is not
 * on this screen.
 */
@Composable
fun MemoryScreen(vm: ChatViewModel, back: () -> Unit) {
    val p = P
    val store = vm.g.memory
    val data by store.data.collectAsState()
    var editing by remember { mutableStateOf<Triple<MemoryTarget, Int, String>?>(null) }   // target, index (-1 = new), text
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        ScreenHeader("Memory", back)
        LazyColumn(Modifier.weight(1f).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text("Axion saves short notes about you and what it learns while working, and reads them at the start of every chat — that is how it gets better at helping you. You can edit or delete anything here; Axion remembers only what is on this screen.",
                    style = Type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.text2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                error?.let { Text(it, style = Type.small, color = p.danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) }
            }
            memorySection(store, MemoryTarget.USER, "About you", data.user) { editing = it }
            memorySection(store, MemoryTarget.MEMORY, "Facts, lessons & projects", data.notes) { editing = it }
            item {
                Spacer(Modifier.height(10.dp))
                Text("Memory is kept small on purpose (About you: ${MemoryStore.USER_CHAR_BUDGET} characters, Facts & lessons: ${MemoryStore.MEMORY_CHAR_BUDGET}). When a section is full, Axion replaces or removes an old entry before adding a new one.",
                    style = Type.small.copy(fontSize = 13.sp, lineHeight = 18.sp), color = p.text3,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }

    editing?.let { (target, index, initial) ->
        var text by remember(target, index) { mutableStateOf(initial) }
        var dlgError by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { editing = null }, containerColor = p.card, shape = RoundedCornerShape(24.dp),
            title = { Text(if (index < 0) "Add memory" else "Edit memory", style = Type.title, color = p.text) },
            text = {
                Column {
                    Field(text, { text = it; dlgError = null }, if (target == MemoryTarget.USER) "e.g. Prefers short answers in Roman Urdu" else "e.g. Store prices include delivery")
                    dlgError?.let { Spacer(Modifier.height(8.dp)); Text(it, style = Type.small, color = p.danger) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val err = if (index < 0) store.add(target, text) else store.replaceAt(target, index, text)
                    if (err == null) editing = null else dlgError = err
                }) { Text("Save", color = p.accent) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = p.text2) } })
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.memorySection(
    store: MemoryStore,
    target: MemoryTarget,
    title: String,
    entries: List<String>,
    onEdit: (Triple<MemoryTarget, Int, String>) -> Unit,
) {
    item { SectionLabel(title) }
    if (entries.isEmpty()) {
        item {
            Text("Nothing saved yet — Axion adds entries here as it learns, or add one yourself.",
                style = Type.small, color = P.text3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
        }
    }
    itemsIndexed(entries, key = { i, e -> "${target.name}_$i" }) { i, e ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.surface).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(e, style = Type.body.copy(fontSize = 15.sp, lineHeight = 20.sp), color = P.text, modifier = Modifier.weight(1f))
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).clickable { onEdit(Triple(target, i, e)) }, contentAlignment = Alignment.Center) {
                GkIcon(Gk.editFile, "Edit", 19.dp, P.text2)
            }
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).clickable { store.removeAt(target, i) }, contentAlignment = Alignment.Center) {
                GkIcon(Gk.trash, "Delete", 19.dp, P.danger)
            }
        }
    }
    item {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clickable { onEdit(Triple(target, -1, "")) }, verticalAlignment = Alignment.CenterVertically) {
            GkIcon(Gk.plus, null, 18.dp, P.accent)
            Spacer(Modifier.width(8.dp))
            Text("Add", style = Type.label, color = P.accent)
        }
    }
}
