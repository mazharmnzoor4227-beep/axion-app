package com.mazharansari.axion.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.github.GitHubApi
import com.mazharansari.axion.github.GitHubDeviceFlow
import com.mazharansari.axion.github.GitHubDeviceFlowClient
import com.mazharansari.axion.github.GitHubToken
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P1-1 (§6.1–6.2): Settings → GitHub.
 *
 * Two ways in, in this order: (a) OAuth device flow — the app shows a code, the
 * user authorises on github.com in a browser, the app polls for the token (no
 * password is ever typed in Axion); (b) paste a PAT. The OAuth App client ID is
 * the user's own (empty by default, with instructions — there is no hardcoded
 * client ID). The token is KeyVault-encrypted, never logged, shown only as its
 * last 4 characters. Every git action is listed in the audit log below.
 */
@Composable
fun GitHubScreen(vm: ChatViewModel, back: () -> Unit) {
    val p = P
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = vm.g.github
    val st by store.state.collectAsState()
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.text2, unfocusedBorderColor = p.border,
        focusedTextColor = p.text, unfocusedTextColor = p.text, cursorColor = p.text,
        focusedLabelColor = p.text2, unfocusedLabelColor = p.text3,
    )
    var clientId by remember(st.clientId) { mutableStateOf(st.clientId) }
    var pat by remember { mutableStateOf("") }
    var name by remember(st.name) { mutableStateOf(st.name) }
    var email by remember(st.email) { mutableStateOf(st.email) }
    var newRepo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<GitHubDeviceFlow.DeviceCode?>(null) }
    var pollStatus by remember { mutableStateOf<String?>(null) }
    var auditTick by remember { mutableStateOf(0) }

    fun afterConnect() {
        scope.launch {
            runCatching { GitHubApi(token = { store.token() }).whoami() }
                .onSuccess { store.setLogin(it); msg = "Connected as @$it" }
                .onFailure { msg = "Connected. GitHub didn't confirm the account name yet." }
            busy = false
        }
    }

    // Device-flow polling: waits the interval GitHub asked for, stops on a token,
    // on expiry/denial, or when the user leaves the screen (code becomes null).
    val currentCode = code
    LaunchedEffect(currentCode) {
        val dc = currentCode ?: return@LaunchedEffect
        val started = System.currentTimeMillis()
        while (true) {
            delay(dc.intervalSec * 1000L)
            if (System.currentTimeMillis() - started > dc.expiresInSec * 1000L) {
                pollStatus = "The code expired. Get a new code and try again."; code = null; break
            }
            // `continue` cannot live inside the getOrElse lambda (experimental
            // "break and continue in inline lambdas") — a null result means the
            // poll failed, so wait and let the loop's own `continue` run.
            val r = runCatching { GitHubDeviceFlowClient.poll(st.clientId, dc.deviceCode, dc.intervalSec) }.getOrElse {
                pollStatus = "Couldn't reach GitHub: ${it.message}"; null
            }
            if (r == null) { delay(5000); continue }
            when (r) {
                is GitHubDeviceFlow.PollResult.Token -> {
                    store.saveOAuthToken(r.accessToken); code = null; pollStatus = "Signed in."; afterConnect(); break
                }
                is GitHubDeviceFlow.PollResult.Wait -> { pollStatus = "Waiting for you to approve on github.com…"; delay(r.waitMs) }
                is GitHubDeviceFlow.PollResult.Failed -> { pollStatus = r.message; code = null; break }
            }
        }
    }

    SettingsPage("GitHub", back) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Connect GitHub so Axion can clone repos, commit, push and open pull requests from your phone. The agent runs on your own AI keys — no separate billing.",
                style = Type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.text2)
            // ── status ─────────────────────────────────────────────────────────────────
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(16.dp)) {
                Text(if (st.tokenBlob.isBlank()) "Not connected" else "Connected" + st.login.let { if (it.isBlank()) "" else " · @$it" },
                    style = Type.label, color = p.text)
                if (st.tokenBlob.isNotBlank()) {
                    Text("Token ${GitHubToken.hint(store.token().orEmpty())}", style = Type.small, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                    Text("Never shown in full, never logged. Disconnect removes it from this phone.",
                        style = Type.small, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(10.dp))
                    SettingsRow(Gk.trash, "Disconnect GitHub", chevron = false, titleColor = p.danger, iconTint = p.danger) {
                        store.disconnect(); code = null; msg = "Disconnected."
                    }
                }
            }
            // ── (a) OAuth device flow ────────────────────────────────────────────────────
            Text("Sign in with GitHub", style = Type.label, color = p.text)
            Text("First create an OAuth App at github.com/settings/developers (any name, any homepage URL — no secret is needed for this sign-in), then paste its Client ID here.",
                style = Type.small, color = p.text2)
            OutlinedTextField(clientId, { clientId = it.trim() }, label = { Text("OAuth App Client ID") },
                placeholder = { Text("Ov23li…", color = p.text3) }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(if (busy) "Getting code…" else "Get sign-in code", filled = true) {
                    if (busy) return@Pill
                    if (clientId.isBlank()) { msg = GitHubDeviceFlow.NO_CLIENT_ID; return@Pill }
                    busy = true; msg = null; pollStatus = null
                    scope.launch {
                        store.setClientId(clientId)
                        runCatching { GitHubDeviceFlowClient.start(clientId) }
                            .onSuccess { code = it; pollStatus = "Enter the code on github.com, then wait here." }
                            .onFailure { msg = it.message }
                        busy = false
                    }
                }
                if (code != null) { Spacer(Modifier.width(8.dp)); Pill("Cancel") { code = null; pollStatus = null } }
            }
            code?.let { dc ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your sign-in code", style = Type.small, color = p.text2)
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer { Text(GitHubDeviceFlow.formatUserCode(dc.userCode), style = Type.title.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold), color = p.text) }
                    Spacer(Modifier.height(10.dp))
                    Text("Open github.com/login/device in a browser and enter the code.", style = Type.small, color = p.text2)
                    Spacer(Modifier.height(10.dp))
                    Pill("Open github.com/login/device", icon = Gk.arrowUpRight, filled = true) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    pollStatus?.let { Text(it, style = Type.small, color = p.text2, modifier = Modifier.padding(top = 10.dp)) }
                }
            }
            // ── (b) PAT fallback ─────────────────────────────────────────────────────────
            Text("Or paste a token", style = Type.label, color = p.text)
            Text("A classic token (ghp_…) or a fine-grained token (github_pat_…) with repo scope. Create one at github.com/settings/tokens. Axion never asks for your GitHub password.",
                style = Type.small, color = p.text2)
            OutlinedTextField(pat, { pat = it.trim() }, label = { Text("Personal access token") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = colors, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(if (busy) "Connecting…" else "Connect with token", filled = true) {
                    if (busy || pat.isBlank()) return@Pill
                    busy = true; msg = null
                    store.savePat(pat)?.let { msg = it; busy = false; return@Pill }
                    pat = ""; afterConnect()
                }
            }
            msg?.let { Text(it, style = Type.small, color = if (it.startsWith("Connected")) p.text else p.danger) }
            // ── identity (commits use this) ──────────────────────────────────────────────
            Text("Commit identity", style = Type.label, color = p.text)
            Text("Commits are signed with this name and email.", style = Type.small, color = p.text2)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, placeholder = { Text("you@example.com", color = p.text3) }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
            Pill("Save name and email", filled = true) { store.setIdentity(name, email); msg = "Saved." }
            // ── ungated repos ────────────────────────────────────────────────────────────
            Text("Ungated repos", style = Type.label, color = p.text)
            Text("Normally Axion shows you the diff and waits for approval before a commit or push. A repo listed here skips that review step. Pushes to main/master, force-pushes and merges always ask, even here.",
                style = Type.small, color = p.text2)
            if (st.ungatedRepos.isEmpty()) Text("No ungated repos.", style = Type.small, color = p.text3)
            st.ungatedRepos.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(r, style = Type.body, color = p.text, modifier = Modifier.weight(1f))
                    Pill("Remove") { store.setUngated(r, false) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(newRepo, { newRepo = it.trim() }, label = { Text("owner/repo") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(8.dp))
                Pill("Add") { if (newRepo.isNotBlank()) { store.setUngated(newRepo, true); newRepo = "" } }
            }
            // ── audit log ────────────────────────────────────────────────────────────────
            Text("Audit log", style = Type.label, color = p.text)
            Text("Every git action Axion takes, with its result.", style = Type.small, color = p.text2)
            val entries = remember(auditTick) { store.audit.list() }
            if (entries.isEmpty()) Text("No git actions yet.", style = Type.small, color = p.text3)
            entries.take(30).forEach { e ->
                val whenText = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(e.at))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.action, style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.text, modifier = Modifier.weight(1f))
                        Text(e.result, style = Type.small, color = if (e.result == "ok") p.text2 else p.danger)
                    }
                    Text("${e.repo}${if (e.branch.isNotBlank()) " · ${e.branch}" else ""} · $whenText",
                        style = Type.small, color = p.text2, modifier = Modifier.padding(top = 2.dp))
                    if (e.detail.isNotBlank()) Text(e.detail, style = Type.small, color = p.text2, maxLines = 2)
                }
                Spacer(Modifier.height(8.dp))
            }
            if (entries.isNotEmpty()) Pill("Clear audit log") { store.audit.clear(); auditTick++ }
            Spacer(Modifier.height(8.dp))
        }
    }
}
