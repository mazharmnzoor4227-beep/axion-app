package com.mazharansari.axion.ui.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mazharansari.axion.livecall.ApiKeyCredential
import com.mazharansari.axion.livecall.LiveCallController
import com.mazharansari.axion.livecall.LiveCallProtocol
import com.mazharansari.axion.livecall.LiveToolBridge
import com.mazharansari.axion.livecall.LiveCallUiState
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.chat.AxionSheet
import com.mazharansari.axion.ui.chat.SheetRow
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.settings.Orb
import com.mazharansari.axion.ui.settings.OrbMode
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import com.mazharansari.axion.voice.SameVoiceCall
import com.mazharansari.axion.voice.VoicePrefs
import com.mazharansari.axion.voice.LiveVoiceCall
import com.mazharansari.axion.voice.AxVoice
import com.mazharansari.axion.voice.VoiceFamily
import com.mazharansari.axion.voice.VoiceCatalog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Live call (v0.3) — two real modes, picked with the chip at the top:
 *  • Same voice (default): Axion listens, answers with your connected AI, and speaks with the voice you picked in
 *    Voice Library — the exact read-aloud voice. Turn-based; tap the orb to cut in.
 *  • Gemini Live: Kodex's real-time call (Gemini Live native audio, gemini-3.8-live, ported from vayro), full-duplex
 *    and interruptible, but it talks in Gemini's own voices (default Kore) — Google's API can't use a phone/Edge voice.
 */
