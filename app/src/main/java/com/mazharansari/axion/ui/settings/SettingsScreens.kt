package com.mazharansari.axion.ui.settings

import com.mazharansari.axion.update.UpdateInfo
import com.mazharansari.axion.update.UpdateRules
import com.mazharansari.axion.update.UpdateState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.BuildConfig
import com.mazharansari.axion.data.AppSettings
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import com.mazharansari.axion.ui.chat.ArtifactCard
import com.mazharansari.axion.ui.chat.sizeLabel
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.home.FileActions
import com.mazharansari.axion.ui.home.openFile
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import com.mazharansari.axion.voice.ReadAloudPlayer
import com.mazharansari.axion.voice.VoicePrefs
import com.mazharansari.axion.voice.VoiceCatalog
import com.mazharansari.axion.voice.VoiceFamily
import com.mazharansari.axion.voice.AxVoice

/** Grok settings page: #181818 page, left-aligned header, grouped #242424 tiles. */
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, close: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(P.page2)) {
        ScreenHeader(title, onBack, close)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 28.dp), content = content)
    }
}

@Composable
private fun settingsOf(vm: ChatViewModel): AppSettings = vm.g.settings.state.collectAsState().value

@Composable
private fun Check(on: Boolean) { if (on) GkIcon(Gk.check, "Selected", 22.dp, P.text) }

@Composable
fun SettingsScreen(vm: ChatViewModel, nav: (Route) -> Unit, back: () -> Unit) {
    val s = settingsOf(vm)
    val p = P
    val keys by vm.g.connectors.list.collectAsState()
    val skills by vm.g.skills.skills.collectAsState()
    SettingsPage("Settings", back, close = true) {
        // profile card
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).clickable { nav(Route.Customize) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).border(2.dp, if (p.isDark) Color(0xFF3A3A3A) else p.border, CircleShape).padding(4.dp)) { Avatar(s.userName.ifBlank { "You" }, 48.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                // v0.5.2: a fresh install shows "You" + "Add your name" — never the developer's name.
                Text(s.userName.ifBlank { "You" }, style = Type.row.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), color = p.text)
                Text(if (s.userName.isBlank()) "Add your name" else "Edit profile", style = Type.small.copy(fontSize = 15.sp), color = p.text2)
            }
        }
        // Grok's Premium card → Axion's providers card (same layout, blue pill button)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            GkIcon(Gk.connectors, null, 24.dp, p.text)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (keys.isEmpty()) "Connect a free AI key" else "${keys.size} AI key${if (keys.size > 1) "s" else ""} connected", style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = p.text)
                Text(if (keys.isEmpty()) "Gemini, Groq, OpenRouter & more" else "Auto-switch when a limit hits", style = Type.tiny.copy(fontSize = 13.sp), color = p.text2)
            }
            Box(Modifier.height(30.dp).clip(RoundedCornerShape(50)).background(Color(0xFF1A5EFF)).clickable { nav(Route.Connectors) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(if (keys.isEmpty()) "Connect" else "Manage", style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = Color.White)
            }
        }
        // v0.5.3: Settings → Account — the signed-in user's photo, name and email (or "Sign in" for a guest).
        val auth = remember { com.mazharansari.axion.auth.AuthHub.get(vm.getApplication()) }
        if (auth.available) {
            val au = auth.state.collectAsState().value.user
            SectionLabel("Account")
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(20.dp)).background(p.card).clickable { nav(Route.Account) }
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                com.mazharansari.axion.ui.auth.UserPhoto(au, 40.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(au?.shownName ?: "Sign in", style = Type.row, color = p.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(au?.email ?: if (au == null) "Google, Email or GitHub" else "Signed in with ${au.provider.title}", style = Type.small, color = p.text2, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                GkIcon(Gk.chevronRight, null, 20.dp, p.text3)
            }
        }
        SectionLabel("App")
        SettingsGroup {
            SettingsRow(Gk.theme, "Appearance", subtitle = s.theme.replaceFirstChar { it.uppercase() }) { nav(Route.Appearance) }
            SettingsRow(Gk.haptics, "Haptics") { nav(Route.Haptics) }
            SettingsRow(Gk.language, "App Language", subtitle = s.appLanguage) { nav(Route.Language) }
            SettingsRow(Gk.sliders, "Advanced") { nav(Route.Advanced) }
        }
        SectionLabel("Axion")
        SettingsGroup {
            SettingsRow(Gk.wand, "Customize Axion") { nav(Route.Customize) }
            SettingsRow(Gk.connectors, "Connectors", subtitle = if (keys.isEmpty()) "None" else keys.joinToString(", ") { it.provider.name }.take(40)) { nav(Route.Connectors) }
            SettingsRow(Gk.skills, "Skills", subtitle = "${skills.count { it.enabled }} enabled") { nav(Route.Skills) }
            SettingsRow(Gk.tool, "Tools library", subtitle = com.mazharansari.axion.agent.ToolCatalog.groups.count { com.mazharansari.axion.agent.ToolCatalog.isEnabled(it, s.toolSwitches) }.let { "$it of ${com.mazharansari.axion.agent.ToolCatalog.groups.size} on" }) { nav(Route.ToolsLibrary) }   // axion-p3
            SettingsRow(Gk.shield, "Phone control", subtitle = if (s.phoneConsent) "Allowed · red STOP button" else "Off · needs your permission") { nav(Route.PhoneControl) }
            SettingsRow(Gk.terminal, "Developer tools", subtitle = "Linux · Python, Node, C++, PHP…") { nav(Route.DevTools) }
            SettingsRow(Gk.rocket, "Antigravity", subtitle = vm.g.agy.store.collectAsState().value.accounts.size.let { if (it == 0) "Multi-account, auto-switch" else "$it account${if (it > 1) "s" else ""}" }) { nav(Route.Antigravity) }
            SettingsRow(Gk.usage, "Quota", subtitle = "Used, left and reset times") { nav(Route.Quota) }
            SettingsRow(Gk.voice, "Voice") { nav(Route.Voice) }
        }
        SectionLabel("Data & Information")
        SettingsGroup {
            SettingsRow(Gk.library, "Library") { nav(Route.Files) }
            SettingsRow(Gk.database, "Data Controls") { nav(Route.Data) }
            SettingsRow(Gk.folder, "Storage", subtitle = "Space used, delete items, clear cache") { nav(Route.Storage) }
        }
        Spacer(Modifier.height(18.dp))
        SettingsGroup {
            SettingsRow(Gk.info, "About Axion") { nav(Route.About) }
            SettingsRow(Gk.help, "Report a problem") { nav(Route.About) }
        }
        Text("Axion ${BuildConfig.VERSION_NAME}", style = Type.small.copy(fontSize = 15.sp), color = p.text2, modifier = Modifier.fillMaxWidth().padding(top = 26.dp), textAlign = TextAlign.Center)
        Text("by Mazhar Ansari", style = Type.tiny, color = p.text3, modifier = Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.Center)
    }
}

