package com.mazharansari.axion.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.data.Connector
import com.mazharansari.axion.llm.Provider
import com.mazharansari.axion.llm.Providers
import com.mazharansari.axion.skills.SkillRepo
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.launch

@Composable
fun ConnectorsScreen(vm: ChatViewModel, nav: (Route) -> Unit, back: () -> Unit) {
    val p = P
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val connectors by vm.g.connectors.list.collectAsState()
    val mcp by vm.g.mcp.servers.collectAsState()
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var manage by remember { mutableStateOf<Connector?>(null) }
    var connectFor by remember { mutableStateOf<Provider?>(null) }
    fun connect() {
        if (key.isBlank() || busy) return
        busy = true; result = null
        val forced = connectFor?.id   // inside a provider's sheet the key is checked against THAT provider only
        scope.launch {
            val r = vm.g.connectors.addKey(key, forced)
            busy = false
            r.onSuccess { c -> result = "Connected ${c.provider.name} · ${c.models.firstOrNull() ?: ""}"; key = ""; connectFor = null }
                .onFailure { result = it.message ?: "Couldn't connect" }
        }
    }
    val providers = remember(q) { Providers.all.filter { q.isBlank() || it.name.contains(q, true) } }
    Column(Modifier.fillMaxSize().background(p.page2)) {
        ScreenHeader("Connectors", back)
        LazyColumn(Modifier.weight(1f).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text("Connectors let Axion use AI models and external tools. Paste any API key: Axion detects the provider and picks the best model by itself. Add several; when one hits its limit, Axion switches to the next automatically.",
                    style = Type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                // Paste-a-key card
                Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(16.dp)) {
                    Text("Add AI key", style = Type.label, color = p.text)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(p.page2).padding(horizontal = 14.dp, vertical = 13.dp)) {
                            if (key.isEmpty()) Text("Paste any API key", style = Type.body, color = p.text3)
                            BasicTextField(key, { key = it.trim() }, singleLine = true, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.text),
                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.width(8.dp))
                        IconTap(Gk.clipboard, "Paste", tint = p.text) { clip.getText()?.text?.let { key = it.trim() } }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(if (busy) "Detecting…" else "Connect", icon = if (busy) null else Gk.link, filled = true, onClick = ::connect)
                        if (busy) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(18.dp), color = p.text, strokeWidth = 2.dp) }
                    }
                    result?.let { Text(it, style = Type.small, color = if (it.startsWith("Connected")) p.text else p.danger, modifier = Modifier.padding(top = 10.dp)) }
                    if (result == com.mazharansari.axion.data.KeyDetector.NOT_RECOGNISED && key.isNotBlank()) {
                        // Manual fallback: choose the provider, the pasted key stays in the box.
                        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("gemini", "groq", "openrouter", "mistral", "cerebras", "huggingface", "cohere", "sambanova", "nvidia", "together", "deepseek", "openai", "anthropic", "xai")
                                .mapNotNull { Providers.byId(it) }.forEach { pr -> Pill(pr.name) { result = null; connectFor = pr } }
                        }
                    }
                }
            }
            if (connectors.isNotEmpty()) {
                item { Spacer(Modifier.height(10.dp)); SettingsGroup { SettingsRow(Gk.usage, "Usage", "Quota for every key and Antigravity account") { nav(Route.Quota) } } }
                item { SectionLabel("Connected · used top to bottom") }
                item { SettingsGroup { connectors.filterNot { it.own }.forEach { c -> ConnectedRow(c, connectors.count { it.providerId == c.providerId } > 1) { manage = c } } } }
            }
            // v0.5 (addendum 6): the user's own models — downloaded on tap only, honestly labelled slower/weaker.
            item {
                SectionLabel("Your own model · slower than API keys")
                val local = connectors.firstOrNull { it.providerId == "local" }
                val server = connectors.firstOrNull { it.providerId == "myserver" }
                SettingsGroup {
                    SettingsRow(Gk.phone, "Local model", local?.let { "In use · ${it.keyHint} · offline" } ?: "Runs on this phone, offline · download 0.8–2.5 GB", onClick = { nav(Route.LocalModels) })
                    SettingsRow(Gk.database, "My server", server?.let { "Connected · ${it.keyHint}" + (it.pinnedModel?.let { m -> " · $m" } ?: "") } ?: "Your Ollama / llama.cpp server (OpenAI-compatible)", onClick = { nav(Route.MyServer) })
                }
            }
            item {
                SectionLabel("AI providers · free tiers")
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50)).background(p.card).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    GkIcon(Gk.search, null, 18.dp, p.text2); Spacer(Modifier.width(8.dp))
                    Box { if (q.isEmpty()) Text("Search", style = Type.body, color = p.text3); BasicTextField(q, { q = it }, singleLine = true, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.text), modifier = Modifier.fillMaxWidth()) }
                }
                Spacer(Modifier.height(8.dp))
            }
            item { SettingsGroup { providers.filter { it.free }.forEach { pr -> ProviderRow(pr, connectors.count { it.providerId == pr.id }, { open(ctx, pr.keyPage) }) { connectFor = pr } } } }
            item { SectionLabel("Paid providers (optional)") }
            item { SettingsGroup { providers.filter { !it.free }.forEach { pr -> ProviderRow(pr, connectors.count { it.providerId == pr.id }, { open(ctx, pr.keyPage) }) { connectFor = pr } } } }
            item {
                SectionLabel("Tools · MCP servers")
                SettingsGroup {
                    mcp.forEach { s ->
                        SettingsRow(Gk.connectors, s.name, s.url, chevron = false, trailing = {
                            IconTap(Gk.trash, "Remove") { vm.g.mcp.remove(s.id) }
                        })
                        Divider()
                    }
                    SettingsRow(Gk.plus, "Add custom connector", "Any remote MCP server URL") { nav(Route.CustomConnector) }
                }
                SectionLabel("Antigravity · usage")
                SettingsGroup {
                    SettingsRow(Gk.rocket, "Antigravity accounts", "Several Google accounts, auto-switch on limits") { nav(Route.Antigravity) }
                    SettingsRow(Gk.usage, "Quota", "Used, left and reset time for every key and account") { nav(Route.Quota) }
                    SettingsRow(Gk.key, "Export / import keys", "Encrypted file with your own password") { nav(Route.KeyBackup) }
                }
            }
        }
    }
    manage?.let { c -> ManageSheet(vm, c) { manage = null } }
    connectFor?.let { pr ->
        com.mazharansari.axion.ui.chat.AxionSheet({ connectFor = null; result = null }) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { ProviderBadge(pr.id, pr.name, 40.dp); Spacer(Modifier.width(14.dp)); Column { Text("Connect ${pr.name}", style = Type.title, color = p.text); Text(pr.note, style = Type.small, color = p.text2) } }
                Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50)).background(p.page2).clickable { open(ctx, pr.keyPage) }.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("1.  Get a key", style = Type.label, color = p.text, modifier = Modifier.weight(1f)); GkIcon(Gk.arrowUpRight, null, 18.dp, p.text2)
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.page2).padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        if (key.isEmpty()) Text("2.  Paste it here", style = Type.body, color = p.text3)
                        BasicTextField(key, { key = it.trim() }, singleLine = true, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.text), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    }
                    IconTap(Gk.clipboard, "Paste", tint = p.text) { clip.getText()?.text?.let { key = it.trim() } }
                }
                // A key pasted into the wrong provider's sheet (e.g. a Gemini "AQ." key under another provider): offer the right one.
                val owner = Providers.ownerByPrefix(key)
                if (owner != null && owner.id != pr.id && key.length >= 16) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.page2).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("This looks like a ${owner.name} key — connect as ${owner.name}?", style = Type.small, color = p.text, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Pill("Connect as ${owner.name}", filled = true) { connectFor = owner; connect() }
                    }
                }
                Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(50)).background(p.accent).clickable { connect() }, contentAlignment = Alignment.Center) {
                    Text(if (busy) "Checking key…" else "Connect", style = Type.label, color = p.onAccent)
                }
                result?.let { Text(it, style = Type.small, color = if (it.startsWith("Connected")) p.text else p.danger) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun open(ctx: android.content.Context, url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

/** Brand badge: Grok shows each connector's logo in a 32dp rounded square; Axion draws a brand-coloured monogram (real logos are trademarks). */
private val brand = mapOf(
    "gemini" to Color(0xFF4C8DF6), "groq" to Color(0xFFF55036), "cerebras" to Color(0xFFF15A29), "mistral" to Color(0xFFFA520F),
    "openrouter" to Color(0xFF6467F2), "github" to Color(0xFF2B3137), "huggingface" to Color(0xFFFFB000), "nvidia" to Color(0xFF76B900),
    "sambanova" to Color(0xFFEE7624), "cohere" to Color(0xFF39594D), "together" to Color(0xFF0F6FFF), "openai" to Color(0xFF10A37F),
    "anthropic" to Color(0xFFD97757), "deepseek" to Color(0xFF4D6BFE), "xai" to Color(0xFF111111),
)

@Composable
fun ProviderBadge(id: String, name: String, size: androidx.compose.ui.unit.Dp = 32.dp) {
    val logo = BrandLogos.res(id)
    if (logo != null) {
        // v0.3: the brand's own mark, its own colours (no tint), on a dark tile so the white marks (OpenAI, Grok, Groq…) show.
        Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Color(0xFF1E1E1E)), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(logo), contentDescription = name, modifier = Modifier.size(size * 0.62f))
        }
    } else Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(brand[id] ?: Color(0xFF3A3A3A)), contentAlignment = Alignment.Center) {
        Text(name.removePrefix("Google ").take(1), style = Type.label.copy(fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold), color = Color.White)
    }
}