@Composable
fun VoiceCallScreen(vm: ChatViewModel, back: () -> Unit, openConnectors: () -> Unit = back) {
    val p = P
    val ctx = LocalContext.current
    var hasMic by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> hasMic = ok; if (!ok) vm.toast = "Mic permission is needed for a call" }
    LaunchedEffect(Unit) { if (!hasMic) perm.launch(Manifest.permission.RECORD_AUDIO) }
    var modeOpen by remember { mutableStateOf(false) }
    var voiceOpen by remember { mutableStateOf(false) }
    var turnsAnyway by remember { mutableStateOf(false) }
    var voiceId by remember { mutableStateOf(VoicePrefs.activeVoice(ctx).id) }
    val activeVoice = VoiceCatalog.byId(voiceId)
    val keys = vm.g.connectors.list.collectAsState().value.filter { it.enabled && it.providerId == "gemini" }
    // v0.4 (mazhar 2:09 PM: "I don't want speak → transcribe → think → answer, I want a LIVE call: I talk, the reply comes
    // straight back"). With a Gemini key the call is ALWAYS native Gemini Live audio (full-duplex, talk over it to cut in).
    // A non-Gemini voice is spoken by its same-gender Gemini twin; "Live + my own voice" (slower) is opt-in in the sheet.
    var ownVoice by remember { mutableStateOf(VoicePrefs.callOwnVoice(ctx)) }
    val mode = when {
        keys.isEmpty() -> if (turnsAnyway) CallMode.TURNS else CallMode.NEEDS_KEY
        activeVoice.family == VoiceFamily.GEMINI || !ownVoice -> CallMode.NATIVE
        else -> CallMode.HYBRID
    }
    val liveVoice = VoiceCatalog.geminiTwin(activeVoice)
    // v0.4: decrypting keys (Android Keystore) once per key list, not on every recomposition of the call screen
    val creds = remember(keys) { keys.map { ApiKeyCredential(it.id, it.provider.name, it.key(), true) } }

    Column(Modifier.fillMaxSize().background(p.bg).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        ScreenHeader("Voice", back) { Pill(mode.label, icon = Gk.voice) { modeOpen = true } }
        if (!hasMic) {
            Spacer(Modifier.weight(1f)); Orb(200.dp)
            Text("Allow the microphone to start a call", style = Type.body, color = p.text2, modifier = Modifier.padding(24.dp))
            Pill("Allow mic", filled = true) { perm.launch(Manifest.permission.RECORD_AUDIO) }
            Spacer(Modifier.weight(1f))
        } else vm.debugCall?.let { d ->
            CallBody(0.35f, "Speaking · tap the orb to cut in", "${activeVoice.name} · ${mode.sub}", { voiceOpen = true }, d.userText, d.replyText, d.status, false, d.muted, {}, {}, back)
        } ?: when (mode) {
            CallMode.NATIVE -> key(liveVoice.id, keys.size) { GeminiCall(vm, creds, liveVoice, onVoice = { voiceOpen = true }, back = back) }
            CallMode.HYBRID -> key(activeVoice.id, keys.size) { LiveVoiceCallBody(vm, creds, activeVoice, onVoice = { voiceOpen = true }, back = back) }
            CallMode.TURNS -> key(activeVoice.id) { SameVoiceCallBody(vm, "${activeVoice.name} · ${mode.sub}", onVoice = { voiceOpen = true }, back) }
            CallMode.NEEDS_KEY -> {
                Spacer(Modifier.weight(1f)); Orb(200.dp)
                Text("A live call needs a Gemini key — add one in Connectors.\n(Free key: aistudio.google.com)",
                    style = Type.body, color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(28.dp))
                Pill("Add Gemini key", filled = true) { openConnectors() }
                Spacer(Modifier.height(12.dp))
                Pill("Call without live mode (slow)") { turnsAnyway = true }
                Spacer(Modifier.weight(1f))
            }
        }
    }
    if (modeOpen) AxionSheet({ modeOpen = false }) {
        Text("How this call works", style = Type.header.copy(fontSize = 18.sp), color = p.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
        SheetRow(Gk.voice, "Live (recommended)", "You talk, the answer comes straight back in a Gemini voice (${liveVoice.name}). Fastest, talk over it to cut in.",
            onClick = { ownVoice = false; VoicePrefs.setCallOwnVoice(ctx, false); modeOpen = false }) { if (mode == CallMode.NATIVE) GkIcon(Gk.check, "Current", 22.dp, p.text) }
        SheetRow(Gk.voice, "Live + my own voice", "Gemini Live listens and answers, ${activeVoice.name} reads it. About 1 s slower.",
            onClick = { ownVoice = true; VoicePrefs.setCallOwnVoice(ctx, true); modeOpen = false }) { if (mode == CallMode.HYBRID) GkIcon(Gk.check, "Current", 22.dp, p.text) }
        Text(if (keys.isEmpty()) "Both need a free Gemini key in Connectors." else "Pick a Gemini voice (Kore, Puck…) to hear the same voice in calls and read-aloud.",
            style = Type.small, color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Pill("Change voice", filled = true) { modeOpen = false; voiceOpen = true } }
        Spacer(Modifier.height(12.dp))
    }
    if (voiceOpen) AxionSheet({ voiceOpen = false }) {
        Text("Voice", style = Type.header.copy(fontSize = 18.sp), color = p.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(VoiceCatalog.kodex + VoiceCatalog.natural + VoiceCatalog.gemini, key = { it.id }) { v ->
                SheetRow(Gk.voice, v.name, when (v.family) {
                    VoiceFamily.GEMINI -> "Gemini · ${v.tag} · native live"
                    VoiceFamily.NATURAL -> "Natural · ${v.language}"
                    else -> "Phone voice · ${v.language}"
                }, onClick = {
                    VoicePrefs.setActive(ctx, v.id); voiceId = v.id; voiceOpen = false   // same voice for read-aloud too
                }) { if (v.id == voiceId) GkIcon(Gk.check, "Selected", 22.dp, p.text) }
            }
        }
    }
}

private enum class CallMode(val label: String, val sub: String, val explain: String) {
    NATIVE("Live", "Gemini Live, real-time", "Gemini voices: Gemini hears you and answers in its own voice, live. Fastest (≈0.6 s), talk over it to cut in."),
    HYBRID("Live + your voice", "Gemini Live listens, your voice speaks", "Phone or natural voices: Gemini Live listens and answers, your voice reads the answer as it streams. ≈1 s slower than native; tap the orb to cut in."),
    TURNS("Turn-based", "add a Gemini key for a live call", "No Gemini key: the phone listens, your fastest AI key answers, your voice speaks it sentence by sentence."),
    NEEDS_KEY("Needs Gemini key", "", ""),
}

