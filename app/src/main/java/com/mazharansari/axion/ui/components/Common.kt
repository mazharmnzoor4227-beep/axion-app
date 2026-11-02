package com.mazharansari.axion.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.R
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type

val LocalHaptics = staticCompositionLocalOf { true }

/** Grok's own icon set (24-unit grid, 2-unit strokes) — the vector drawables shipped in Grok 1.2.51, renamed gk_*. */
object Gk {
    val bell = R.drawable.gk_bell   // v0.5.3 onboarding
    val mail = R.drawable.gk_mail   // v0.5.3 sign-in
    val menu = R.drawable.gk_menu; val newChat = R.drawable.gk_new_chat; val plus = R.drawable.gk_plus; val close = R.drawable.gk_close
    val chevronDown = R.drawable.gk_chevron_down; val chevronRight = R.drawable.gk_chevron_right; val chevronLeft = R.drawable.gk_chevron_left
    val mic = R.drawable.gk_mic; val voice = R.drawable.gk_voice; val send = R.drawable.gk_send; val stop = R.drawable.gk_stop
    val camera = R.drawable.gk_camera; val image = R.drawable.gk_image; val file = R.drawable.gk_file; val skills = R.drawable.gk_skills
    val connectors = R.drawable.gk_connectors; val bolt = R.drawable.gk_bolt; val think = R.drawable.gk_think; val rocket = R.drawable.gk_rocket
    val hammer = R.drawable.gk_hammer; val check = R.drawable.gk_check; val search = R.drawable.gk_search; val globe = R.drawable.gk_globe
    val readFile = R.drawable.gk_read_file; val writeFile = R.drawable.gk_write_file; val terminal = R.drawable.gk_terminal
    val copy = R.drawable.gk_copy; val refresh = R.drawable.gk_refresh; val link = R.drawable.gk_link; val share = R.drawable.gk_share
    val thumbsUp = R.drawable.gk_thumbs_up; val thumbsDown = R.drawable.gk_thumbs_down; val speaker = R.drawable.gk_speaker
    val settings = R.drawable.gk_settings; val library = R.drawable.gk_library; val projects = R.drawable.gk_projects; val agent = R.drawable.gk_agent
    val collapse = R.drawable.gk_collapse; val expand = R.drawable.gk_expand; val theme = R.drawable.gk_theme; val haptics = R.drawable.gk_haptics
    val widget = R.drawable.gk_widget; val language = R.drawable.gk_language; val sliders = R.drawable.gk_sliders; val wand = R.drawable.gk_wand
    val kids = R.drawable.gk_kids; val database = R.drawable.gk_database; val pdf = R.drawable.gk_pdf; val arrowDown = R.drawable.gk_arrow_down
    val more = R.drawable.gk_more; val imagine = R.drawable.gk_imagine; val brain = R.drawable.gk_brain; val person = R.drawable.gk_person
    val help = R.drawable.gk_help; val logout = R.drawable.gk_logout; val trash = R.drawable.gk_trash; val pin = R.drawable.gk_pin
    val pencil = R.drawable.gk_pencil; val folder = R.drawable.gk_folder; val key = R.drawable.gk_key; val info = R.drawable.gk_info
    val sparkles = R.drawable.gk_sparkles; val usage = R.drawable.gk_usage; val history = R.drawable.gk_history; val privacy = R.drawable.gk_privacy
    val support = R.drawable.gk_support; val chatExport = R.drawable.gk_chat_export; val headphones = R.drawable.gk_headphones
    val textSize = R.drawable.gk_text_size; val codeBlock = R.drawable.gk_code_block; val cube = R.drawable.gk_cube; val shield = R.drawable.gk_shield
    val uploadImage = R.drawable.gk_upload_image; val tool = R.drawable.gk_tool; val arrowsRight = R.drawable.gk_arrows_right
    val pause = R.drawable.gk_pause; val play = R.drawable.gk_play; val eye = R.drawable.gk_eye; val eyeOff = R.drawable.gk_eye_off
    val arrowUpRight = R.drawable.gk_arrow_up_right; val download = R.drawable.gk_download; val grid = R.drawable.gk_grid; val clock = R.drawable.gk_clock
    val paintBrush = R.drawable.gk_paint_brush; val clipboard = R.drawable.gk_clipboard; val barChart = R.drawable.gk_bar_chart; val calculator = R.drawable.gk_calculator
    val circleCheck = R.drawable.gk_circle_check; val editFile = R.drawable.gk_edit_file; val building = R.drawable.gk_building; val music = R.drawable.gk_music; val gem = R.drawable.gk_gem
    val arrowLeft = R.drawable.gk_arrow_left; val arrowUp = R.drawable.gk_arrow_up; val moon = R.drawable.gk_moon; val sun = R.drawable.gk_sun
    val keyboard = R.drawable.gk_keyboard; val phone = R.drawable.gk_phone; val android = R.drawable.gk_android; val speakerMuted = R.drawable.gk_speaker_muted; val github = R.drawable.gk_github
}

