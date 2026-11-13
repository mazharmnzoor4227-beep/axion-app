package com.mazharansari.axion.security

import java.io.File

/**
 * v0.5.2: finds API keys and tokens in text and in project files.
 *
 * Two checks:
 *  1. well-known key shapes (OpenAI/OpenRouter/Anthropic sk-…, Google AIza… and the new Gemini AQ.… auth keys,
 *     Groq gsk_…, Together tgp_…, GitHub ghp_/github_pat_…, Hugging Face hf_…,
 *     xAI, NVIDIA, Cerebras, Netlify, Slack, AWS, Google OAuth tokens, private-key blocks);
 *  2. the user's OWN saved secrets, matched literally — this catches tokens with no fixed shape (Vercel, Cloudflare,
 *     Mistral, a My-server key).
 * Used to: scrub tool output, chat history and errors before they are saved or shown; and block publishing / APK
 * builds when a project file contains a secret.
 */
object SecretScanner {
    data class Pattern(val kind: String, val regex: Regex)

    val patterns: List<Pattern> = listOf(
        Pattern("Anthropic key", Regex("""sk-ant-[A-Za-z0-9_\-]{20,}""")),
        Pattern("OpenRouter key", Regex("""sk-or-v1-[A-Za-z0-9]{20,}""")),
        Pattern("OpenAI-style key", Regex("""\bsk-(?:proj-|svcacct-|admin-)?[A-Za-z0-9_\-]{24,}""")),
        Pattern("sk_ API key", Regex("""\bsk_[a-f0-9]{40,}""")),
        Pattern("Google API key", Regex("""AIza[0-9A-Za-z_\-]{35}""")),
        // P0-14 (ME-2): since May 2026 Google AI Studio issues Gemini auth keys as "AQ." + 40–60
        // base64url chars (not AIza…); Together AI project keys are "tgp_v1_" + 43 base64url chars.
        Pattern("Gemini AQ. key", Regex("""\bAQ\.[A-Za-z0-9_\-]{40,}""")),
        Pattern("Together key", Regex("""\btgp_[A-Za-z0-9_\-]{20,}""")),
        Pattern("Google OAuth token", Regex("""\bya29\.[0-9A-Za-z_\-]{20,}""")),
        Pattern("Google refresh token", Regex("""\b1//0[0-9A-Za-z_\-]{30,}""")),
        Pattern("Groq key", Regex("""\bgsk_[A-Za-z0-9]{20,}""")),
        Pattern("GitHub token", Regex("""\bgh[pousr]_[A-Za-z0-9]{30,}""")),
        Pattern("GitHub token", Regex("""\bgithub_pat_[A-Za-z0-9_]{40,}""")),
        Pattern("Hugging Face token", Regex("""\bhf_[A-Za-z0-9]{30,}""")),
        Pattern("xAI key", Regex("""\bxai-[A-Za-z0-9]{40,}""")),
        Pattern("NVIDIA key", Regex("""\bnvapi-[A-Za-z0-9_\-]{30,}""")),
        Pattern("Cerebras key", Regex("""\bcsk-[a-z0-9]{30,}""")),
        Pattern("Netlify token", Regex("""\bnfp_[A-Za-z0-9]{30,}""")),
        Pattern("Slack token", Regex("""\bxox[abposr]-[A-Za-z0-9\-]{20,}""")),
        Pattern("AWS access key", Regex("""\bAKIA[0-9A-Z]{16}\b""")),
        Pattern("Private key", Regex("""-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----""")),
    )

    /** The user's saved secrets (keys, deploy tokens, MCP tokens). Set by the app; empty in tests unless set. */
    @Volatile var known: () -> Collection<String> = { emptyList() }

    data class Hit(val kind: String, val start: Int, val end: Int)

    fun find(text: String, known: Collection<String> = safeKnown()): List<Hit> {
        // P0-14 (ME-2): the old 16-char floor let short texts (e.g. a pasted key on its own) through
        // unscrubbed. Patterns need 20+ chars anyway; only a literal known secret can hit below that.
        if (text.length < 8) return emptyList()
        val hits = ArrayList<Hit>()
        for (k in known) {
            if (k.length < 8) continue   // P0-14: short secrets (>= 8 chars) are literal-matched too
            var i = text.indexOf(k)
            while (i >= 0) { hits += Hit("your saved key", i, i + k.length); i = text.indexOf(k, i + k.length) }
        }
        // AQ. and tgp_ prefixes belong to no ordinary text, so they match on shape alone —
        // the looksRandom gate (digits/letters mix) must not let a real key through.
        for (p in patterns) for (m in p.regex.findAll(text)) if (p.kind == "Private key" || p.kind == "Gemini AQ. key" || p.kind == "Together key" || looksRandom(m.value)) hits += Hit(p.kind, m.range.first, m.range.last + 1)
        // merge overlaps, keep the earliest kind
        return hits.sortedBy { it.start }.fold(ArrayList<Hit>()) { acc, h ->
            val last = acc.lastOrNull()
            if (last != null && h.start < last.end) acc[acc.size - 1] = last.copy(end = maxOf(last.end, h.end)) else acc += h
            acc
        }
    }