@Composable
private fun ColumnScope.LiveVoiceCallBody(vm: ChatViewModel, creds: List<ApiKeyCredential>, voice: AxVoice, onVoice: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val call = remember { LiveVoiceCall(ctx, creds, voice, onTurn = { u, a -> vm.appendCallTurn(u, a) }) }
    DisposableEffect(call) { call.start(); onDispose { call.stop() } }
    val st by call.state.collectAsState()
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(call) {
        var t = 0f
        while (true) {
            delay(33); t += 0.033f
            val target = when (st.phase) {
                LiveVoiceCall.Phase.LISTENING -> call.micLevel
                LiveVoiceCall.Phase.SPEAKING -> 0.35f + 0.25f * kotlin.math.sin(t * 7f).let { it * it }
                LiveVoiceCall.Phase.THINKING, LiveVoiceCall.Phase.CONNECTING -> 0.12f + 0.08f * kotlin.math.sin(t * 3f)
                else -> 0f
            }
            level += (target - level) * 0.25f
        }
    }
    CallBody(
        orbLevel = level,
        orbMode = when (st.phase) {
            LiveVoiceCall.Phase.LISTENING -> OrbMode.Listening
            LiveVoiceCall.Phase.THINKING, LiveVoiceCall.Phase.SPEAKING -> OrbMode.Composing
            else -> OrbMode.Frozen   // Connecting / Error / Ended: a still listening frame (owner's defaults, 2026-11-03)
        },
        phaseText = when (st.phase) {
            LiveVoiceCall.Phase.CONNECTING -> "Connecting…"
            LiveVoiceCall.Phase.LISTENING -> if (st.muted) "Muted" else "Listening…"
            LiveVoiceCall.Phase.THINKING -> "Thinking…"
            LiveVoiceCall.Phase.SPEAKING -> "Speaking · tap the orb to cut in"
            LiveVoiceCall.Phase.ERROR -> "Tap the orb to reconnect"
            LiveVoiceCall.Phase.ENDED -> "Call ended"
        },
        sub = "${voice.name} · ${CallMode.HYBRID.sub}", onSub = onVoice, user = st.userText, reply = st.replyText, status = st.status,
        error = st.phase == LiveVoiceCall.Phase.ERROR, muted = st.muted, onOrb = { call.interrupt() }, onMute = { call.setMuted(!st.muted) }, onEnd = { call.stop(); back() },
    )
}

@Composable
private fun ColumnScope.SameVoiceCallBody(vm: ChatViewModel, voiceLabel: String, onVoice: () -> Unit, back: () -> Unit) {
    val p = P
    val ctx = LocalContext.current
    val call = remember {
        SameVoiceCall(ctx, language = { vm.g.settings.state.value.speechLang }, reply = { h, t, d -> vm.callReply(h, t, d) }, onTurn = { u, a -> vm.appendCallTurn(u, a) })
    }
    DisposableEffect(call) { call.start(); onDispose { call.stop() } }
    val st by call.state.collectAsState()
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(call) {
        var t = 0f
        while (true) {
            delay(33); t += 0.033f
            val target = when (st.phase) {
                SameVoiceCall.Phase.LISTENING -> call.micLevel
                SameVoiceCall.Phase.SPEAKING -> 0.35f + 0.25f * kotlin.math.sin(t * 7f).let { it * it }
                SameVoiceCall.Phase.THINKING -> 0.12f + 0.08f * kotlin.math.sin(t * 3f)
                else -> 0f
            }
            level += (target - level) * 0.25f
        }
    }
    CallBody(
        orbLevel = level,
        orbMode = when (st.phase) {
            SameVoiceCall.Phase.LISTENING -> OrbMode.Listening
            SameVoiceCall.Phase.THINKING, SameVoiceCall.Phase.SPEAKING -> OrbMode.Composing
            else -> OrbMode.Frozen
        },
        phaseText = when (st.phase) {
            SameVoiceCall.Phase.LISTENING -> if (st.muted) "Muted" else "Listening…"
            SameVoiceCall.Phase.THINKING -> "Thinking…"
            SameVoiceCall.Phase.SPEAKING -> "Speaking · tap the orb to cut in"
            SameVoiceCall.Phase.ERROR -> "Tap the orb to try again"
            SameVoiceCall.Phase.ENDED -> "Call ended"
        },
        sub = voiceLabel, onSub = onVoice, user = st.userText, reply = st.replyText, status = st.status, error = st.phase == SameVoiceCall.Phase.ERROR,
        muted = st.muted, onOrb = { call.interrupt() }, onMute = { call.setMuted(!st.muted) }, onEnd = { call.stop(); back() },
    )
}

