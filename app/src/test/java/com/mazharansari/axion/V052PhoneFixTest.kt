package com.mazharansari.axion

import androidx.test.core.app.ApplicationProvider
import com.mazharansari.axion.agent.*
import com.mazharansari.axion.data.*
import com.mazharansari.axion.linux.LinuxRuntime
import com.mazharansari.axion.linux.ToolStatus
import com.mazharansari.axion.llm.*
import com.mazharansari.axion.skills.SkillRepo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * v0.5.2 — bugs from mazhar's real phone (v0.5.1): Free (slow) "No connection… Network: Canceled", answers labelled "openai",
 * and Antigravity setup failing with "Could not install: ca-certificates curl git …" (reproduced: DNS broken inside PRoot).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AxionApp::class)
class V052PhoneFixTest {
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()
    private fun agent() = Agent(Workspace(File(app.filesDir, "workspace")), SkillRepo(app, File(app.filesDir, "skills")), firstContentMs = 300, stallMs = 300, thinkCapMs = 900)
    private class Rec : AgentSink {
        val models = mutableListOf<String>()
        override fun text(delta: String) {}
        override fun reasoning(delta: String) {}
        override fun textToThoughts() {}
        override fun step(step: Step) {}
        override fun artifact(a: Artifact) {}
        override fun model(label: String) { models += label }
        override fun switching(label: String) {}
        override fun discardRound() {}
    }

    /** The watchdog closing a slow call used to surface as the socket's "Canceled" → "No connection to the AI". */
    @Test fun watchdogCancelIsReportedAsSlowNotNetwork() = runBlocking {
        val socketSaysCanceled = object : LlmClient {
            override fun stream(req: LlmRequest) = flow<LlmEvent> {
                try { delay(60_000) } catch (e: CancellationException) { throw LlmException("Network: Canceled", FailKind.NETWORK) }
            }
        }
        val r = Rec()
        val e = runCatching { agent().run(emptyList(), UserItem("ek kahani sunao"), { listOf(Attempt(FreeFallback.NAME, FreeFallback.NAME, socketSaysCanceled)) }, r, intent = Intent.CHAT) }.exceptionOrNull()
        assertTrue("got $e", e is LlmException)
        e as LlmException
        assertNotEquals(FailKind.NETWORK, e.kind)
        assertTrue(e.message!!, e.message!!.startsWith("Free (slow) sent no answer"))
        assertEquals("answers are labelled Free (slow), not openai", listOf("Free (slow)"), r.models)
    }