/** Grok Appearance (frame 0:05): theme tiles (selected = white tile, black glyph) + a Text size card with live preview. */
@Composable
fun AppearanceScreen(vm: ChatViewModel, back: () -> Unit) {
    val s = settingsOf(vm); val p = P
    SettingsPage("Appearance", back) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Triple("system", "System", Gk.settings), Triple("dark", "Dark", Gk.moon), Triple("light", "Light", Gk.sun)).forEach { (id, label, icon) ->
                val sel = s.theme == id
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(22.dp)).background(if (sel) p.accent else p.card).clickable { vm.g.settings.update { it.copy(theme = id) } },
                        contentAlignment = Alignment.Center) { GkIcon(icon, null, 24.dp, if (sel) p.onAccent else p.text2) }
                    Spacer(Modifier.height(10.dp))
                    Text(label, style = Type.body.copy(fontSize = 16.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium), color = if (sel) p.text else p.text2)
                }
            }
        }
        SectionLabel("Text size")
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(s.textScale, { v -> vm.g.settings.update { it.copy(textScale = (Math.round(v * 20) / 20f)) } }, valueRange = 0.85f..1.3f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.page2, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.align(Alignment.End).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)).background(p.page2).padding(horizontal = 16.dp, vertical = 11.dp)) {
                Text("What should we work on today?", style = Type.body, color = p.text)
            }
            Spacer(Modifier.height(14.dp))
            Text("Hi! I'm Axion. What can I help you with?", style = Type.body, color = p.text)
            Spacer(Modifier.height(18.dp))
            Text("Reset", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.text, modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(50)).clickable { vm.g.settings.update { it.copy(textScale = 1f) } }.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

