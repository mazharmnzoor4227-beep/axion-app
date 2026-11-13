package com.mazharansari.axion.agent

import com.mazharansari.axion.github.GitAuditEntry
import com.mazharansari.axion.github.GitCommands
import com.mazharansari.axion.github.GitHubApi
import com.mazharansari.axion.github.GitHubRepo
import com.mazharansari.axion.github.GitHubStore
import com.mazharansari.axion.linux.LinuxHub
import com.mazharansari.axion.security.SecretScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * P1-1 (§6.1): the GitHub coding-agent tools. Git runs in the on-device Linux
 * (git CLI, shallow clone by default); PRs / CI / comments go through the
 * GitHub REST API on the same token. The agent runs on the user's own
 * configured providers — no separate billing, no hardcoded provider.
 *
 * Safety, enforced here (the approval gate already asked where §6.2 requires):
 *  - the token NEVER appears in a shell command, a step, or a log. Network git
 *    authenticates through a credential file written to the private workspace
 *    (workspace/.axion/git-credentials) just before the command and deleted
 *    right after, in a finally block;
 *  - commit/push are refused when SecretScanner finds a secret in the diff;
 *  - commit needs the user's configured name/email, and either an explicit
 *    review (reviewed=true — the agent shows the diff and the user approves it)
 *    or the repo switched to ungated in Settings → GitHub (ChangeGate's
 *    >25 MB / >400 MB guards still apply to Build projects, not here);
 *  - every action is written to the audit log (Settings → GitHub).
 * The signing keystore is never involved: this edits code only.
 */

private fun githubStore(): GitHubStore =
    LinuxHub.graph?.github ?: throw IllegalStateException("GitHub is not available in this context.")

private fun shell() =
    LinuxHub.graph?.shell ?: throw IllegalStateException("The shell session is not available in this context.")

private fun requireLinux() {
    val linux = LinuxHub.graph?.linux
    if (linux == null || !linux.isInstalled()) throw IllegalStateException("Linux is not installed yet. Install it in Developer tools first, then ask Axion to work on the repo.")
}

private fun requireToken(store: GitHubStore): String =
    store.token() ?: throw IllegalStateException("GitHub is not connected. Connect it in Settings → GitHub first.")

private fun repoDirOf(args: JsonObject): String {
    args.s("repo_dir")?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    val repo = args.s("repo")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Give the repo (owner/repo) or the repo_dir.")
    val (owner, name) = GitHubRepo.parse(repo) ?: throw IllegalArgumentException("That repo doesn't look like owner/repo: $repo")
    return GitHubRepo.guestDir(owner, name)
}

private fun repoSlugOf(args: JsonObject): String {
    // A repo_dir like /workspace/repos/owner-repo cannot be split back into
    // owner/repo reliably (both sides may contain '-'), so PR/CI tools need
    // the repo itself.
    val repo = args.s("repo")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Give the repo (owner/repo).")
    val (owner, name) = GitHubRepo.parse(repo) ?: throw IllegalArgumentException("That repo doesn't look like owner/repo: $repo")
    return "$owner/$name"
}

private fun checkRepoDir(dir: String) {
    require(dir.startsWith("/workspace/")) { "Repos live under /workspace/repos — refusing $dir." }
    require(!dir.contains("..")) { "Refusing a repo path with '..': $dir" }
}

private fun audit(store: GitHubStore, repo: String, branch: String, action: String, result: String, detail: String = "") {
    runCatching { store.audit.append(GitAuditEntry(repo, branch, action, result = result, detail = SecretScanner.scrub(detail).take(200))) }
}

private suspend fun shellOut(command: String, timeoutSec: Long = 120): Pair<Int, String> = withContext(Dispatchers.IO) {
    val r = shell().exec(command, timeoutSec * 1000)
    val text = (r.output + if (r.error != null) "\n${r.error}" else "").trim()
    r.exitCode to SecretScanner.scrub(text)
}

/**
 * Runs [block] with git able to authenticate to github.com, without the token
 * ever appearing in a command string. The credential file is deleted in a
 * finally block even when the command fails.
 */
