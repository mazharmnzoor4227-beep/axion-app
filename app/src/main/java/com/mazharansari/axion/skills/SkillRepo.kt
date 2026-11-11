package com.mazharansari.axion.skills

import android.content.Context
import com.mazharansari.axion.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.Request
import java.io.File

@Serializable
data class Skill(
    val name: String,
    val description: String,
    val source: String,          // "built-in" or the GitHub URL it came from
    val path: String,            // asset path or file path of SKILL.md
    val enabled: Boolean = true,
)

/** SKILL.md skills (Anthropic format: YAML front matter with name + description, then instructions). */
class SkillRepo(private val ctx: Context?, private val dir: File) {
    private val index = File(dir, "skills.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills

    init {
        dir.mkdirs()
        val installed = runCatching { json.decodeFromString<List<Skill>>(index.readText()) }.getOrDefault(emptyList())
        val names = runCatching { ctx?.assets?.list("skills")?.toList() }.getOrNull().orEmpty().ifEmpty { BUILT_IN }
        val builtIn = names.mapNotNull { n ->
            val text = runCatching { ctx!!.assets.open("skills/$n/SKILL.md").bufferedReader().readText() }.getOrNull() ?: return@mapNotNull null
            val (name, desc) = frontMatter(text, n)
            Skill(name, desc, "built-in", "asset:skills/$n/SKILL.md")
        }
        _skills.value = builtIn.filter { b -> installed.none { it.name == b.name } } + installed
    }

    fun body(name: String): String? {
        fun norm(x: String) = x.lowercase().replace(Regex("[^a-z0-9]"), "")
        val s = _skills.value.firstOrNull { it.name.equals(name, true) } ?: _skills.value.firstOrNull { norm(it.name) == norm(name) }
            ?: _skills.value.firstOrNull { norm(it.name).contains(norm(name)) || norm(name).contains(norm(it.name)) } ?: return null
        return if (s.path.startsWith("asset:")) ctx?.assets?.open(s.path.removePrefix("asset:"))?.bufferedReader()?.readText()
        else File(s.path).takeIf { it.exists() }?.readText()
    }

    fun setEnabled(name: String, on: Boolean) { _skills.value = _skills.value.map { if (it.name == name) it.copy(enabled = on) else it }; persist() }
    fun remove(name: String) { _skills.value = _skills.value.filterNot { it.name == name && it.source != "built-in" }; persist() }

    /**
     * v0.10 (Hermes learning loop): the agent writes its OWN skills with the skill_create /
     * skill_update tools. Learned skills live in the same folder format as imported ones, are
     * tagged source = [LEARNED] so the UI can badge them, and are the only skills the agent may
     * edit or delete — built-in and user-installed skills are never touched by the agent.
     * [fullText] is the complete SKILL.md (front matter included). Returns the saved skill;
     * throws with a plain message when the name clashes with a skill the agent does not own.
     */
    fun saveLearned(name: String, fullText: String, updating: Boolean): Skill {
        val text = fullText.replace("\r\n", "\n").trim()
        require(text.length >= 20) { "The skill is too short — write real instructions." }
        val (parsedName, desc) = frontMatter(text, name)
        val existing = _skills.value.firstOrNull { it.name.equals(parsedName, ignoreCase = true) }
        if (existing != null && existing.source != LEARNED)
            error("A skill named '${existing.name}' already exists and was not written by you (source: ${existing.source}). Pick a different name.")
        if (existing == null && updating) error("No learned skill named '$parsedName' — create it with skill_create first.")
        if (existing != null && !updating) error("You already wrote a skill named '$parsedName' — improve it with skill_update instead of creating a second one.")
        val folder = File(dir, safe(parsedName)).apply { mkdirs() }
        val file = File(folder, "SKILL.md"); file.writeText(text)
        val skill = Skill(parsedName, desc, LEARNED, file.absolutePath, enabled = existing?.enabled ?: true)
        _skills.value = _skills.value.filterNot { it.name.equals(parsedName, ignoreCase = true) } + skill
        persist()
        return skill
    }

    /** Deletes a skill ONLY when the agent itself wrote it (source = [LEARNED]). True when deleted. */
    fun deleteLearned(name: String): Boolean {
        val s = _skills.value.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
        if (s.source != LEARNED) return false
        _skills.value = _skills.value.filterNot { it.name == s.name }
        runCatching { File(s.path).parentFile?.deleteRecursively() }
        persist()
        return true
    }

    fun isLearned(name: String): Boolean = _skills.value.firstOrNull { it.name.equals(name, ignoreCase = true) }?.source == LEARNED
    private fun persist() = index.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Skill.serializer()), _skills.value.filter { it.source != "built-in" }))


    /**
     * v0.3: add skills from a file the user picks — a ZIP (one or many SKILL.md folders, every file in each folder kept),
     * a single SKILL.md / .md / .txt, or a PDF (its text becomes the skill's instructions).
     * Returns what was installed; throws with a plain message when nothing usable was inside.
     */
    suspend fun importFile(fileName: String, bytes: ByteArray): List<Skill> = withContext(Dispatchers.IO) {
        val lower = fileName.lowercase()
        val added = when {
            lower.endsWith(".zip") || (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) -> importZip(fileName, bytes)
            lower.endsWith(".pdf") || (bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF") -> listOf(importText(fileName.substringBeforeLast('.'), pdfText(bytes), "upload:$fileName"))
            else -> listOf(importText(fileName.substringBeforeLast('.').let { if (it.equals("SKILL", true)) "uploaded-skill" else it }, bytes.toString(Charsets.UTF_8), "upload:$fileName"))
        }
        if (added.isEmpty()) error("No skills found in $fileName")
        _skills.value = _skills.value.filterNot { s -> added.any { it.name == s.name } } + added
        persist(); added
    }

    private fun safe(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "skill" }

    private fun importText(fallbackName: String, rawText: String, source: String): Skill {
        val text = rawText.replace("\r\n", "\n").trim()
        if (text.length < 20) error("That file has no readable text")
        val hasFm = Regex("^---\\s*\\n.*?\\n---", RegexOption.DOT_MATCHES_ALL).containsMatchIn(text)
        val (name, desc) = frontMatter(text, fallbackName)
        val body = if (hasFm) text else "---\nname: $name\ndescription: ${desc.replace("\n", " ").take(200)}\n---\n\n$text"
        val folder = File(dir, safe(name)).apply { mkdirs() }
        val file = File(folder, "SKILL.md"); file.writeText(body)
        return Skill(name, desc, source, file.absolutePath)
    }

    private fun importZip(fileName: String, bytes: ByteArray): List<Skill> {
        val entries = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            var total = 0L
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name.replace('\\', '/').trimStart('/')
                if (n.contains("../") || n.startsWith("__MACOSX") || n.substringAfterLast('/').startsWith(".")) continue
                val data = z.readBytes(); total += data.size
                if (total > 60_000_000) error("ZIP is too big (over 60 MB unpacked)")
                entries[n] = data
            }
        }
        val skillFiles = entries.keys.filter { it.substringAfterLast('/').equals("SKILL.md", true) }
        if (skillFiles.isNotEmpty()) return skillFiles.map { sk ->
            val folderPrefix = sk.substringBeforeLast('/', "")
            val text = entries[sk]!!.toString(Charsets.UTF_8)
            val (name, desc) = frontMatter(text, folderPrefix.substringAfterLast('/').ifBlank { fileName.substringBeforeLast('.') })
            val out = File(dir, safe(name)).apply { deleteRecursively(); mkdirs() }
            entries.filterKeys { folderPrefix.isEmpty() || it.startsWith("$folderPrefix/") }.forEach { (k, v) ->
                val rel = if (folderPrefix.isEmpty()) k else k.removePrefix("$folderPrefix/")
                if (folderPrefix.isEmpty() && skillFiles.size > 1 && rel.contains('/')) return@forEach
                File(out, rel).apply { parentFile?.mkdirs(); writeBytes(v) }
            }
            File(out, "SKILL.md").writeText(text)
            Skill(name, desc, "upload:$fileName", File(out, "SKILL.md").absolutePath)
        }
        // No SKILL.md: every .md / .txt becomes its own skill.
        return entries.filterKeys { it.endsWith(".md", true) || it.endsWith(".txt", true) }.map { (k, v) ->
            importText(k.substringAfterLast('/').substringBeforeLast('.'), v.toString(Charsets.UTF_8), "upload:$fileName")
        }
    }

    private fun pdfText(bytes: ByteArray): String {
        ctx?.let { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(it) }
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes).use { doc ->
            val t = com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
            if (t.isBlank()) error("This PDF has no text layer (it's a scan) — export it as text or .md and upload that")
            return t
        }
    }

    data class Found(val name: String, val description: String, val repoPath: String, val rawUrl: String)

    /** Lists every SKILL.md in a GitHub repo (or a sub-folder URL) without installing. */
    suspend fun scanGitHub(url: String): List<Found> = withContext(Dispatchers.IO) {
        val m = Regex("github\\.com/([^/\\s]+)/([^/\\s#?]+)(?:/tree/([^/\\s]+)(/[^\\s#?]*)?)?").find(url) ?: error("Not a GitHub repo URL")
        val owner = m.groupValues[1]; val repo = m.groupValues[2].removeSuffix(".git")
        var branch = m.groupValues[3]
        val sub = m.groupValues[4].trim('/')
        if (branch.isEmpty()) branch = get("https://api.github.com/repos/$owner/$repo").let { Json.parseToJsonElement(it).jsonObject["default_branch"]?.jsonPrimitive?.content ?: "main" }
        val tree = Json.parseToJsonElement(get("https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1")).jsonObject["tree"]?.jsonArray.orEmpty()
        val paths = tree.mapNotNull { it.jsonObject["path"]?.jsonPrimitive?.content }
            .filter { it.endsWith("SKILL.md", ignoreCase = true) && (sub.isEmpty() || it.startsWith(sub)) }
            .take(200)
        paths.map { p ->
            val raw = "https://raw.githubusercontent.com/$owner/$repo/$branch/$p"
            val text = runCatching { get(raw) }.getOrDefault("")
            val (name, desc) = frontMatter(text, p.substringBeforeLast('/').substringAfterLast('/').ifEmpty { repo })
            Found(name, desc, p, raw)
        }
    }

    suspend fun install(found: List<Found>, sourceUrl: String): List<Skill> = withContext(Dispatchers.IO) {
        val added = found.mapNotNull { f ->
            val text = runCatching { get(f.rawUrl) }.getOrNull() ?: return@mapNotNull null
            val folder = File(dir, f.name.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { mkdirs() }
            val file = File(folder, "SKILL.md"); file.writeText(text)
            Skill(f.name, f.description, sourceUrl, file.absolutePath)
        }
        _skills.value = _skills.value.filterNot { s -> added.any { it.name == s.name } } + added
        persist(); added
    }

    private fun get(url: String): String {
        val r = Request.Builder().url(url).header("User-Agent", Http.UA).header("Accept", "application/vnd.github+json").build()
        Http.quick.newCall(r).execute().use { if (!it.isSuccessful) error("GitHub HTTP ${it.code}"); return it.body!!.string() }
    }

    companion object {
        /** Source tag for skills the agent wrote itself (Settings → Skills shows them as "Learned"). */
        const val LEARNED = "learned"
        val BUILT_IN = listOf("pdf", "website-audit", "deep-research", "roman-urdu", "video")
        fun frontMatter(text: String, fallback: String): Pair<String, String> {
            val fm = Regex("^---\\s*\\n(.*?)\\n---", RegexOption.DOT_MATCHES_ALL).find(text.trimStart())?.groupValues?.get(1).orEmpty()
            fun field(k: String) = Regex("^$k:\\s*(.+)$", RegexOption.MULTILINE).find(fm)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
            return (field("name") ?: fallback) to (field("description") ?: text.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("---") && !it.startsWith("#") }.orEmpty().take(200))
        }
    }
}