@Composable
fun HapticsScreen(vm: ChatViewModel, back: () -> Unit) {
    val s = settingsOf(vm)
    SettingsPage("Haptics", back) {
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            ToggleRow(Gk.haptics, "Haptic feedback", "Light taps on buttons", s.haptics) { v -> vm.g.settings.update { it.copy(haptics = v) } }
            ToggleRow(Gk.voice, "While streaming", "Subtle ticks as answers arrive", s.hapticsStreaming) { v -> vm.g.settings.update { it.copy(hapticsStreaming = v) } }
        }
    }
}

@Composable
fun LanguageScreen(vm: ChatViewModel, back: () -> Unit) {
    val s = settingsOf(vm)
    SettingsPage("App Language", back) {
        SectionLabel("App language")
        SettingsGroup {
            listOf("English", "اردو (Urdu)", "हिन्दी (Hindi)").forEach { l ->
                SettingsRow(null, l, chevron = false, trailing = { Check(s.appLanguage == l) }) { vm.g.settings.update { it.copy(appLanguage = l) } }
            }
        }
        SectionLabel("Speech language (mic + read aloud)")
        SettingsGroup {
            listOf("ur-PK" to "Urdu (Pakistan)", "hi-IN" to "Hindi", "en-US" to "English").forEach { (id, l) ->
                SettingsRow(null, l, chevron = false, trailing = { Check(s.speechLang == id) }) { vm.g.settings.update { it.copy(speechLang = id) } }
            }
        }
    }
}