@Composable
fun GkIcon(@DrawableRes id: Int, desc: String? = null, size: Dp = 24.dp, tint: Color = P.text, modifier: Modifier = Modifier) =
    Icon(painterResource(id), desc, tint = tint, modifier = modifier.size(size))

@Composable
fun rememberTap(onClick: () -> Unit): () -> Unit {
    val h = LocalHapticFeedback.current; val on = LocalHaptics.current
    return remember(onClick, on) { { if (on) h.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() } }
}

@Composable
fun IconTap(@DrawableRes icon: Int, desc: String, size: Dp = 20.dp, tint: Color = P.text2, box: Dp = 36.dp, onClick: () -> Unit) {
    val tap = rememberTap(onClick)
    Box(
        Modifier.size(box).clip(CircleShape).clickable(remember { MutableInteractionSource() }, ripple(bounded = true), onClick = tap),
        contentAlignment = Alignment.Center,
    ) { GkIcon(icon, desc, size, tint) }
}

/** Grok's round top-bar buttons: 48dp, #1B1B1B fill, hairline #232323 rim, 24dp glyph. */
@Composable
fun CircleButton(@DrawableRes icon: Int, desc: String, size: Dp = 48.dp, bg: Color = if (P.isDark) Color(0xFF1B1B1B) else Color(0xFFF2F2F2), tint: Color = P.text, iconSize: Dp = 24.dp, onClick: () -> Unit) {
    val tap = rememberTap(onClick)
    Box(Modifier.size(size).clip(CircleShape).background(bg).border(1.dp, if (P.isDark) Color(0xFF232323) else Color(0xFFE5E5E5), CircleShape).clickable(onClick = tap), contentAlignment = Alignment.Center) {
        GkIcon(icon, desc, iconSize, tint)
    }
}

@Composable
fun AxionMark(size: Dp, modifier: Modifier = Modifier, tint: Color? = null) {
    Image(
        painterResource(R.drawable.axion_mark), "Axion",
        modifier = modifier.size(size),
        colorFilter = tint?.let { androidx.compose.ui.graphics.ColorFilter.tint(it) } ?: if (!P.isDark) androidx.compose.ui.graphics.ColorFilter.tint(P.text) else null,
    )
}

/** Outlined pill (Grok's chips: "Save 50%", template chips). */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, filled: Boolean = false, onClick: () -> Unit) {
    val p = P; val tap = rememberTap(onClick)
    Row(
        modifier.clip(RoundedCornerShape(50)).then(if (filled) Modifier.background(p.accent) else Modifier.border(1.dp, p.border, RoundedCornerShape(50)))
            .clickable(onClick = tap).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { GkIcon(icon, null, 18.dp, if (filled) p.onAccent else p.text); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = if (filled) p.onAccent else p.text, maxLines = 1)
    }
}

