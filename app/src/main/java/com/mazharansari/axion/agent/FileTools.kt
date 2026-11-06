package com.mazharansari.axion.agent

import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class RunCommandTool : Tool {
    override val spec = ToolSpec(
        "run_command",
        "Run a shell command. When Linux is installed (Developer tools) this is bash in a private Ubuntu with whatever toolchains the user installed (python3, node, gcc, php, java…, apt-get works); otherwise Android sh with toybox. " +
            "The working directory is the workspace root (uploads/ and outputs/ live there). Output is returned; long output is cut.",
        schema { str("command", "The shell command"); int("timeout_sec", "Timeout in seconds (default 60, max 600)") },
    )

    override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "Bash", args.s("command").orEmpty())

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val cmd = args.s("command").orEmpty()
        if (cmd.isBlank()) return@withContext ToolOutput("Empty command", isError = true)
        val timeout = (args.i("timeout_sec") ?: 60).coerceIn(1, 600).toLong()
        // v0.4: inside the private Ubuntu (PRoot) when Linux is installed — real bash, Python, Node… whatever the user installed.
        val linux = com.mazharansari.axion.linux.LinuxHub.linux
        if (linux != null && linux.isInstalled() && ctx.workspace.root.canonicalPath == linux.workspaceHost.canonicalPath) {
            val (code, text) = try { linux.run(cmd, timeout * 1000) { line -> ctx.progress(line.takeLast(4000)) } }
                catch (e: CancellationException) { throw e } catch (e: Throwable) { return@withContext ToolOutput("Linux error: ${e.message}", isError = true) }
            val head = if (code == -1) "[timed out after ${timeout}s]\n" else if (code != 0) "[exit code $code]\n" else ""
            val body = text.ifEmpty { "(no output)" }
            // v0.6.1: the saved (displayed) copy keeps 20k of a long log, not just 4k
            return@withContext ToolOutput((head + body).clip(10000), display = (head + body).clip(20000), isError = code != 0, exitCode = code)
        }
        val sh = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
        val pb = ProcessBuilder(sh, "-c", cmd).directory(ctx.workspace.root).redirectErrorStream(true)
        pb.environment()["HOME"] = ctx.workspace.root.absolutePath
        pb.environment()["TMPDIR"] = File(ctx.workspace.root, ".tmp").apply { mkdirs() }.absolutePath
        val proc = runCatching { pb.start() }.getOrElse { return@withContext ToolOutput("Could not start shell: ${it.message}", isError = true) }
        val out = StringBuilder()
        val reader = launch {
            proc.inputStream.bufferedReader().use { br ->
                val buf = CharArray(4096)
                while (true) {
                    val n = br.read(buf); if (n < 0) break
                    synchronized(out) { if (out.length < 200_000) out.append(buf, 0, n) }
                    ctx.progress(synchronized(out) { out.takeLast(4000).toString() })
                }
            }
        }
        val finished = try {
            runInterruptible { proc.waitFor(timeout, TimeUnit.SECONDS) }
        } catch (e: CancellationException) { proc.destroyForcibly(); throw e }
        if (!finished) proc.destroyForcibly()
        withTimeoutOrNull(2000) { reader.join() }
        val code = if (finished) proc.exitValue() else -1
        val text = synchronized(out) { out.toString() }.ifEmpty { "(no output)" }
        val head = if (!finished) "[timed out after ${timeout}s]\n" else if (code != 0) "[exit code $code]\n" else ""
        ToolOutput((head + text).clip(10000), display = (head + text).clip(20000), isError = code != 0, exitCode = code)   // v0.6.1: display cap 4k -> 20k
    }
}

class ReadFileTool : Tool {
    override val spec = ToolSpec(
        "read_file",
        "Read a file from the workspace (text, code, CSV, JSON, HTML, .docx/.pptx/.xlsx text). Use offset/max_chars for big files.",
        schema { str("path", "Path relative to the workspace, e.g. uploads/report.docx"); int("offset", "Start character (default 0)"); int("max_chars", "Default 12000") },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.READ, "Read file", args.s("path").orEmpty().substringAfterLast('/'))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val f = runCatching { ctx.workspace.resolve(args.s("path").orEmpty()) }.getOrElse { return@withContext ToolOutput(it.message ?: "bad path", isError = true) }
        if (!f.exists()) return@withContext ToolOutput("No such file: ${args.s("path")}", isError = true)
        if (f.isDirectory) return@withContext ToolOutput("That is a folder. Use list_files.", isError = true)
        val text = DocText.extract(f)
        val off = (args.i("offset") ?: 0).coerceIn(0, maxOf(0, text.length))
        val max = (args.i("max_chars") ?: 12000).coerceIn(500, 60000)
        val slice = text.substring(off, minOf(text.length, off + max))
        val more = if (off + max < text.length) "\n…[${text.length - off - max} more characters; call again with offset=${off + max}]" else ""
        ToolOutput("${ctx.workspace.rel(f)} (${f.length()} bytes)\n$slice$more", display = slice.clip(2000))
    }
}