@Composable
fun AdvancedScreen(vm: ChatViewModel, back: () -> Unit) {
    val s = settingsOf(vm)
    SettingsPage("Advanced", back) {
        SectionLabel("Paste as File")
        SettingsGroup {
            listOf("Always ask", "Always", "Never").forEach { l ->
                SettingsRow(null, l, chevron = false, trailing = { Check(s.pasteAsFile == l) }) { vm.g.settings.update { it.copy(pasteAsFile = l) } }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", minLines: Int = 1) {
    val p = P
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = p.text2, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 15.dp)) {
            if (value.isEmpty()) Text(placeholder, style = Type.body, color = p.text3)
            BasicTextField(value, onChange, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.text), minLines = minLines, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun CustomizeScreen(vm: ChatViewModel, back: () -> Unit) {
    val s = settingsOf(vm)
    var name by remember { mutableStateOf(s.userName) }
    var asst by remember { mutableStateOf(s.assistantName) }
    var lang by remember { mutableStateOf(s.replyLanguage) }
    var instr by remember { mutableStateOf(s.instructions) }
    DisposableEffect(Unit) { onDispose { vm.g.settings.update { it.copy(userName = name.trim(), assistantName = asst.trim().ifBlank { "Axion" }, replyLanguage = lang.trim(), instructions = instr.trim()) } } }
    SettingsPage("Customize Axion", back) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Field("Your name", name, { name = it }, "Your name")
            Field("What should Axion call itself?", asst, { asst = it }, "Axion")
            Field("Reply language", lang, { lang = it }, "Roman Urdu")
            Field("Custom instructions", instr, { instr = it }, "I run Clawora Beauty (Korean skincare, Karachi). Keep answers short. Prices in PKR.", minLines = 5)
            Text("Saved automatically.", style = Type.small, color = P.text3)
        }
    }
}

/**
 * Voice (v0.3) — Axion's own Grok-style Voice Library page (orb on top, voices as tiles), now holding ONLY the voices
 * mazhar asked for: Kodex's six Google voices (same names, same order, same default: Rounded at Normal pace) and the
 * free natural voices that passed testing. Every tap speaks through ReadAloudPlayer — the same path as read-aloud
 * and the Live call — so what he hears here is exactly what he'll hear everywhere.
 */
@Composable
fun VoiceScreen(vm: ChatViewModel, back: () -> Unit, nav: (Route) -> Unit = {}) {
    val s = settingsOf(vm); val p = P
    val ctx = LocalContext.current
    val prefs = remember { VoicePrefs.prefs(ctx) }
    var installed by remember { mutableStateOf<Set<String>?>(null) }
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { st ->
            if (st == TextToSpeech.SUCCESS) installed = runCatching { engine?.voices?.filter { v -> !v.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }?.map { it.name }?.toSet() }.getOrNull() ?: emptySet()
            else installed = emptySet()
        }
        engine = if (ReadAloudPlayer.hasGoogleTts(ctx)) TextToSpeech(ctx, listener, ReadAloudPlayer.GOOGLE_TTS) else TextToSpeech(ctx, listener)
        onDispose { runCatching { engine?.shutdown() }; if (ReadAloudPlayer.playingMessageId == TEST_ID) ReadAloudPlayer.stop() }
    }
    var active by remember { mutableStateOf(VoicePrefs.activeVoice(ctx).id) }
    var pace by remember { mutableFloatStateOf(VoicePrefs.pace(ctx)) }
    var smart by remember { mutableStateOf(VoicePrefs.smartUrdu(ctx)) }
    var paceOpen by remember { mutableStateOf(false) }
    var elOpen by remember { mutableStateOf(false) }
    val testing = ReadAloudPlayer.playingMessageId == TEST_ID
    fun preview(v: AxVoice = VoiceCatalog.byId(active)) {
        ReadAloudPlayer.stop()
        val text = if (v.family == VoiceFamily.NATURAL && v.engineVoice.startsWith("ur-")) "السلام علیکم! میں آپ کا ایکسیون ہوں۔ پوچھیں، کیا جاننا ہے؟"
            else if (v.family == VoiceFamily.NATURAL && v.engineVoice.startsWith("hi-")) VoiceCatalog.PREVIEW_URDU
            else VoiceCatalog.PREVIEW_TEXT
        ReadAloudPlayer.speak(ctx, TEST_ID, text)
    }
    val hasGemini = vm.g.connectors.list.collectAsState().value.any { it.enabled && it.providerId == "gemini" }
    fun choose(v: AxVoice) { active = v.id; VoicePrefs.setActive(ctx, v.id); preview(v) }
    fun kodexAvailable(v: AxVoice): Boolean? = installed?.let { set -> set.isEmpty() || v.engineVoice in set || v.engineVoice.replace("-network", "-local") in set }

    Column(Modifier.fillMaxSize().background(p.page2)) {
        ScreenHeader("Voice Library", back)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Box(Modifier.fillMaxWidth().height(184.dp), contentAlignment = Alignment.Center) { Orb(120.dp) } }
            item {
                Text("Choose the voice Axion uses for read-aloud and Live calls. Tap a voice to make it active.",
                    style = Type.small.copy(fontSize = 15.sp, lineHeight = 21.sp), color = p.text2, modifier = Modifier.padding(start = 30.dp, end = 24.dp, bottom = 12.dp))
                SettingsGroup {
                    SettingsRow(Gk.voice, "Active voice", subtitle = VoiceCatalog.byId(active).let { "${it.name} · ${it.language}" }, chevron = false,
                        trailing = { Pill(if (testing) "Stop test" else "Test voice", icon = if (testing) Gk.stop else Gk.speaker) { if (testing) ReadAloudPlayer.stop() else preview() } }) { if (testing) ReadAloudPlayer.stop() else preview() }
                    SettingsRow(Gk.clock, "Pace", subtitle = VoiceCatalog.PACES.minBy { kotlin.math.abs(it.second - pace) }.first) { paceOpen = true }
                    // v0.3.1: the same speed the read-aloud pill's "1x" list sets (Grok keeps it for the next message too).
                    var rspeed by remember { mutableFloatStateOf(VoicePrefs.speed(ctx)) }
                    SettingsRow(Gk.sliders, "Read-aloud speed", subtitle = com.mazharansari.axion.ui.chat.speedLabel(rspeed) + " · tap to change, or use the 1x button on the player") {
                        val list = VoicePrefs.SPEEDS; val next = list[(list.indexOf(rspeed).coerceAtLeast(0) + 1) % list.size]
                        rspeed = next; ReadAloudPlayer.setSpeed(ctx, next)
                    }
                    ToggleRow(Gk.speaker, "Read answers aloud", "Speak every reply when it finishes", s.autoRead) { v -> vm.g.settings.update { it.copy(autoRead = v) } }
                    var ownCall by remember { mutableStateOf(VoicePrefs.callOwnVoice(ctx)) }
                    ToggleRow(Gk.phone, "Use my chosen voice in calls (a bit slower)", if (ownCall) "On: Gemini Live listens, ${VoiceCatalog.byId(active).name} speaks (≈1 s slower)" else "Off: fast live call in a Gemini voice (${VoiceCatalog.geminiTwin(VoiceCatalog.byId(active)).name})", ownCall) { v ->
                        ownCall = v; VoicePrefs.setCallOwnVoice(ctx, v)
                    }
                    SettingsRow(Gk.phone, "Live call", subtitle = VoiceCatalog.byId(active).let { v ->
                        when { v.family == VoiceFamily.GEMINI && hasGemini -> "${v.name} · Gemini Live, real-time"; hasGemini && ownCall -> "${v.name} · Live + your voice (slower)"; hasGemini -> "Live, real-time · ${VoiceCatalog.geminiTwin(v).name} voice"; else -> "Add a free Gemini key for a live call" } }) { nav(Route.VoiceCall) }
                }
                SectionLabel("Phone voices")
            }
            item { SettingsGroup { VoiceCatalog.kodex.forEach { v ->
                val ok = kodexAvailable(v)
                VoiceTile(v.name, v.language + " · " + (if (ok == false) "install Speech Services by Google" else v.tag), active == v.id, dim = ok == false) { choose(v) }
            } } }
            item {
                SectionLabel("Natural voices · free, needs internet")
                SettingsGroup {
                    ToggleRow(Gk.globe, "Smart Urdu (off = your voice reads everything)", "On: Urdu script → Uzma/Asad, Roman Urdu → Swara/Madhur", smart) { v ->
                        smart = v; prefs.edit().putBoolean(VoicePrefs.KEY_SMART_URDU, v).apply()
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            item { SettingsGroup { VoiceCatalog.natural.forEach { v -> VoiceTile(v.name, "${v.language} · ${v.tag}", active == v.id) { choose(v) } } } }
            item { SectionLabel(if (hasGemini) "Gemini voices · native live calls" else "Gemini voices · add a Gemini key in Connectors") }
            item { SettingsGroup { VoiceCatalog.gemini.forEach { v -> VoiceTile(v.name, "${v.tag} · speaks any language", active == v.id, dim = !hasGemini) { choose(v) } } } }
            item {
                SectionLabel("Premium")
                SettingsGroup {
                    VoiceTile("ElevenLabs", if (VoicePrefs.secret(ctx, "elevenlabs") != null) "Your own key · tap to edit" else "Bring your own ElevenLabs key + Voice ID", active == VoiceCatalog.elevenLabs.id) { elOpen = true }
                }
            }
        }
    }
    if (paceOpen) com.mazharansari.axion.ui.chat.AxionSheet({ paceOpen = false }) {
        Text("Pace", style = Type.header.copy(fontSize = 18.sp), color = p.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
        VoiceCatalog.PACES.forEach { (label, rate) ->
            com.mazharansari.axion.ui.chat.SheetRow(Gk.clock, label, null, onClick = {
                pace = rate; prefs.edit().putFloat(VoicePrefs.KEY_PACE, rate).apply(); paceOpen = false; preview()
            }) { Check(kotlin.math.abs(pace - rate) < 0.04f) }
        }
    }
    if (elOpen) {
        var key by remember { mutableStateOf("") }; var id by remember { mutableStateOf(VoicePrefs.secret(ctx, "elevenlabs_voice").orEmpty()) }
        com.mazharansari.axion.ui.chat.AxionSheet({ elOpen = false }) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("ElevenLabs", style = Type.header, color = p.text)
                Text("Optional. Your key and Voice ID stay encrypted on this phone.", style = Type.small, color = p.text2)
                Field("API key", key, { key = it }, if (VoicePrefs.secret(ctx, "elevenlabs") != null) "Saved — paste to replace" else "sk_…")
                Field("Voice ID", id, { id = it }, "e.g. 21m00Tcm4TlvDq8ikWAM")
                Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(50)).background(p.accent).clickable {
                    if (key.isNotBlank()) VoicePrefs.setSecret(ctx, "elevenlabs", key)
                    VoicePrefs.setSecret(ctx, "elevenlabs_voice", id)
                    elOpen = false
                    if (VoicePrefs.secret(ctx, "elevenlabs") != null && id.isNotBlank()) choose(VoiceCatalog.elevenLabs) else vm.toast = "Add both the key and a Voice ID"
                }, contentAlignment = Alignment.Center) { Text("Use ElevenLabs", style = Type.label, color = p.onAccent) }
            }
        }
    }
}

