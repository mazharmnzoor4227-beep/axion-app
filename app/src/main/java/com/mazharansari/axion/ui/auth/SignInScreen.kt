package com.mazharansari.axion.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.R
import com.mazharansari.axion.auth.*
import com.mazharansari.axion.ui.components.AxionMark
import com.mazharansari.axion.ui.components.Gk
import com.mazharansari.axion.ui.components.GkIcon
import com.mazharansari.axion.ui.components.IconTap
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.launch

/** Which part of the sign-in screen is showing (public so tests and screenshots can open a given state). */
enum class SignInPage { CHOOSE, EMAIL_SIGN_IN, EMAIL_SIGN_UP, EMAIL_RESET }

fun Context.findActivity(): Activity? { var c: Context? = this; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }

/**
 * v0.5.3 — the Kodex-style sign-in screen shown when Axion opens (mazhar's ask): Google, Email (sign in / create account /
 * forgot password) and GitHub, plus "Continue as guest". It is only the Axion account (Firebase axion-d5461) — Antigravity's
 * own Google sign-in stays in Settings → Antigravity, unchanged.
 */
@Composable
fun SignInScreen(
    auth: AxionAuth,
    onGuest: (() -> Unit)?,
    onDone: () -> Unit,
    onClose: (() -> Unit)? = null,
    startPage: SignInPage = SignInPage.CHOOSE,
    startError: String? = null,
    startInfo: String? = null,
) {
    val p = P
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var page by remember { mutableStateOf(startPage) }
    var busy by remember { mutableStateOf<String?>(null) }   // which button is working
    var error by remember { mutableStateOf(startError) }
    var info by remember { mutableStateOf(startInfo) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    val limits = remember { AuthHub.limits(ctx) }
    // v0.5.3 security: a countdown runs only while a limit is active (wrong passwords / emails per hour).
    var waitUntil by remember { mutableLongStateOf(0L) }
    var waitWhat by remember { mutableStateOf("") }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(waitUntil) { while (waitUntil > System.currentTimeMillis()) { kotlinx.coroutines.delay(1000); nowMs = System.currentTimeMillis() }; nowMs = System.currentTimeMillis() }
    val waitLeft = (waitUntil - nowMs).coerceAtLeast(0)
    fun limitWait(ms: Long, what: String): Boolean { if (ms <= 0) return false; waitUntil = System.currentTimeMillis() + ms; nowMs = System.currentTimeMillis(); waitWhat = what; error = null; info = null; return true }

    fun go(to: SignInPage) { page = to; error = null; info = null; password = ""; confirm = ""; waitUntil = 0 }
    fun run(tag: String, block: suspend () -> Result<*>) {
        if (busy != null) return
        focus.clearFocus(); error = null; info = null; busy = tag
        scope.launch {
            val r = block()
            busy = null
            r.onSuccess { if (it is AxionUser) onDone() }
            r.onFailure { e -> if (e !is SignInCancelled) error = e.message ?: "Sign-in failed. Please try again." }
        }
    }
    val activity = ctx.findActivity()
    BackHandler(page != SignInPage.CHOOSE) { go(if (page == SignInPage.EMAIL_RESET) SignInPage.EMAIL_SIGN_IN else SignInPage.CHOOSE) }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page != SignInPage.CHOOSE) IconTap(Gk.arrowLeft, "Back", 24.dp, p.text, 44.dp) { go(if (page == SignInPage.EMAIL_RESET) SignInPage.EMAIL_SIGN_IN else SignInPage.CHOOSE) }
                else if (onClose != null) IconTap(Gk.close, "Close", 24.dp, p.text, 44.dp) { onClose() }
                Spacer(Modifier.weight(1f))
            }
            when (page) {
                SignInPage.CHOOSE -> {
                    Spacer(Modifier.height(36.dp))
                    AxionMark(64.dp, Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(22.dp))
                    Text("Welcome to Axion", style = Type.title.copy(fontSize = 28.sp, lineHeight = 34.sp), color = p.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Sign in to keep your Axion account on this phone. Your chats and keys stay on your device.", style = Type.body.copy(fontSize = 15.sp, lineHeight = 22.sp),
                        color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                    Spacer(Modifier.height(36.dp))
                    ProviderButton("Continue with Google", R.drawable.brand_google_g, tint = null, busy = busy == "google", enabled = busy == null, primary = true) {
                        run("google") { if (activity == null) Result.failure<AxionUser>(Exception("Couldn't open Google sign-in.")) else auth.signInWithGoogle(activity) }
                    }
                    Spacer(Modifier.height(12.dp))
                    ProviderButton("Continue with Email", R.drawable.gk_mail, busy = false, enabled = busy == null) { go(SignInPage.EMAIL_SIGN_IN) }
                    Spacer(Modifier.height(12.dp))
                    ProviderButton("Continue with GitHub", R.drawable.brand_github, busy = busy == "github", enabled = busy == null) {
                        run("github") { if (activity == null) Result.failure<AxionUser>(Exception("Couldn't open GitHub sign-in.")) else auth.signInWithGitHub(activity) }
                    }
                    Message(error, info)
                    if (onGuest != null) {
                        Spacer(Modifier.height(22.dp))
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = busy == null) { onGuest() }.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Maybe later", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                            Text("Continue as a guest. You can sign in any time in Settings.", style = Type.tiny.copy(fontSize = 12.5.sp), color = p.text3, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                    Text("Sign-in is handled by Google Firebase. This doesn't sign you in to Antigravity.", style = Type.tiny.copy(fontSize = 12.5.sp), color = p.text3,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp))
                }
                SignInPage.EMAIL_SIGN_IN, SignInPage.EMAIL_SIGN_UP -> {
                    val up = page == SignInPage.EMAIL_SIGN_UP
                    Spacer(Modifier.height(12.dp))
                    Text(if (up) "Create your account" else "Sign in with email", style = Type.title.copy(fontSize = 26.sp, lineHeight = 32.sp), color = p.text)
                    Spacer(Modifier.height(6.dp))
                    Text(if (up) "Use any email. We'll send a link to verify it." else "Use the email and password of your Axion account.", style = Type.body.copy(fontSize = 15.sp), color = p.text2)
                    Spacer(Modifier.height(18.dp))
                    // Sign in | Create account switch
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(p.card).padding(4.dp)) {
                        listOf(false to "Sign in", true to "Create account").forEach { (isUp, label) ->
                            val sel = up == isUp
                            Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(50)).background(if (sel) p.accent else Color.Transparent)
                                .clickable { if (!sel) go(if (isUp) SignInPage.EMAIL_SIGN_UP else SignInPage.EMAIL_SIGN_IN) }, contentAlignment = Alignment.Center) {
                                Text(label, style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = if (sel) p.onAccent else p.text2)
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    if (up) { Field("Name", name, { name = it }, "Your name"); Spacer(Modifier.height(12.dp)) }
                    Field("Email", email, { email = it.trim() }, "you@example.com", KeyboardType.Email)
                    Spacer(Modifier.height(12.dp))
                    Field("Password", password, { password = it }, if (up) "At least 8 characters" else "Your password", KeyboardType.Password, secret = !showPw,
                        trailing = { IconTap(if (showPw) Gk.eyeOff else Gk.eye, if (showPw) "Hide password" else "Show password", 20.dp, p.text2, 40.dp) { showPw = !showPw } },
                        imeAction = if (up) ImeAction.Next else ImeAction.Done)
                    if (up && password.isNotEmpty()) StrengthMeter(password)
                    if (up) { Spacer(Modifier.height(12.dp)); Field("Confirm password", confirm, { confirm = it }, "Type it again", KeyboardType.Password, secret = !showPw, imeAction = ImeAction.Done) }
                    if (!up) Text("Forgot password?", style = Type.small.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = p.text,
                        modifier = Modifier.align(Alignment.End).padding(top = 6.dp).clip(RoundedCornerShape(50)).clickable { go(SignInPage.EMAIL_RESET) }.padding(horizontal = 8.dp, vertical = 8.dp))
                    Message(error ?: waitText(waitLeft, waitWhat), info)
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton(if (up) "Create account" else "Sign in", busy == "email", busy == null && waitLeft == 0L) {
                        val bad = AuthErrors.checkEmail(email) ?: AuthErrors.checkPassword(password, if (up) confirm else null, newAccount = up)
                            ?: if (up && name.isBlank()) "Enter your name." else null
                        if (bad != null) { error = bad; return@PrimaryButton }
                        if (!up && limitWait(limits.signInWait(email), "signin")) return@PrimaryButton
                        if (up && limitWait(limits.emailWait("verify", email), "verify")) return@PrimaryButton
                        run("email") {
                            val r = if (up) auth.signUpWithEmail(name, email, password) else auth.signInWithEmail(email, password)
                            if (up && r.isSuccess) limits.emailSent("verify", email)
                            if (!up) r.onSuccess { limits.signedIn(email) }.onFailure { if (it.message == "Email or password is wrong.") limits.wrongPassword(email) }
                            if (!up && r.isFailure && limits.signInWait(email) > 0) { limitWait(limits.signInWait(email), "signin"); Result.failure<AxionUser>(SignInCancelled()) } else r
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                SignInPage.EMAIL_RESET -> {
                    Spacer(Modifier.height(12.dp))
                    Text("Reset your password", style = Type.title.copy(fontSize = 26.sp, lineHeight = 32.sp), color = p.text)
                    Spacer(Modifier.height(6.dp))
                    Text("Enter your account email. We'll send you a link to choose a new password.", style = Type.body.copy(fontSize = 15.sp), color = p.text2)
                    Spacer(Modifier.height(18.dp))
                    Field("Email", email, { email = it.trim() }, "you@example.com", KeyboardType.Email, imeAction = ImeAction.Done)
                    Message(error ?: waitText(waitLeft, waitWhat), info)
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton(if (waitLeft > 0) "Send again in ${AuthLimits.clock(waitLeft)}" else "Send reset link", busy == "reset", busy == null && waitLeft == 0L) {
                        AuthErrors.checkEmail(email)?.let { error = it; return@PrimaryButton }
                        if (busy != null) return@PrimaryButton
                        if (limitWait(limits.emailWait("reset", email), "reset")) return@PrimaryButton
                        focus.clearFocus(); error = null; busy = "reset"
                        scope.launch {
                            val r = auth.sendPasswordReset(email); busy = null
                            r.onSuccess {
                                limits.emailSent("reset", email)
                                val left = limits.emailsLeft("reset", email)
                                info = AuthErrors.RESET_SENT + if (left == 0) " You can send another link in an hour." else ""
                            }.onFailure { error = it.message }
                        }
                    }
                }
            }
        }
    }
}

/** v0.5.3: the countdown line under a form while a limit is active. */
private fun waitText(left: Long, what: String): String? = if (left <= 0) null else when (what) {
    "signin" -> "Too many wrong passwords. For your security, try again in ${AuthLimits.clock(left)}, or reset your password."
    "reset" -> "You've asked for 3 reset links in the last hour. You can send another in ${AuthLimits.clock(left)}."
    "verify" -> "Too many verification emails for this address. Try again in ${AuthLimits.clock(left)}."
    else -> "Please wait ${AuthLimits.clock(left)}."
}

@Composable
private fun StrengthMeter(pw: String) {
    val p = P
    val sc = PasswordStrength.score(pw)
    val col = when (sc) { 0, 1 -> Color(0xFFFF5C5C); 2 -> Color(0xFFFFB020); 3 -> Color(0xFF7BD88F); else -> Color(0xFF2BB673) }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { i -> Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50)).background(if (i < sc) col else (if (p.isDark) Color(0xFF333333) else Color(0xFFE0E0E0)))) }
        }
        Text(PasswordStrength.label(sc), style = Type.tiny.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium), color = col, modifier = Modifier.padding(start = 10.dp))
    }
    if (sc < 2) Text("Use 8+ characters and mix letters, numbers or symbols.", style = Type.tiny.copy(fontSize = 12.5.sp), color = p.text3, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

@Composable
private fun Message(error: String?, info: String?) {
    val p = P
    val text = error ?: info ?: return
    val isErr = error != null
    Row(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (isErr) Color(0x33FF4D4F) else (if (p.isDark) Color(0x2234C759) else Color(0x1A34C759))).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(text, style = Type.small.copy(fontSize = 14.5.sp, lineHeight = 20.sp), color = if (isErr) (if (p.isDark) Color(0xFFFF8A8C) else Color(0xFFC62828)) else p.text)
    }
}

