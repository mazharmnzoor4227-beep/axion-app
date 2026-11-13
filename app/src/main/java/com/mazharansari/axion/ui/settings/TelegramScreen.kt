package com.mazharansari.axion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mazharansari.axion.telegram.HttpTelegramApi
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.components.Gk
import com.mazharansari.axion.ui.components.ScreenHeader
import com.mazharansari.axion.ui.components.SectionLabel
import com.mazharansari.axion.ui.components.SettingsRow
import com.mazharansari.axion.ui.components.ToggleRow
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * P2-1 (Remote access: Telegram bot control) — Settings → Telegram control.
 *
 * The user supplies his own bot token (from @BotFather); it is stored only as
 * a KeyVault-encrypted blob and never shown again. The bot is OFF by default
 * and talks to exactly ONE paired chat: a chat sends /start, the user approves
 * it here, and only then does the bot answer that chat. Messages from any
 * other chat are ignored with no reply. /stop on Telegram pauses the bot.
 */
@Composable
fun TelegramScreen(vm: ChatViewModel, back: () -> Unit) {
    val p = P
    val g = vm.g
    val scope = rememberCoroutineScope()
    val settings by g.telegram.settings.collectAsState()
    var token by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val hasToken = settings.tokenBlob.isNotBlank()
    val paired = settings.allowedChatId != 0L

    Column(Modifier.fillMaxSize().background(p.bg)) {
        ScreenHeader("Telegram control", back)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            Note("Text Axion from anywhere — it replies on Telegram and can run tasks. The bot is off by default and talks to one paired chat only. Your bot token is encrypted on this phone; never share it with anyone.")
            Spacer(Modifier.height(16.dp))
            SectionLabel("Bot token")
            if (hasToken) {
                Text("A bot token is saved.", style = Type.row, color = p.text)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButtonOutline("Test connection") {
                        if (testing) return@BigButtonOutline
                        testing = true
                        scope.launch {
                            val name = HttpTelegramApi(token = { g.telegram.token() }).getMe().getOrNull()
                            testing = false
                            msg = if (name != null) "Connected as @$name." else "Could not reach Telegram — check the token."
                        }
                    }
                    BigButtonOutline("Remove token") {
                        g.telegram.clearToken()
                        g.setTelegramEnabled(false)
                        msg = "Bot token removed. Telegram control is off."
                    }
                }
            } else {
                Field(token, { token = it }, "Bot token from @BotFather", password = true)
                Spacer(Modifier.height(10.dp))
                BigButton("Save token", enabled = token.isNotBlank()) {
                    runCatching { g.telegram.setToken(token.trim()) }.onSuccess {
                        token = ""
                        msg = "Bot token saved (encrypted). Turn Telegram control on below."
                    }.onFailure { msg = it.message ?: "That token does not look right." }
                }
            }
            msg?.let { Text(it, style = Type.small, color = p.text2, modifier = Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(16.dp))
            SectionLabel("Control")
            ToggleRow(
                Gk.voice, "Telegram control",
                if (hasToken) "On: the bot answers your paired chat. /stop on Telegram pauses it." else "Save a bot token first.",
                settings.enabled,
            ) {
                if (!hasToken) {
                    msg = "Save a bot token first."
                    return@ToggleRow
                }
                g.setTelegramEnabled(it)
                msg = if (it) "Telegram control is on. Send /start to your bot from Telegram to pair it." else "Telegram control is off."
            }
            if (settings.pendingPairChatId != 0L) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("Pairing request")
                SettingsRow(
                    Gk.person, settings.pendingPairName.ifBlank { "Telegram user" },
                    subtitle = "Chat ${settings.pendingPairChatId} sent /start — approve it to let this chat talk to Axion.",
                    chevron = false,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButtonOutline("Approve") {
                        g.telegram.approvePair()
                        msg = "Chat paired — only this chat can talk to Axion now."
                    }
                    BigButtonOutline("Reject") { g.telegram.rejectPair() }
                }
            }
            if (paired) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("Paired chat")
                Text(
                    "Chat ${settings.allowedChatId} is paired — only this chat can talk to Axion.",
                    style = Type.small, color = p.text2,
                )
                Spacer(Modifier.height(10.dp))
                BigButtonOutline("Unpair this chat") {
                    g.telegram.unpair()
                    msg = "Chat unpaired. Send /start from Telegram to pair again."
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