private const val TEST_ID = "axion-voice-test"

fun callModeLabel(mode: String?) = if (mode == "gemini") "Gemini Live · real-time, Gemini's own voice" else "Same voice as read-aloud"

@Composable
private fun VoiceTile(title: String, tag: String, selected: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    val p = P
    Row(Modifier.fillMaxWidth().height(74.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) (if (p.isDark) Color(0xFF2F2F2F) else p.border) else p.card)
        .clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.row, color = if (dim) p.text3 else p.text, maxLines = 1)
            Text(tag, style = Type.small.copy(fontSize = 15.sp), color = p.text2, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Check(selected)
    }
}


/**
 * The orb, redrawn 2026-11-06 to the owner's 2026-11-03 choice: the two thinking-orbs he circled on the
 * preview — listening (wave engine) and composing (ribbon engine), monochrome in the theme's own ink
 * (see ThinkingOrbEngine for the ported math). Only this drawing changed: sizes, the [level] breathing
 * and every call site's behaviour are as before. Frozen = a still listening frame (Connecting/Error/Ended).
 */
@Composable
fun Orb(size: Dp, level: Float = 0f, mode: OrbMode = OrbMode.Listening) {
    val p = P
    var elapsed by remember { mutableFloatStateOf(0f) }
    if (mode != OrbMode.Frozen) {
        LaunchedEffect(mode) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { elapsed = (it - start) / 1_000_000_000f }
        }
    }
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val dots = when (mode) {
            OrbMode.Listening -> ThinkingOrbEngine.wave(s, elapsed * 4.388f, level, breathe = 1f + 0.015f * kotlin.math.sin(elapsed * 2.4f))
            OrbMode.Composing -> ThinkingOrbEngine.ribbon(s, elapsed * 2.34f, level)
            OrbMode.Frozen -> ThinkingOrbEngine.wave(s, 5.7f, 0f)
        }
        val dim = if (mode == OrbMode.Frozen) 0.72f else 1f
        for (d in dots) {
            val ink = (1f - d.white).coerceIn(0f, 1f)
            val col = androidx.compose.ui.graphics.lerp(p.bg, p.text, ink)
            drawCircle(col.copy(alpha = d.a * dim), d.r, Offset(d.x, d.y))
        }
    }
}