private suspend fun <T> withGitAuth(ctx: ToolContext, store: GitHubStore, block: suspend () -> T): T {
    val token = requireToken(store)
    val cred = File(ctx.workspace.root, ".axion/git-credentials").apply { parentFile?.mkdirs() }
    cred.writeText("https://x-access-token:$token@github.com\n")
    return try {
        shellOut("git config --global credential.helper 'store --file=/workspace/.axion/git-credentials'")
        block()
    } finally {
        runCatching { cred.delete() }
        runCatching { shellOut("git config --global --unset credential.helper") }
    }
}

private fun ok(text: String, title: String? = null) = ToolOutput(SecretScanner.scrub(text).clip(10000), display = SecretScanner.scrub(text).clip(2000), doneTitle = title)
private fun err(text: String) = ToolOutput(SecretScanner.scrub(text).clip(10000), isError = true)

class GitHubCloneTool : Tool {
    override val spec = ToolSpec("github_clone",
        "Clone a GitHub repo into /workspace/repos/ (shallow by default) so Axion can work on it. Needs GitHub connected in Settings → GitHub and Linux installed.",
        schema { str("repo", "owner/repo, a github.com URL, or an SSH URL"); bool("shallow", "Shallow clone (default true)") })
    override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "GitHub clone", args.s("repo").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val store = githubStore()
        val repo = args.s("repo").orEmpty()
        val (owner, name) = GitHubRepo.parse(repo) ?: return err("That repo doesn't look like owner/repo: $repo")
        val dest = GitHubRepo.guestDir(owner, name)
        return try {
            requireLinux(); requireToken(store)
            val (code, out) = withGitAuth(ctx, store) { shellOut(GitCommands.clone(owner, name, dest, shallow = args.b("shallow") != false), 600) }
            audit(store, "$owner/$name", "", "clone", if (code == 0) "ok" else "failed", out.take(200))
            if (code != 0) err("Clone failed (exit $code):\n$out") else ok("Cloned $owner/$name into $dest.\n$out", "Cloned $owner/$name")
        } catch (e: Exception) { audit(store, "$owner/$name", "", "clone", "failed", e.message.orEmpty()); err(e.message ?: "Clone failed") }
    }
}

class GitHubStatusTool : Tool {
    override val spec = ToolSpec("github_status",
        "Show a cloned repo's status, branches, and (with diff=true) its uncommitted diff. Read-only — runs without asking.",
        schema { str("repo_dir", "Cloned repo path, e.g. /workspace/repos/owner-repo"); str("repo", "owner/repo (used when repo_dir is not given)"); bool("diff", "Include the uncommitted diff") })
    override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "GitHub status", args.s("repo").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val store = githubStore()
        return try {
            requireLinux()
            val dir = repoDirOf(args); checkRepoDir(dir)
            val (sc, status) = shellOut(GitCommands.status(dir))
            if (sc != 0) return err("Not a repo, or git failed:\n$status")
            val (bc, branches) = shellOut(GitCommands.branches(dir))
            val diff = if (args.b("diff") == true) shellOut(GitCommands.diff(dir)).second else ""
            val text = buildString {
                appendLine("Repo: $dir"); appendLine("Status:"); appendLine(status.ifBlank { "(clean)" })
                if (bc == 0) { appendLine("Branches:"); appendLine(branches) }
                if (diff.isNotBlank()) { appendLine("Diff:"); append(diff) }
            }
            audit(store, dir.removePrefix("/workspace/repos/"), "", if (args.b("diff") == true) "diff" else "status", "ok")
            ok(text)
        } catch (e: Exception) { err(e.message ?: "Status failed") }
    }
}

