package com.mazharansari.axion.build

import java.io.File

/** File tree + text read/write for a project folder (the Files tab and the editor). Pure JVM. */
object ProjectFiles {
    /** Folders the tree hides (huge, generated) — still on disk, still used by the build. */
    val HIDDEN = setOf("node_modules", ".git", ".gradle", ".axion", "__pycache__", ".venv", "venv", ".next", ".cache", ".idea", ".kotlin")
    /** Shown collapsed, never auto-expanded. */
    val GENERATED = setOf("dist", "build", "out", ".vercel", ".netlify", ".wrangler")
    const val MAX_EDIT_BYTES = 512 * 1024

    data class Node(val file: File, val rel: String, val depth: Int, val isDir: Boolean, val size: Long, val childCount: Int = 0)

    /** Flattened tree in display order (folders first, then files, A→Z). [expanded] = relative paths of open folders. */
    fun tree(root: File, expanded: Set<String>, limit: Int = 4000): List<Node> {
        val out = ArrayList<Node>()
        fun walk(dir: File, depth: Int) {
            val kids = dir.listFiles()?.filter { it.name !in HIDDEN && !(it.name.startsWith(".") && it.name != ".env.example" && it.name != ".gitignore") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (f in kids) {
                if (out.size >= limit) return
                val rel = f.relativeTo(root).path
                if (f.isDirectory) {
                    out += Node(f, rel, depth, true, 0, f.list()?.size ?: 0)
                    if (rel in expanded) walk(f, depth + 1)
                } else out += Node(f, rel, depth, false, f.length())
            }
        }
        walk(root, 0)
        return out
    }

    fun fileCount(root: File): Int = root.walkTopDown().onEnter { it.name !in HIDDEN }.count { it.isFile }

    fun isProbablyText(f: File): Boolean {
        if (!f.isFile) return false
        if (f.extension.lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "ico", "apk", "zip", "jar", "gz", "woff", "woff2", "ttf", "otf", "mp4", "mp3", "pdf", "so", "class", "dex", "gguf")) return false
        val head = f.inputStream().use { s -> ByteArray(minOf(4096L, f.length()).toInt()).also { s.read(it) } }
        return head.none { it == 0.toByte() }
    }

    fun read(f: File): String? = if (isProbablyText(f) && f.length() <= MAX_EDIT_BYTES) f.readText() else null

    /** Writes through a temp file so a crash never leaves half a file. */
    fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.axion-tmp"); tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    fun safeChild(root: File, rel: String): File {
        val c = File(root, rel.trimStart('/')).canonicalFile
        require(c.path == root.canonicalPath || c.path.startsWith(root.canonicalPath + File.separator)) { "Outside the project: $rel" }
        return c
    }

    fun languageOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "HTML"; "css" -> "CSS"; "js", "mjs", "cjs" -> "JavaScript"; "ts" -> "TypeScript"; "tsx", "jsx" -> "React"
        "json" -> "JSON"; "md" -> "Markdown"; "py" -> "Python"; "kt", "kts" -> "Kotlin"; "java" -> "Java"; "xml" -> "XML"
        "php" -> "PHP"; "c", "h" -> "C"; "cpp", "cc", "hpp" -> "C++"; "sh" -> "Shell"; "yml", "yaml" -> "YAML"; "svg" -> "SVG"
        else -> "Text"
    }

    /** Snapshot of every small text file (for "what did this turn change" diffs and Undo). */
    fun snapshot(root: File, maxFiles: Int = 600, maxBytes: Long = 200_000): Map<String, String> {
        val out = HashMap<String, String>()
        root.walkTopDown().onEnter { it == root || (it.name !in HIDDEN && it.name !in GENERATED) }.filter { it.isFile }.forEach { f ->
            if (out.size < maxFiles && f.length() <= maxBytes && isProbablyText(f)) runCatching { out[f.relativeTo(root).path] = f.readText() }
        }
        return out
    }

    /** Restores [snap]: files it had get their old text back; text files it didn't have (made since) are removed. */
    fun restore(root: File, snap: Map<String, String>): Int {
        var n = 0
        val now = snapshot(root)
        for ((rel, text) in snap) if (now[rel] != text) { write(File(root, rel), text); n++ }
        for (rel in now.keys - snap.keys) { File(root, rel).delete(); n++ }
        return n
    }
}
