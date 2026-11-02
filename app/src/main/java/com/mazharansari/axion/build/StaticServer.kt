package com.mazharansari.axion.build

import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * In-app preview of a static site (index.html + assets) with no toolchain at all: a tiny HTTP/1.1 server on 127.0.0.1
 * that the WebView opens. Dev servers (npm run dev / vite) run inside Linux instead; Linux shares the phone's localhost.
 * Pure JVM — StaticServerTest starts it on this box and fetches pages.
 */
class StaticServer(private val root: File, private val spaFallback: Boolean = true) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "axion-preview").apply { isDaemon = true } }
    val port: Int get() = socket?.localPort ?: 0
    val url: String get() = "http://127.0.0.1:$port/"

    fun start(port: Int = 0): StaticServer {
        val s = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")); socket = s
        Thread({ while (!s.isClosed) { val c = runCatching { s.accept() }.getOrNull() ?: break; pool.execute { runCatching { handle(c) }; runCatching { c.close() } } } }, "axion-preview-accept")
            .apply { isDaemon = true; start() }
        return this
    }

    fun stop() { runCatching { socket?.close() }; pool.shutdownNow() }

    private fun handle(c: Socket) {
        c.soTimeout = 15_000
        val inp = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = inp.readLine() ?: return
        while (true) { val h = inp.readLine() ?: break; if (h.isEmpty()) break }
        val parts = request.split(' ')
        val method = parts.getOrNull(0) ?: "GET"
        val rawPath = parts.getOrNull(1)?.substringBefore('?')?.substringBefore('#') ?: "/"
        val out = BufferedOutputStream(c.getOutputStream())
        val path = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
        val f = resolve(path)
        if (f == null) { respond(out, 404, "text/html; charset=utf-8", "<h1>404</h1><p>$path not found</p>".toByteArray(), method); return }
        respond(out, 200, mime(f.name), f.readBytes(), method)
    }

    fun resolve(path: String): File? {
        val canonRoot = root.canonicalFile
        val f = File(canonRoot, path.trimStart('/')).canonicalFile
        if (!(f.path == canonRoot.path || f.path.startsWith(canonRoot.path + File.separator))) return null
        if (f.isFile) return f
        if (f.isDirectory && File(f, "index.html").isFile) return File(f, "index.html")
        if (File(f.path + ".html").isFile) return File(f.path + ".html")
        // Single-page apps: unknown routes without a file extension get index.html
        if (spaFallback && !path.substringAfterLast('/').contains('.') && File(canonRoot, "index.html").isFile) return File(canonRoot, "index.html")
        return null
    }

    private fun respond(out: BufferedOutputStream, code: Int, type: String, body: ByteArray, method: String) {
        val status = if (code == 200) "OK" else "Not Found"
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray())
        if (method != "HEAD") out.write(body)
        out.flush()
    }

    companion object {
        fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"; "css" -> "text/css; charset=utf-8"; "js", "mjs" -> "text/javascript; charset=utf-8"
            "json", "map", "webmanifest" -> "application/json"; "svg" -> "image/svg+xml"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"; "webp" -> "image/webp"; "ico" -> "image/x-icon"; "woff2" -> "font/woff2"; "woff" -> "font/woff"; "ttf" -> "font/ttf"
            "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"; "wasm" -> "application/wasm"; "txt", "md" -> "text/plain; charset=utf-8"; "xml" -> "application/xml"
            else -> "application/octet-stream"
        }
    }
}

/**
 * Fast checks of a static site Axion runs after every Build turn, on the phone itself (no Linux needed): broken local
 * links/scripts/images, missing viewport, empty page, and the folder that should be served/deployed.
 */
object SiteChecker {
    data class Report(val siteDir: File?, val problems: List<String>, val notes: List<String>)

    /** Where the site to serve/deploy lives: a built dist/build/out with index.html, else the project root, else a single html. */
    fun siteDir(project: File): File? {
        for (d in listOf("dist", "build", "out", "public", "site", "docs")) {
            val f = File(project, d); if (File(f, "index.html").isFile && (d != "public" || !File(project, "package.json").isFile)) return f
        }
        if (File(project, "index.html").isFile) return project
        if (File(project, "package.json").isFile) return null   // needs a build first
        return project.listFiles()?.firstOrNull { it.isFile && it.extension.equals("html", true) }?.let { project }
    }

    fun check(project: File): Report {
        val site = siteDir(project) ?: return Report(null, emptyList(), listOf(if (File(project, "package.json").isFile) "Not built yet (no dist/ with index.html)." else "No index.html yet."))
        val problems = ArrayList<String>(); val notes = ArrayList<String>()
        val htmlFiles = site.walkTopDown().onEnter { it.name !in ProjectFiles.HIDDEN }.filter { it.isFile && it.extension.lowercase() in setOf("html", "htm") }.take(40).toList()
        for (h in htmlFiles) {
            val rel = h.relativeTo(site).path
            val doc = runCatching { org.jsoup.Jsoup.parse(h, "UTF-8") }.getOrNull() ?: continue
            val refs = doc.select("script[src], link[href], img[src], source[src], video[src], audio[src], a[href]").map { e -> e.tagName() to (e.attr("src").ifBlank { e.attr("href") }) }
            for ((tag, ref) in refs) {
                if (ref.isBlank() || ref.startsWith("#") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(ref) || ref.startsWith("//") || ref.contains("{{") || ref.contains("\${")) continue
                val clean = ref.substringBefore('?').substringBefore('#')
                if (clean.isBlank()) continue
                val target = if (clean.startsWith("/")) File(site, clean.trimStart('/')) else File(h.parentFile, clean)
                val ok = target.isFile || (target.isDirectory && File(target, "index.html").isFile) || File(target.path + ".html").isFile
                if (!ok && !(tag == "a" && !clean.contains('.'))) problems += "$rel: <$tag> points to \"$ref\" but that file doesn't exist."
            }
            if (rel == "index.html") {
                if (doc.select("meta[name=viewport]").isEmpty()) problems += "index.html has no <meta name=\"viewport\"> — it will look tiny on phones."
                if (doc.title().isBlank()) notes += "index.html has no <title>."
                val bodyText = doc.body()?.text().orEmpty()
                if (bodyText.isBlank() && doc.select("script").isEmpty() && doc.select("img,svg,canvas").isEmpty()) problems += "index.html has an empty page body."
            }
        }
        // inline JS syntax smell: unbalanced braces in local .js files (cheap, catches truncated writes)
        site.walkTopDown().onEnter { it.name !in ProjectFiles.HIDDEN }.filter { it.isFile && it.extension == "js" && it.length() < 400_000 }.take(30).forEach { f ->
            val t = f.readText().replace(Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`|//[^\n]*|/\\*[\\s\\S]*?\\*/"), "")
            val diff = t.count { it == '{' } - t.count { it == '}' }
            if (kotlin.math.abs(diff) >= 1 && !f.name.endsWith(".min.js")) problems += "${f.relativeTo(site).path}: braces don't balance ($diff) — the file may be cut off."
        }
        return Report(site, problems.distinct().take(25), notes)
    }
}
