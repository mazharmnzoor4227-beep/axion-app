package com.mazharansari.axion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.12: remembered approvals, in the open — the command kinds Axion may run
 * without asking, because the user tapped "Always allow" once. Revoke any of
 * them here and Axion asks again. Banking/payment and destructive commands are
 * never on this list: those ask every single time.
 */
@Composable
fun ApprovalsScreen(vm: ChatViewModel, back: () -> Unit) {
    val p = P
    val store = vm.g.approvals
    val rules by store.rules.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.ENGLISH) }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        ScreenHeader("Approvals", back)
        LazyColumn(Modifier.weight(1f).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text("When Axion wants to run a command that could change things, it asks you first. If you tap \"Always allow\", that kind of command is remembered here and runs without asking next time — the step is marked \"(remembered approval)\" so you can always see it happened.",
                    style = Type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.text2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                Text("Banking and payment commands, and commands that could wipe storage, are never remembered — those ask every time.",
                    style = Type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.text2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            }
            if (rules.isEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Nothing remembered yet. The first time a command needs your OK, you can choose \"Always allow\" for that kind.",
                        style = Type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.text3,
                        modifier = Modifier.padding(horizontal = 20.dp))
                }
            } else {
                items(rules, key = { it.pattern }) { rule ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        GkIcon(Gk.shield, null, 20.dp, p.text2)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(rule.pattern, style = Type.row.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = p.text)
                            if (rule.example.isNotBlank()) Text(rule.example.take(80), style = Type.tiny, color = p.text2, maxLines = 1)
                            Text("Allowed since ${dateFmt.format(Date(rule.createdAt))}", style = Type.tiny, color = p.text3)
                        }
                        TextButton(onClick = { store.remove(rule.pattern) }) { Text("Revoke", color = p.danger) }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text("Revoke all", color = p.danger)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(onDismissRequest = { confirmClear = false }, containerColor = p.card, shape = RoundedCornerShape(24.dp),
            title = { Text("Revoke all approvals?", style = Type.title, color = p.text) },
            text = { Text("Axion will ask before every command again, until you allow kinds once more.", style = Type.small, color = p.text2) },
            confirmButton = { TextButton(onClick = { store.clear(); confirmClear = false }) { Text("Revoke all", color = p.danger) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = p.text) } })
    }
}
