package com.mazharansari.axion.github

import com.mazharansari.axion.data.KeyVault
import com.mazharansari.axion.llm.Http
import com.mazharansari.axion.security.SecretScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * P1-1 (§6.1): the GitHub account in Axion.
 *
 * Storage: the token is KeyVault-encrypted (AES-256-GCM, Android Keystore) exactly
 * like provider keys. The OAuth App client ID is NOT a secret (it is public by
 * design) and is stored in plain text; there is no hardcoded client ID anywhere —
 * the user enters their own (empty by default). The token is never logged and
 * never shown in full (only the last 4, via [GitHubToken.hint]).
 */
@Serializable
data class GitHubState(
    val tokenBlob: String = "",
    val clientId: String = "",
    val login: String = "",
    val name: String = "",
    val email: String = "",
    val ungatedRepos: List<String> = emptyList(),
    val connectedAt: Long = 0,
)

class GitHubStore(dir: File) {
    private val file = File(dir, "github.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(runCatching { json.decodeFromString<GitHubState>(file.readText()) }.getOrDefault(GitHubState()))
    val state: StateFlow<GitHubState> = _state.asStateFlow()

    val audit = GitAuditStore(dir)

    private fun save(s: GitHubState) {
        _state.value = s
        runCatching { file.parentFile?.mkdirs(); file.writeText(json.encodeToString(s)) }
    }

    fun isConnected(): Boolean = _state.value.tokenBlob.isNotBlank()
    fun token(): String? = KeyVault.decrypt(_state.value.tokenBlob)?.takeIf { it.isNotBlank() }
    fun hint(): String = token()?.let { GitHubToken.hint(it) } ?: "Not connected"

    /** A pasted PAT (classic or fine-grained). Returns an error message, or null on success. */
    fun savePat(rawToken: String): String? {
        val t = rawToken.trim()
        if (!GitHubToken.isValidPat(t)) return "That doesn't look like a GitHub token. Paste a classic token (ghp_…) or a fine-grained token (github_pat_…) with repo scope."
        save(_state.value.copy(tokenBlob = KeyVault.encrypt(t), login = "", connectedAt = System.currentTimeMillis()))
        return null
    }

    /** An OAuth device-flow token (the login is filled in by whoami right after). */
    fun saveOAuthToken(token: String, login: String = "") {
        save(_state.value.copy(tokenBlob = KeyVault.encrypt(token.trim()), login = login, connectedAt = System.currentTimeMillis()))
    }

    fun setLogin(login: String) { if (login.isNotBlank()) save(_state.value.copy(login = login)) }
    fun setClientId(clientId: String) { save(_state.value.copy(clientId = clientId.trim())) }
    fun setIdentity(name: String, email: String) { save(_state.value.copy(name = name.trim(), email = email.trim())) }
    fun setUngated(repo: String, on: Boolean) {
        val r = repo.trim().lowercase()
        if (r.isBlank()) return
        val repos = if (on) (_state.value.ungatedRepos + r).distinct() else _state.value.ungatedRepos - r
        save(_state.value.copy(ungatedRepos = repos))
    }

    fun isUngated(repo: String): Boolean = repo.trim().lowercase() in _state.value.ungatedRepos

    /** Disconnect: forgets the token (and its cached plain text) and the login. The client ID stays. */
    fun disconnect() {
        KeyVault.forget(_state.value.tokenBlob)
        save(_state.value.copy(tokenBlob = "", login = "", connectedAt = 0))
    }
}

/** GitHub REST calls the coding agent needs. The token travels only in an Authorization header, never in a URL or a log. */
class GitHubApi(
    private val token: () -> String?,
    private val http: OkHttpClient = Http.quick,
    private val apiBase: String = "https://api.github.com",
) {
    private val JSONT = "application/json".toMediaType()

    private fun call(rb: Request.Builder): Pair<Int, String> {
        val t = token() ?: throw IllegalStateException("GitHub is not connected. Connect it in Settings → GitHub first.")
        return http.newCall(rb.header("User-Agent", Http.UA).header("Authorization", "Bearer $t")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").build())
            .execute().use { it.code to (it.body?.string().orEmpty()) }
    }

    private fun obj(s: String): JsonObject = runCatching { Json.parseToJsonElement(s).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun fail(what: String, code: Int, body: String): Nothing {
        val msg = obj(body).str("message") ?: body.take(160)
        throw IllegalStateException(when (code) {
            401, 403 -> "$what: GitHub rejected the token ($code). Reconnect it in Settings → GitHub."
            404 -> "$what: not found (404). Check the repo name and that the token has repo scope."
            else -> "$what failed ($code): ${SecretScanner.scrub(msg).take(200)}"
        })
    }

    /** The login the token belongs to (used right after connect, and to show "Connected · @login"). */
    suspend fun whoami(): String = withContext(Dispatchers.IO) {
        val (c, b) = call(Request.Builder().url("$apiBase/user"))
        if (c != 200) fail("GitHub sign-in", c, b)
        obj(b).str("login") ?: throw IllegalStateException("GitHub didn't say who this token belongs to")
    }

    /** Opens a PR; returns its web URL. */
    suspend fun createPr(owner: String, repo: String, title: String, body: String, head: String, base: String): String = withContext(Dispatchers.IO) {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("title", title); put("body", body); put("head", head); put("base", base)
        }.toString().toRequestBody(JSONT)
        val (c, b) = call(Request.Builder().url("$apiBase/repos/$owner/$repo/pulls").post(payload))
        if (c !in 200..299) fail("Opening the pull request", c, b)
        obj(b).str("html_url") ?: "https://github.com/$owner/$repo/pulls"
    }

    /** CI status for a ref (branch or SHA): check-runs + combined status, one line each. */
    suspend fun checks(owner: String, repo: String, ref: String): String = withContext(Dispatchers.IO) {
        val (c, b) = call(Request.Builder().url("$apiBase/repos/$owner/$repo/commits/${ref}/check-runs"))
        if (c != 200) fail("Reading CI status", c, b)
        val runs = obj(b)["check_runs"]?.jsonArray.orEmpty().mapNotNull { r ->
            val o = r.jsonObject
            val name = o.str("name") ?: return@mapNotNull null
            val status = o.str("status") ?: ""
            val conclusion = o.str("conclusion") ?: ""
            val done = status == "completed"
            "• $name: ${if (done) conclusion.ifBlank { status } else status}"
        }
        val (sc, sb) = call(Request.Builder().url("$apiBase/repos/$owner/$repo/commits/$ref/status"))
        val combined = if (sc == 200) obj(sb).str("state") else null
        buildString {
            if (combined != null) appendLine("Overall: $combined")
            if (runs.isEmpty()) append("No check runs reported for $ref yet.")
            else append(runs.joinToString("\n"))
        }.trim()
    }

    /** PR comments, newest last, as "author: text" lines. */
    suspend fun prComments(owner: String, repo: String, number: Int): String = withContext(Dispatchers.IO) {
        val (c, b) = call(Request.Builder().url("$apiBase/repos/$owner/$repo/issues/$number/comments?per_page=30"))
        if (c != 200) fail("Reading PR comments", c, b)
        val items = runCatching { Json.parseToJsonElement(b).jsonArray }.getOrDefault(kotlinx.serialization.json.JsonArray(emptyList()))
        if (items.isEmpty()) return@withContext "No comments on PR #$number."
        items.mapNotNull { el ->
            val o = el.jsonObject
            val author = (o["user"] as? JsonObject)?.str("login") ?: "?"
            val text = o.str("body")?.take(500) ?: return@mapNotNull null
            "$author: $text"
        }.joinToString("\n\n")
    }

    /** Merges a PR (squash by default); returns the merge result line. */
    suspend fun mergePr(owner: String, repo: String, number: Int): String = withContext(Dispatchers.IO) {
        val payload = kotlinx.serialization.json.buildJsonObject { put("merge_method", "squash") }.toString().toRequestBody(JSONT)
        val (c, b) = call(Request.Builder().url("$apiBase/repos/$owner/$repo/pulls/$number/merge").put(payload))
        if (c !in 200..299) fail("Merging PR #$number", c, b)
        val o = obj(b)
        if (o["merged"]?.jsonPrimitive?.contentOrNull == "true") "PR #$number merged." else "PR #$number was not merged: ${o.str("message") ?: "GitHub refused the merge."}"
    }
}

/** The device-flow network calls (parsing lives in [GitHubDeviceFlow], which is unit-tested). */
object GitHubDeviceFlowClient {
    private val JSONT = "application/json".toMediaType()

    private fun post(url: String, body: String, http: OkHttpClient): JsonObject {
        val (code, text) = http.newCall(Request.Builder().url(url).header("User-Agent", Http.UA)
            .header("Accept", "application/json").post(body.toRequestBody(JSONT)).build())
            .execute().use { it.code to (it.body?.string().orEmpty()) }
        if (code !in 200..299) error("GitHub answered $code: ${text.take(160)}")
        return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { error("GitHub answered with something Axion couldn't read.") }
    }

    suspend fun start(clientId: String, http: OkHttpClient = Http.quick): GitHubDeviceFlow.DeviceCode = withContext(Dispatchers.IO) {
        require(clientId.isNotBlank()) { GitHubDeviceFlow.NO_CLIENT_ID }
        val body = kotlinx.serialization.json.buildJsonObject {
            put("client_id", clientId.trim()); put("scope", GitHubDeviceFlow.SCOPE)
        }.toString()
        GitHubDeviceFlow.parseDeviceCode(post(GitHubDeviceFlow.DEVICE_CODE_URL, body, http))
    }

    suspend fun poll(clientId: String, deviceCode: String, intervalSec: Int, http: OkHttpClient = Http.quick): GitHubDeviceFlow.PollResult = withContext(Dispatchers.IO) {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("client_id", clientId.trim())
            put("device_code", deviceCode)
            put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        }.toString()
        GitHubDeviceFlow.parseTokenResponse(post(GitHubDeviceFlow.ACCESS_TOKEN_URL, body, http), intervalSec)
    }
}
