package com.mazharansari.axion

import com.mazharansari.axion.linux.LinuxSlim
import com.mazharansari.axion.linux.StorageScan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v0.5.3: storage sizes and the Linux slimming; Live call protocol checks. Pure JVM. */
class V053AuthStorageTest {
    @Test fun liveLoudness() {
        assertEquals(10, com.mazharansari.axion.livecall.LiveLoudness.startVolume(3, 15))
        assertEquals(14, com.mazharansari.axion.livecall.LiveLoudness.startVolume(14, 15))
        assertTrue(com.mazharansari.axion.livecall.LiveLoudness.DEFAULT_BOOST_MB in 800..1000)
    }
    @Test fun liveAffectiveDialogSetup() {
        val p = com.mazharansari.axion.livecall.LiveCallProtocol
        assertEquals(p.MODEL, p.attemptOrder(p.MODEL)[0])                    // default: exactly the 0.5.2 order (affective is off)
        p.tryAffective = true
        val order = p.attemptOrder(p.MODEL)
        p.tryAffective = false
        assertTrue(p.isAffective(order[0])); assertTrue(order[0].contains("native-audio"))
        assertEquals(p.MODEL, order.first { !p.isAffective(it) })
        val js = p.buildSetupJson("Kore", model = order[0])
        assertTrue(js.contains("\"enableAffectiveDialog\":true") && !js.contains("proactiveAudio") && js.contains("models/gemini-2.5-flash-native-audio-latest"))
        assertFalse(js.contains("affective:"))
        val plain = p.buildSetupJson("Kore", model = p.MODEL)
        assertFalse(plain.contains("enableAffectiveDialog"))
        assertTrue(p.endpointFor("AQ.x", alpha = true).contains(".v1alpha.")); assertTrue(p.endpointFor("AQ.x").contains(".v1beta."))
        assertTrue(p.SYSTEM_INSTRUCTION.contains("match the user's mood"))
    }
    @Test fun storageLabels() {
        assertEquals("0 MB", StorageScan.label(0))
        assertEquals("820 KB", StorageScan.label(820L * 1024))
        assertEquals("7.9 MB", StorageScan.label((7.9 * 1048576).toLong()))
        assertEquals("512 MB", StorageScan.label(512L shl 20))
        assertEquals("1.5 GB", StorageScan.label(1536L shl 20))
        assertEquals(0, StorageScan.linuxBase(100, 80, 40))
        assertEquals(60, StorageScan.linuxBase(200, 100, 40))
    }
    @Test fun storageBytesDoesNotFollowLinks() {
        val d = Files.createTempDirectory("st").toFile()
        File(d, "a").writeBytes(ByteArray(1000)); File(d, "sub").mkdirs(); File(d, "sub/b").writeBytes(ByteArray(500))
        Files.createSymbolicLink(File(d, "loop").toPath(), d.toPath())          // like /usr/lib64 → / in a rootfs
        Files.createSymbolicLink(File(d, "big").toPath(), File("/usr").toPath())
        assertEquals(1500, StorageScan.bytes(d))
        assertEquals(0, StorageScan.bytes(File(d, "missing")))
        d.deleteRecursively()
    }
    @Test fun linuxSlimRemovesDocsOnceAndKeepsEnglish() {
        val r = Files.createTempDirectory("rootfs").toFile()
        listOf("usr/bin/bash", "usr/share/doc/curl/changelog.gz", "usr/share/doc/curl/copyright", "usr/share/man/man1/ls.1.gz", "usr/share/locale/de/LC_MESSAGES/x.mo",
            "usr/share/locale/en_GB/LC_MESSAGES/x.mo", "usr/share/locale/locale.alias", "usr/share/info/dir").forEach { File(r, it).apply { parentFile.mkdirs(); writeBytes(ByteArray(100)) } }
        Files.createSymbolicLink(File(r, "usr/share/doc/outside").toPath(), File("/usr/share").toPath())   // must not be followed
        val freed = LinuxSlim.apply(r)
        assertEquals(500, freed)
        assertTrue(File(r, "usr/bin/bash").exists())
        assertFalse(File(r, "usr/share/doc/curl").exists())
        assertTrue(File(r, "usr/share/doc").isDirectory)
        assertFalse(File(r, "usr/share/man/man1").exists())
        assertFalse(File(r, "usr/share/locale/de").exists())
        assertTrue(File(r, "usr/share/locale/en_GB/LC_MESSAGES/x.mo").exists())
        assertTrue(File(r, "usr/share/locale/locale.alias").exists())
        assertTrue(File("/usr/share").isDirectory)
        assertTrue(File(r, LinuxSlim.DPKG_CFG).readText().contains("path-exclude=/usr/share/doc/*"))
        // second run: nothing more to do
        File(r, "usr/share/doc/new/README").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals(0, LinuxSlim.apply(r))
        assertTrue(File(r, "usr/share/doc/new/README").exists())
        r.deleteRecursively()
    }
}