class GitHubBranchTool : Tool {
    override val spec = ToolSpec("github_branch",
        "List, switch to, or create a branch in a cloned repo. Deleting a branch always asks the user first and is never remembered.",
        schema { str("repo_dir", "Cloned repo path"); str("repo", "owner/repo (used when repo_dir is not given)"); str("action", "list, switch, create, or delete"); str("name", "Branch name (switch, create, delete)") })
    override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "GitHub branch", args.s("name").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val store = githubStore()
        return try {
            requireLinux()
            val dir = repoDirOf(args); checkRepoDir(dir)
            val action = args.s("action")?.lowercase() ?: "list"
            val name = args.s("name").orEmpty()
            val (code, out) = when (action) {
                "list" -> shellOut(GitCommands.branches(dir))
                "switch", "create" -> {
                    if (name.isBlank()) return err("Give the branch name to $action.")
                    shellOut(GitCommands.checkout(dir, name, create = action == "create"))
                }
                "delete" -> {
                    if (name.isBlank()) return err("Give the branch name to delete.")
                    shellOut(GitCommands.deleteBranch(dir, name))
                }
                else -> return err("Unknown branch action: $action (list, switch, create, delete).")
            }
            audit(store, dir.removePrefix("/workspace/repos/"), name, "branch $action", if (code == 0) "ok" else "failed", out.take(200))
            if (code != 0) err("Branch $action failed (exit $code):\n$out") else ok(out.ifBlank { "Done." })
        } catch (e: Exception) { err(e.message ?: "Branch failed") }
    }
}

class GitHubCommitTool : Tool {
    override val spec = ToolSpec("github_commit",
        "Commit the repo's changes with the user's configured name and email (Settings → GitHub). The user must have approved the diff first (reviewed=true) unless the repo is ungated in Settings → GitHub. Refused when the diff contains a secret.",
        schema { str("repo_dir", "Cloned repo path"); str("repo", "owner/repo (used when repo_dir is not given)"); str("message", "Commit message"); bool("reviewed", "The user approved this diff (default false)") })
    override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "GitHub commit", args.s("message").orEmpty().take(80))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val store = githubStore()
        return try {
            requireLinux(); requireToken(store)
            val dir = repoDirOf(args); checkRepoDir(dir)
            val slug = dir.removePrefix("/workspace/repos/")
            val message = args.s("message")?.trim().orEmpty()
            if (message.isBlank()) return err("Give a commit message.")
            if (args.b("reviewed") != true && !store.isUngated(slug)) {
                audit(store, slug, "", "commit", "refused", "diff not reviewed")
                return err("The user hasn't approved this diff yet. Show the diff (github_status with diff=true), let the user approve it, then commit again with reviewed=true — or switch the repo to ungated in Settings → GitHub.")
            }
            val name = store.state.value.name; val email = store.state.value.email
            if (name.isBlank() || email.isBlank()) return err("Set your name and email in Settings → GitHub first — commits use them.")
            val diff = shellOut(GitCommands.diff(dir)).second
            if (GitCommands.diffHasSecret(diff)) {
                audit(store, slug, "", "commit", "refused", "secret in diff")
                return err("Commit refused: the diff contains a secret (${GitCommands.secretKindsIn(diff).joinToString(", ")}). Remove it from the files first — keys belong in Axion's settings, never in a repo.")
            }
            val (code, out) = shellOut(GitCommands.commit(dir, message, name, email))
            audit(store, slug, "", "commit", if (code == 0) "ok" else "failed", out.take(200))
            if (code != 0) err("Commit failed (exit $code):\n$out") else ok("Committed in $slug:\n$out", "Committed")
        } catch (e: Exception) { err(e.message ?: "Commit failed") }
    }
}