/** Grok sub-page header (Settings frames): ← (or ✕ on the Settings root) 16dp in, title left-aligned at 56dp, 20sp SemiBold, 64dp tall. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, close: Boolean = false, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTap(if (close) Gk.close else Gk.arrowLeft, if (close) "Close" else "Back", 24.dp, P.text, 44.dp, onBack)
        Spacer(Modifier.width(8.dp))
        Text(title, style = Type.title, color = P.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = P.text2, modifier = modifier.padding(start = 30.dp, top = 22.dp, bottom = 10.dp))

/**
 * Grok's grouped list: each row is its own #242424 tile, 2dp apart, the group's outer corners 20dp and the inner
 * corners 6dp — measured off the Settings and Connectors frames (rows 56dp tall, 16dp side margins).
 */
class GroupScope internal constructor(val count: Int) { internal var index = 0 }

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

fun tileShape(first: Boolean, last: Boolean): Shape = RoundedCornerShape(
    topStart = if (first) 20.dp else 6.dp, topEnd = if (first) 20.dp else 6.dp,
    bottomStart = if (last) 20.dp else 6.dp, bottomEnd = if (last) 20.dp else 6.dp,
)

@Composable
fun SettingsRow(
    @DrawableRes icon: Int?, title: String, subtitle: String? = null, value: String? = null, chevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null, titleColor: Color = P.text, iconTint: Color = P.text2, onClick: (() -> Unit)? = null,
) {
    val p = P
    val tap = onClick?.let { rememberTap(it) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(6.dp)).background(p.card)
            .then(if (tap != null) Modifier.clickable(onClick = tap) else Modifier).padding(horizontal = 16.dp, vertical = if (subtitle != null) 13.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { GkIcon(icon, null, 24.dp, iconTint); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.row, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Type.small, color = p.text2, modifier = Modifier.padding(top = 2.dp))
        }
        if (value != null) Text(value, style = Type.small.copy(fontSize = 15.sp), color = p.text2, modifier = Modifier.padding(start = 8.dp, end = 4.dp))
        if (trailing != null) trailing()
    }
}

/** Grok switch: white thumb on a white track when on, grey when off. */
@Composable
fun GrokSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = P
    val x by androidx.compose.animation.core.animateDpAsState(if (checked) 20.dp else 0.dp, label = "sw")
    val track by androidx.compose.animation.animateColorAsState(if (checked) p.accent else if (p.isDark) Color(0xFF3A3A3A) else Color(0xFFD9D9D9), label = "tr")
    Box(Modifier.size(width = 48.dp, height = 28.dp).clip(RoundedCornerShape(50)).background(track).clickable { onChange(!checked) }.padding(3.dp)) {
        Box(Modifier.offset(x = x).size(22.dp).clip(CircleShape).background(if (checked) p.onAccent else p.text))
    }
}

@Composable
fun ToggleRow(@DrawableRes icon: Int?, title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsRow(icon, title, subtitle, chevron = false, trailing = { GrokSwitch(checked, onChange) }, onClick = { onChange(!checked) })
}

@Composable
fun Divider(start: Dp = 52.dp) = Box(Modifier.padding(start = start).fillMaxWidth().height(1.dp).background(P.border))

@Composable
fun Avatar(letter: String, size: Dp = 36.dp, bg: Color = if (P.isDark) Color(0xFF3A3A3A) else Color(0xFFDFDFDF)) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(letter.take(1).uppercase(), color = P.text, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold, fontFamily = com.mazharansari.axion.ui.theme.GoogleSans)
    }
}


/**
 * v0.4: closes the keyboard and takes focus off the composer while this is in composition (popovers, sheets, the
 * drawer, every screen pushed over Home), so the keyboard can't pop back up over a menu.
 */
@Composable
fun DismissKeyboard() {
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(Unit) { fm.clearFocus(force = true); kb?.hide() }
}
