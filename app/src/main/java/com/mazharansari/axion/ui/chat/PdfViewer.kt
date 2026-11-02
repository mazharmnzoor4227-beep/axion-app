package com.mazharansari.axion.ui.chat

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.ui.components.Gk
import com.mazharansari.axion.ui.components.IconTap
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val renderLock = Mutex()

/**
 * v0.3.1 — tapping Grok's PDF card opens the document full screen inside the app. Axion's version: dark page,
 * ✕ · name · share · download on top, every page as a white sheet, pinch or double-tap to zoom, "2 / 5" counter.
 */
@Composable
fun PdfViewer(a: Artifact, onClose: () -> Unit, onShare: () -> Unit, onDownload: () -> Unit) {
    val p = P
    BackHandler(onBack = onClose)
    val count by produceState(maxOf(a.pages, 1), a.path) { value = withContext(Dispatchers.IO) { pdfPageCount(a.path) } }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val tf = rememberTransformableState { z, d, _ ->
        scale = (scale * z).coerceIn(1f, 4f)
        pan = if (scale == 1f) Offset.Zero else pan + d
    }
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0B)).pointerInput(Unit) { detectTapGestures { } }) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2f; pan = Offset.Zero }) }
            .transformable(tf)
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y },
            contentPadding = PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp, bottom = 72.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(count) { i ->
                val bmp by produceState<Bitmap?>(null, a.path, i) { value = withContext(Dispatchers.IO) { renderLock.withLock { pdfPageBitmap(a.path, i, 1080) } } }
                Box(Modifier.fillMaxWidth().aspectRatio(595f / 842f).clip(RoundedCornerShape(4.dp)).background(Color.White)) {
                    bmp?.let { Image(it.asImageBitmap(), "Page ${i + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        // top bar
        Row(Modifier.fillMaxWidth().background(Color(0xF00B0B0B)).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTap(Gk.close, "Close", 22.dp, Color.White, 48.dp, onClose)
            Text(a.name, style = Type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            IconTap(Gk.share, "Share", 20.dp, Color.White, 48.dp, onShare)
            IconTap(Gk.download, "Download", 20.dp, Color.White, 48.dp, onDownload)
        }
        // page counter
        val first by remember { derivedStateOf { list.firstVisibleItemIndex } }
        Text("${(first + 1).coerceAtMost(count)} / $count", style = Type.small.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp).clip(CircleShape).background(Color(0xE6252525)).padding(horizontal = 14.dp, vertical = 7.dp))
    }
}
