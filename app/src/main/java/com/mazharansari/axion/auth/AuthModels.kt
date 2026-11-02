package com.mazharansari.axion.auth

/**
 * v0.5.3 sign-in (ported from Kodex v3.2.55's Firebase sign-in, adapted to Axion). This is ONLY the Axion account
 * (Firebase project axion-d5461). It never signs in to Antigravity and never uploads chats or keys.
 */
enum class AuthProvider(val title: String) {
    EMAIL("Email"), GOOGLE("Google"), GITHUB("GitHub"), OTHER("Account");
    companion object {
        fun fromFirebase(providerId: String?): AuthProvider = when (providerId) {
            "google.com" -> GOOGLE; "github.com" -> GITHUB; "password" -> EMAIL; else -> OTHER
        }
    }
}

data class AxionUser(
    val uid: String,
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val emailVerified: Boolean = true,
    val provider: AuthProvider = AuthProvider.EMAIL,
) {
    /** What Settings shows as the name: the account name, else the part of the email before @, else the provider. */
    val shownName: String get() = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: email?.substringBefore('@')?.takeIf { it.isNotBlank() } ?: "${provider.title} user"
}

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val account: AxionUser) : AuthState
    val user: AxionUser? get() = (this as? SignedIn)?.account
}

/** A failure the user should not see as an error (they closed the Google sheet or the GitHub page). */
class SignInCancelled : Exception("Sign-in was cancelled")

/**
 * Pure (unit-tested): turns Firebase / Credential Manager failures into one friendly English sentence.
 * [code] is FirebaseAuthException.errorCode when there is one; [message] the exception message; [type] the class name.
 */
object AuthErrors {
    fun friendly(code: String?, message: String?, type: String? = null, provider: AuthProvider? = null): String {
        val c = code.orEmpty().uppercase(); val m = message.orEmpty(); val ml = m.lowercase(); val t = type.orEmpty()
        val who = provider?.title ?: "This"
        return when {
            c == "ERROR_OPERATION_NOT_ALLOWED" || "provider is disabled" in ml || "operation is not allowed" in ml || "operation_not_allowed" in ml ->
                if (provider == AuthProvider.GITHUB) "GitHub sign-in isn't switched on for Axion yet. Use Google or Email for now, or try again later."
                else "$who sign-in isn't switched on for Axion yet. Try another way to sign in."
            "invalid_client" in ml || "incorrect_client_credentials" in ml || ("client_id" in ml && "invalid" in ml) || "redirect_uri" in ml ->
                "GitHub sign-in is set up wrong in Firebase (client ID, secret or callback URL). Use Google or Email for now."
            c == "ERROR_WEB_CONTEXT_CANCELED" -> "Sign-in was cancelled."
            c == "ERROR_WEB_CONTEXT_ALREADY_PRESENTED" -> "A sign-in page is already open. Finish it or go back, then try again."
            c == "ERROR_WEB_STORAGE_UNSUPPORTED" || c == "ERROR_WEB_INTERNAL_ERROR" || "no browser" in ml || "activitynotfound" in t.lowercase() ->
                "Couldn't open the sign-in page. Make sure Chrome (or another browser) is installed and enabled, then try again."
            c == "ERROR_INTERNAL_ERROR" || "an internal error has occurred" in ml ->
                "$who sign-in couldn't start. Check your internet, and that Chrome and Google Play services are installed and up to date, then try again."
            c == "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" || c == "ERROR_CREDENTIAL_ALREADY_IN_USE" ->
                "This email already has an Axion account with a different sign-in. Sign in the way you did before (Google or Email)."
            // v0.5.3 security: never reveal whether an email has an account (sign-up, sign-in and reset all answer the same way).
            c == "ERROR_EMAIL_ALREADY_IN_USE" -> "Couldn't create the account with this email. If you already have one, sign in or reset your password."
            c == "ERROR_INVALID_EMAIL" -> "That email address doesn't look right."
            c == "ERROR_WEAK_PASSWORD" || "weak" in ml && "password" in ml || "password_does_not_meet_requirements" in ml ->
                "Password is too weak. Use at least 8 characters with letters and numbers."
            c == "ERROR_WRONG_PASSWORD" || c == "ERROR_INVALID_CREDENTIAL" || "supplied auth credential is incorrect" in ml || "password is invalid" in ml ->
                if (provider == AuthProvider.EMAIL || provider == null) "Email or password is wrong." else "$who sign-in failed. Please try again."
            c == "ERROR_USER_NOT_FOUND" -> if (provider == AuthProvider.EMAIL || provider == null) "Email or password is wrong." else "$who sign-in failed. Please try again."
            c == "ERROR_USER_DISABLED" -> "This account has been disabled."
            c == "ERROR_TOO_MANY_REQUESTS" || "too many" in ml || "blocked all requests" in ml -> "Too many tries. Wait a few minutes and try again."
            c == "ERROR_REQUIRES_RECENT_LOGIN" -> "Please sign in again first."
            c == "ERROR_NETWORK_REQUEST_FAILED" || "network" in t.lowercase() || "network error" in ml || "unable to resolve host" in ml || "timeout" in ml ->
                "No internet connection. Check your connection and try again."
            "nocredential" in t.lowercase() || "no credentials available" in ml || "cannot find a matching credential" in ml ->
                "No Google account found on this phone. Add one in Android Settings → Accounts, or continue with Email."
            "developer_error" in ml || "developer console is not set up correctly" in ml || "[10]" in m || "status code: 10" in ml || "16: " in m && "unknown calling package" in ml ->
                "Google sign-in isn't set up for this app build yet. Continue with Email or as a guest."
            "credentialmanager" in ml && "not available" in ml || "providerconfigurationexception" in t.lowercase() ->
                "Google sign-in needs Google Play services, which isn't available on this phone. Continue with Email."
            "api key not valid" in ml || "configuration_not_found" in ml || "configuration not found" in ml ->
                "Sign-in isn't set up correctly for this build. Continue as a guest for now."
            else -> "Sign-in failed" + (if (m.isNotBlank()) ": " + m.take(140) else ". Please try again.")
        }
    }

