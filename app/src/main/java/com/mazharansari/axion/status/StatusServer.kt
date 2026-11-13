package com.mazharansari.axion.status

import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Read-only status dashboard: a tiny HTTP/1.1 server on 127.0.0.1 that shows
 * counts and short labels only (app version, chat/schedule/job counts, quota
 * lines, uptime). Read-only by design: GET only — no POST/PUT/DELETE and no
 * control surface of any kind. Localhost only, bound to 127.0.0.1 on an
 * ephemeral port. OFF by default: the coordinator owns start/stop from
 * Settings. No credentials are ever served: the snapshot has no secret,
 * token, key, file-path, chat-content or message-text field, and nothing
 * like that is ever added to the page or the JSON.
 *
 * Pure JVM — P2StatusServerTest starts it on this box and fetches pages.
 * Separate from [com.mazharansari.axion.build.StaticServer] on purpose, so
 * the preview server is untouched.
 */
data class StatusSnapshot(
    val appVersion: String,
    val chats: Int,
    val schedules: Int,
    val nextScheduleAt: Long?,
    val runningJobs: Int,
    val interruptedJobs: Int,
    val quotaLines: List<String>,
    val uptimeMs: Long,
)

/** Supplies the current snapshot; the coordinator wires a real one in. */
fun interface StatusProvider {
    fun snapshot(): StatusSnapshot
}

class StatusServer(private val provider: StatusProvider) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "axion-status").apply { isDaemon = true } }
    val port: Int get() = socket?.localPort ?: 0
    val url: String get() = "http://127.0.0.1:$port/"

    fun start(): StatusServer {
        if (socket?.isClosed == false) return this // already running — a second start must not leak a socket
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")); socket = s
        Thread({ while (!s.isClosed) { val c = runCatching { s.accept() }.getOrNull() ?: break; pool.execute { runCatching { handle(c) }; runCatching { c.close() } } } }, "axion-status-accept")
            .apply { isDaemon = true; start() }
        return this
    }

    fun stop() { runCatching { socket?.close() }; pool.shutdownNow() }

    private fun handle(c: Socket) {
        runCatching {
            c.soTimeout = 15_000
            val inp = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = inp.readLine() ?: return
            while (true) { val h = inp.readLine() ?: break; if (h.isEmpty()) break }
            val parts = request.split(' ')
            val method = parts.getOrNull(0) ?: "GET"
            val rawPath = parts.getOrNull(1)?.substringBefore('?')?.substringBefore('#') ?: "/"
            val path = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
            val out = BufferedOutputStream(c.getOutputStream())
            if (method != "GET") {
                respond(out, 405, "text/plain; charset=utf-8", "Method not allowed".toByteArray(Charsets.UTF_8), method)
                return
            }
            when (path) {
                "/" -> respond(out, 200, "text/html; charset=utf-8", statusPage(provider.snapshot()).toByteArray(Charsets.UTF_8), method)
                "/api/status" -> respond(out, 200, "application/json; charset=utf-8", statusJson(provider.snapshot()).toByteArray(Charsets.UTF_8), method)
                else -> respond(out, 404, "text/html; charset=utf-8", "<h1>404</h1><p>Not found</p>".toByteArray(Charsets.UTF_8), method)
            }
        }
    }

    private fun respond(out: BufferedOutputStream, code: Int, type: String, body: ByteArray, method: String) {
        val status = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "OK"
        }
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
        if (method != "HEAD") out.write(body)
        out.flush()
    }

    /** HTML status page: plain, English, no external resources, no JavaScript. */
    fun statusPage(s: StatusSnapshot): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>Axion status</title></head><body>")
        sb.append("<h1>Axion status</h1>")
        sb.append("<p>Read-only &mdash; this page cannot change anything.</p>")
        sb.append("<ul>")
        sb.append("<li>App version: ").append(escapeHtml(s.appVersion)).append("</li>")
        sb.append("<li>Chats: ").append(s.chats).append("</li>")
        sb.append("<li>Schedules: ").append(s.schedules).append("</li>")
        sb.append("<li>Next schedule: ").append(if (s.nextScheduleAt == null) "none" else escapeHtml(java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.ENGLISH).format(java.util.Date(s.nextScheduleAt)))).append("</li>")
        sb.append("<li>Running jobs: ").append(s.runningJobs).append("</li>")
        sb.append("<li>Interrupted jobs: ").append(s.interruptedJobs).append("</li>")
        sb.append("<li>Uptime (ms): ").append(s.uptimeMs).append("</li>")
        sb.append("</ul>")
        sb.append("<h2>Quota</h2><ul>")
        for (line in s.quotaLines) sb.append("<li>").append(escapeHtml(line)).append("</li>")
        sb.append("</ul>")
        sb.append("<p><a href=\"/api/status\">Status as JSON</a></p>")
        sb.append("</body></html>")
        return sb.toString()
    }

    /** JSON with exactly the snapshot fields, built by hand (no dependency). */
    fun statusJson(s: StatusSnapshot): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"appVersion\":").append(jsonString(s.appVersion)).append(',')
        sb.append("\"chats\":").append(s.chats).append(',')
        sb.append("\"schedules\":").append(s.schedules).append(',')
        sb.append("\"nextScheduleAt\":").append(s.nextScheduleAt?.toString() ?: "null").append(',')
        sb.append("\"runningJobs\":").append(s.runningJobs).append(',')
        sb.append("\"interruptedJobs\":").append(s.interruptedJobs).append(',')
        sb.append("\"quotaLines\":[")
        for ((i, line) in s.quotaLines.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(jsonString(line))
        }
        sb.append("],")
        sb.append("\"uptimeMs\":").append(s.uptimeMs)
        sb.append('}')
        return sb.toString()
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder().append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    companion object {
        /** Escapes every value printed into the HTML page. */
        fun escapeHtml(value: String): String {
            val sb = StringBuilder(value.length)
            for (ch in value) {
                when (ch) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '"' -> sb.append("&quot;")
                    '\'' -> sb.append("&#39;")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