@Composable
fun DataScreen(vm: ChatViewModel, back: () -> Unit) {
    val p = P
    var confirm by remember { mutableStateOf<String?>(null) }
    // v0.5.2 bug hunt: this walked every file on the main thread (an ANR with node_modules in the sandbox).
    val wsSize by produceState<Long?>(null) { value = withContext(kotlinx.coroutines.Dispatchers.IO) { vm.g.workspace.root.walkTopDown().filter { it.isFile }.sumOf { it.length() } } }
    SettingsPage("Data Controls", back) {
        Text("Your data stays on this phone. Keys are encrypted with the Android Keystore. Messages go only to the AI providers you connect.",
            style = Type.small.copy(fontSize = 15.sp, lineHeight = 21.sp), color = p.text2, modifier = Modifier.padding(start = 30.dp, end = 24.dp, top = 8.dp, bottom = 14.dp))
        SettingsGroup {
            SettingsRow(Gk.trash, "Delete all conversations", chevron = false, titleColor = p.danger, iconTint = p.danger) { confirm = "chats" }
            SettingsRow(Gk.folder, "Clear sandbox files", value = wsSize?.let { sizeLabel(it) } ?: "…", chevron = false) { confirm = "files" }
            SettingsRow(Gk.trash, "Delete all data", subtitle = "Keys, tokens, chats, projects, Linux — everything", chevron = false, titleColor = p.danger, iconTint = p.danger) { confirm = "all" }
        }
    }
    if (confirm == "all") { DeleteAllDialog(vm) { confirm = null }; return }
    confirm?.let { what ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = p.card, shape = RoundedCornerShape(24.dp),
            title = { Text(if (what == "chats") "Delete all conversations?" else "Clear all sandbox files?", style = Type.title, color = p.text) },
            text = { Text("This can't be undone.", style = Type.body, color = p.text2) },
            confirmButton = { TextButton({
                if (what == "chats") { vm.g.chats.deleteAll(); vm.newChat() } else { val root = vm.g.workspace.root; kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { root.listFiles()?.forEach { it.deleteRecursively() } } }
                vm.toast = "Done"; confirm = null
            }) { Text("Delete", style = Type.label, color = p.danger) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel", style = Type.label, color = p.text2) } })
    }
}

@Composable
fun AboutScreen(vm: ChatViewModel, back: () -> Unit, nav: (Route) -> Unit = {}) {
    val p = P; val ctx = LocalContext.current
    var deleteAll by remember { mutableStateOf(vm.debugDeleteAll) }
    if (deleteAll) DeleteAllDialog(vm) { deleteAll = false; vm.debugDeleteAll = false }
    SettingsPage("About", back) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AxionMark(72.dp)
            Spacer(Modifier.height(12.dp))
            Text("Axion", style = Type.title.copy(fontSize = 24.sp), color = p.text)
            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = Type.small, color = p.text2)
            Text("Developer: Mazhar Ansari", style = Type.small, color = p.text2)
        }
        SectionLabel("Updates")
        UpdateCard(vm)
        SectionLabel("Privacy and security")
        SettingsGroup {
            SettingsRow(Gk.shield, "Privacy Policy", subtitle = "What's stored on this phone, what's sent where") { nav(Route.Privacy) }
            SettingsRow(Gk.key, "Security", subtitle = "How your keys and tokens are protected") { nav(Route.Security) }
            SettingsRow(Gk.info, "Terms of Use") { nav(Route.Terms) }
            SettingsRow(Gk.trash, "Delete all data", subtitle = "Keys, tokens, chats, projects, Linux — everything", chevron = false, titleColor = p.danger, iconTint = p.danger) { deleteAll = true }
        }
        SectionLabel("Legal")
        SettingsGroup {
            SettingsRow(Gk.shield, "Open-source licences", subtitle = "Compose, OkHttp, jsoup, Coil, kotlinx, Haze (Apache 2.0) · llama.cpp (MIT) · Google Sans Flex & Code (OFL) · voices and voice engine from vayro/Kodex (MIT) · Edge read-aloud protocol after edge-tts (LGPL-3.0, re-implemented)", chevron = false)
            SettingsRow(Gk.help, "Report a problem", subtitle = "Opens your email app") {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_SUBJECT, "Axion ${BuildConfig.VERSION_NAME} problem")) }
            }
        }
    }
}