    /** A tiny local HTTP/1.1 server (one response per connection) standing in for the free endpoint. */
    private class Srv(val s: java.net.ServerSocket) { fun stop(@Suppress("UNUSED_PARAMETER") x: Int) = s.close() }
    private fun server(handler: (Int) -> Pair<Int, String>): Pair<Srv, String> {
        val n = AtomicInteger()
        val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching {
                    c.use { sock ->
                        val inp = sock.getInputStream().buffered()
                        var len = 0
                        while (true) {
                            val line = StringBuilder(); while (true) { val ch = inp.read(); if (ch < 0 || ch == '\n'.code) break; if (ch != '\r'.code) line.append(ch.toChar()) }
                            if (line.isEmpty()) break
                            line.toString().let { l -> if (l.startsWith("Content-Length:", true)) len = l.substringAfter(':').trim().toInt() }
                        }
                        repeat(len) { inp.read() }
                        val (code, body) = handler(n.incrementAndGet())
                        val b = body.toByteArray()
                        val head = "HTTP/1.1 $code X\r\n" + (if (code == 429) "Retry-After: 1\r\nContent-Type: application/json\r\n" else "Content-Type: text/event-stream\r\n") +
                            "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n"
                        sock.getOutputStream().apply { write(head.toByteArray()); write(b); flush() }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return Srv(ss) to "http://127.0.0.1:${ss.localPort}/openai"
    }
    private val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Ek din\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    /** "busy" (429) is waited out with a visible countdown instead of failing the turn. */
    @Test fun freeModelWaitsOutBusyWithCountdown() = runBlocking {
        val (s, base) = server { i -> if (i == 1) 429 to "{\"error\":\"1 request per 45 seconds\"}" else 200 to sse }
        try {
            FreeFallback.lastAskAt = 0
            val ticks = mutableListOf<Int>()
            val ev = FreeFallback.Client(onWait = { ticks += it }, base = base).stream(LlmRequest("x", emptyList(), emptyList())).toList()
            assertEquals("Ek din", ev.filterIsInstance<LlmEvent.Text>().joinToString("") { it.delta })
            assertTrue("countdown $ticks", ticks.isNotEmpty() && ticks.first() > 0 && ticks.last() == 0)
        } finally { s.stop(0) }
    }

    /** Still busy after two waits → the friendly "free model is busy" line, never "No connection". */
    @Test fun freeModelBusyTooLongSaysBusy() = runBlocking {
        val (s, base) = server { 429 to "{\"error\":\"rate limited\"}" }
        try {
            FreeFallback.lastAskAt = 0
            val e = runCatching { FreeFallback.Client(base = base).stream(LlmRequest("x", emptyList(), emptyList())).toList() }.exceptionOrNull() as LlmException
            assertEquals(FreeFallback.BUSY, e.message); assertEquals(FailKind.RATE, e.kind)
        } finally { s.stop(0) }
    }

    /** A second message within 45 s waits for the free slot instead of sending a request that will be refused. */
    @Test fun freeModelSpacesItsRequests() {
        val now = 1_000_000L
        FreeFallback.lastAskAt = now - 15_000
        assertEquals(30_000L, FreeFallback.waitNeeded(now))
        FreeFallback.lastAskAt = now - 60_000
        assertEquals(0L, FreeFallback.waitNeeded(now))
    }

    @Test fun resolverHasNetworkDnsThenPublicOnes() {
        val t = LinuxRuntime.resolverText(listOf("2400:cb00::1", "10.10.0.1", "fe80::1%wlan0"))
        assertEquals(listOf("10.10.0.1", "8.8.8.8", "1.1.1.1"), t.lines().filter { it.startsWith("nameserver") }.map { it.substringAfter(' ') })
        assertTrue(t.contains("options timeout:3 attempts:2"))
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), LinuxRuntime.resolverText(emptyList()).lines().filter { it.startsWith("nameserver") }.map { it.substringAfter(' ') })
        assertEquals(listOf("2400:cb00::1", "8.8.8.8", "1.1.1.1"), LinuxRuntime.resolverText(listOf("2400:cb00::1")).lines().filter { it.startsWith("nameserver") }.map { it.substringAfter(' ') })
    }

    @Test fun hostsPinPackageServers() {
        val h = LinuxRuntime.hostsText(mapOf("ports.ubuntu.com" to "185.125.190.36", "mirror.sg.gs" to "163.47.176.15"))
        assertTrue(h.startsWith("127.0.0.1 localhost"))
        assertTrue(h.contains("185.125.190.36 ports.ubuntu.com")); assertTrue(h.contains("163.47.176.15 mirror.sg.gs"))
        assertTrue(LinuxRuntime.PIN_HOSTS.containsAll(listOf("ports.ubuntu.com", "mirror.sg.gs", "storage.googleapis.com")))
    }

