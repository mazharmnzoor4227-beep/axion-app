package com.mazharansari.axion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mazharansari.axion.linux.LinuxState
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import com.mazharansari.axion.ui.components.Gk
import com.mazharansari.axion.ui.components.GkIcon
import com.mazharansari.axion.ui.components.ScreenHeader
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * The Terminal: the user types commands into the SAME persistent Linux shell session the agent uses
 * (shell_session), so he can watch what the agent did, run his own commands, and pick up where it left
 * off — same directory, same variables. Nothing here is a separate emulator; it is one shared shell.
 */
@Composable
fun TerminalScreen(vm: ChatViewModel, nav: (Route) -> Unit, back: () -> Unit) {
    val g = vm.g
    val shell = g.shell
    val linuxState by g.linux.state.collectAsState()
    val transcript by shell.transcript.collectAsState()
    val busy by shell.busy.collectAsState()
    val cwd by shell.cwdFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(transcript) { scroll.animateScrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxSize().background(P.bg)) {
        ScreenHeader("Terminal", back)
        if (linuxState !is LinuxState.Ready) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text("Linux is not installed yet, so there is no terminal. Install it once (about 80 MB) in Developer tools — after that this screen is a live shell in Axion's Ubuntu, the same one the agent uses.", style = Type.body, color = P.text2)
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = { nav(Route.DevTools) }) { Text("Open Developer tools", style = Type.label, color = P.accent) }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (busy) "Running…" else "Shell ready", style = Type.small, color = if (busy) P.accent else P.text2)
            Spacer(Modifier.width(10.dp))
            Text(cwd, style = Type.mono, color = P.text3, maxLines = 1)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { scope.launch { shell.reset() } }) { Text("Reset", style = Type.small, color = P.text2) }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(14.dp)).background(P.surface).padding(12.dp),
        ) {
            Text(
                transcript.ifBlank { "A persistent Linux shell — cd and variables stay between commands.\nTry:  ls · pwd · python3 --version · apt list --installed\n" },
                style = Type.mono, color = P.text,
                modifier = Modifier.fillMaxSize().verticalScroll(scroll),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Type a command…", style = Type.small) },
                textStyle = Type.mono,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val cmd = input.trim()
                    if (cmd.isNotEmpty() && !busy) { input = ""; scope.launch { shell.exec(cmd, 600_000) } }
                }),
            )
            Spacer(Modifier.width(4.dp))
            if (busy) {
                IconButton(onClick = { shell.stopCurrent() }) { GkIcon(Gk.stop, "Stop", 24.dp, P.text) }
            } else {
                IconButton(onClick = {
                    val cmd = input.trim()
                    if (cmd.isNotEmpty()) { input = ""; scope.launch { shell.exec(cmd, 600_000) } }
                }) { GkIcon(Gk.send, "Run", 24.dp, P.text) }
            }
        }
    }
}
