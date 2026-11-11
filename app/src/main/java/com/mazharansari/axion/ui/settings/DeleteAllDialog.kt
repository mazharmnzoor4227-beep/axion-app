package com.mazharansari.axion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.security.DataWiper
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.5.2 "Delete all data": what goes, then a typed confirmation, then the wipe (which closes Axion).
 * (It lived in the old PolicyScreens.kt next to the policy pages; the dialog stays — Data Controls and
 * About both use it — so it keeps its own file now that the policy screens are gone.)
 */
@Composable
fun DeleteAllDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    val p = P; val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf(vm.debugTyped) }
    var working by remember { mutableStateOf(false) }
    val danger = Color(0xFFF04438)
    AlertDialog(onDismissRequest = { if (!working) onDismiss() }, containerColor = p.card, shape = RoundedCornerShape(24.dp),
        title = { Text("Delete all data?", style = Type.title, color = p.text) },
        text = {
            Column {
                Text("This erases everything Axion keeps on this phone and closes the app:", style = Type.body, color = p.text2)
                Spacer(Modifier.height(8.dp))
                listOf("API keys and publishing tokens", "Antigravity sign-ins", "All chats and Build projects", "Uploaded and created files", "Linux, developer tools and local models", "Settings").forEach {
                    Text("•  $it", style = Type.body.copy(fontSize = 15.sp), color = p.text)
                }
                Spacer(Modifier.height(10.dp))
                Text("It can't be undone. To keep your keys, export them first (Settings → Export / import keys). Files in Downloads/Axion are not touched.", style = Type.small, color = p.text2)
                Spacer(Modifier.height(12.dp))
                Text("Type DELETE to confirm", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                Spacer(Modifier.height(6.dp))
                if (!vm.debugNoField) Field(typed, { typed = it }, "DELETE") else Text(typed.ifEmpty { "DELETE" }, style = Type.body, color = if (typed.isEmpty()) p.text3 else p.text, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF2A2A2A)).padding(horizontal = 14.dp, vertical = 13.dp))
            }
        },
        confirmButton = {
            TextButton({
                working = true
                vm.stop()
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { vm.g.build.store.list.value.forEach { vm.g.build.stopPreview(it.id) } }; DataWiper.wipe(ctx.applicationContext, clearAndroid = !vm.debugNoClear) }
                    if (!r.androidCleared) {
                        // Android's clear didn't run (tests / very old phones): restart Axion fresh.
                        vm.toast = "All data deleted"
                        if (!vm.debugNoClear) runCatching {
                            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { i -> ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    }
                    working = false; onDismiss()
                }
            }, enabled = typed.trim().equals("DELETE", ignoreCase = false) && !working) {
                Text(if (working) "Deleting…" else "Delete everything", style = Type.label, color = if (typed.trim() == "DELETE") danger else p.text3)
            }
        },
        dismissButton = { TextButton({ onDismiss() }, enabled = !working) { Text("Cancel", style = Type.label, color = p.text2) } })
}
