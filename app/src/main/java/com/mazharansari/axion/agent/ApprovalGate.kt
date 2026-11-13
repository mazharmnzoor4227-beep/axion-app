package com.mazharansari.axion.agent

import kotlinx.serialization.json.JsonObject

/** What the user (or the headless default) decided about one gated action. */
enum class ApprovalDecision { ALLOW_ONCE, ALLOW_REMEMBER, DENY }

/**
 * One action that needs the user's OK before it runs.
 * [pattern] is the "kind" a remembered rule keys on ("" = can never be remembered).
 * [hard] = denylist hit: a fresh confirmation is ALWAYS required and remembering
 * is never offered, even if some remembered rule would otherwise match.
 */
data class GateRequest(
    val tool: String,
    /** The command itself, shown in the confirmation dialog. */
    val summary: String,
    val pattern: String,
    val hard: Boolean = false,
    val hardReason: String = "",
)

/** The surface that can actually ask the user. Null in headless runs (scheduled tasks). */
interface Approver {
    suspend fun decide(req: GateRequest): ApprovalDecision
}

/**
 * v0.12: the approval gate for shell work — the one surface in Axion that can do
 * anything (delete, install, send) without a narrower tool's own limits.
 *
 * Rules, in order:
 *  1. DENYLIST (hard): bank/payment wording and irreversibly destructive shell
 *     patterns ALWAYS need a fresh confirmation and can never be remembered.
 *  2. Plainly read-only commands (ls, cat, grep … with no chaining or pipes into
 *     other programs) run without asking.
 *  3. Everything else in the shell tools asks once per "kind" of command
 *     (program + subcommand, e.g. "git status"); the user can allow once, deny,
 *     or remember the kind in Settings → Approvals. In headless runs there is
 *     nobody to ask: remembered kinds run, everything else is refused with an
 *     explanation the model relays.
 */
object ApprovalGate {
    private val SHELL_TOOLS = setOf("run_command", "shell_session", "run_background")

    private val READ_ONLY = setOf(
        "ls", "cat", "pwd", "echo", "printf", "head", "tail", "grep", "egrep", "fgrep", "rg",
        "find", "wc", "du", "df", "stat", "file", "which", "type", "whoami", "id", "date",
        "env", "printenv", "tree", "uname", "hostname", "sort", "uniq", "cut", "diff",
        "md5sum", "sha256sum", "basename", "dirname", "realpath", "readlink", "true", "false",
        "jq", "yq", "ps", "free", "uptime", "lsblk", "getprop",
    )