    /** The install script itself: loud apt update, https mirrors with fallback, required vs optional packages. */
    @Test fun installScriptHandlesRealPhoneFailures() {
        val common = app.assets.open("devtools/common.sh").bufferedReader().readText()
        val core = app.assets.open("devtools/core.sh").bufferedReader().readText()
        assertTrue("apt update must fail on a failed download", common.contains("update --error-on=any"))
        assertTrue(common.contains("APT::Sandbox::User \"root\";")); assertTrue(common.contains("Acquire::Retries \"5\";"))
        assertTrue(common.contains("--force-confnew"))
        val mirrors = Regex("AXION_MIRRORS:-([^}]*)\\}").find(common)!!.groupValues[1].split(' ')
        assertEquals("https://ports.ubuntu.com/ubuntu-ports", mirrors.first()); assertTrue(mirrors.last().startsWith("http://"))
        assertTrue(mirrors.size >= 4 && mirrors.dropLast(1).all { it.startsWith("https://") })
        assertTrue("the list is marked fresh only after a real success", common.indexOf("touch /var/lib/apt/lists/.axion-updated") > common.indexOf("lists_ok &&"))
        val required = core.lines().first { it.contains("apt_install 10") }
        assertFalse(required.contains("procps")); assertFalse(required.contains("nano")); assertTrue(required.contains("ca-certificates curl git"))
        assertTrue(core.contains("apt_optional procps less nano"))
    }

    /** "View log" has the real output (scrubbed), kept on disk so it can be shared. */
    @Test fun installLogIsKeptScrubbedAndShareable() {
        val dt = app.graph.devtools
        val key = "gsk_" + "Ab3dEf6hIj9kLm2nOp5qRs8tUv1wXy4z" + "Qr7"
        val lines = (1..300).map { "line $it" } + listOf("E: Unable to locate package curl", "Authorization: Bearer $key", "ERROR: Could not install: curl git")
        dt.debugFail("agy", "Could not install: curl git", lines)
        val text = dt.logText("agy")
        assertEquals(200, text.lines().size)
        assertTrue(text.contains("E: Unable to locate package curl"))
        assertFalse("keys never reach the log", text.contains(key))
        assertTrue(dt.logFile("agy").readText().contains("line 1\n"))
        assertTrue(dt.status.value["agy"] is ToolStatus.Failed)
    }

    /** Antigravity installs without apt: the app unpacks the official archive and puts the binary in place itself. */
    @Test fun agyInstallsWithoutApt() {
        val work = File(app.cacheDir, "agytest").apply { deleteRecursively(); mkdirs() }
        // an archive shaped like Google's (one executable named "antigravity"), built with the same tar code the app uses
        val tgz = File(work, "agy.tgz")
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(java.util.zip.GZIPOutputStream(tgz.outputStream())).use { tar ->
            val data = "#!/bin/sh\necho 1.1.27\n".toByteArray()
            val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry("antigravity").apply { size = data.size.toLong(); mode = "100755".toInt(8) }
            tar.putArchiveEntry(e); tar.write(data); tar.closeArchiveEntry()
        }
        val x = File(work, "x").apply { mkdirs() }
        LinuxRuntime.extractTarGz(tgz, x)
        val bin = com.mazharansari.axion.linux.AgyCli.findBinary(x)!!
        assertEquals("antigravity", bin.name)
        val rootfs = File(work, "rootfs").apply { mkdirs() }
        com.mazharansari.axion.linux.AgyCli.installInto(rootfs, bin)
        val agy = File(rootfs, "root/.local/bin/agy")
        assertTrue(agy.exists() && agy.canExecute())
        assertEquals("1.1.27", File(rootfs, "root/.local/bin/.axion-agy-version").readText())
        assertTrue(File(rootfs, "root/.gemini/antigravity-cli").isDirectory)
        // the pinned build in the app matches the one the install script names (same URL + SHA-512)
        val script = app.assets.open("devtools/agy.sh").bufferedReader().readText()
        assertTrue(script.contains(com.mazharansari.axion.linux.AgyCli.URL_ARM64)); assertTrue(script.contains(com.mazharansari.axion.linux.AgyCli.SHA512_ARM64))
        assertEquals(128, com.mazharansari.axion.linux.AgyCli.SHA512_ARM64.length)
    }
}
