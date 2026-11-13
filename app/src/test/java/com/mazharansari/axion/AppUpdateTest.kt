
package com.mazharansari.axion

import androidx.test.core.app.ApplicationProvider
import com.mazharansari.axion.update.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/** v0.5 in-app updates: feed rules + the check → download → verify pipeline against a local HTTP server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AxionApp::class)
class AppUpdateTest {
    private lateinit var srv: ServerSocket
    private val routes = HashMap<String, Pair<Int, ByteArray>>()
    private val base get() = "http://127.0.0.1:${srv.localPort}"
    private val apkBytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val ours = UpdateRules.CERT_SHA256

    @Before fun up() {
        srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!srv.isClosed) {
                val s = runCatching { srv.accept() }.getOrNull() ?: break
                Thread {
                    s.use { so ->
                        val r = so.getInputStream().bufferedReader(); val line = r.readLine() ?: return@use
                        while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break }
                        val path = line.split(" ")[1].substringBefore('?')
                        val (code, body) = routes[path] ?: (404 to "nope".toByteArray())
                        val os = so.getOutputStream()
                        os.write("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); os.write(body); os.flush()
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }
    }
    @After fun down() { srv.close() }

    private fun feed(code: Int, sha: String = UpdateRules.sha256(apkBytes), size: Long = apkBytes.size.toLong()) {
        feedAt("/latest.json", code, sha, size)
        routes["/a.apk"] = 200 to apkBytes
    }
    /** P0-2: a feed served at an arbitrary path, so two feeds can run on the one test server. */
    private fun feedAt(path: String, code: Int, sha: String = UpdateRules.sha256(apkBytes), size: Long = apkBytes.size.toLong()) {
        routes[path] = 200 to """{"versionCode":$code,"versionName":"0.$code.0","url":"https://example.invalid/a.apk","sha256":"$sha","size":$size,"notes":"New things"}""".toByteArray()
    }
    private fun updater(code: Int = 6, certs: List<String> = listOf(ours)) =
        updater(listOf("$base/latest.json"), code, certs)
    private fun updater(feeds: List<String>, code: Int = 6, certs: List<String> = listOf(ours)) =
        AppUpdater(ApplicationProvider.getApplicationContext(), feeds, currentCode = code, certReader = { certs })
    /** the feed's url must be https; the local test server is http, so point the info at it after parsing */
    private fun local(i: UpdateInfo) = i.copy(url = "$base/a.apk")

    @Test fun rules() {
        val i = UpdateRules.parse("""{"versionCode":6,"versionName":"0.5.0","url":"https://claworabeauty.store/uploads/dl/axion/Axion-0.5.0.apk","sha256":"${"AB".repeat(32)}","size":5,"notes":"x","extra":1}""")
        assertEquals("ab".repeat(32), i.sha256)
        assertTrue(UpdateRules.isNewer(i, 5)); assertFalse(UpdateRules.isNewer(i, 6))
        for (bad in listOf("not json", """{"versionCode":7,"versionName":"x","url":"http://a/b.apk","sha256":"${"a".repeat(64)}"}""", """{"versionCode":7,"versionName":"x","url":"https://a/b.apk","sha256":"abc"}"""))
            try { UpdateRules.parse(bad); fail("accepted $bad") } catch (_: java.io.IOException) {}
        assertTrue(UpdateRules.certOk(listOf(ours.uppercase())))
        assertFalse(UpdateRules.certOk(emptyList())); assertFalse(UpdateRules.certOk(listOf(ours, "00".repeat(32))))
        assertEquals("No internet connection. Try again when you're online.", UpdateRules.friendly(java.net.UnknownHostException("x")))
    }

    @Test fun alreadyLatest() = runBlocking { feed(6); assertTrue(updater(6).check() is UpdateState.UpToDate) }

    @Test fun downloadVerifyReady() = runBlocking {
        feed(7); val u = updater(6)
        val st = u.check(); assertTrue("$st", st is UpdateState.Available)
        val r = u.download(local((st as UpdateState.Available).info))
        assertTrue("$r", r is UpdateState.Ready); assertArrayEquals(apkBytes, (r as UpdateState.Ready).file.readBytes())
        // a second check finds the verified file and goes straight to Ready
        assertTrue(u.check() is UpdateState.Ready)
    }

    @Test fun badHashIsDeleted() = runBlocking {
        feed(7, sha = "0".repeat(64)); val u = updater(6)
        val info = local((u.check() as UpdateState.Available).info)
        val r = u.download(info)
        assertTrue("$r", r is UpdateState.Failed && r.message.contains("checksum"))
        assertTrue(File(ApplicationProvider.getApplicationContext<AxionApp>().cacheDir, "updates").listFiles().orEmpty().none { it.name.endsWith(".apk") })
    }

    @Test fun foreignSignatureRefused() = runBlocking {
        feed(7); val u = updater(6, certs = listOf("11".repeat(32)))
        val r = u.download(local((u.check() as UpdateState.Available).info))
        assertTrue("$r", r is UpdateState.Failed && r.message.contains("isn't signed with Axion's key"))
    }

    @Test fun serverErrorsAndOffline() = runBlocking {
        routes.clear()
        val st = updater(6).check(); assertTrue("$st", st is UpdateState.Failed && (st as UpdateState.Failed).message.contains("404"))
        val off = AppUpdater(ApplicationProvider.getApplicationContext(), listOf("https://no-such-host.invalid/latest.json"), currentCode = 6).check()
        assertTrue("$off", off is UpdateState.Failed && (off as UpdateState.Failed).message.startsWith("No internet") )
    }

    // ---- P0-2 (CR-2): both feeds, higher versionCode wins, one dead host is not fatal ----

    @Test fun defaultFeedsCoverBothHosts() {
        assertEquals(listOf("https://axion.130-210-62-94.sslip.io/dl/latest.json", "https://claworabeauty.store/uploads/dl/axion/latest.json"), UpdateRules.FEEDS)
    }

    @Test fun bothFeedsHigherVersionWins() = runBlocking {
        feedAt("/a/latest.json", 8); feedAt("/b/latest.json", 9)
        val st = updater(listOf("$base/a/latest.json", "$base/b/latest.json"), 6).check()
        assertTrue("$st", st is UpdateState.Available && (st as UpdateState.Available).info.versionCode == 9)
    }

    @Test fun oneFeedDownOtherStillOffersUpdate() = runBlocking {
        feedAt("/b/latest.json", 8)
        // first feed is a dead host (nothing listens on 127.0.0.1:1); the second feed must still win
        val st = updater(listOf("http://127.0.0.1:1/latest.json", "$base/b/latest.json"), 6).check()
        assertTrue("$st", st is UpdateState.Available && (st as UpdateState.Available).info.versionCode == 8)
    }

    @Test fun bothFeedsDownFails() = runBlocking {
        val st = updater(listOf("http://127.0.0.1:1/latest.json", "http://127.0.0.1:1/other.json"), 6).check()
        assertTrue("$st", st is UpdateState.Failed)
    }
}
