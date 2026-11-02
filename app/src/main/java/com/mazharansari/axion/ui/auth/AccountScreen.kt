package com.mazharansari.axion.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.mazharansari.axion.auth.AuthProvider
import com.mazharansari.axion.auth.AxionAuth
import com.mazharansari.axion.auth.AxionUser
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.settings.SettingsPage
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.launch

/** Round account photo (Google/GitHub picture), or the first letter when there is none or it can't load. */
@Composable
fun UserPhoto(user: AxionUser?, size: Dp) {
    val letter = user?.shownName ?: "You"
    val url = user?.photoUrl
    if (url.isNullOrBlank()) { Avatar(letter, size); return }
    SubcomposeAsyncImage(model = url, contentDescription = "Account photo", contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape),
        loading = { Avatar(letter, size) }, error = { Avatar(letter, size) })
}

/** v0.5.3 Settings → Account: who is signed in (name, email, photo, how), Sign out; or Sign in for a guest. */
@Composable
fun AccountScreen(auth: AxionAuth, back: () -> Unit, onSignIn: () -> Unit, onSignedOut: () -> Unit) {
    val p = P
    val st by auth.state.collectAsState()
    val user = st.user
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    SettingsPage("Account", back) {
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            UserPhoto(user, 84.dp)
            Spacer(Modifier.height(14.dp))
            Text(user?.shownName ?: "Guest", style = Type.title.copy(fontSize = 22.sp), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(user?.email ?: if (user == null) "Not signed in" else "${user.provider.title} account", style = Type.body.copy(fontSize = 15.sp), color = p.text2,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (user != null) {
                Spacer(Modifier.height(10.dp))
                Text("Signed in with ${user.provider.title}", style = Type.small.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium), color = p.text,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (p.isDark) Color(0xFF2E2E2E) else Color(0xFFEDEDED)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        if (user != null) {
            if (user.provider == AuthProvider.EMAIL && !user.emailVerified) {
                SectionLabel("Email")
                SettingsGroup {
                    SettingsRow(Gk.mail, "Verify your email", subtitle = note ?: "We sent a link to ${user.email}. Tap to send it again.") {
                        val lim = com.mazharansari.axion.auth.AuthHub.limits(ctx); val em = user.email.orEmpty()
                        val w = lim.emailWait("verify", em)
                        if (w > 0) note = "Limit reached (3 emails an hour). Try again in ${com.mazharansari.axion.auth.AuthLimits.clock(w)}."
                        else scope.launch { note = auth.sendEmailVerification().fold({ lim.emailSent("verify", em); "Sent again. Check your inbox (and spam)." }, { it.message }) }
                    }
                }
            }
            SectionLabel("About this account")
            Text("Your Axion account is used only for sign-in (email, name and user ID through Google Firebase). Chats, keys and projects stay on this phone. " +
                "Antigravity has its own Google sign-in in Settings → Antigravity.", style = Type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.text2,
                modifier = Modifier.padding(horizontal = 30.dp))
            Spacer(Modifier.height(22.dp))
            SettingsGroup { SettingsRow(Gk.logout, "Sign out", titleColor = Color(0xFFFF6B6B), iconTint = Color(0xFFFF6B6B), chevron = false) { confirm = true } }
        } else {
            Text("Sign in with Google, email or GitHub to use your Axion account on this phone. Everything keeps working as a guest.",
                style = Type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 18.dp))
            SettingsGroup { SettingsRow(Gk.person, "Sign in", subtitle = "Google, Email or GitHub", onClick = onSignIn) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Sign out?") },
        text = { Text("You'll be signed out of your Axion account. Chats, keys, Linux and Antigravity on this phone stay as they are.") },
        confirmButton = { TextButton({ confirm = false; auth.signOut(); onSignedOut() }) { Text("Sign out", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel", color = p.text) } },
        containerColor = p.sheet, titleContentColor = p.text, textContentColor = p.text2,
    )
}
