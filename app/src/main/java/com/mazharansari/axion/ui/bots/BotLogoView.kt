package com.mazharansari.axion.ui.bots

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mazharansari.axion.data.BotLogo
import com.mazharansari.axion.ui.components.Gk
import com.mazharansari.axion.ui.components.GkIcon
import com.mazharansari.axion.ui.theme.P
import java.io.File

/** The built-in logo id → its glyph. Every id in [BotLogo.BUILTINS] must map here. */
fun builtinIcon(id: String): Int = when (id) {
    "brain" -> Gk.brain
    "agent" -> Gk.agent
    "rocket" -> Gk.rocket
    "sparkles" -> Gk.sparkles
    "bolt" -> Gk.bolt
    "hammer" -> Gk.hammer
    "globe" -> Gk.globe
    "person" -> Gk.person
    "gem" -> Gk.gem
    "shield" -> Gk.shield
    "wand" -> Gk.wand
    "cube" -> Gk.cube
    else -> Gk.brain
}

/**
 * Phase 2 — a bot's logo wherever it appears (chat list, chat header, Bots section,
 * Bot Store): a gallery photo fills the circle; a built-in logo is its glyph on an
 * accent-tinted circle; no logo yet is the bot's initial on the same tint.
 */
@Composable
fun BotLogoView(logo: String, name: String, size: Dp, modifier: Modifier = Modifier) {
    val p = P
    when (val l = BotLogo.parse(logo)) {
        is BotLogo.Custom -> AsyncImage(
            model = File(l.path), contentDescription = "$name logo",
            modifier = modifier.size(size).clip(CircleShape).background(p.chip),
            contentScale = ContentScale.Crop,
        )
        is BotLogo.Builtin -> Box(
            modifier.size(size).clip(CircleShape).background(p.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { GkIcon(builtinIcon(l.id), "$name logo", size * 0.55f, p.accent) }
        BotLogo.None -> Box(
            modifier.size(size).clip(CircleShape).background(p.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), color = p.accent, fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold, fontFamily = com.mazharansari.axion.ui.theme.GoogleSans)
        }
    }
}

/**
 * Copy a gallery image into the app's own storage so the logo survives the gallery
 * permission being revoked. Returns the absolute path, or null when it cannot be read.
 * The file is stored as-is (no downscale yet — coil downsamples on display).
 */
fun saveBotLogo(ctx: Context, botId: String, uri: Uri): String? {
    return runCatching {
        val dir = File(ctx.filesDir, "bot-logos").apply { mkdirs() }
        val safe = botId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val out = File(dir, "logo_$safe.jpg")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        out.absolutePath
    }.getOrNull()
}

/** The label under a built-in logo in the picker. */
fun builtinLabel(id: String): String = BotLogo.BUILTINS.firstOrNull { it.id == id }?.label ?: id
