package com.mazharansari.axion.github

import com.mazharansari.axion.security.SecretScanner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * P1-1 (roadmap §6.1–6.2): in-app GitHub coding agent — pure logic.
 *
 * Everything in this file is plain Kotlin with no Android dependency, so it is
 * unit-tested (P1GitHubTest). The token itself lives in [GitHubStore] (KeyVault
 * AES-256-GCM, like provider keys); it NEVER appears in a shell command, a log
 * line, or an approval dialog — git network operations authenticate through a
 * short-lived credential file the tools write and delete (see GitHubTools).
 *
 * Hard rules enforced here (never break):
 *  - push to the default branch (main/master, or an unknown branch) ALWAYS asks,
 *    even in ungated mode, and is never remembered;
 *  - force-push / --force-with-lease / branch delete / repo delete ALWAYS ask
 *    and are never remembered;
 *  - a push/commit is refused when SecretScanner finds a secret in the diff;
 *  - the GitHub password is never asked for, stored, or used (OAuth or PAT only);
 *  - the signing keystore is never involved.
 */

// ── OAuth device flow (GitHub docs: no password, no client secret) ───────────────────────────────────────────────

object GitHubDeviceFlow {
    const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
    const val VERIFY_URL = "https://github.com/login/device"
    const val SCOPE = "repo"
    const val NO_CLIENT_ID = "Add your OAuth App client ID first: create an app at github.com/settings/developers " +
        "(no secret is needed for device flow), then paste its Client ID above."

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSec: Int,
        val intervalSec: Int,
    )

    /** The user-facing code, shown exactly as GitHub issues it ("ABCD-EFGH"). */
    fun formatUserCode(userCode: String) = userCode.trim().uppercase()

    /** Parses POST /login/device/code. Throws when GitHub did not return a usable code. */
    fun parseDeviceCode(el: JsonObject): DeviceCode {
        fun s(k: String) = (el[k] as? JsonPrimitive)?.contentOrNull
        val device = s("device_code")?.takeIf { it.isNotBlank() }
            ?: error("GitHub did not return a device code${(el["error_description"] as? JsonPrimitive)?.contentOrNull?.let { ": $it" } ?: ""}")
        val user = s("user_code")?.takeIf { it.isNotBlank() } ?: error("GitHub did not return a user code")
        return DeviceCode(
            deviceCode = device,
            userCode = user,
            verificationUri = s("verification_uri")?.takeIf { it.isNotBlank() } ?: VERIFY_URL,
            expiresInSec = (el["expires_in"] as? JsonPrimitive)?.intOrNull ?: 900,
            intervalSec = (el["interval_sec"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(5) ?: 5,
        )
    }

    /** Parses POST /login/oauth/access_token: an access token, or the poll error GitHub returned. */
    sealed interface PollResult {
        data class Token(val accessToken: String) : PollResult
        /** Keep polling (authorization_pending / slow_down). [waitMs] is how long to wait first. */
        data class Wait(val waitMs: Long) : PollResult
        /** Terminal failure: show [message] and stop polling. */
        data class Failed(val message: String) : PollResult
    }

    fun parseTokenResponse(el: JsonObject, intervalSec: Int): PollResult {
        val token = (el["access_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (token != null) return PollResult.Token(token)
        return when ((el["error"] as? JsonPrimitive)?.contentOrNull) {
            null -> PollResult.Failed("GitHub answered without a token and without an error.")
            "authorization_pending" -> PollResult.Wait(intervalSec * 1000L)
            "slow_down" -> PollResult.Wait((intervalSec + 5) * 1000L)
            "expired_token" -> PollResult.Failed("The code expired. Get a new code and try again.")
            "access_denied" -> PollResult.Failed("Authorisation was denied on github.com.")
            "incorrect_device_code" -> PollResult.Failed("The device code was rejected. Get a new code and try again.")
            else -> PollResult.Failed("GitHub sign-in failed: ${(el["error_description"] as? JsonPrimitive)?.contentOrNull ?: (el["error"] as? JsonPrimitive)?.contentOrNull}")
        }
    }
}

// ── Tokens ─────────────────────────────────────────────────────────────────────────────────────────────────────

object GitHubToken {
    private val classic = Regex("""^gh[pousr]_[A-Za-z0-9]{30,}$""")
    private val fineGrained = Regex("""^github_pat_[A-Za-z0-9_]{40,}$""")

    /** Classic PAT (ghp_…), fine-grained PAT (github_pat_…), or an OAuth token (gho_…). */
    fun isValidPat(token: String): Boolean {
        val t = token.trim()
        return classic.matches(t) || fineGrained.matches(t)
    }

    fun kindOf(token: String): String? {
        val t = token.trim()
        return when {
            t.startsWith("github_pat_") && fineGrained.matches(t) -> "Fine-grained personal access token"
            t.startsWith("ghp_") && classic.matches(t) -> "Classic personal access token"
            t.startsWith("gho_") && classic.matches(t) -> "OAuth token"
            else -> null
        }
    }

    /** Never more than the last 4 characters ("ghp_ ••••wxyz"). */
    fun hint(token: String): String = SecretScanner.mask(token.trim())
}

// ── Repos ──────────────────────────────────────────────────────────────────────────────────────────────────────

object GitHubRepo {
    private val slug = Regex("""^[A-Za-z0-9_.-]+$""")

    /** "owner/repo", a github.com URL, or an SSH URL → owner to repo, or null. */
    fun parse(input: String): Pair<String, String>? {
        val t = input.trim().removeSuffix("/").removeSuffix(".git")
        val bare = when {
            t.startsWith("git@github.com:") -> t.removePrefix("git@github.com:")
            t.startsWith("https://github.com/") -> t.removePrefix("https://github.com/")
            t.startsWith("http://github.com/") -> t.removePrefix("http://github.com/")
            t.startsWith("github.com/") -> t.removePrefix("github.com/")
            else -> t
        }.trim('/').substringBefore('?').substringBefore('#')
        val parts = bare.split('/').filter { it.isNotBlank() }
        if (parts.size != 2) return null
        val (owner, repo) = parts
        if (!slug.matches(owner) || !slug.matches(repo)) return null
        return owner to repo
    }

    fun cloneUrl(owner: String, repo: String) = "https://github.com/$owner/$repo.git"

    /** Guest (Linux) path for a clone: /workspace/repos/<owner>-<repo>. */
    fun guestDir(owner: String, repo: String) = "/workspace/repos/$owner-$repo"

    fun isDefaultBranch(branch: String?) = branch.isNullOrBlank() || branch == "main" || branch == "master"
}

// ── Gate: which git actions ask, and which can never be remembered ─────────────────────────────────────────────

enum class GitAction {
    CLONE, LIST_BRANCHES, STATUS, DIFF, CREATE_BRANCH, CHECKOUT, COMMIT,
    PUSH, PUSH_DEFAULT, FORCE_PUSH, BRANCH_DELETE, REPO_DELETE, OPEN_PR, MERGE, READ_CI, LIST_COMMENTS,
}

object GitHubGate {
    /** Always ask, never remembered — even in ungated mode, even with a remembered rule. */
    val ALWAYS_ASK = setOf(GitAction.PUSH_DEFAULT, GitAction.FORCE_PUSH, GitAction.BRANCH_DELETE, GitAction.REPO_DELETE, GitAction.MERGE)
    /** Read-only: runs free, no dialog. */
    val READ_ONLY = setOf(GitAction.LIST_BRANCHES, GitAction.STATUS, GitAction.DIFF, GitAction.READ_CI, GitAction.LIST_COMMENTS)

    /** Maps an agent tool call to its action, or null when the call is read-only (no gate). */
    fun actionFor(tool: String, args: Map<String, String?>, force: Boolean = false, branch: String? = null): GitAction? = when (tool) {
        "github_clone" -> GitAction.CLONE
        "github_status" -> if (args["diff"] == "true") GitAction.DIFF else GitAction.STATUS
        "github_branch" -> when (args["action"]) {
            "delete" -> GitAction.BRANCH_DELETE
            "create" -> GitAction.CREATE_BRANCH
            "switch" -> GitAction.CHECKOUT
            else -> GitAction.LIST_BRANCHES
        }
        "github_commit" -> GitAction.COMMIT
        "github_push" -> when {
            force -> GitAction.FORCE_PUSH
            GitHubRepo.isDefaultBranch(branch) -> GitAction.PUSH_DEFAULT
            else -> GitAction.PUSH
        }
        "github_pr" -> when (args["action"]) {
            "merge" -> GitAction.MERGE
            "comments" -> GitAction.LIST_COMMENTS
            else -> GitAction.OPEN_PR
        }
        "github_ci" -> GitAction.READ_CI
        else -> null
    }

    fun requiresApproval(action: GitAction, ungated: Boolean): Boolean {
        if (action in READ_ONLY) return false
        if (action in ALWAYS_ASK) return true
        return !ungated
    }

    /** True when this action may never be stored as an "Always allow" rule. */
    fun neverRemember(action: GitAction) = action in ALWAYS_ASK

    fun hardReason(action: GitAction): String = when (action) {
        GitAction.PUSH_DEFAULT -> "Pushing to the default branch (main/master) always asks, even in ungated mode."
        GitAction.FORCE_PUSH -> "A force-push can overwrite other people's work."
        GitAction.BRANCH_DELETE -> "Deleting a branch cannot be undone."
        GitAction.REPO_DELETE -> "Deleting a repository cannot be undone."
        GitAction.MERGE -> "Merging a pull request changes the shared branch."
        else -> ""
    }

    /**
     * Shell git commands go through the same hard rules (the agent can also run git
     * via run_command). Returns WHY this command always needs a fresh confirmation, or null.
     * Parses the command; it never executes anything.
     */
    fun hardReasonForShellCommand(command: String): String? {
        for (seg in command.split(Regex("&&|\\|\\||;|\\|"))) {
            val toks = seg.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (toks.isEmpty()) continue
            var i = 0
            while (i < toks.size && (toks[i].contains('=') || toks[i] == "sudo")) i++
            if (i >= toks.size || toks[i].substringAfterLast('/') != "git") continue
            val sub = toks.getOrNull(i + 1) ?: continue
            val rest = toks.drop(i + 2)
            when (sub) {
                "push" -> {
                    if ("--force" in rest || "--force-with-lease" in rest || rest.any { it.startsWith("--force") })
                        return "A force-push can overwrite other people's work."
                    // `git push <remote> <branch>` / `git push origin main`: the last bare token is the branch.
                    val branch = rest.lastOrNull { !it.startsWith("-") && '/' !in it }?.substringAfter(':')
                    if (branch != null && branch in setOf("main", "master"))
                        return "Pushing to the default branch (main/master) always asks, even in ungated mode."
                    if (rest.any { it == "--delete" || it == "-d" || it == "-D" }) return "Deleting a branch cannot be undone."
                }
                "branch" -> if (rest.any { it == "-D" || it == "-d" || it == "--delete" }) {
                    return "Deleting a branch cannot be undone."
                }
            }
        }
        return null
    }
}

// ── Shell commands (run inside on-device Linux; the token is never in any of them) ─────────────────────────────

object GitCommands {
    /** Single-quote a shell argument (a ' inside becomes '\'' — the standard safe form). */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun clone(owner: String, repo: String, destDir: String, shallow: Boolean = true): String =
        "git clone" + (if (shallow) " --depth 1" else "") + " ${shellQuote(GitHubRepo.cloneUrl(owner, repo))} ${shellQuote(destDir)}"

    fun status(dir: String) = "git -C ${shellQuote(dir)} status --short --branch"
    /** Staged + unstaged changes vs HEAD — what a commit or a push would actually send. */
    fun diff(dir: String) = "git -C ${shellQuote(dir)} diff HEAD"
    fun branches(dir: String) = "git -C ${shellQuote(dir)} branch -a"
    fun currentBranch(dir: String) = "git -C ${shellQuote(dir)} branch --show-current"
    fun checkout(dir: String, branch: String, create: Boolean = false) =
        "git -C ${shellQuote(dir)} checkout" + (if (create) " -b" else "") + " ${shellQuote(branch)}"
    fun deleteBranch(dir: String, branch: String) = "git -C ${shellQuote(dir)} branch -D ${shellQuote(branch)}"

    /** Stages everything and commits with the user's configured name/email (via -c, never a global config write). */
    fun commit(dir: String, message: String, name: String, email: String): String =
        "git -C ${shellQuote(dir)} add -A && git -C ${shellQuote(dir)} -c user.name=${shellQuote(name)} " +
            "-c user.email=${shellQuote(email)} commit -m ${shellQuote(message)}"

    fun push(dir: String, branch: String?, force: Boolean = false): String {
        val b = branch?.takeIf { it.isNotBlank() }
        return "git -C ${shellQuote(dir)} push" + (if (force) " --force-with-lease" else "") +
            " origin" + (if (b != null) " ${shellQuote(b)}" else "")
    }

    /** Refuses a push/commit when the diff contains a secret (same requireClean idea as the Builder). */
    fun diffHasSecret(diffText: String): Boolean = SecretScanner.contains(diffText)

    /** The first secret kinds found in the diff, for the refusal message (masked, never the secret itself). */
    fun secretKindsIn(diffText: String): List<String> =
        SecretScanner.find(diffText).map { it.kind }.distinct()
}

// ── Audit log: every git action, viewable in Settings → GitHub ────────────────────────────────────────────────

@kotlinx.serialization.Serializable
data class GitAuditEntry(
    val repo: String,
    val branch: String = "",
    val action: String,
    val at: Long = System.currentTimeMillis(),
    val result: String,
    val detail: String = "",
)

class GitAuditStore(private val dir: File) {
    private val file get() = File(dir, "github-audit.json")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun list(): List<GitAuditEntry> = synchronized(lock) {
        runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(GitAuditEntry.serializer()),
                file.readText(),
            )
        }.getOrDefault(emptyList())
    }

    fun append(entry: GitAuditEntry) = synchronized(lock) {
        val kept = (listOf(entry) + list()).take(200)
        dir.mkdirs()
        file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(GitAuditEntry.serializer()), kept))
    }

    fun clear() = synchronized(lock) { runCatching { file.delete() } }
}
