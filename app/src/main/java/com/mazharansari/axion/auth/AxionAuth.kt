package com.mazharansari.axion.auth

import android.app.Activity
import android.content.Context
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The Axion account (sign-in screen + Settings → Account). Firebase in the app, a fake in tests and screenshots. */
interface AxionAuth {
    /** False when Firebase could not start (no Google services config, Robolectric): the sign-in screen is then skipped. */
    val available: Boolean
    val state: StateFlow<AuthState>
    suspend fun signInWithEmail(email: String, password: String): Result<AxionUser>
    suspend fun signUpWithEmail(name: String, email: String, password: String): Result<AxionUser>
    suspend fun sendPasswordReset(email: String): Result<Unit>
    suspend fun sendEmailVerification(): Result<Unit>
    suspend fun signInWithGoogle(activity: Activity): Result<AxionUser>
    suspend fun signInWithGitHub(activity: Activity): Result<AxionUser>
    fun signOut()
}

object AuthHub {
    @Volatile private var instance: AxionAuth? = null
    @Volatile private var limitsInstance: AuthLimits? = null
    /** v0.5.3: the client-side send / wrong-password limits, kept in private prefs (emails only as hashes). */
    fun limits(ctx: Context): AuthLimits = limitsInstance ?: synchronized(this) {
        limitsInstance ?: run {
            val prefs = ctx.applicationContext.getSharedPreferences("auth_limits", Context.MODE_PRIVATE)
            val map = HashMap<String, String>(); prefs.all.forEach { (k, v) -> if (v is String) map[k] = v }
            AuthLimits(store = map, save = { prefs.edit().clear().apply { map.forEach { (k, v) -> putString(k, v) } }.apply() })
        }.also { limitsInstance = it }
    }
    /** Tests and screenshots put a [FakeAuth] here. */
    @Volatile var override: AxionAuth? = null
    fun get(ctx: Context): AxionAuth = override ?: instance ?: synchronized(this) {
        instance ?: FirebaseAxionAuth.create(ctx.applicationContext).also { instance = it }
    }
}

/** Waits for a Play-services Task without pulling in kotlinx-coroutines-play-services. */
suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { c ->
    addOnCompleteListener { t ->
        when {
            t.isCanceled -> c.cancel()
            t.isSuccessful -> c.resume(t.result)
            else -> c.resumeWithException(t.exception ?: IllegalStateException("Sign-in failed"))
        }
    }
}

class FirebaseAxionAuth private constructor(private val auth: FirebaseAuth?, private val webClientId: String?) : AxionAuth {
    override val available: Boolean get() = auth != null
    private val _state = MutableStateFlow<AuthState>(auth?.currentUser?.let { AuthState.SignedIn(it.toAxion()) } ?: AuthState.SignedOut)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    init { auth?.addAuthStateListener { a -> _state.value = a.currentUser?.let { AuthState.SignedIn(it.toAxion()) } ?: AuthState.SignedOut } }

    companion object {
        fun create(ctx: Context): FirebaseAxionAuth {
            if (Build.FINGERPRINT == "robolectric") return FirebaseAxionAuth(null, null)
            val auth = runCatching {
                if (FirebaseApp.getApps(ctx).isEmpty()) FirebaseApp.initializeApp(ctx)
                FirebaseAuth.getInstance()
            }.getOrNull()
            // The web client ID comes from app/google-services.json (axion-d5461) through the google-services plugin.
            val id = ctx.resources.getIdentifier("default_web_client_id", "string", ctx.packageName).takeIf { it != 0 }?.let { ctx.getString(it) }
            return FirebaseAxionAuth(auth, id ?: AXION_WEB_CLIENT_ID).also { it.appCtx = ctx }
        }
        /** axion-d5461's Web client (the same value google-services.json generates). Never another project's ID. */
        const val AXION_WEB_CLIENT_ID = "1071482495-37nso39ts645mdt87vafagabrka26pug.apps.googleusercontent.com"
    }

    private fun need(): FirebaseAuth = auth ?: throw IllegalStateException("Sign-in isn't available on this phone right now.")

    private suspend fun <T> guard(provider: AuthProvider, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: SignInCancelled) { Result.failure(e)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Throwable) {
        val code = (e as? FirebaseAuthException)?.errorCode
        if (code == "ERROR_WEB_CONTEXT_CANCELED") Result.failure(SignInCancelled())
        else Result.failure(Exception(AuthErrors.friendly(code, e.message, e.javaClass.name, provider), e))
    }

    override suspend fun signInWithEmail(email: String, password: String) = guard(AuthProvider.EMAIL) {
        need().signInWithEmailAndPassword(email.trim(), password).awaitResult().user!!.toAxion()
    }