@Composable
private fun ColumnScope.GeminiCall(vm: ChatViewModel, creds: List<ApiKeyCredential>, axVoice: AxVoice, onVoice: () -> Unit, back: () -> Unit) {
    val voice = axVoice.engineVoice
    val ctx = LocalContext.current
    // v0.8.3: the call itself lives in the view model (ChatViewModel.startLiveCall — tools, phone
    // arming and the v0.8.2 conversation seeding all happen there). This screen is only a view of
    // it, so going back now MINIMIZES the call (AppRoot floats its orb) instead of hanging up;
    // the End button below is the real hang-up.
    val controller = remember { vm.startLiveCall(ctx, creds, voice) }
    val st by controller.uiState.collectAsState()
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(controller) { while (true) { delay(33); level += (controller.orbLevel() - level) * 0.3f } }
    CallBody(
        orbLevel = level,
        orbMode = when (st.phase) {
            LiveCallUiState.Phase.LISTENING -> OrbMode.Listening
            LiveCallUiState.Phase.SPEAKING -> OrbMode.Composing
            else -> OrbMode.Frozen   // Connecting / Error / Ended: a still listening frame (owner's defaults, 2026-11-03)
        },
        phaseText = when (st.phase) {
            LiveCallUiState.Phase.CONNECTING -> "Connecting…"
            LiveCallUiState.Phase.LISTENING -> if (st.muted) "Muted" else "Listening…"
            LiveCallUiState.Phase.SPEAKING -> "Speaking · just talk to cut in"
            LiveCallUiState.Phase.ERROR -> "Call failed"
            LiveCallUiState.Phase.ENDED -> "Call ended"
        },
        sub = "$voice · ${CallMode.NATIVE.sub}", onSub = onVoice,
        // v0.4: a live call shows no transcribe/thinking steps while you talk — the words land in the chat afterwards.
        user = "", reply = "", status = st.statusLine, error = st.statusIsError,
        muted = st.muted, onOrb = {}, onMute = { controller.setMuted(!st.muted) }, onEnd = { vm.endLiveCall(); back() },
        speaker = st.speaker, onSpeaker = { controller.setSpeaker(!st.speaker) }, boost = st.boost, onBoost = { controller.setBoost(!st.boost) },
    )
    // v0.5.3: hardware volume keys change the call's volume while the call screen is open.
    val act = LocalContext.current.let { c -> var x: android.content.Context? = c; while (x is android.content.ContextWrapper && x !is android.app.Activity) x = x.baseContext; x as? android.app.Activity }
    DisposableEffect(act) {
        val old = act?.volumeControlStream
        act?.volumeControlStream = android.media.AudioManager.STREAM_VOICE_CALL
        onDispose { if (act != null && old != null) act.volumeControlStream = old }
    }
}