/** Grok connector row (Connectors frame): logo, name 17sp, grey "Connect" on the right — plus Axion's "Get key ↗" line under the name. */
@Composable
private fun ProviderRow(pr: Provider, connectedCount: Int, onGetKey: () -> Unit, onConnect: () -> Unit) {
    val p = P
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(6.dp)).background(p.card).clickable(onClick = onConnect).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ProviderBadge(pr.id, pr.name)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(pr.name, style = Type.row.copy(fontWeight = FontWeight.Normal), color = p.text, maxLines = 1)
            Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onGetKey).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Get key", style = Type.small.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = Color(0xFF5B8CFF))
                Spacer(Modifier.width(3.dp)); GkIcon(Gk.arrowUpRight, null, 13.dp, Color(0xFF5B8CFF))
                Text("  ·  " + pr.note.substringBefore(" · ").take(28), style = Type.small.copy(fontSize = 14.sp), color = p.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (connectedCount > 0) GkIcon(Gk.circleCheck, "Connected", 20.dp, p.text)
        else Text("Connect", style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.text2)
    }
}

@Composable
private fun ConnectedRow(c: Connector, duplicate: Boolean, onClick: () -> Unit) {
    val p = P
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(6.dp)).background(p.card).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.then(if (c.enabled) Modifier else Modifier.background(Color.Transparent))) { ProviderBadge(c.providerId, c.provider.name) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(c.provider.name + if (duplicate) " · ${c.keyHint}" else "", style = Type.row.copy(fontWeight = FontWeight.Normal), color = if (c.enabled) p.text else p.text3, maxLines = 1)
            if (c.lastError.isNotBlank()) Text(c.lastError, style = Type.small.copy(fontSize = 14.sp), color = Color(0xFFFF8A3D), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Text(if (!c.enabled) "Paused" else (c.pinnedModel ?: c.models.firstOrNull() ?: ""), style = Type.small.copy(fontSize = 14.sp), color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GkIcon(Gk.more, "Manage", 22.dp, p.text2)
    }
}

@Composable
private fun ManageSheet(vm: ChatViewModel, c: Connector, onDismiss: () -> Unit) {
    val p = P
    val repo = vm.g.connectors
    com.mazharansari.axion.ui.chat.AxionSheet(onDismiss) {
        Text(c.provider.name, style = Type.title, color = p.text, modifier = Modifier.padding(horizontal = 22.dp))
        Text("Key ${c.keyHint}", style = Type.small, color = p.text2, modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp))
        com.mazharansari.axion.ui.chat.SheetRow(Gk.arrowUp, "Move up", "Tried earlier", onClick = { repo.move(c.id, true) })
        com.mazharansari.axion.ui.chat.SheetRow(Gk.arrowDown, "Move down", null, onClick = { repo.move(c.id, false) })
        com.mazharansari.axion.ui.chat.SheetRow(if (c.enabled) Gk.pause else Gk.play, if (c.enabled) "Disable" else "Enable", null, onClick = { repo.setEnabled(c.id, !c.enabled); onDismiss() })
        com.mazharansari.axion.ui.chat.SheetRow(Gk.trash, "Remove", null, onClick = { repo.remove(c.id); onDismiss() })
        Text("Model (auto picks the first; tap to pin one)", style = Type.small, color = p.text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
        LazyColumn(Modifier.heightIn(max = 280.dp)) {
            item(key = "auto") { com.mazharansari.axion.ui.chat.SheetRow(Gk.sparkles, "Auto (best available)", null, onClick = { repo.pinModel(c.id, null); onDismiss() }) { if (c.pinnedModel == null) GkIcon(Gk.check, null, 22.dp, p.text) } }
            items(c.models, key = { it }) { m -> com.mazharansari.axion.ui.chat.SheetRow(Gk.brain, m, null, onClick = { repo.pinModel(c.id, m); onDismiss() }) { if (c.pinnedModel == m) GkIcon(Gk.check, null, 22.dp, p.text) } }
        }
    }
}

@Composable
fun CustomConnectorScreen(vm: ChatViewModel, back: () -> Unit) {
    val p = P
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.text2, unfocusedBorderColor = p.border, focusedTextColor = p.text, unfocusedTextColor = p.text, cursorColor = p.text, focusedLabelColor = p.text2, unfocusedLabelColor = p.text3)
    SettingsPage("Custom connector", back) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Connect any remote MCP server. Its tools become available to the agent in chat.", style = Type.small, color = p.text2)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it.trim() }, label = { Text("Server URL") }, placeholder = { Text("https://example.com/mcp", color = p.text3) }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it.trim() }, label = { Text("Token (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = colors, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(if (busy) "Connecting…" else "Add", filled = true) {
                    if (busy || url.isBlank()) return@Pill
                    busy = true; msg = null
                    scope.launch {
                        vm.g.mcp.add(name, url, token).onSuccess { n -> msg = "Connected · $n tools"; busy = false; back() }.onFailure { msg = it.message; busy = false }
                    }
                }
                if (busy) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(18.dp), color = p.text, strokeWidth = 2.dp) }
            }
            msg?.let { Text(it, style = Type.small, color = if (it.startsWith("Connected")) p.text else p.danger) }
        }
    }
}