/** Plain-text extraction without heavy libraries (Office files are zipped XML). */
object DocText {
    fun extract(f: File): String {
        val name = f.name.lowercase()
        return runCatching {
            when {
                name.endsWith(".docx") -> zipXml(f) { it == "word/document.xml" }
                name.endsWith(".pptx") -> zipXml(f) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                name.endsWith(".xlsx") -> zipXml(f) { it == "xl/sharedStrings.xml" || it.startsWith("xl/worksheets/sheet") }
                isBinary(f) -> "[binary file, ${f.length()} bytes — not readable as text. Images and PDFs are sent to the model directly when attached.]"
                else -> f.readText()
            }
        }.getOrElse { "Could not read: ${it.message}" }
    }

    private fun zipXml(f: File, pick: (String) -> Boolean): String = ZipFile(f).use { z ->
        z.entries().toList().filter { pick(it.name) }.sortedBy { it.name }.joinToString("\n\n") { e ->
            z.getInputStream(e).bufferedReader().readText()
                .replace(Regex("</(w:p|a:p|row)>"), "\n").replace(Regex("<[^>]+>"), " ")
                .replace(Regex("[ \t]+"), " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
        }
    }

    private fun isBinary(f: File): Boolean {
        val head = f.inputStream().use { s -> ByteArray(minOf(4096L, f.length()).toInt()).also { s.read(it) } }
        return head.count { it == 0.toByte() } > 0
    }
}

class WriteFileTool : Tool {
    override val spec = ToolSpec(
        "write_file",
        "Create or overwrite a text file in the workspace (code, HTML, CSV, markdown…). Files in outputs/ are shown to the user as downloadable cards.",
        schema { str("path", "Path relative to the workspace, e.g. outputs/site/index.html"); str("content", "Full file content") },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.WRITE, "Wrote file", args.s("path").orEmpty().substringAfterLast('/'))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val f = runCatching { ctx.workspace.resolve(args.s("path").orEmpty()) }.getOrElse { return@withContext ToolOutput(it.message ?: "bad path", isError = true) }
        f.parentFile?.mkdirs(); f.writeText(args.s("content").orEmpty())
        val rel = ctx.workspace.rel(f)
        val artifact = if (rel.startsWith("outputs/")) com.mazharansari.axion.data.Artifact(f.name, Mime.of(f.name), f.absolutePath, f.length()) else null
        ToolOutput("Wrote ${f.length()} bytes to $rel", artifact = artifact)
    }
}

class ListFilesTool : Tool {
    override val spec = ToolSpec("list_files", "List files and folders in the workspace (recursive, up to 300 entries).",
        schema { str("path", "Folder relative to the workspace (default: root)", required = false) })
    override fun label(args: JsonObject) = StepLabel(StepKind.READ, "Listed", args.s("path") ?: "workspace")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val dir = runCatching { ctx.workspace.resolve(args.s("path") ?: ".") }.getOrElse { return@withContext ToolOutput(it.message ?: "bad path", isError = true) }
        val lines = dir.walkTopDown().filter { it != dir && !it.name.startsWith(".") }.take(300)
            .map { (if (it.isDirectory) "[dir] " else "") + ctx.workspace.rel(it) + if (it.isFile) "  (${it.length()} B)" else "" }.toList()
        ToolOutput(lines.joinToString("\n").ifEmpty { "(empty)" })
    }
}

class ExtractZipTool : Tool {
    override val spec = ToolSpec("extract_zip", "Unpack a .zip (or .apk/.jar/.docx) from the workspace into a folder and list what is inside.",
        schema { str("path", "Zip path relative to the workspace"); str("dest", "Destination folder (default: next to the zip)", required = false) })
    override fun label(args: JsonObject) = StepLabel(StepKind.ZIP, "Extracted", args.s("path").orEmpty().substringAfterLast('/'))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val zf = runCatching { ctx.workspace.resolve(args.s("path").orEmpty()) }.getOrElse { return@withContext ToolOutput(it.message ?: "bad path", isError = true) }
        val dest = args.s("dest")?.let { ctx.workspace.resolve(it) } ?: File(zf.parentFile, zf.nameWithoutExtension)
        dest.mkdirs()
        var total = 0L; val names = mutableListOf<String>()
        runCatching {
            ZipFile(zf).use { z ->
                for (e in z.entries()) {
                    val out = File(dest, e.name).canonicalFile
                    if (!out.path.startsWith(dest.canonicalPath)) continue // zip-slip guard
                    if (e.isDirectory) { out.mkdirs(); continue }
                    total += e.size; if (total > 500L * 1024 * 1024 || names.size > 20000) break
                    out.parentFile?.mkdirs()
                    z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    names += e.name
                }
            }
        }.onFailure { return@withContext ToolOutput("Could not unzip: ${it.message}", isError = true) }
        val listing = names.take(400).joinToString("\n") + if (names.size > 400) "\n…and ${names.size - 400} more" else ""
        ToolOutput("Extracted ${names.size} files (${total / 1024} KB) to ${ctx.workspace.rel(dest)}:\n$listing", display = listing.clip(2500))
    }
}

object Mime {
    fun of(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "zip" -> "application/zip"; "apk" -> "application/vnd.android.package-archive"; "html", "htm" -> "text/html"
        "md" -> "text/markdown"; "txt", "log" -> "text/plain"; "csv" -> "text/csv"; "json" -> "application/json"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "mp3" -> "audio/mpeg"; "mp4" -> "video/mp4"; else -> "application/octet-stream"
    }
}