class GitHubPushTool : Tool {
    override val spec = ToolSpec("github_push",
        "Push the current branch to GitHub. Pushing to main/master and force-pushing always ask the user first and are never remembered. Refused when the diff contains a secret.",
        schema { str("repo_dir", "Cloned repo path"); str("repo", "owner/repo (used when repo_dir is not given)"); str("branch", "Branch to push (default: current branch)"); bool("force", "Force-push with --force-with-lease (default false)") })
    override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "GitHub push", args.s("repo").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val store = githubStore()
        return try {
            requireLinux(); requireToken(store)
            val dir = repoDirOf(args); checkRepoDir(dir)
            val slug = dir.removePrefix("/workspace/repos/")
            val branch = args.s("branch")?.takeIf { it.isNotBlank() }
            val force = args.b("force") == true
            // The gate already asked; re-check here because a secret can land after approval.
            // Diff first (what the push would send), then the whole working tree — a secret
            // committed earlier must not go out either.
            val diff = shellOut(GitCommands.diff(dir)).second
            val hostDir = File(ctx.workspace.root, dir.removePrefix("/workspace/").trimStart('/'))
            val treeFindings = SecretScanner.scanDir(hostDir)
            if (GitCommands.diffHasSecret(diff) || treeFindings.isNotEmpty()) {
                audit(store, slug, branch.orEmpty(), "push", "refused", "secret in diff")
                val kinds = (GitCommands.secretKindsIn(diff) + treeFindings.map { it.kind }).distinct()
                val files = treeFindings.take(5).joinToString(", ") { it.file }
                return err("Push refused: a secret was found (${kinds.joinToString(", ")}${if (files.isNotBlank()) " in $files" else ""}). Remove the secret first — keys belong in Axion's settings, never in a repo.")
            }
            val (code, out) = withGitAuth(ctx, store) { shellOut(GitCommands.push(dir, branch, force), 300) }
            audit(store, slug, branch.orEmpty(), if (force) "force-push" else "push", if (code == 0) "ok" else "failed", out.take(200))
            if (code != 0) err("Push failed (exit $code):\n$out") else ok("Pushed $slug${branch?.let { " ($it)" } ?: ""}.\n$out", "Pushed")
        } catch (e: Exception) { err(e.message ?: "Push failed") }
    }
}

class GitHubPrTool : Tool {
    override val spec = ToolSpec("github_pr",
        "Open a pull request, list its comments, or merge it (merge always asks first). Uses the GitHub API on the connected token.",
        schema { str("repo", "owner/repo"); str("action", "open, comments, or merge"); str("title", "PR title (open)"); str("body", "PR description (open)"); str("head", "Source branch (open)"); str("base", "Target branch (open, default main)"); int("number", "PR number (comments, merge)") })
    override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "GitHub PR", args.s("repo").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val store = githubStore()
        return try {
            val token = requireToken(store)
            val api = GitHubApi(token = { token })
            val slug = repoSlugOf(args)
            val (owner, name) = GitHubRepo.parse(slug) ?: return err("That repo doesn't look like owner/repo: $slug")
            when (args.s("action")?.lowercase() ?: "open") {
                "open" -> {
                    val title = args.s("title")?.trim().orEmpty()
                    val head = args.s("head")?.trim().orEmpty()
                    if (title.isBlank() || head.isBlank()) return err("To open a PR, give a title and the source branch (head).")
                    val url = api.createPr(owner, name, title, args.s("body").orEmpty(), head, args.s("base")?.takeIf { it.isNotBlank() } ?: "main")
                    audit(store, slug, head, "open PR", "ok", url)
                    ok("Pull request opened: $url", "PR opened")
                }
                "comments" -> {
                    val number = args.i("number") ?: return err("Give the PR number to list comments for.")
                    val comments = api.prComments(owner, name, number)
                    audit(store, slug, "", "list PR comments", "ok")
                    ok(comments)
                }
                "merge" -> {
                    val number = args.i("number") ?: return err("Give the PR number to merge.")
                    val result = api.mergePr(owner, name, number)
                    audit(store, slug, "", "merge PR #$number", "ok", result)
                    ok(result, "PR merged")
                }
                else -> err("Unknown PR action (open, comments, merge).")
            }
        } catch (e: Exception) { err(e.message ?: "PR failed") }
    }
}

class GitHubCiTool : Tool {
    override val spec = ToolSpec("github_ci",
        "Read CI status (check runs) for a branch or commit. Read-only — runs without asking.",
        schema { str("repo", "owner/repo"); str("ref", "Branch or commit SHA (default main)") })
    override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "GitHub CI", args.s("repo").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val store = githubStore()
        return try {
            val token = requireToken(store)
            val api = GitHubApi(token = { token })
            val slug = repoSlugOf(args)
            val (owner, name) = GitHubRepo.parse(slug) ?: return err("That repo doesn't look like owner/repo: $slug")
            val ref = args.s("ref")?.takeIf { it.isNotBlank() } ?: "main"
            val checks = api.checks(owner, name, ref)
            audit(store, slug, ref, "read CI", "ok")
            ok("CI status for $slug ($ref):\n$checks")
        } catch (e: Exception) { err(e.message ?: "CI check failed") }
    }
}
