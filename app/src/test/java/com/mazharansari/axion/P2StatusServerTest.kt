package com.mazharansari.axion

import com.mazharansari.axion.status.StatusProvider
import com.mazharansari.axion.status.StatusServer
import com.mazharansari.axion.status.StatusSnapshot
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

/**
 * P2-8 pure-JVM tests (no Robolectric, no Android framework, localhost only):
 * the read-only status server — HTML page, JSON endpoint, 405/404, and
 * escaping / no-secrets behaviour.
 */
class P2StatusServerTest {

    private val fakeSnapshot = StatusSnapshot(
        appVersion = "0.9.6",
        chats = 7,
        schedules = 3,
        nextScheduleAt = null,
        runningJobs = 1,
        interruptedJobs = 2,
        quotaLines = listOf("OpenRouter: 12 left today"),
        uptimeMs = 12345L,
    )

    private fun startServer(snapshot: StatusSnapshot = fakeSnapshot): StatusServer =
        StatusServer(StatusProvider { snapshot }).start()

    private fun fetch(server: StatusServer, path: String, method: String = "GET"): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        if (method == "POST") conn.doOutput = true
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
        conn.disconnect()
        return code to body
    }

    @Test fun getRootReturns200AndReadOnlyNote() {
        val server = startServer()
        try {
            val (code, body) = fetch(server, "/")
            assertEquals(200, code)
            assertTrue(body.contains("Read-only"))
            assertTrue(body.contains("this page cannot change anything"))
            assertTrue(body.contains("/api/status"))
            assertTrue(body.contains("7")) // chats count shown
        } finally {
            server.stop()
        }
    }

    @Test fun getApiStatusReturns200AndSnapshotJson() {
        val server = startServer()
        try {
            val (code, body) = fetch(server, "/api/status")
            assertEquals(200, code)
            assertTrue(body.startsWith("{") && body.endsWith("}"))
            assertTrue(body.contains("\"chats\":7"))
            assertTrue(body.contains("\"schedules\":3"))
            assertTrue(body.contains("\"appVersion\":\"0.9.6\""))
            assertTrue(body.contains("\"nextScheduleAt\":null"))
            assertTrue(body.contains("\"runningJobs\":1"))
            assertTrue(body.contains("\"interruptedJobs\":2"))
            assertTrue(body.contains("\"uptimeMs\":12345"))
        } finally {
            server.stop()
        }
    }

    @Test fun postApiStatusReturns405() {
        val server = startServer()
        try {
            val (code, _) = fetch(server, "/api/status", "POST")
            assertEquals(405, code)
        } finally {
            server.stop()
        }
    }

    @Test fun getUnknownPathReturns404() {
        val server = startServer()
        try {
            val (code, _) = fetch(server, "/nope")
            assertEquals(404, code)
        } finally {
            server.stop()
        }
    }

    @Test fun apiStatusServesQuotaLineAndNoSecretFieldNames() {
        val server = startServer()
        try {
            val (_, body) = fetch(server, "/api/status")
            assertTrue(body.contains("OpenRouter: 12 left today"))
            // The snapshot type has no secret field: no token/key field names in the JSON.
            assertFalse(body.contains("\"token\""))
            assertFalse(body.contains("\"key\""))
            assertFalse(body.contains("\"secret\""))
            assertFalse(body.contains("\"password\""))
        } finally {
            server.stop()
        }
    }

    @Test fun htmlEscapesScriptInQuotaLine() {
        val snapshot = fakeSnapshot.copy(quotaLines = listOf("<script>alert(1)</script>"))
        val server = startServer(snapshot)
        try {
            val (code, body) = fetch(server, "/")
            assertEquals(200, code)
            assertFalse(body.contains("<script>"))
            assertTrue(body.contains("&lt;script&gt;"))
        } finally {
            server.stop()
        }
    }
}