@Composable
private fun ProviderButton(label: String, @DrawableRes icon: Int, tint: Color? = P.text, busy: Boolean, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    val p = P
    val bg = if (primary) p.accent else p.card
    val fg = if (primary) p.onAccent else p.text
    Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(50)).background(bg)
        .then(if (primary) Modifier else Modifier.border(1.dp, p.border, RoundedCornerShape(50)))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = fg, strokeWidth = 2.dp)
            else if (tint == null) Image(painterResource(icon), null, Modifier.size(20.dp))
            else GkIcon(icon, null, 20.dp, if (primary) p.onAccent else tint)
            Spacer(Modifier.width(12.dp))
            Text(label, style = Type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = fg)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = P
    Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(50)).background(p.accent).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = p.onAccent, strokeWidth = 2.dp)
        else Text(label, style = Type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = p.onAccent)
    }
}

@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit, hint: String, type: KeyboardType = KeyboardType.Text, secret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null, imeAction: ImeAction = ImeAction.Next,
) {
    val p = P
    Column {
        Text(label, style = Type.small.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = p.text2, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(p.card).border(1.dp, p.border, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (value.isEmpty()) Text(hint, style = Type.body, color = p.text3)
                BasicTextField(value, onChange, singleLine = true, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.text),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = imeAction, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
            }
            trailing?.invoke()
        }
    }
}
