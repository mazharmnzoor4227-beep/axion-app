package com.mazharansari.axion.ui.auth

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mazharansari.axion.ui.components.AxionMark
import com.mazharansari.axion.ui.components.Gk
import com.mazharansari.axion.ui.components.GkIcon
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** v0.5.3 first run. Off under Robolectric unless a test turns it on (older screen tests open straight on Home). */
object Onboarding {
    @Volatile var forceInTests = false
    val enabled: Boolean get() = Build.FINGERPRINT != "robolectric" || forceInTests
    /** Tests/screenshots: hold the intro at this progress (0..1) instead of animating. */
    @Volatile var freezeIntroAt: Float? = null
}

/**
 * The intro: the Axion mark grows in out of a soft glow, the name and tagline rise in, then it moves on by itself
 * (~1.8 s). Tap anywhere or Skip to go on at once. Shown on the first launch only.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val p = P
    val progress = remember { Animatable(Onboarding.freezeIntroAt ?: 0f) }
    var done by remember { mutableStateOf(false) }
    fun finish() { if (!done) { done = true; onDone() } }
    LaunchedEffect(Unit) {
        if (Onboarding.freezeIntroAt != null) return@LaunchedEffect
        progress.animateTo(1f, tween(1500, easing = LinearEasing))
        delay(300); finish()
    }
    val t = progress.value
    fun seg(a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    val markIn = FastOutSlowInEasing.transform(seg(0f, 0.45f))
    val glow = seg(0.05f, 0.6f).let { if (it < 1f) it else 1f } * (1f - 0.35f * seg(0.6f, 1f))
    val nameIn = FastOutSlowInEasing.transform(seg(0.35f, 0.7f))
    val tagIn = FastOutSlowInEasing.transform(seg(0.55f, 0.9f))
    Box(Modifier.fillMaxSize().background(Color(0xFF000000)).clickable(remember { MutableInteractionSource() }, null) { finish() }) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height * 0.42f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF3B6BFF).copy(alpha = 0.38f * glow), Color(0xFF7A4DFF).copy(alpha = 0.16f * glow), Color.Transparent), c, size.minDimension * 0.62f), size.minDimension * 0.62f, c)
        }
        Column(Modifier.fillMaxWidth().align(Alignment.Center).offset(y = (-40).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AxionMark(96.dp, Modifier.graphicsLayer { alpha = markIn; scaleX = 0.6f + 0.4f * markIn; scaleY = 0.6f + 0.4f * markIn; rotationZ = -18f * (1f - markIn) }, tint = Color.White)
            Spacer(Modifier.height(26.dp))
            Text("Axion", style = Type.title.copy(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp), color = Color.White,
                modifier = Modifier.graphicsLayer { alpha = nameIn; translationY = 28f * (1f - nameIn) })
            Spacer(Modifier.height(8.dp))
            Text("Your AI agent, right on your phone", style = Type.body.copy(fontSize = 16.sp), color = Color(0xFFB4B4B4),
                modifier = Modifier.graphicsLayer { alpha = tagIn; translationY = 20f * (1f - tagIn) })
        }
        Text("Skip", style = Type.body.copy(fontWeight = FontWeight.Medium), color = Color(0xFF9A9A9A),
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).clip(RoundedCornerShape(50)).clickable { finish() }.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/** One permission page. */
data class PermPage(val id: String, @DrawableRes val icon: Int, val title: String, val body: String, val permission: String?)

object PermPages {
    fun all(): List<PermPage> = listOf(
        PermPage("notifications", Gk.bell, "Stay in the loop", "Axion tells you when a download, a tool install, a build or an app update is done, even while you're in another app. No ads, ever.", null),
        PermPage("mic", Gk.mic, "Talk to Axion", "The microphone is used only when you tap the mic to dictate, or start a voice call. Nothing is recorded in the background.", Manifest.permission.RECORD_AUDIO),
        PermPage("camera", Gk.camera, "Show Axion a photo", "The camera opens only when you choose to take a photo to send in a chat.", Manifest.permission.CAMERA),
    )
    fun granted(ctx: Context, page: PermPage): Boolean =
        if (page.permission == null) NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        else ContextCompat.checkSelfPermission(ctx, page.permission) == PackageManager.PERMISSION_GRANTED
    /** Pages still worth asking (already-granted ones are skipped). */
    fun toAsk(ctx: Context, showAll: Boolean = false): List<PermPage> = all().filter { showAll || !granted(ctx, it) }
}

/** v0.5.3 permissions onboarding: each permission explained on its own page, with Allow / Not now. */
@Composable
fun PermissionsOnboarding(onDone: () -> Unit, showAll: Boolean = Onboarding.forceInTests, startIndex: Int = 0) {
    val p = P
    val ctx = LocalContext.current
    val pages = remember { PermPages.toAsk(ctx, showAll) }
    var index by remember { mutableIntStateOf(startIndex) }
    LaunchedEffect(pages) { if (pages.isEmpty()) onDone() }
    if (pages.isEmpty()) return
    fun next() { if (index >= pages.lastIndex) onDone() else index++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    val page = pages[index.coerceIn(0, pages.lastIndex)]
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pages.indices.forEach { i -> Box(Modifier.height(6.dp).width(if (i == index) 22.dp else 6.dp).clip(RoundedCornerShape(50)).background(if (i == index) p.text else p.text3)) }
                }
                Spacer(Modifier.weight(1f))
                Text("Skip all", style = Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = p.text2,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onDone() }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            AnimatedContent(targetState = page, label = "perm", modifier = Modifier.weight(1f),
                transitionSpec = { (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(220))) togetherWith (slideOutHorizontally(tween(260)) { -it / 3 } + fadeOut(tween(160))) }) { pg ->
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) { drawCircle(Brush.radialGradient(listOf(Color(0xFF3B6BFF).copy(alpha = 0.30f), Color.Transparent))) }
                        Box(Modifier.size(84.dp).clip(CircleShape).background(p.card), contentAlignment = Alignment.Center) { GkIcon(pg.icon, null, 38.dp, p.text) }
                    }
                    Spacer(Modifier.height(30.dp))
                    Text(pg.title, style = Type.title.copy(fontSize = 28.sp, lineHeight = 34.sp), color = p.text, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(pg.body, style = Type.body.copy(fontSize = 16.sp, lineHeight = 24.sp), color = p.text2, textAlign = TextAlign.Center)
                }
            }
            Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(50)).background(p.accent).clickable {
                when {
                    PermPages.granted(ctx, page) -> next()
                    page.permission != null -> launcher.launch(page.permission)
                    else -> { requestNotifications(ctx); next() }
                }
            }, contentAlignment = Alignment.Center) { Text("Allow", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.onAccent) }
            Text("Not now", style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.text, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp).clip(RoundedCornerShape(50)).clickable { next() }.padding(vertical = 12.dp))
        }
    }
}

/** Axion targets API 28, so Android 13+ asks about notifications when the app creates its first channel; if they're still off,
 *  the app's notification settings open so it's one switch. */
private fun requestNotifications(ctx: Context) {
    runCatching {
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(com.mazharansari.axion.update.AppUpdater.CH, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
    }
    if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