    override suspend fun signUpWithEmail(name: String, email: String, password: String) = guard(AuthProvider.EMAIL) {
        val u = need().createUserWithEmailAndPassword(email.trim(), password).awaitResult().user!!
        if (name.isNotBlank()) runCatching { u.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).awaitResult(); u.reload().awaitResult() }
        runCatching { u.sendEmailVerification().awaitResult() }
        val fresh = need().currentUser ?: u
        fresh.toAxion().also { _state.value = AuthState.SignedIn(it) }
    }

    /** No account enumeration: "no user with this email" is answered exactly like a sent link. */
    override suspend fun sendPasswordReset(email: String) = guard(AuthProvider.EMAIL) {
        try { need().sendPasswordResetEmail(email.trim()).awaitResult() }
        catch (e: FirebaseAuthException) { if (e.errorCode != "ERROR_USER_NOT_FOUND") throw e }
        Unit
    }

    override suspend fun sendEmailVerification() = guard(AuthProvider.EMAIL) { need().currentUser?.sendEmailVerification()?.awaitResult(); Unit }

    override suspend fun signInWithGoogle(activity: Activity) = guard(AuthProvider.GOOGLE) {
        val token = GoogleIdToken.request(activity, webClientId ?: AXION_WEB_CLIENT_ID)
        need().signInWithCredential(GoogleAuthProvider.getCredential(token, null)).awaitResult().user!!.toAxion()
    }

    /** Firebase's GitHub provider (scope "repo"). If mazhar hasn't enabled GitHub in the console, Firebase answers
     *  ERROR_OPERATION_NOT_ALLOWED and the user gets a friendly sentence — never a crash. */
    override suspend fun signInWithGitHub(activity: Activity) = guard(AuthProvider.GITHUB) {
        val a = need()
        val provider = OAuthProvider.newBuilder("github.com").setScopes(listOf("repo")).build()
        val result = a.pendingAuthResult?.awaitResult() ?: a.startActivityForSignInWithProvider(activity, provider).awaitResult()
        result.user!!.toAxion()
    }

    /** Sign-out wipes the session: Firebase's user/tokens, and Credential Manager's remembered Google account choice. */
    override fun signOut() {
        runCatching { auth?.signOut() }
        _state.value = AuthState.SignedOut
        appCtx?.let { c -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { androidx.credentials.CredentialManager.create(c).clearCredentialState(androidx.credentials.ClearCredentialStateRequest()) }
        } }
    }
    private var appCtx: Context? = null
}

internal fun FirebaseUser.toAxion(): AxionUser {
    val p = providerData.firstOrNull { it.providerId != "firebase" }
    val provider = AuthProvider.fromFirebase(p?.providerId)
    return AxionUser(
        uid = uid,
        email = email ?: p?.email,
        displayName = displayName?.takeIf { it.isNotBlank() } ?: p?.displayName,
        photoUrl = (photoUrl ?: p?.photoUrl)?.toString(),
        emailVerified = provider != AuthProvider.EMAIL || isEmailVerified,
        provider = provider,
    )
}

/** In-memory account for unit tests and screenshots. */
class FakeAuth(start: AuthState = AuthState.SignedOut, override val available: Boolean = true, githubEnabledFlag: Boolean = false) : AxionAuth {
    private val _state = MutableStateFlow(start)
    override val state: StateFlow<AuthState> = _state.asStateFlow()
    var nextError: String? = null
    var githubEnabled = githubEnabledFlag
    private fun <T> res(v: () -> T): Result<T> = nextError?.let { nextError = null; Result.failure(Exception(it)) } ?: Result.success(v())
    private fun signed(u: AxionUser) = u.also { _state.value = AuthState.SignedIn(it) }
    override suspend fun signInWithEmail(email: String, password: String) = res { signed(AxionUser("u1", email, null, null, true, AuthProvider.EMAIL)) }
    override suspend fun signUpWithEmail(name: String, email: String, password: String) = res { signed(AxionUser("u1", email, name, null, false, AuthProvider.EMAIL)) }
    override suspend fun sendPasswordReset(email: String) = res { }
    override suspend fun sendEmailVerification() = res { }
    override suspend fun signInWithGoogle(activity: Activity) = res { signed(AxionUser("g1", "mazhar@gmail.com", "Mazhar Ansari", null, true, AuthProvider.GOOGLE)) }
    override suspend fun signInWithGitHub(activity: Activity): Result<AxionUser> =
        if (!githubEnabled) Result.failure(Exception(AuthErrors.friendly("ERROR_OPERATION_NOT_ALLOWED", "The given sign-in provider is disabled for this Firebase project.", null, AuthProvider.GITHUB)))
        else res { signed(AxionUser("gh1", null, "mazhar", null, true, AuthProvider.GITHUB)) }
    override fun signOut() { _state.value = AuthState.SignedOut }
}
