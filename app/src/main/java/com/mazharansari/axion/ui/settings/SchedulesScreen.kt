package com.mazharansari.axion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.data.ScheduleEntry
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import com.mazharansari.axion.work.ScheduleEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.12: the user's scheduled tasks, in the open. The agent creates these from
 * plain words in chat ("roz subah 9 baje…"); here the user can switch them off
 * or delete them. When one fires, its result lands in a new chat and a
 * notification arrives — the last run's state is shown on each card.
 */
@Composable
fun SchedulesScreen(vm: ChatViewModel, back: () -> Unit) {
    val p = P
    val ctx = LocalContext.current
    val store = vm.g.schedules
    val entries by store.entries.collectAsState()
    var deleting by remember { mutableStateOf<ScheduleEntry?>(null) }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH) }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        ScreenHeader("Schedules", back)
        LazyColumn(Modifier.weight(1f).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text("Ask Axion in chat — \"kal subah 9 baje mujhe mausam bata dena\" or \"har Monday prices check karna\" — and it schedules the task itself. At the set time Axion does the task on its own, saves the result as a new chat and sends you a notification.",
                    style = Type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.text2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                Text("Honest note: Android may run a task a few minutes late to save battery, and nothing fires while the phone is switched off.",
                    style = Type.small.copy(fontSize = 13.sp, lineHeight = 18.sp), color = p.text3,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            }
            if (entries.isEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("No scheduled tasks yet.", style = Type.small.copy(fontSize = 14.sp), color = p.text3,
                        modifier = Modifier.padding(horizontal = 20.dp))
                }
            } else {
                items(entries, key = { it.id }) { e ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        GkIcon(Gk.clock, null, 20.dp, p.text2)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = Type.row.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = p.text)
                            Text(e.describe(), style = Type.tiny, color = p.text2)
                            Text(e.prompt.take(90), style = Type.tiny, color = p.text3, maxLines = 1)
                            if (e.lastRunAt > 0) Text("Last run ${fmt.format(Date(e.lastRunAt))}: ${e.lastStatus.ifBlank { "done" }}", style = Type.tiny, color = p.text3)
                        }
                        TextButton(onClick = { deleting = e }) { Text("Delete", color = p.danger) }
                        Switch(checked = e.enabled, onCheckedChange = { on ->
                            store.setEnabled(e.id, on)
                            val fresh = store.get(e.id) ?: e.copy(enabled = on)
                            if (on) ScheduleEngine.arm(ctx, fresh, store) else ScheduleEngine.disarm(ctx, e.id)
                        })
                    }
                }
            }
        }
    }

    deleting?.let { e ->
        AlertDialog(onDismissRequest = { deleting = null }, containerColor = p.card, shape = RoundedCornerShape(24.dp),
            title = { Text("Delete this schedule?", style = Type.title, color = p.text) },
            text = { Text("\"${e.title}\" (${e.describe()}) will never run again.", style = Type.small, color = p.text2) },
            confirmButton = {
                TextButton(onClick = { store.remove(e.id); ScheduleEngine.disarm(ctx, e.id); deleting = null }) {
                    Text("Delete", color = p.danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = p.text) } })
    }
}
