package com.mazharansari.axion.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.data.Attachment
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type

/**
 * Grok's composer, measured off the recordings: 8dp side margins, #1F1F1F fill, 1dp #2D2D2D rim, 20dp corners,
 * 17sp "Ask anything" placeholder 16dp in, then one 36dp control row 6dp from the edge:
 * (+) circle · [⚡ Fast ⌄] chip ……… (mic) circle · [≋ Speak] white pill → (↑) when there is text → (■) while running.
 */
@Composable
fun Composer(
    text: String, onText: (String) -> Unit, placeholder: String, modeLabel: String, running: Boolean,
    attachments: List<Attachment>, onRemoveAttachment: (Attachment) -> Unit,
    listening: Boolean, level: () -> Float, partial: String,
    onPlus: () -> Unit, onMode: () -> Unit, onMic: () -> Unit, onSend: () -> Unit, onStop: () -> Unit, onVoiceCall: () -> Unit,
    modifier: Modifier = Modifier, plusOpen: Boolean = false, modeOpen: Boolean = false, compact: Boolean = false,
    onMicCancel: () -> Unit = onMic,
    /** v0.5.2 speed: the field got focus or the first character was typed — warm up the connection. */
    onFocus: () -> Unit = {},
    /** v0.11: Deep Research toggle chip, shown in the Ask composer only (null = hidden; Build / Imagine pass nothing). */
    researchOn: Boolean? = null, onResearch: () -> Unit = {},
    /** v0.12: Long task toggle chip, same deal — Ask composer only. */
    longTaskOn: Boolean? = null, onLongTask: () -> Unit = {},
) {
    val p = P
    val shape = RoundedCornerShape(if (compact) 28.dp else 20.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(shape).background(p.surface).border(1.dp, p.border, shape)) {
        if (attachments.isNotEmpty()) {
            Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                attachments.forEach { a -> key(a.path) { AttachmentChip(a) { onRemoveAttachment(a) } } }
            }
        }
        if (compact) {
            // Build tab: one-line pill — (+) "Type to Build…" (mic) (↑)
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundTool(if (plusOpen) Gk.close else Gk.plus, "Attach", onPlus)
                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { Field(text, onText, placeholder, listening, partial, maxLines = 4, onFocus = onFocus) }
                if (!running) { RoundTool(Gk.mic, "Dictate", onMic, active = listening); Spacer(Modifier.width(8.dp)) }
                SendButton(if (running) 2 else if (text.isNotBlank() || attachments.isNotEmpty()) 1 else 3, onSend, onStop, onVoiceCall)
            }
            return@Column
        }
        // v0.3.1 (Grok, measured): text line 13 dp below the top, control row 16 dp under it, 7 dp from the bottom → 95 dp box.
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 16.dp).heightIn(min = 24.dp, max = 168.dp)) {
            Field(text, onText, placeholder, listening, partial, maxLines = 7, onFocus = onFocus)
        }
        if (listening) {
            // Grok dictation row: (×) cancel · dotted line + live bars · (✓) done
            Row(Modifier.fillMaxWidth().padding(start = 7.dp, end = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundTool(Gk.close, "Cancel dictation", onMicCancel)
                // Kodex's waveform: frame-loop driven, reads the mic level once per frame, no recomposition per RMS event.
                com.mazharansari.axion.ui.voice.LiveWaveform(level, Modifier.weight(1f).height(36.dp).padding(start = 16.dp, end = 10.dp), color = p.text)
                Box(Modifier.size(36.dp).clip(CircleShape).background(p.accent).clickable(onClick = onMic), contentAlignment = Alignment.Center) { GkIcon(Gk.check, "Done", 20.dp, p.onAccent) }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = 7.dp, end = 10.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundTool(if (plusOpen) Gk.close else Gk.plus, "Attach", onPlus)
            Spacer(Modifier.width(6.dp))
            ModeChip(modeLabel, modeOpen, onMode)
            if (researchOn != null) { Spacer(Modifier.width(6.dp)); ResearchChip(researchOn, onResearch) }
            if (longTaskOn != null) { Spacer(Modifier.width(6.dp)); LongTaskChip(longTaskOn, onLongTask) }
            Spacer(Modifier.weight(1f))
            val state = when { running -> 2; text.isNotBlank() || attachments.isNotEmpty() -> 1; else -> 0 }
            AnimatedVisibility(!running, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                Row { RoundTool(Gk.mic, if (listening) "Stop dictation" else "Dictate", onMic, active = listening); Spacer(Modifier.width(8.dp)) }
            }
            SendButton(state, onSend, onStop, onVoiceCall)
        }
    }
}

