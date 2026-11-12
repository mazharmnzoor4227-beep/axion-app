package com.mazharansari.axion.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.components.Gk
import com.mazharansari.axion.ui.components.GkIcon
import com.mazharansari.axion.ui.components.ScreenHeader
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * Axion's own browser, on screen: the same WebView the agent drives with its browser_* tools.
 * Whatever the agent opened is here (and the user can take over, type, log in…), and whatever the
 * user opens here is what the agent reads. The WebView itself lives in BrowserController, so it
 * survives leaving this screen; here it is only attached for display.
 */
@Composable
fun BrowserScreen(vm: ChatViewModel, back: () -> Unit) {
    val browser = vm.g.browser
    val url by browser.url.collectAsState()
    val title by browser.title.collectAsState()
    val loading by browser.loading.collectAsState()
    val progress by browser.progress.collectAsState()
    val canBack by browser.canBack.collectAsState()
    val canForward by browser.canForward.collectAsState()
    val scope = rememberCoroutineScope()
    var field by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(url) { if (!focused) field = url }
    LaunchedEffect(Unit) { browser.ensureStarted() }
    DisposableEffect(Unit) { onDispose { browser.releaseDisplay() } }

    Column(Modifier.fillMaxSize().background(P.bg)) {
        ScreenHeader("Browser", back)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = field, onValueChange = { field = it },
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                singleLine = true,
                placeholder = { Text("Search or type a URL", style = Type.small) },
                textStyle = Type.small,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { scope.launch { browser.openSmart(field) } }),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { if (loading) browser.stopLoading() else scope.launch { browser.reload() } }) {
                GkIcon(if (loading) Gk.close else Gk.refresh, if (loading) "Stop" else "Reload", 22.dp, P.text)
            }
        }
        if (loading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = P.accent)
        AndroidView(
            factory = { ctx -> browser.displayView(ctx) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { browser.back() } }, enabled = canBack) {
                GkIcon(Gk.chevronLeft, "Back", 24.dp, if (canBack) P.text else P.text3)
            }
            IconButton(onClick = { scope.launch { browser.forward() } }, enabled = canForward) {
                GkIcon(Gk.chevronRight, "Forward", 24.dp, if (canForward) P.text else P.text3)
            }
            IconButton(onClick = { scope.launch { browser.reload() } }) {
                GkIcon(Gk.refresh, "Reload", 22.dp, P.text)
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "Browser" }, style = Type.small, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(url, style = Type.tiny, color = P.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
