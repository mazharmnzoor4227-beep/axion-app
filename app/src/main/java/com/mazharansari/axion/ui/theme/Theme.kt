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
 * Grok's dark palette. Hex values sampled from mazhar's three Grok recordings (480x1068, same phone) and
 * cross-checked against the grey ramp in Grok 1.2.51's own theme (FCFCFC / 9E9E9E / 636363 / 242424 / 181818).
 *  bg       #111111  chat page            surface  #1F1F1F  composer, user bubble rim
 *  chip     #282828  + button, model chip card     #242424  settings rows, drawer cards, user bubble
 *  page2    #181818  settings / drawer / sheets    border   #2D2D2D composer outline, #1F1F1F card outline
 *  text     #FCFCFC  text2 #9E9E9E (Thinking…, Loaded 1 skill)  text3 #636363  dim #6E6E6E (inactive tabs)
 */
@Immutable
data class Palette(
    val bg: Color, val surface: Color, val surface2: Color, val sheet: Color, val border: Color,
    val text: Color, val text2: Color, val text3: Color, val accent: Color, val onAccent: Color,
    val code: Color, val danger: Color, val isDark: Boolean,
    val chip: Color = surface2, val card: Color = surface, val page2: Color = sheet, val cardBorder: Color = border,
    val dim: Color = text3, val popover: Color = sheet, val blue: Color = Color(0xFF1A5EFF),
)

val DarkPalette = Palette(
    bg = Color(0xFF111111), surface = Color(0xFF1F1F1F), surface2 = Color(0xFF282828), sheet = Color(0xFF1C1C1C),
    border = Color(0xFF2D2D2D), text = Color(0xFFFCFCFC), text2 = Color(0xFF9E9E9E), text3 = Color(0xFF636363),
    accent = Color(0xFFFCFCFC), onAccent = Color(0xFF0F0F0F), code = Color(0xFF111111), danger = Color(0xFFFF4245), isDark = true,
    chip = Color(0xFF282828), card = Color(0xFF242424), page2 = Color(0xFF181818), cardBorder = Color(0xFF1F1F1F),
    dim = Color(0xFF6E6E6E), popover = Color(0xE6262626), blue = Color(0xFF1445DC),
)
val LightPalette = Palette(
    bg = Color(0xFFFCFCFC), surface = Color(0xFFF2F2F2), surface2 = Color(0xFFE8E8E8), sheet = Color(0xFFF7F7F7),
    border = Color(0xFFDFDFDF), text = Color(0xFF0F0F0F), text2 = Color(0xFF636363), text3 = Color(0xFF9E9E9E),
    accent = Color(0xFF0F0F0F), onAccent = Color(0xFFFCFCFC), code = Color(0xFFF7F7F7), danger = Color(0xFFF4212E), isDark = false,
    chip = Color(0xFFEDEDED), card = Color(0xFFFFFFFF), page2 = Color(0xFFF2F2F2), cardBorder = Color(0xFFE5E5E5),
    dim = Color(0xFF9E9E9E), popover = Color(0xF2FFFFFF), blue = Color(0xFF1A5EFF),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }
val P: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current

/** Grok bundles Google Sans Flex (OFL) and maps Compose weights onto its wght axis: 400→400, 500→550, 600→600, 700→650, 800→800. */
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

private val GrokTypography: Typography = Typography().let { t ->
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
        MaterialTheme(colorScheme = scheme, typography = GrokTypography) {
            CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides Type.body, content = content)
        }
    }
}
