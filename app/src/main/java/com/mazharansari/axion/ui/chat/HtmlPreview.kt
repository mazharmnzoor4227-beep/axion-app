package com.mazharansari.axion.ui.chat

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mazharansari.axion.build.StaticServer
import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.security.PreviewWeb
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import java.io.File

/**
 * v0.6 (Phase 1.3, Ask section): a website the agent wrote (an .html file in outputs/) opens INSIDE Axion — same hardened
 * WebView as the Build preview, served from a local static server rooted at the file's folder so CSS/JS/images next to
 * it load. Refresh reloads; "Open in browser" hands the local address to the phone's browser.
 */
@Composable
fun HtmlPreview(a: Artifact, onClose: () -> Unit) {
    val p = P; val ctx = LocalContext.current
    val file = remember(a.path) { File(a.path) }
    val server = remember(a.path) { StaticServer(file.parentFile ?: file, spaFallback = false).start() }
    DisposableEffect(server) { onDispose { server.stop() } }
    val url = remember(server) { server.url + file.name }
    var web by remember { mutableStateOf<WebView?>(null) }
    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTap(Gk.chevronLeft, "Close preview", 22.dp, p.text, 44.dp, onClose)
            Text(a.name, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconTap(Gk.refresh, "Refresh", 20.dp, p.text2, 44.dp) { web?.reload() }
            IconTap(Gk.arrowUpRight, "Open in browser", 20.dp, p.text2, 44.dp) {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color.White)) {
            AndroidView(factory = { c -> PreviewWeb.harden(WebView(c)).apply { web = this; loadUrl(url) } }, modifier = Modifier.fillMaxSize())
        }
    }
}