@Composable
private fun Field(text: String, onText: (String) -> Unit, placeholder: String, listening: Boolean, partial: String, maxLines: Int, onFocus: () -> Unit = {}) {
    val p = P
    val style = Type.body.copy(fontSize = 17.sp, lineHeight = 24.sp)
    if (listening) {
        Text(partial.ifBlank { "Listening…" }, style = style, color = p.text2, maxLines = 3)
    } else Box {
        if (text.isEmpty()) Text(placeholder, style = style, color = Color(0xFF8E8E8E).takeIf { p.isDark } ?: p.text3, maxLines = 1)
        BasicTextField(text, { t -> if (text.isEmpty() && t.isNotEmpty()) onFocus(); onText(t) }, textStyle = style.copy(color = p.text), cursorBrush = SolidColor(p.text), maxLines = maxLines,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() })
    }
}

/** 36dp #282828 circle holding a 22dp Grok glyph (the + and the mic). */
@Composable
fun RoundTool(icon: Int, desc: String, onClick: () -> Unit, active: Boolean = false) {
    val p = P; val tap = rememberTap(onClick)
    Box(Modifier.size(36.dp).clip(CircleShape).background(if (active) p.accent else p.chip).clickable(onClick = tap), contentAlignment = Alignment.Center) {
        Crossfade(icon, animationSpec = tween(120), label = "tool") { GkIcon(it, desc, 20.dp, if (active) p.onAccent else p.text) }
    }
}

@Composable
private fun ModeChip(label: String, open: Boolean, onClick: () -> Unit) {
    val p = P; val tap = rememberTap(onClick)
    Row(Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(if (open) Color(0xFF363636).takeIf { p.isDark } ?: p.border else p.chip).clickable(onClick = tap).padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        GkIcon(modeIcon(label), null, 18.dp, p.text)
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = p.text)
        Spacer(Modifier.width(4.dp))
        GkIcon(Gk.chevronDown, null, 16.dp, p.text2)
    }
}

fun modeIcon(label: String) = when (label) { "Fast" -> Gk.bolt; "Expert" -> Gk.think; "Heavy" -> Gk.connectors; "Build" -> Gk.hammer; else -> Gk.rocket }

/** v0.11: the Deep Research toggle — filled with the accent colour while on, so the mode is visible at a glance. */
@Composable
private fun ResearchChip(on: Boolean, onClick: () -> Unit) {
    val p = P; val tap = rememberTap(onClick)
    Row(Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(if (on) p.accent else p.chip).clickable(onClick = tap).padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        GkIcon(Gk.search, null, 17.dp, if (on) p.onAccent else p.text2)
        Spacer(Modifier.width(6.dp))
        Text("Research", style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = if (on) p.onAccent else p.text)
    }
}

/** v0.12: the Long task toggle — same chip family as Research: accent-filled while on. */
@Composable
private fun LongTaskChip(on: Boolean, onClick: () -> Unit) {
    val p = P; val tap = rememberTap(onClick)
    Row(Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(if (on) p.accent else p.chip).clickable(onClick = tap).padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        GkIcon(Gk.clock, null, 17.dp, if (on) p.onAccent else p.text2)
        Spacer(Modifier.width(6.dp))
        Text("Long task", style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = if (on) p.onAccent else p.text)
    }
}

/** 0 = Speak pill (empty, opens voice), 1 = send ↑, 2 = stop ■, 3 = disabled send (Build). */
@Composable
private fun SendButton(state: Int, onSend: () -> Unit, onStop: () -> Unit, onVoice: () -> Unit) {
    val p = P
    val tap = rememberTap { when (state) { 0 -> onVoice(); 1 -> onSend(); 2 -> onStop(); else -> {} } }
    val bg = if (state == 3) p.chip else p.accent
    Row(
        Modifier.height(36.dp).animateContentSize(tween(160)).clip(RoundedCornerShape(50)).background(bg).clickable(onClick = tap)
            .padding(horizontal = if (state == 0) 14.dp else 0.dp).widthIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        when (state) {
            0 -> { GkIcon(Gk.voice, "Voice mode", 18.dp, p.onAccent); Spacer(Modifier.width(6.dp)); Text("Speak", style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = p.onAccent) }
            1 -> Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { GkIcon(Gk.send, "Send", 20.dp, p.onAccent) }
            2 -> Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(p.onAccent)) }
            else -> Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { GkIcon(Gk.send, "Send", 20.dp, p.text3) }
        }
    }
}

