package com.mazharansari.axion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.R

/**
 * Axion's own dark palette — deep ink neutrals with a restrained indigo-violet accent.
 * (Phase 2: the old ramp copied another app's grey-on-white look; this one is Axion's.)
 *  bg       #0E0F13  chat page            surface  #181A20  composer, user bubble rim
 *  chip     #20232C  + button, model chip card     #1B1E26  settings rows, drawer cards
 *  page2    #121419  settings / drawer / sheets    border   #262A33 composer outline
 *  text     #F5F6F8  text2 #9BA1AD (Thinking…, Loaded 1 skill)  text3 #6B7280  dim #7A8190
 *  accent   #6C63FF  indigo-violet — the one brand colour; onAccent #FFFFFF
 */
@Immutable
data class Palette(
    val bg: Color, val surface: Color, val surface2: Color, val sheet: Color, val border: Color,
    val text: Color, val text2: Color, val text3: Color, val accent: Color, val onAccent: Color,
    val code: Color, val danger: Color, val isDark: Boolean,
    val chip: Color = surface2, val card: Color = surface, val page2: Color = sheet, val cardBorder: Color = border,
    val dim: Color = text3, val popover: Color = sheet, val blue: Color = Color(0xFF3B82F6),
)

val DarkPalette = Palette(
    bg = Color(0xFF0E0F13), surface = Color(0xFF181A20), surface2 = Color(0xFF20232C), sheet = Color(0xFF14161B),
    border = Color(0xFF262A33), text = Color(0xFFF5F6F8), text2 = Color(0xFF9BA1AD), text3 = Color(0xFF6B7280),
    accent = Color(0xFF6C63FF), onAccent = Color(0xFFFFFFFF), code = Color(0xFF0B0C10), danger = Color(0xFFFF5A5F), isDark = true,
    chip = Color(0xFF20232C), card = Color(0xFF1B1E26), page2 = Color(0xFF121419), cardBorder = Color(0xFF23272F),
    dim = Color(0xFF7A8190), popover = Color(0xE61B1E26), blue = Color(0xFF3B82F6),
)
val LightPalette = Palette(
    bg = Color(0xFFFCFCFC), surface = Color(0xFFF2F2F2), surface2 = Color(0xFFE8E8E8), sheet = Color(0xFFF7F7F7),
    border = Color(0xFFDFDFDF), text = Color(0xFF0F0F0F), text2 = Color(0xFF636363), text3 = Color(0xFF9E9E9E),
    accent = Color(0xFF5A50E6), onAccent = Color(0xFFFCFCFC), code = Color(0xFFF7F7F7), danger = Color(0xFFF4212E), isDark = false,
    chip = Color(0xFFEDEDED), card = Color(0xFFFFFFFF), page2 = Color(0xFFF2F2F2), cardBorder = Color(0xFFE5E5E5),
    dim = Color(0xFF9E9E9E), popover = Color(0xF2FFFFFF), blue = Color(0xFF3B82F6),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }
val P: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current

/** Axion bundles Google Sans Flex (OFL) and maps Compose weights onto its wght axis: 400→400, 500→550, 600→600, 700→650, 800→800. */
@OptIn(ExperimentalTextApi::class)
private fun flex(w: Int, axis: Int) = Font(R.font.google_sans_flex, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(axis)))
@OptIn(ExperimentalTextApi::class)
private fun code(w: Int) = Font(R.font.google_sans_code, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val GoogleSans = FontFamily(flex(300, 300), flex(400, 400), flex(500, 550), flex(600, 600), flex(700, 650), flex(800, 800), flex(900, 850))
val GoogleSansCode = FontFamily(code(300), code(400), code(500), code(600), code(700))

object Type {
    val body = TextStyle(fontFamily = GoogleSans, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp)
    val small = TextStyle(fontFamily = GoogleSans, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
    val tiny = TextStyle(fontFamily = GoogleSans, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp)
    val label = TextStyle(fontFamily = GoogleSans, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
    val row = TextStyle(fontFamily = GoogleSans, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
    val title = TextStyle(fontFamily = GoogleSans, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    val header = TextStyle(fontFamily = GoogleSans, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    val mono = TextStyle(fontFamily = GoogleSansCode, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
}

private val AxionTypography: Typography = Typography().let { t ->
    fun TextStyle.g() = copy(fontFamily = GoogleSans, letterSpacing = 0.sp)
    Typography(
        displayLarge = t.displayLarge.g(), displayMedium = t.displayMedium.g(), displaySmall = t.displaySmall.g(),
        headlineLarge = t.headlineLarge.g(), headlineMedium = t.headlineMedium.g(), headlineSmall = t.headlineSmall.g(),
        titleLarge = t.titleLarge.g(), titleMedium = t.titleMedium.g(), titleSmall = t.titleSmall.g(),
        bodyLarge = t.bodyLarge.g(), bodyMedium = t.bodyMedium.g(), bodySmall = t.bodySmall.g(),
        labelLarge = t.labelLarge.g(), labelMedium = t.labelMedium.g(), labelSmall = t.labelSmall.g(),
    )
}

@Composable
fun AxionTheme(theme: String = "dark", textScale: Float = 1f, content: @Composable () -> Unit) {
    val dark = when (theme) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(
        background = p.bg, surface = p.bg, surfaceContainer = p.page2, surfaceContainerLow = p.page2, surfaceContainerHigh = p.card,
        onBackground = p.text, onSurface = p.text, primary = p.accent, onPrimary = p.onAccent, outline = p.border, surfaceVariant = p.card,
        onSurfaceVariant = p.text2,
    ) else lightColorScheme(
        background = p.bg, surface = p.bg, surfaceContainer = p.page2, surfaceContainerLow = p.page2, surfaceContainerHigh = p.card,
        onBackground = p.text, onSurface = p.text, primary = p.accent, onPrimary = p.onAccent, outline = p.border, surfaceVariant = p.card,
        onSurfaceVariant = p.text2,
    )
    val d = LocalDensity.current
    CompositionLocalProvider(LocalPalette provides p, LocalDensity provides Density(d.density, d.fontScale * textScale)) {
        MaterialTheme(colorScheme = scheme, typography = AxionTypography) {
            CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides Type.body, content = content)
        }
    }
}