/** v0.5 in-app updates: check, download with progress, verify (SHA-256 + Axion's signing key), then Android's installer. */
@Composable
private fun UpdateCard(vm: ChatViewModel) {
    val p = P; val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val up = vm.g.updater
    val st by up.state.collectAsState()
    var needPermission by remember { mutableStateOf(false) }
    // coming back from Android's "Install unknown apps" page: re-check the permission
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(life) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) needPermission = !up.canInstall() && needPermission }
        life.lifecycle.addObserver(o); onDispose { life.lifecycle.removeObserver(o) }
    }
    fun installNow(f: java.io.File) { if (!up.canInstall()) needPermission = true else runCatching { up.install(f) }.onFailure { vm.toast = it.message } }
    val green = Color(0xFF3FB950)
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(16.dp)) {
        when (val s = st) {
            UpdateState.Idle -> Text("Axion checks for a new version once a day. You can check now too.", style = Type.small, color = p.text2)
            UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = p.text, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                Text("Checking for updates…", style = Type.small, color = p.text2)
            }
            is UpdateState.UpToDate -> Row(verticalAlignment = Alignment.CenterVertically) {
                GkIcon(Gk.check, null, 18.dp, green); Spacer(Modifier.width(8.dp))
                Text("You're on the latest version (${BuildConfig.VERSION_NAME}).", style = Type.small, color = p.text)
            }
            is UpdateState.Available -> UpdateInfoBlock(s.info)
            is UpdateState.Downloading -> {
                UpdateInfoBlock(s.info)
                QuotaBar(s.done.toDouble() / s.total.coerceAtLeast(1), Modifier.padding(top = 12.dp), 5.dp)
                Text("Downloading ${UpdateRules.mb(s.done)} of ${UpdateRules.mb(s.total)}", style = Type.tiny, color = p.text2, modifier = Modifier.padding(top = 6.dp))
            }
            is UpdateState.Verifying -> { UpdateInfoBlock(s.info); Text("Checking the download (checksum and signing key)…", style = Type.small, color = p.text2, modifier = Modifier.padding(top = 10.dp)) }
            is UpdateState.Ready -> { UpdateInfoBlock(s.info); Text("Downloaded and verified: checksum matches and it's signed with Axion's key.", style = Type.small, color = green, modifier = Modifier.padding(top = 10.dp)) }
            is UpdateState.Failed -> { s.info?.let { UpdateInfoBlock(it) }; Text(s.message, style = Type.small, color = Color(0xFFF04438), modifier = Modifier.padding(top = if (s.info != null) 10.dp else 0.dp)) }
        }
        if (needPermission) {
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (p.isDark) Color(0xFF2A2210) else Color(0xFFFFF4DC)).padding(12.dp)) {
                Text("Allow Axion to install updates", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                Text("Android asks once: turn on \"Allow from this source\" for Axion, then come back and tap Install.", style = Type.small, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                SmallButton("Open settings", filled = true) { runCatching { ctx.startActivity(up.installPermissionIntent()) } }
            }
        }
        Spacer(Modifier.height(14.dp))
        when (val s = st) {
            is UpdateState.Available -> BigButton("Update now" + if (s.info.size > 0) " (${UpdateRules.mb(s.info.size)})" else "") { scope.launch { val r = up.download(s.info); if (r is UpdateState.Ready) installNow(r.file) } }
            is UpdateState.Ready -> BigButton("Install ${s.info.versionName}") { installNow(s.file) }
            is UpdateState.Downloading, is UpdateState.Verifying, UpdateState.Checking -> BigButton("Please wait…", enabled = false) {}
            is UpdateState.Failed -> BigButton(if (s.info != null) "Try again" else "Check again") { scope.launch { if (s.info != null) { val r = up.download(s.info); if (r is UpdateState.Ready) installNow(r.file) } else up.check() } }
            else -> BigButton("Check for updates") { scope.launch { up.check() } }
        }
    }
}

