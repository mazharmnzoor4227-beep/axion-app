package com.mazharansari.axion.ui.home

import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.chat.Composer
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import java.io.File

@Composable
fun ImaginePane(vm: ChatViewModel) {
    val p = P
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var aspect by rememberSaveableAspect()
    var open by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) { vm.loadImages() }
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.images.isEmpty() && !vm.imagining) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    GkIcon(Gk.imagine, null, 40.dp, p.text)
                    Spacer(Modifier.height(12.dp))
                    Text("Imagine anything", style = Type.title.copy(fontSize = 24.sp), color = p.text)
                    Spacer(Modifier.height(6.dp))
                    Text("Product shots, posters, logos, reels covers.", style = Type.body, color = p.text2)
                    Spacer(Modifier.height(18.dp))
                    listOf("Korean snail cream on wet marble, soft morning light, luxury product photo",
                        "Minimal black and white poster for a skincare sale, Urdu headline space",
                        "Flat lay of serums and sunscreen with pastel flowers, top view").forEach { s ->
                        Text(s, style = Type.small, color = p.text2, modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, p.border, RoundedCornerShape(14.dp)).clickable { text = s }.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            } else {
                LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp) {
                    if (vm.imagining) item(key = "loading") { LoadingTile(aspect) }
                    items(vm.images, key = { it.path }) { f ->
                        AsyncImage(f, f.name, contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).clickable { open = f })
                    }
                }
            }
        }
        vm.imagineNote?.let { Text(it, style = Type.small, color = p.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1:1", "9:16", "16:9", "3:4").forEach { a ->
                val sel = aspect == a
                Text(a, style = Type.small.copy(fontWeight = FontWeight.Medium), color = if (sel) p.onAccent else p.text,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) p.accent else p.card).clickable { aspect = a }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
        Composer(
            text = text, onText = { text = it }, placeholder = "Describe an image…", modeLabel = "Imagine", running = vm.imagining,
            attachments = emptyList(), onRemoveAttachment = {}, listening = false, level = { 0f }, partial = "",
            onPlus = {}, onMode = {}, onMic = {}, onSend = { vm.imagine(text, aspect); text = "" }, onStop = {}, onVoiceCall = {},
            modifier = Modifier.navigationBarsPadding().padding(bottom = 6.dp),
        )
    }
    open?.let { f -> ImageViewer(f, onClose = { open = null }, onSave = {
        vm.toast = FileActions.download(ctx, Artifact(f.name, if (f.extension == "png") "image/png" else "image/jpeg", f.path, f.length()))
    }, onShare = {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
    }) }
}

@Composable
private fun rememberSaveableAspect() = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("1:1") }

@Composable
private fun LoadingTile(aspect: String) {
    val p = P
    val t = rememberInfiniteTransition(label = "load")
    val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    val ratio = aspect.split(":").let { it[0].toFloat() / it[1].toFloat() }
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(16.dp)).background(p.surface2).alpha(a), contentAlignment = Alignment.Center) {
        Text("Imagining…", style = Type.small, color = p.text)
    }
}

@Composable
fun ImageViewer(f: File, onClose: () -> Unit, onSave: () -> Unit, onShare: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(f, f.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().align(Alignment.Center))
            Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)) { CircleButton(Gk.close, "Close", onClick = onClose) }
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Pill("Save", icon = Gk.download, filled = true, onClick = onSave)
                Pill("Share", icon = Gk.share, onClick = onShare)
            }
        }
    }
}
