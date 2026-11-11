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
        if (tool !in SHELL_TOOLS) return null
        val cmd = args.s("command")?.trim().orEmpty()
        if (cmd.isBlank()) return null
        hardReason(cmd)?.let { return GateRequest(tool, cmd, pattern = "", hard = true, hardReason = it) }
        if (isReadOnly(cmd)) return null
        return GateRequest(tool, cmd, pattern = patternOf(cmd))
    }

    /**
     * The command "kind": leading env assignments and sudo skipped, then program +
     * subcommand when the second word really is one (no path, flag, dot or colon —
     * so "git status" and "npm run" are kinds, but "python3 build.py" keys on
     * "python3" and "cat notes.txt" on "cat").
     */
    fun patternOf(command: String): String {
        val first = command.lineSequence().firstOrNull().orEmpty()
        val toks = first.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (toks.isEmpty()) return ""
        var i = 0
        while (i < toks.size && (toks[i].contains('=') || toks[i] == "sudo")) i++
        if (i >= toks.size) i = 0
        val program = toks[i].substringAfterLast('/')
        val second = toks.getOrNull(i + 1)?.takeIf { t ->
            !t.startsWith("-") && '/' !in t && '.' !in t && ':' !in t && '=' !in t
        }
        return if (second != null) "$program $second" else program
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
        // irreversibly destructive shell work
        if (Regex("rm\\s+(-[a-z]*r[a-z]*f|-[a-z]*f[a-z]*r)\\s+(/|~|\\$home|/data|/system|/storage)(\\s|/|$)").containsMatchIn(c))
            return "This deletes files at the root of the phone's storage."
        if (Regex("\\bmkfs|\\bdd\\s+[^|]*of=/dev/|>\\s*/dev/block|\\bformat\\s+/dev/").containsMatchIn(c))
            return "This can format or overwrite a whole storage device."
        if (c.contains(":(){") || Regex("\\bshutdown\\b|\\breboot\\b|\\bpoweroff\\b").containsMatchIn(c))
            return "This can crash or switch off the phone."
        return null
    }
}