    /** Pure: checks run before anything is sent. Returns an error sentence or null. */
    fun checkEmail(email: String): String? = when {
        email.isBlank() -> "Enter your email."
        !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$").matches(email.trim()) -> "That email address doesn't look right."
        else -> null
    }
    /** Sign-in: any non-empty password (older accounts). Sign-up ([newAccount]): at least 8 characters and "Fair" or better. */
    fun checkPassword(password: String, confirm: String? = null, newAccount: Boolean = confirm != null): String? = when {
        password.isEmpty() -> "Enter your password."
        newAccount && password.length < PasswordStrength.MIN_LENGTH -> "Password must be at least ${PasswordStrength.MIN_LENGTH} characters."
        newAccount && PasswordStrength.score(password) < 2 -> "Password is too easy to guess. Mix letters, numbers or symbols."
        confirm != null && confirm != password -> "Passwords don't match."
        else -> null
    }

    /** What the reset screen always says, whether or not the email has an account (no account enumeration). */
    const val RESET_SENT = "If an account exists for this email, a reset link is on its way. Check your inbox and spam."
}

/** v0.5.3 password strength meter (pure). 0 = too short, 1 weak, 2 fair, 3 good, 4 strong. */
object PasswordStrength {
    const val MIN_LENGTH = 8
    private val common = setOf("password", "12345678", "123456789", "1234567890", "qwerty123", "11111111", "00000000", "password1", "iloveyou", "abcd1234", "pakistan", "qwertyui", "asdfghjk")
    fun score(pw: String): Int {
        if (pw.length < MIN_LENGTH) return 0
        if (pw.lowercase() in common || pw.toSet().size <= 2) return 1
        var classes = 0
        if (pw.any { it.isLowerCase() }) classes++
        if (pw.any { it.isUpperCase() }) classes++
        if (pw.any { it.isDigit() }) classes++
        if (pw.any { !it.isLetterOrDigit() }) classes++
        var s = when { classes >= 3 -> 3; classes == 2 -> 2; else -> 1 }
        if (pw.length >= 12 && classes >= 2) s++
        if (Regex("(.)\\1{3,}").containsMatchIn(pw) || Regex("(0123|1234|2345|3456|4567|5678|6789|abcd|qwer)").containsMatchIn(pw.lowercase())) s--
        return s.coerceIn(1, 4)
    }
    fun label(score: Int) = when (score) { 0 -> "Too short"; 1 -> "Weak"; 2 -> "Fair"; 3 -> "Good"; else -> "Strong" }
}

/**
 * v0.5.3 client-side limits (on top of Firebase's own): reset/verification emails max 3 per email per hour, and a 5-minute
 * cooldown after 5 wrong passwords in 15 minutes. Emails are stored only as a SHA-256 hash. Pure: the clock and store are given.
 */
class AuthLimits(private val now: () -> Long = System::currentTimeMillis, private val store: MutableMap<String, String> = HashMap(), private val save: () -> Unit = {}) {
    companion object {
        const val MAX_EMAILS_PER_HOUR = 3
        const val HOUR = 3_600_000L
        const val MAX_WRONG = 5
        const val WRONG_WINDOW = 15 * 60_000L
        const val COOLDOWN = 5 * 60_000L
        fun key(kind: String, email: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            return kind + ":" + md.digest(email.trim().lowercase().toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        }
        /** Pure: "4:59", "0:07". */
        fun clock(ms: Long): String { val s = (ms + 999) / 1000; return "%d:%02d".format(s / 60, s % 60) }
    }
    private fun times(k: String): MutableList<Long> = store[k]?.split(',')?.mapNotNull { it.toLongOrNull() }?.toMutableList() ?: mutableListOf()
    private fun put(k: String, l: List<Long>) { if (l.isEmpty()) store.remove(k) else store[k] = l.joinToString(","); save() }

    /** Milliseconds until another [kind] email ("reset" / "verify") may be sent to [email]; 0 = allowed now. */
    fun emailWait(kind: String, email: String): Long {
        val t = now(); val l = times(key(kind, email)).filter { t - it < HOUR }
        return if (l.size < MAX_EMAILS_PER_HOUR) 0 else (l.min() + HOUR - t).coerceAtLeast(1)
    }
    fun emailSent(kind: String, email: String) { val t = now(); put(key(kind, email), times(key(kind, email)).filter { t - it < HOUR } + t) }
    fun emailsLeft(kind: String, email: String): Int { val t = now(); return (MAX_EMAILS_PER_HOUR - times(key(kind, email)).count { t - it < HOUR }).coerceAtLeast(0) }

    /** Milliseconds of sign-in cooldown left for [email] after too many wrong passwords; 0 = may try. */
    fun signInWait(email: String): Long {
        val t = now(); val l = times(key("wrong", email)).filter { t - it < WRONG_WINDOW + COOLDOWN }
        if (l.size < MAX_WRONG) return 0
        val last = l.sorted()[MAX_WRONG - 1].let { l.max() }
        return (last + COOLDOWN - t).coerceAtLeast(0)
    }
    fun wrongPassword(email: String) { val t = now(); put(key("wrong", email), (times(key("wrong", email)).filter { t - it < WRONG_WINDOW } + t).takeLast(MAX_WRONG)) }
    fun signedIn(email: String) = put(key("wrong", email), emptyList())
}