@Composable
private fun UpdateInfoBlock(info: UpdateInfo) {
    val p = P
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Axion ${info.versionName}", style = Type.row, color = p.text, modifier = Modifier.weight(1f))
        Badge("New", Color(0xFF3FB950))
    }
    if (info.notes.isNotBlank()) Text(info.notes, style = Type.small.copy(fontSize = 13.5.sp, lineHeight = 19.sp), color = p.text2, modifier = Modifier.padding(top = 6.dp))
}

@Composable
fun FilesScreen(vm: ChatViewModel, back: () -> Unit) {
    val p = P; val ctx = LocalContext.current
    // v0.5.2 bug hunt: listed on a background thread (was the main thread → freeze with many files).
    val files by produceState(emptyList<java.io.File>()) { value = withContext(kotlinx.coroutines.Dispatchers.IO) { vm.g.workspace.root.walkTopDown().onEnter { it.name != "node_modules" }.filter { it.isFile && !it.path.contains("/.") }.sortedByDescending { it.lastModified() }.take(200).toList() } }
    Column(Modifier.fillMaxSize().background(p.page2)) {
        ScreenHeader("Library", back)
        if (files.isEmpty()) Text("Files you upload and files Axion creates (PDFs, code, sites) show up here.", style = Type.body, color = p.text2, modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.weight(1f).navigationBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(files, key = { it.path }) { f ->
                val a = com.mazharansari.axion.data.Artifact(f.name, com.mazharansari.axion.agent.Mime.of(f.name), f.absolutePath, f.length())
                ArtifactCard(a, { openFile(ctx, a) }, { vm.toast = FileActions.download(ctx, a) })
            }
        }
    }
}