@Composable
private fun ColumnScope.CallBody(
    orbLevel: Float, phaseText: String, sub: String, onSub: (() -> Unit)? = null, user: String, reply: String, status: String?, error: Boolean,
    muted: Boolean, onOrb: () -> Unit, onMute: () -> Unit, onEnd: () -> Unit,
    speaker: Boolean? = null, onSpeaker: () -> Unit = {}, boost: Boolean? = null, onBoost: () -> Unit = {},
    orbMode: OrbMode = OrbMode.Listening,
) {
    val p = P
    Spacer(Modifier.weight(0.7f))
    Box(Modifier.clip(RoundedCornerShape(50)).clickable(remember { MutableInteractionSource() }, null, onClick = onOrb)) { Orb(220.dp, orbLevel, orbMode) }
    Spacer(Modifier.height(28.dp))
    Text(phaseText, style = Type.title, color = p.text)
    Text(sub, style = Type.small.copy(fontSize = 14.sp), color = p.text2, modifier = Modifier.padding(top = 6.dp).then(if (onSub != null) Modifier.clickable(onClick = onSub) else Modifier))
    Spacer(Modifier.height(18.dp))
    Column(Modifier.padding(horizontal = 28.dp).heightIn(min = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (user.isNotBlank()) Text("“$user”", style = Type.body.copy(fontSize = 15.sp), color = p.text2, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (reply.isNotBlank()) Text(reply, style = Type.body, color = p.text, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        if (!status.isNullOrBlank()) Text(status, style = Type.small, color = if (error) p.danger else p.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
    Spacer(Modifier.weight(1f))
    if (speaker != null) Row(Modifier.padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CallChip(if (speaker) "Speaker" else "Earpiece", if (speaker) Gk.speaker else Gk.phone, speaker, onSpeaker)
        if (boost != null) CallChip(if (boost) "Boost on" else "Boost off", Gk.bolt, boost, onBoost)
    }
    Row(Modifier.padding(bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        CircleButton(if (muted) Gk.speakerMuted else Gk.mic, if (muted) "Unmute" else "Mute", size = 64.dp,
            bg = if (muted) p.text else (if (p.isDark) Color(0xFF1B1B1B) else Color(0xFFF2F2F2)), tint = if (muted) p.bg else p.text, onClick = onMute)
        CircleButton(Gk.close, "End call", size = 64.dp, bg = p.danger, tint = Color.White, onClick = onEnd)
    }
}

/** v0.5.3 in-call chip (Speaker / Earpiece, volume Boost). */
@Composable
private fun CallChip(label: String, icon: Int, on: Boolean, onClick: () -> Unit) {
    val p = P
    Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) p.text else (if (p.isDark) Color(0xFF1B1B1B) else Color(0xFFF2F2F2))).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        GkIcon(icon, null, 18.dp, if (on) p.bg else p.text)
        Spacer(Modifier.width(8.dp))
        Text(label, style = Type.small.copy(fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = if (on) p.bg else p.text)
    }
}

/**
 * v0.8.3: the floating call orb AppRoot shows over every other screen while a Live call is held
 * by the view model and the call screen is not open (the owner asked to keep talking while he
 * works in the chat). Drag it anywhere; tap it to return to the full call; the red badge hangs up.
 */
@Composable
fun FloatingCallOrb(vm: ChatViewModel, onOpen: () -> Unit) {
    val controller = vm.liveCall ?: return
    val st by controller.uiState.collectAsState()
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(controller) { while (true) { delay(33); level += (controller.orbLevel() - level) * 0.3f } }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 110.dp)
                .offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
                .size(68.dp)
        ) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(P.surface2)
                    .pointerInput(controller) {
                        detectDragGestures { change, drag -> change.consume(); ox += drag.x; oy += drag.y }
                    }
                    .clickable(remember { MutableInteractionSource() }, null) { onOpen() },
                contentAlignment = Alignment.Center,
            ) {
                Orb(54.dp, level, when (st.phase) {
                    LiveCallUiState.Phase.LISTENING -> OrbMode.Listening
                    LiveCallUiState.Phase.SPEAKING -> OrbMode.Composing
                    else -> OrbMode.Frozen
                })
            }
            Box(
                Modifier.align(Alignment.TopEnd).size(24.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE5252A))
                    .clickable(remember { MutableInteractionSource() }, null) { vm.endLiveCall() },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Color.White, fontSize = 12.sp) }
        }
    }
}
