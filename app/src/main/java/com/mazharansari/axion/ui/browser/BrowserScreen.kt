package com.mazharansari.axion.ui.browser

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mazharansari.axion.browser.BrowserDownloads
import com.mazharansari.axion.browser.DownloadStatus
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.components.Gk
import com.mazharansari.axion.ui.components.GkIcon
import com.mazharansari.axion.ui.components.ScreenHeader
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * Axion's own browser, on screen: the same WebViews the agent drives with its browser_* tools.
 * Whatever the agent opened is here (and the user can take over, type, log in…), and whatever the
 * user opens here is what the agent reads. The WebViews live in BrowserController, so they survive
 * leaving this screen; here they are only attached for display.
 *
 * P1-7: a tab strip (each tab keeps its own page and history — switching restores it), a downloads
 * list (files save into Downloads/Axion, with progress and an Open action), and the file picker for
 * pages that ask for a file (<input type=file>).
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
    val tabs by browser.tabs.collectAsState()
    val activeTabId by browser.activeTabId.collectAsState()
    val downloads by browser.downloads.collectAsState()
    val scope = rememberCoroutineScope()
    var field by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(url) { if (!focused) field = url }
    LaunchedEffect(Unit) { browser.ensureStarted() }

    // P1-7 (ME-10): a page's <input type=file> opens the system file picker; the result goes back to the page.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        browser.deliverFileChooserResult(uris)
    }
    DisposableEffect(Unit) {
        browser.onFileChooserRequested = { types -> filePicker.launch(types) }
        onDispose { browser.onFileChooserRequested = null; browser.deliverFileChooserResult(null) }
    }
    DisposableEffect(Unit) { onDispose { browser.releaseDisplay() } }

    Column(Modifier.fillMaxSize().background(P.bg)) {
        ScreenHeader("Browser", back)
        // Tab strip: one chip per tab, tap to switch, close button on each tab when more than one is open.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { tab ->
                val active = tab.id == activeTabId
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (active) P.card else P.bg)
                        .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { browser.switchTab(tab.id) }, modifier = Modifier.padding(0.dp)) {
                        Text(tab.displayName(), style = Type.small, color = if (active) P.text else P.text2,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
                    }
                    if (tabs.size > 1) {
                        IconButton(onClick = { scope.launch { browser.closeTab(tab.id) } }) {
                            GkIcon(Gk.close, "Close tab", 16.dp, P.text3)
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = {
                scope.launch {
                    if (!browser.newTab()) vm.toast = "Maximum 10 tabs are open — close one first."
                }
            }) {
                GkIcon(Gk.plus, "New tab", 22.dp, P.text)
            }
        }
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
        // Downloads (P1-7, ME-9): files save into Downloads/Axion, with progress and an Open action.
        downloads.forEach { d ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp)).background(P.card).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GkIcon(Gk.download, "Download", 18.dp, P.text2)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.fileName, style = Type.small, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (d.status) {
                                DownloadStatus.DOWNLOADING -> if (d.progressPercent >= 0) "Downloading — ${d.progressPercent}%" else "Downloading…"
                                DownloadStatus.COMPLETE -> "Saved in Downloads/${BrowserDownloads.DIR}"
                                DownloadStatus.FAILED -> "Download failed"
                            },
                            style = Type.tiny, color = P.text2,
                        )
                    }
                    if (d.status == DownloadStatus.COMPLETE) {
                        TextButton(onClick = { if (!browser.openDownload(d.id)) vm.toast = "Could not open that file." }) {
                            Text("Open", color = P.accent)
                        }
                    }
                }
                if (d.status == DownloadStatus.DOWNLOADING && d.progressPercent >= 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { d.progressPercent / 100f }, modifier = Modifier.fillMaxWidth(), color = P.accent)
                }
            }
        }
        key(activeTabId) {
            AndroidView(
                factory = { ctx -> browser.displayView(ctx) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
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