@Composable
fun SkillsScreen(vm: ChatViewModel, back: () -> Unit) {
    val p = P
    val scope = rememberCoroutineScope()
    val skills by vm.g.skills.skills.collectAsState()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<SkillRepo.Found>>(emptyList()) }
    val picked = remember { mutableStateMapOf<String, Boolean>() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val upload = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true; msg = null
        scope.launch {
            val results = uris.map { uri ->
                runCatching {
                    val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null } ?: "skill"
                    val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                    vm.g.skills.importFile(name, bytes)
                }
            }
            val n = results.sumOf { it.getOrNull()?.size ?: 0 }
            val err = results.mapNotNull { it.exceptionOrNull()?.message }.firstOrNull()
            msg = (if (n > 0) "Added $n skill${if (n == 1) "" else "s"}" else "") + (err?.let { (if (n > 0) " · " else "") + it } ?: "")
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        ScreenHeader("Skills", back) { Pill("Upload", icon = Gk.file) { upload.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/markdown", "text/plain", "application/pdf", "application/octet-stream", "*/*")) } }
        LazyColumn(Modifier.weight(1f).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text("Skills teach Axion how to do a kind of task. Paste a GitHub repo URL to see every skill in it, or tap Upload to add a ZIP of skill folders, a SKILL.md / .md / .txt file, or a PDF. You can also paste a URL in chat.",
                    style = Type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface).padding(16.dp)) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.page2).padding(horizontal = 14.dp, vertical = 13.dp)) {
                        if (url.isEmpty()) Text("https://github.com/owner/repo", style = Type.body, color = p.text3)
                        BasicTextField(url, { url = it.trim() }, singleLine = true, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.text), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(if (busy) "Scanning…" else "Find skills", icon = Gk.globe, filled = true) {
                            if (busy || url.isBlank()) return@Pill
                            busy = true; msg = null
                            scope.launch {
                                runCatching { vm.g.skills.scanGitHub(url) }.onSuccess { f -> found = f; picked.clear(); f.forEach { picked[it.repoPath] = true }; msg = "${f.size} skills found" }
                                    .onFailure { msg = it.message }
                                busy = false
                            }
                        }
                        if (found.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Pill("Install ${picked.count { it.value }}") {
                            busy = true
                            scope.launch { val n = vm.g.skills.install(found.filter { picked[it.repoPath] == true }, url).size; msg = "Installed $n skills"; found = emptyList(); busy = false }
                        } }
                    }
                    msg?.let { Text(it, style = Type.small, color = p.text2, modifier = Modifier.padding(top = 10.dp)) }
                }
            }
            items(found, key = { "f_" + it.repoPath }) { f ->
                Row(Modifier.fillMaxWidth().clickable { picked[f.repoPath] = !(picked[f.repoPath] ?: false) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(picked[f.repoPath] == true, { picked[f.repoPath] = it }, colors = CheckboxDefaults.colors(checkedColor = p.accent, checkmarkColor = p.onAccent, uncheckedColor = p.text3))
                    Column(Modifier.weight(1f)) { Text(f.name, style = Type.label, color = p.text); Text(f.description, style = Type.small, color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
            item { SectionLabel("Installed") }
            items(skills, key = { "s_" + it.name }) { s ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GkIcon(Gk.skills, null, 22.dp, p.text); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name + if (s.source == "built-in") "  · built-in" else "", style = Type.label, color = p.text)
                        Text(s.description, style = Type.small, color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(s.enabled, { vm.g.skills.setEnabled(s.name, it) }, colors = SwitchDefaults.colors(checkedThumbColor = p.onAccent, checkedTrackColor = p.accent, uncheckedThumbColor = p.text2, uncheckedTrackColor = p.surface2, uncheckedBorderColor = p.border))
                }
            }
        }
    }
}