    /** Real keys are random: digits mixed with letters. "sk-button-primary-hover-large" (a CSS class) is not a key. */
    internal fun looksRandom(s: String): Boolean {
        val body = s.substringAfterLast('-').substringAfterLast('_').ifEmpty { s }
        val digits = s.count { it.isDigit() }
        return digits >= 2 && s.count { it.isLetter() } >= 4 && (body.length >= 16 || digits >= 4)
    }

    fun contains(text: String, known: Collection<String> = safeKnown()) = find(text, known).isNotEmpty()

    /** "gsk_abc…wxyz" → "gsk_••••wxyz". Keeps a short readable prefix and the last 4 so the user knows which key it was. */
    fun mask(secret: String): String {
        val prefix = Regex("""^(sk-ant-|sk-or-v1-|sk-proj-|sk-|sk_|AIza|AQ\.|gsk_|tgp_|ghp_|gho_|ghu_|ghs_|ghr_|github_pat_|hf_|xai-|nvapi-|csk-|nfp_|ya29\.|1//|AKIA|xox[a-z]-)""").find(secret)?.value.orEmpty()
        return if (secret.length <= 8) "••••" else prefix + "••••" + secret.takeLast(4)
    }

    /** Replaces every secret in [text] with its masked form. Cheap when there's nothing to replace. */
    fun scrub(text: String, known: Collection<String> = safeKnown()): String {
        if (text.isEmpty()) return text
        val hits = find(text, known)
        if (hits.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var at = 0
        for (h in hits) {
            sb.append(text, at, h.start)
            val s = text.substring(h.start, h.end)
            sb.append(if (s.startsWith("-----BEGIN")) "-----BEGIN [private key removed by Axion]-----" else mask(s))
            at = h.end
        }
        sb.append(text, at, text.length)
        return sb.toString()
    }

    private fun safeKnown(): Collection<String> = runCatching { known() }.getOrDefault(emptyList())

    // ── project scan (pre-publish, pre-APK) ─────────────────────────────────────────────────────────────────────────

    data class Finding(val file: String, val line: Int, val kind: String, val preview: String)

    /** Folders that are never published or packed into an APK (and are huge). */
    private val skipDirs = setOf("node_modules", ".git", ".gradle", "build", ".axion", ".cache", ".idea", ".next", ".vercel", ".netlify", ".wrangler")
    private val textExt = setOf(
        "html", "htm", "js", "mjs", "cjs", "jsx", "ts", "tsx", "css", "scss", "json", "md", "txt", "xml", "yml", "yaml", "toml",
        "env", "properties", "gradle", "kts", "kt", "java", "py", "php", "rb", "go", "rs", "c", "cc", "cpp", "h", "hpp", "sh",
        "vue", "svelte", "astro", "ini", "cfg", "conf", "map", "csv", "svg", "webmanifest", "sql", "dart", "swift",
    )

    /**
     * Scans [root] (a project, or the exact folder being published) for secrets. Text files up to 2 MB; dot-env files
     * always (".env", ".env.local"); generated folders skipped unless [includeGenerated] (a site's dist/ IS what gets
     * published, so the caller passes that folder as [root]).
     */
    fun scanDir(root: File, known: Collection<String> = safeKnown(), maxFiles: Int = 20_000): List<Finding> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<Finding>()
        var seen = 0
        root.walkTopDown()
            .onEnter { it == root || it.name !in skipDirs }
            .filter { it.isFile }
            .forEach { f ->
                if (++seen > maxFiles || out.size >= 50) return@forEach
                val ext = f.extension.lowercase()
                val isEnv = f.name == ".env" || f.name.startsWith(".env.")
                if (!isEnv && ext !in textExt) return@forEach
                if (f.length() > 2L * 1024 * 1024) return@forEach
                val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
                for (h in find(text, known)) {
                    val line = text.substring(0, h.start).count { it == '\n' } + 1
                    out += Finding(f.relativeTo(root).path.replace('\\', '/'), line, h.kind, mask(text.substring(h.start, h.end)))
                    if (out.size >= 50) break
                }
            }
        return out
    }

    /** Before a file leaves Axion (share, open in another app, save to Downloads): the user-facing reason to stop, or null. */
    fun leaveCheck(f: File, known: Collection<String> = safeKnown()): String? {
        if (!f.isFile || f.length() > 2L * 1024 * 1024) return null
        val ext = f.extension.lowercase()
        if (ext !in textExt && !(f.name == ".env" || f.name.startsWith(".env."))) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val h = find(text, known).firstOrNull() ?: return null
        val line = text.substring(0, h.start).count { it == '\n' } + 1
        return "Not shared: ${f.name} has a secret key in it (line $line: ${mask(text.substring(h.start, h.end))}). Remove it first."
    }

    class SecretFoundException(val findings: List<Finding>, what: String) : Exception(
        "$what stopped: a secret key was found in your project, and publishing it would make it public.\n" +
            findings.take(5).joinToString("\n") { "• ${it.file}, line ${it.line}: ${it.kind} (${it.preview})" } +
            (if (findings.size > 5) "\n…and ${findings.size - 5} more." else "") +
            "\nRemove the key from these files (or ask the Build agent to), then try again. Keys belong in Axion's settings, never in project files."
    )

    fun requireClean(root: File, what: String, known: Collection<String> = safeKnown()) {
        val f = scanDir(root, known)
        if (f.isNotEmpty()) throw SecretFoundException(f, what)
    }
}
