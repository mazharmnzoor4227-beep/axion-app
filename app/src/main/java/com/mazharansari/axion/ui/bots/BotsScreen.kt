package com.mazharansari.axion.ui.bots

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.agent.BotKind
import com.mazharansari.axion.agent.BotRun
import com.mazharansari.axion.agent.BotStatus
import com.mazharansari.axion.data.BotDefinition
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import com.mazharansari.axion.ui.components.Gk
import com.mazharansari.axion.ui.components.GkIcon
import com.mazharansari.axion.ui.theme.P

/**
 * PHASE 1 SWARM — the Bots section (opened from the hamburger menu, Phase 2).
 *
 * Any bot can create another bot, and the user (or the creating bot) NAMES it — "CEO" is
 * just one possible name, not a built-in role. There is no hardcoded CEO bot: a parent
 * run's card shows its given name ("Axion" by default, or an installed/created bot's
 * name), and each worker card shows who its parent is. Spawning is visible: the moment a
 * parent creates a worker, its card appears here with status "starting", then "working".
 * Tapping a worker opens that worker's own chat (its full transcript); tapping a parent
 * opens the parent chat. The section starts empty — no demo bots.
 * Original Axion code; no code copied from any other project.
 */
@Composable
fun BotsScreen(vm: ChatViewModel, navigate: (Route) -> Unit, back: () -> Unit) {
    val p = P
    Column(Modifier.fillMaxSize().background(p.bg)) {
        com.mazharansari.axion.ui.components.ScreenHeader("Bots", back)
        BotsPane(vm, navigate, onStartBot = { bot -> vm.startBotChat(bot); back() })
    }
}
@Composable
fun BotsPane(vm: ChatViewModel, navigate: (Route) -> Unit, onStartBot: (BotDefinition) -> Unit) {
    val p = P
    val bots by vm.g.bots.bots.collectAsState()
    val myBots by vm.g.botStore.definitions.collectAsState()
    val chatIndex by vm.g.chats.index.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    val parents = bots.filter { it.kind == BotKind.PARENT }
    val workers = bots.filter { it.kind == BotKind.WORKER }
    // Finished workers whose run is no longer in the live registry (e.g. after a restart) —
    // their transcripts live in the chat store, so they stay openable.
    val finished = chatIndex.filter { it.section == "worker" && bots.none { b -> b.conversationId == it.id } }

    Column(Modifier.fillMaxSize().background(p.bg).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Every live bot — and every bot you installed. Nothing about workers is hidden.",
            fontSize = 13.sp, color = p.text2, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionChip("Bot Store", Gk.grid, Modifier.weight(1f)) { navigate(Route.BotStore) }
            ActionChip("New bot", Gk.plus, Modifier.weight(1f), primary = true) { showCreate = true }
        }
        if (showCreate) CreateBotCard(vm, onStartBot = onStartBot) { showCreate = false }

        SectionLabel("Live")
        if (parents.isEmpty() && workers.isEmpty()) {
            Text("No bots running yet. Ask Axion something, or start a chat with one of your bots below.",
                fontSize = 13.sp, color = p.text3, modifier = Modifier.padding(vertical = 6.dp))
        }
        parents.forEach { parent ->
            ParentCard(parent, liveWorkers = workers.count { it.parentId == parent.conversationId }) { vm.open(parent.conversationId) }
            Spacer(Modifier.height(8.dp))
            workers.filter { it.parentId == parent.conversationId }.forEach { w ->
                WorkerCard(w, parentName = parent.name.ifBlank { "Axion" }, indent = true) {
                    vm.openWorkerChat(w.conversationId, w.parentId, parent.name.ifBlank { "Axion" })
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        // Workers whose parent card is gone (registry was in memory only) — still openable.
        workers.filter { w -> parents.none { it.conversationId == w.parentId } }.forEach { w ->
            WorkerCard(w, parentName = "", indent = false) { vm.openWorkerChat(w.conversationId, w.parentId, null) }
            Spacer(Modifier.height(8.dp))
        }

        SectionLabel("Your bots")
        if (myBots.isEmpty()) {
            Text("No bots installed yet — open the Bot Store to install one, or create your own above.",
                fontSize = 13.sp, color = p.text3, modifier = Modifier.padding(vertical = 6.dp))
        }
        myBots.forEach { def ->
            BotDefCard(def, onStart = { onStartBot(def) }, onRemove = { vm.g.botStore.remove(def.id) },
                onLogo = { logo -> vm.g.botStore.setLogo(def.id, logo) },
                onLogoFailed = { vm.toast = "Could not save that image" })
            Spacer(Modifier.height(8.dp))
        }

        if (finished.isNotEmpty()) {
            SectionLabel("Finished workers")
            finished.forEach { m ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card)
                    .clickable { vm.openWorkerChat(m.id, null) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    GkIcon(Gk.agent, "Worker", 22.dp, p.text2)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(m.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (m.preview.isNotBlank()) Text(m.preview, fontSize = 13.sp, color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    GkIcon(Gk.chevronRight, "Open", 20.dp, p.text3)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.text2,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
private fun ActionChip(label: String, icon: Int, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val p = P
    Row(modifier.clip(RoundedCornerShape(12.dp))
        .background(if (primary) p.accent else p.card)
        .border(1.dp, if (primary) p.accent else p.border, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        GkIcon(icon, label, 18.dp, if (primary) p.onAccent else p.text)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (primary) p.onAccent else p.text)
    }
}

@Composable
private fun statusColor(status: BotStatus): Color = when (status) {
    BotStatus.STARTING -> P.dim
    BotStatus.WORKING -> P.accent
    BotStatus.DONE -> P.text2
    BotStatus.FAILED -> P.danger
}

private fun statusLabel(status: BotStatus): String = when (status) {
    BotStatus.STARTING -> "Starting"
    BotStatus.WORKING -> "Working"
    BotStatus.DONE -> "Done"
    BotStatus.FAILED -> "Failed"
}

@Composable
private fun ParentCard(parent: BotRun, liveWorkers: Int, onOpen: () -> Unit) {
    val p = P
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).clickable(onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(p.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            GkIcon(Gk.brain, "Parent bot", 22.dp, p.accent)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(parent.name.ifBlank { "Axion" }, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(parent.task, fontSize = 13.sp, color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor(parent.status)))
                Text(" ${statusLabel(parent.status)} · ${parent.stepsUsed} step${if (parent.stepsUsed == 1) "" else "s"}" +
                    (if (liveWorkers > 0) " · $liveWorkers worker${if (liveWorkers == 1) "" else "s"}" else ""),
                    fontSize = 12.sp, color = p.text3)
            }
            if (parent.status == BotStatus.DONE || parent.status == BotStatus.FAILED) {
                if (parent.summary.isNotBlank()) Text(parent.summary, fontSize = 13.sp, color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
        GkIcon(Gk.chevronRight, "Open", 20.dp, p.text3)
    }
}

@Composable
private fun WorkerCard(worker: BotRun, parentName: String, indent: Boolean, onOpen: () -> Unit) {
    val p = P
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 20.dp else 0.dp)
        .clip(RoundedCornerShape(14.dp)).background(p.card).clickable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(p.chip), contentAlignment = Alignment.Center) {
            GkIcon(Gk.agent, "Worker", 19.dp, p.text2)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text((worker.role?.label ?: "Worker") + if (parentName.isNotBlank()) " · Parent: $parentName" else "",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(worker.task, fontSize = 13.sp, color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor(worker.status)))
                Text(" ${statusLabel(worker.status)} · ${worker.stepsUsed} step${if (worker.stepsUsed == 1) "" else "s"}",
                    fontSize = 12.sp, color = p.text3)
            }
            if (worker.status == BotStatus.DONE || worker.status == BotStatus.FAILED) {
                if (worker.summary.isNotBlank()) Text(worker.summary, fontSize = 13.sp, color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
        GkIcon(Gk.chevronRight, "Open", 20.dp, p.text3)
    }
}

@Composable
private fun BotDefCard(def: BotDefinition, onStart: () -> Unit, onRemove: () -> Unit, onLogo: (String) -> Unit, onLogoFailed: () -> Unit) {
    val p = P
    val ctx = LocalContext.current
    var pickingLogo by remember { mutableStateOf(false) }
    // Phase 2: pick a gallery image as this bot's logo — copied into the app's own
    // storage, so it survives the gallery permission being revoked.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val path = saveBotLogo(ctx, def.id, uri)
            if (path != null) { onLogo(com.mazharansari.axion.data.BotLogo.custom(path)); pickingLogo = false }
            else onLogoFailed()
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable { pickingLogo = true }) { BotLogoView(def.logo, def.name, 44.dp) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(def.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(def.description, fontSize = 13.sp, color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onStart) { Text("Start chat", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (def.sourceName.isNotBlank()) "${def.sourceName}${if (def.license.isNotBlank()) " · ${def.license}" else ""}" else "Your bot",
                fontSize = 12.sp, color = p.text3, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { pickingLogo = true }) { Text("Change logo", color = p.text2, fontSize = 12.sp) }
            GkIcon(Gk.trash, "Remove bot", 18.dp, p.text3, modifier = Modifier.clickable(onClick = onRemove).padding(4.dp))
        }
    }
    if (pickingLogo) LogoPickerSheet(def,
        onPickBuiltin = { id -> onLogo(com.mazharansari.axion.data.BotLogo.builtin(id)); pickingLogo = false },
        onPickGallery = { pickImage.launch("image/*") },
        onClear = { onLogo(""); pickingLogo = false },
        onDismiss = { pickingLogo = false })
}

/** Phase 2 — change a bot's logo: a built-in icon, a gallery photo, or no logo. */
@Composable
private fun LogoPickerSheet(def: BotDefinition, onPickBuiltin: (String) -> Unit, onPickGallery: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val p = P
    val current = com.mazharansari.axion.data.BotLogo.parse(def.logo)
    com.mazharansari.axion.ui.chat.AxionSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Logo for ${def.name}", style = com.mazharansari.axion.ui.theme.Type.title, color = p.text)
            Spacer(Modifier.height(14.dp))
            // Built-in icons, 4 across.
            val builtins = com.mazharansari.axion.data.BotLogo.BUILTINS
            builtins.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { b ->
                        val selected = (current as? com.mazharansari.axion.data.BotLogo.Builtin)?.id == b.id
                        Column(Modifier.clickable { onPickBuiltin(b.id) }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(52.dp).clip(CircleShape)
                                .background(if (selected) p.accent.copy(alpha = 0.16f) else p.chip)
                                .border(1.dp, if (selected) p.accent else p.border, CircleShape),
                                contentAlignment = Alignment.Center) {
                                GkIcon(builtinIcon(b.id), b.label, 26.dp, if (selected) p.accent else p.text2)
                            }
                            Text(b.label, style = com.mazharansari.axion.ui.theme.Type.tiny, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card).clickable(onClick = onPickGallery).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                GkIcon(Gk.image, "Gallery", 22.dp, p.accent); Spacer(Modifier.width(12.dp))
                Text("Choose from gallery", style = com.mazharansari.axion.ui.theme.Type.row, color = p.text, modifier = Modifier.weight(1f))
                GkIcon(Gk.chevronRight, null, 18.dp, p.text3)
            }
            if (current != com.mazharansari.axion.data.BotLogo.None) {
                TextButton(onClick = onClear, modifier = Modifier.padding(top = 6.dp)) { Text("Remove logo", color = p.danger) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Create a bot and name it anything — "CEO" is just the owner's example name. */
@Composable
private fun CreateBotCard(vm: ChatViewModel, onStartBot: (BotDefinition) -> Unit, onDone: () -> Unit) {
    val p = P
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf<String?>(null) }
    // Phase 2: pick the bot's logo at creation (built-in icon; gallery comes later from its card).
    var logo by remember { mutableStateOf("brain") }
    val providers = listOf(null to "Auto", "Google Gemini" to "Gemini", "Groq" to "Groq", "OpenRouter" to "OpenRouter", "xAI Grok" to "Grok")
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.text2, unfocusedBorderColor = p.border,
        focusedTextColor = p.text, unfocusedTextColor = p.text,
        cursorColor = p.text, focusedLabelColor = p.text2, unfocusedLabelColor = p.text3)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(p.card).padding(14.dp)) {
        Text("New bot", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = p.text)
        Text("Name it anything. A bot that spawns workers is the parent for its run — the name is what makes it the boss, not a built-in role. Tell it its role in chat (\"You are the CEO — manage everything\") and it will spawn a worker bot per task.",
            fontSize = 13.sp, color = p.text2, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(description, { description = it }, label = { Text("What it does") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(persona, { persona = it }, label = { Text("How it should work (persona)") }, minLines = 3, colors = colors, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text("Logo", fontSize = 12.sp, color = p.text3)
        val builtins = com.mazharansari.axion.data.BotLogo.BUILTINS
        builtins.chunked(6).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { b ->
                    val selected = logo == b.id
                    Box(Modifier.size(40.dp).clip(CircleShape)
                        .background(if (selected) p.accent.copy(alpha = 0.16f) else p.chip)
                        .border(1.dp, if (selected) p.accent else p.border, CircleShape)
                        .clickable { logo = b.id }, contentAlignment = Alignment.Center) {
                        GkIcon(builtinIcon(b.id), b.label, 20.dp, if (selected) p.accent else p.text2)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Preferred provider (free)", fontSize = 12.sp, color = p.text3)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            providers.forEach { (id, label) ->
                val selected = provider == id
                Box(Modifier.clip(RoundedCornerShape(20.dp))
                    .background(if (selected) p.accent.copy(alpha = 0.16f) else p.chip)
                    .border(1.dp, if (selected) p.accent else p.border, RoundedCornerShape(20.dp))
                    .clickable { provider = id }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(label, fontSize = 12.sp, color = if (selected) p.accent else p.text2, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text("Cancel", color = p.text2) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val created = vm.g.botStore.create(name, description, persona, provider)
                    val logoStr = com.mazharansari.axion.data.BotLogo.builtin(logo)
                    vm.g.botStore.setLogo(created.id, logoStr)
                    onDone()
                    // Phase 2: creating a bot opens its chat at once — the user names it
                    // and tells it its role there ("tum CEO ho…"), and it spawns workers.
                    onStartBot(created.copy(logo = logoStr))
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
            ) { Text("Create bot") }
        }
    }
}