    /** Returns a gate request for this tool call, or null when it may run freely. */
    fun requestFor(tool: String, args: JsonObject): GateRequest? {
        // P1-1 (§6.2): the GitHub tools gate here, not in the tool itself — so the
        // agent's normal approval dialog (and remembered rules) apply, and the
        // always-ask actions (default-branch push, force-push, merge…) can never
        // be remembered. P1-2 adds the mcp_ tools the same way.
        if (tool.startsWith("github_")) {
            val map = args.entries.associate { (k, v) -> k to (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            val action = com.mazharansari.axion.github.GitHubGate.actionFor(
                tool, map,
                force = args.b("force") == true,
                branch = args.s("branch"),
            ) ?: return null
            if (action in com.mazharansari.axion.github.GitHubGate.READ_ONLY) return null
            val hard = action in com.mazharansari.axion.github.GitHubGate.ALWAYS_ASK
            val label = args.s("repo") ?: args.s("repo_dir") ?: tool
            return GateRequest(
                tool = tool,
                summary = "GitHub ${action.name.lowercase().replace('_', ' ')}: $label",
                pattern = if (hard) "" else "github ${action.name.lowercase()}",
                hard = hard,
                hardReason = if (hard) com.mazharansari.axion.github.GitHubGate.hardReason(action) else "",
            )
        }
        // P1-2 (§6.3): writing MCP calls (MCP_WRITE) gate here too; read-only MCP calls return null inside McpGate.
        if (tool.startsWith("mcp_")) return com.mazharansari.axion.mcp.McpGate.requestFor(tool, args)
        if (tool !in SHELL_TOOLS) return null
        val cmd = args.s("command")?.trim().orEmpty()
        if (cmd.isBlank()) return null
        // P1-1 (§6.2): shell git gets the same hard rules — a remembered "git push"
        // must never silently push to main/master or force-push.
        com.mazharansari.axion.github.GitHubGate.hardReasonForShellCommand(cmd)?.let {
            return GateRequest(tool, cmd, pattern = "", hard = true, hardReason = it)
        }
        hardReason(cmd)?.let { return GateRequest(tool, cmd, pattern = "", hard = true, hardReason = it) }
        if (isReadOnly(cmd)) return null
        return GateRequest(tool, cmd, pattern = patternOf(cmd))
    }

    /**
     * The command "kind": leading env assignments and sudo skipped, then program +
     * flags + the first real argument. A bare second word is a subcommand ("git status",
     * "npm run"); anything else is the actual target of the command, and the remembered
     * rule must name it — P0-11 (ME-17): "python3 build.py" must not also allow
     * "python3 other.py", and "rm -rf /tmp/x" must not also allow "rm -rf /".
     * (Read-only programs such as "cat notes.txt" now also key on their file; they are
     * never gated, so that changes nothing about what runs without asking.)
     */
    fun patternOf(command: String): String {
        val first = command.lineSequence().firstOrNull().orEmpty()
        val toks = first.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (toks.isEmpty()) return ""
        var i = 0
        while (i < toks.size && (toks[i].contains('=') || toks[i] == "sudo")) i++
        if (i >= toks.size) i = 0
        val program = toks[i].substringAfterLast('/')
        val rest = toks.drop(i + 1)
        val flags = rest.takeWhile { it.startsWith("-") && it != "-" }
        val firstArg = rest.drop(flags.size).firstOrNull()
        val sub = firstArg?.takeIf { t ->
            !t.startsWith("-") && '/' !in t && '.' !in t && ':' !in t && '=' !in t
        }
        return when {
            sub != null && flags.isEmpty() -> "$program $sub"
            firstArg != null -> (listOf(program) + flags + firstArg).joinToString(" ")
            flags.isNotEmpty() -> (listOf(program) + flags).joinToString(" ")
            else -> program
        }
    }

    private fun isReadOnly(cmd: String): Boolean {
        // any chaining / piping / substitution / redirection can hide a second program — gate it
        if (Regex("&&|\\|\\||;|\\||`|\\$\\(|>|<").containsMatchIn(cmd)) return false
        val program = patternOf(cmd).substringBefore(' ').substringAfterLast('/')
        return program in READ_ONLY
    }

    /** Denylist: returns WHY this always needs a fresh confirmation, or null. Checked before everything. */
    fun hardReason(command: String): String? {
        val c = " " + command.lowercase().replace(Regex("\\s+"), " ") + " "
        // money: never remembered, always a fresh human yes
        if (Regex("\\b(bank|banking|payment|pay now|paytm|easypaisa|jazzcash|raast|paypal|stripe|checkout|billing|credit card|debit card|send money|transfer money|wire transfer|upi)\\b").containsMatchIn(c))
            return "This mentions banking or a payment."
        // irreversibly destructive shell work — P0-11 (ME-17): the old regex required "/" to be
        // followed by a space, slash or end, so "rm -rf /*" (and "--recursive --force" spellings)
        // slipped through as a normal gate. Token-based: recursive + force flags aimed at a root.
        if (isDestructiveRm(command))
            return "This deletes files at the root of the phone's storage."
        if (Regex("\\bmkfs|\\bdd\\s+[^|]*of=/dev/|>\\s*/dev/block|\\bformat\\s+/dev/").containsMatchIn(c))
            return "This can format or overwrite a whole storage device."
        if (c.contains(":(){") || Regex("\\bshutdown\\b|\\breboot\\b|\\bpoweroff\\b").containsMatchIn(c))
            return "This can crash or switch off the phone."
        return null
    }

    /**
     * P0-11 (ME-17): `rm` with recursive + force flags aimed at a root of the phone's
     * storage. Every chained segment is checked, so `echo hi; rm -rf /*` is caught too.
     * Short flags may be combined (`-rf`, `-fr`, `-rfv`) or separate (`-r -f`); long flags
     * (`--recursive --force`) count the same. A root target is `/` (so `rm -rf / *` and
     * `rm -rf / --no-preserve-root` count — the bare `/` operand is the danger), `/*`,
     * `~`, `$HOME`, `/data`, `/system`, `/storage`, `/sdcard` or `/home`, or anything
     * directly under one of those roots. `rm -rf /tmp/x` is NOT a root: it stays a
     * normal gate with its own fine-grained remembered pattern.
     */
    private fun isDestructiveRm(command: String): Boolean {
        val roots = setOf("/", "/*", "/**", "~", "\$home", "/data", "/system", "/storage", "/sdcard", "/home")
        for (seg in command.split(Regex("&&|\\|\\||;|\\|"))) {
            val toks = seg.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (toks.isEmpty()) continue
            var i = 0
            while (i < toks.size && (toks[i].contains('=') || toks[i] == "sudo")) i++
            if (i >= toks.size || toks[i].substringAfterLast('/') != "rm") continue
            var recursive = false; var force = false; var j = i + 1
            while (j < toks.size && toks[j].startsWith("-") && toks[j] != "-") {
                val flag = toks[j]
                when {
                    flag == "--recursive" -> recursive = true
                    flag == "--force" -> force = true
                    flag.startsWith("--") -> { /* --no-preserve-root and friends: not r/f */ }
                    else -> { if ('r' in flag) recursive = true; if ('f' in flag) force = true }
                }
                j++
            }
            if (!recursive || !force) continue
            for (k in j until toks.size) {
                val op = toks[k]
                if (op.startsWith("-")) continue   // trailing flags, e.g. --no-preserve-root
                if (op in roots || op.startsWith("/*") || op.startsWith("~/") ||
                    op.startsWith("/data/") || op.startsWith("/system/") || op.startsWith("/storage/") ||
                    op.startsWith("/sdcard/") || op.startsWith("/home/")) return true
            }
        }
        return false
    }
}
