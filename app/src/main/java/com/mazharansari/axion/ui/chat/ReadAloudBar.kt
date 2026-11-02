package com.mazharansari.axion.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.ui.components.Gk
import com.mazharansari.axion.ui.components.GkIcon
import com.mazharansari.axion.ui.components.rememberTap
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type

/*
 * v0.3.1 — Grok's read-aloud player, measured from mazhar's 10:33 recording of the real Grok app (480×1068 capture of a
 * 720×1600 / 1.75x screen → 1 capture px = 0.857 dp):
 *  pill 281×52 dp, #252525, centred 16 dp above the composer; five 34 dp #2D2D2D circles / capsules 5 dp apart, 9 dp in:
 *  pause/play · ⟲15 · 15⟳ · speed capsule (46 dp, "1x") · elapsed "0:20" (56 dp) · ✕.
 *  A 2 dp progress line runs along the pill's straight bottom edge (track #585858, played part white).
 *  Speed list: 113 dp wide #1A1A1A panel standing on the pill above the speed capsule, 0.75x at the top … 3x at the bottom,
 *  48 dp rows, the current speed in bold (no checkmark — Grok shows none).
 */

private val PillBg = Color(0xFF252525)
private val BtnBg = Color(0xFF2D2D2D)
private val MenuBg = Color(0xFF1A1A1A)

fun speedLabel(v: Float): String {
    val s = if (v % 1f == 0f) v.toInt().toString() else v.toString().trimEnd('0').trimEnd('.')
    return s + "x"
}

fun clockLabel(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun ReadAloudPill(
    paused: Boolean, elapsedMs: Long, progress: Float, speed: Float, speedOpen: Boolean,
    onPlayPause: () -> Unit, onBack: () -> Unit, onForward: () -> Unit, onSpeed: () -> Unit, onClose: () -> Unit,
    onSpeedAnchor: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier.height(52.dp).clip(CircleShape).background(PillBg).border(1.dp, Color(0xFF2B2B2B), CircleShape)) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PillButton(if (paused) "Play" else "Pause", onPlayPause) { GkIcon(if (paused) Gk.play else Gk.pause, null, 17.dp, Color.White) }
            PillButton("Back 15 seconds", onBack) { Skip15(back = true) }
            PillButton("Forward 15 seconds", onForward) { Skip15(back = false) }
            val tap = rememberTap(onSpeed)
            Box(Modifier.width(46.dp).height(34.dp).clip(CircleShape).background(if (speedOpen) Color(0xFF3A3A3A) else BtnBg)
                .onGloballyPositioned { onSpeedAnchor(it.boundsInRoot()) }
                .semantics { contentDescription = "Playback speed" }.clickable(onClick = tap), contentAlignment = Alignment.Center) {
                Text(speedLabel(speed), style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = Color.White, maxLines = 1)
            }
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                Text(clockLabel(elapsedMs), style = Type.small.copy(fontSize = 15.sp), color = Color(0xFFB8B8B8), maxLines = 1)
            }
            PillButton("Close player", onClose) { GkIcon(Gk.close, null, 16.dp, Color.White) }
        }
        if (progress > 0f) Box(Modifier.matchParentSize()) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 26.dp).height(2.dp).background(Color(0xFF585858))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Color.White))
            }
        }
    }
}

@Composable
private fun PillButton(desc: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val tap = rememberTap(onClick)
    Box(Modifier.size(34.dp).clip(CircleShape).background(BtnBg).semantics { contentDescription = desc }.clickable(onClick = tap), contentAlignment = Alignment.Center) { content() }
}

/** Grok's ⟲15 / 15⟳ glyph: a ~300° ring with an arrow head at the top and "15" inside. */
@Composable
fun Skip15(back: Boolean, size: Dp = 22.dp, color: Color = Color.White) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 1.6.dp.toPx(); val r = this.size.minDimension / 2f - sw
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            drawArc(color, if (back) -120f else -60f, if (back) -330f else 330f, false,
                topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
            // arrow head at the top, pointing into the gap (the direction of travel)
            val dir = if (back) -1f else 1f
            val top = c.y - r
            val p = Path().apply {
                moveTo(c.x + dir * r * 0.48f, top)
                lineTo(c.x - dir * r * 0.06f, top - r * 0.38f)
                lineTo(c.x - dir * r * 0.06f, top + r * 0.38f)
                close()
            }
            drawPath(p, color)
        }
        Text("15", style = Type.tiny.copy(fontSize = 7.5.sp, lineHeight = 8.sp, fontWeight = FontWeight.Bold), color = color, modifier = Modifier.padding(top = 1.dp))
    }
}

/** The vertical speed list standing on the pill (Grok: 0.75x at the top … 3x at the bottom). */
@Composable
fun SpeedMenu(open: Boolean, current: Float, speeds: List<Float>, onPick: (Float) -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(open, modifier = modifier,
        enter = fadeIn(tween(140)) + expandVertically(tween(200, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom),
        exit = fadeOut(tween(120)) + shrinkVertically(tween(160), shrinkTowards = Alignment.Bottom)) {
        Column(Modifier.width(113.dp).clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 4.dp, bottomEnd = 4.dp)).background(MenuBg)) {
            speeds.forEach { v ->
                val sel = v == current
                val tap = rememberTap { onPick(v) }
                Box(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = tap).padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
                    Text(speedLabel(v), style = Type.body.copy(fontSize = 16.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal),
                        color = if (sel) Color.White else Color(0xFFDADADA))
                }
            }
        }
    }
}

/**
 * Grok's pull-up-for-new-chat (recording 0:01–0:05): drag the chat up past its end and a dot rises from the bottom,
 * growing into a 35 dp light-blue circle with the new-chat glyph; at the threshold a light haptic tick and "New chat"
 * appears under it. Let go there → new chat. [pull] is 0..1+ of the threshold.
 */
@Composable
fun NewChatPull(pull: Float, armed: Boolean, modifier: Modifier = Modifier) {
    if (pull <= 0.02f) return
    val f = pull.coerceIn(0f, 1f)
    val circle = (8f + 27f * f).dp
    val blue by animateColorAsState(if (armed) Color(0xFF6DB4FF) else Color(0xFF3A3A3A), tween(140), label = "pullc")
    Column(modifier.graphicsLayer { alpha = (f * 1.6f).coerceAtMost(1f) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(circle).clip(CircleShape).background(blue), contentAlignment = Alignment.Center) {
            if (f > 0.55f) GkIcon(Gk.newChat, "New chat", (18f * f).dp, if (armed) Color(0xFF111111) else Color(0xFFBDBDBD))
        }
        AnimatedVisibility(armed, enter = fadeIn(tween(120)) + expandVertically(), exit = fadeOut(tween(100)) + shrinkVertically()) {
            Text("New chat", style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = P.text, modifier = Modifier.padding(top = 7.dp))
        }
    }
}
