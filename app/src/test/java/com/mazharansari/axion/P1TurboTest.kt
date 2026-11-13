package com.mazharansari.axion

import com.mazharansari.axion.data.AppSettings
import com.mazharansari.axion.linux.TurboAuth
import com.mazharansari.axion.linux.TurboCommands
import com.mazharansari.axion.linux.TurboSettings
import com.mazharansari.axion.linux.TurboStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator

/**
 * P1-3 Turbo (server): pure-logic tests. No SSH, no server, no KeyVault — the credential
 * round-trip is deliberately NOT tested here (KeyVault needs the Android Keystore or
 * Robolectric); the phone test in Settings proves the save/load path.
 */
class P1TurboTest {

    @Test fun turboIsOffByDefault() {
        assertFalse("the cloud sandbox must be OFF until the user turns it on", TurboSettings().enabled)
        assertFalse("the per-task composer toggle must also default off", AppSettings().turbo)
        assertEquals(22, TurboSettings().port)
        assertEquals("~/axion-workspace", TurboSettings().remoteWorkspace)
    }

    @Test fun emptySettingsFailValidation() {
        val problems = TurboSettings().validate()
        assertTrue(problems.any { it.contains("server address") })
        assertTrue(problems.any { it.contains("username") })
        assertTrue(problems.any { it.contains("password") })
    }

    @Test fun hostMustBeBare() {
        assertTrue(TurboSettings(host = "https://1.2.3.4", username = "u", passwordBlob = "gcm:x").validate().any { it.contains("http://") })
        assertTrue(TurboSettings(host = "1.2.3.4/path", username = "u", passwordBlob = "gcm:x").validate().any { it.contains("host name or IP") })
        assertTrue(TurboSettings(host = "1.2.3.4", port = 70000, username = "u", passwordBlob = "gcm:x").validate().any { it.contains("Port") })
        assertTrue(TurboSettings(host = "1.2.3.4", port = 0, username = "u", passwordBlob = "gcm:x").validate().any { it.contains("Port") })
    }

    @Test fun completeSettingsPassValidation() {
        val pw = TurboSettings(enabled = true, host = "130.210.34.84", port = 22, username = "ubuntu", auth = TurboAuth.PASSWORD, passwordBlob = "gcm:xxx")
        assertTrue(pw.validate().toString(), pw.validate().isEmpty())
        assertEquals("ubuntu@130.210.34.84:22", pw.hostLabel())
        val key = TurboSettings(enabled = true, host = "example.com", username = "ubuntu", auth = TurboAuth.KEY, keyBlob = "gcm:xxx")
        assertTrue(key.validate().isEmpty())
        // Key auth must not demand a password, password auth must not demand a key.
        assertTrue(TurboSettings(enabled = true, host = "h", username = "u", auth = TurboAuth.KEY).validate().any { it.contains("private key") })
    }

    @Test fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'plain'", TurboCommands.shellQuote("plain"))
        assertEquals("'it'\\''s'", TurboCommands.shellQuote("it's"))
        assertEquals("'a b\$c'", TurboCommands.shellQuote("a b\$c"))
    }

    @Test fun cdCommandQuotesTheDirectory() {
        val cmd = TurboCommands.cdCommand("/root/my dir", "npm run build")
        assertTrue(cmd.startsWith("cd '/root/my dir'"))
        assertTrue(cmd.contains("npm run build"))
    }

    @Test fun fingerprintIsStableOpenSshSha256() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val fp = TurboCommands.fingerprint(pair.public)
        assertTrue(fp, fp.startsWith("SHA256:"))
        assertFalse("OpenSSH base64 fingerprints carry no padding", fp.contains("="))
        assertEquals(fp, TurboCommands.fingerprint(pair.public))
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        assertNotEquals(fp, TurboCommands.fingerprint(other.public))
    }

    @Test fun settingsPersistWithoutCredentials() {
        val dir = Files.createTempDirectory("p1-3-turbo-").toFile()
        try {
            val file = File(dir, "turbo.json")
            val store = TurboStore(file)
            store.save(TurboSettings(enabled = true, host = "10.0.0.5", port = 2222, username = "ubuntu", auth = TurboAuth.PASSWORD))
            val reloaded = TurboStore(file).settings.value
            assertTrue(reloaded.enabled)
            assertEquals("10.0.0.5", reloaded.host)
            assertEquals(2222, reloaded.port)
            assertNull("no credential blob was saved, so none may appear", reloaded.passwordBlob)
        } finally {
            dir.deleteRecursively()
        }
    }
}
