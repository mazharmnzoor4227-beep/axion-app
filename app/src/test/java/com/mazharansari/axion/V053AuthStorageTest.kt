package com.mazharansari.axion

import com.mazharansari.axion.auth.*
import com.mazharansari.axion.linux.LinuxSlim
import com.mazharansari.axion.linux.StorageScan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v0.5.3: sign-in error wording, input checks, the guest/fake account, storage sizes and the Linux slimming. Pure JVM. */
class V053AuthStorageTest {
    @Test fun githubDisabledIsFriendly() {
        val m = AuthErrors.friendly("ERROR_OPERATION_NOT_ALLOWED", "The given sign-in provider is disabled for this Firebase project. Enable it in the Firebase console, under the sign-in method tab of the Auth section.", "com.google.firebase.auth.FirebaseAuthException", AuthProvider.GITHUB)
        assertTrue(m, m.startsWith("GitHub sign-in isn't switched on"))
        assertFalse(m.contains("Firebase console"))
        // same text without a code (web flow) still maps
        assertTrue(AuthErrors.friendly(null, "This operation is not allowed. You must enable this service in the console.", null, AuthProvider.GITHUB).startsWith("GitHub sign-in isn't switched on"))
    }
    @Test fun githubMisconfiguredSecret() {
        assertTrue(AuthErrors.friendly("ERROR_INVALID_CREDENTIAL", "Remote site 5XX from github.com for CODE_EXCHANGE: invalid_client", null, AuthProvider.GITHUB).contains("set up wrong"))
    }
    @Test fun emailErrors() {
        assertEquals("Email or password is wrong.", AuthErrors.friendly("ERROR_INVALID_CREDENTIAL", "The supplied auth credential is incorrect, malformed or has expired.", null, AuthProvider.EMAIL))
        assertEquals("Email or password is wrong.", AuthErrors.friendly("ERROR_WRONG_PASSWORD", "x", null, AuthProvider.EMAIL))
        assertTrue(AuthErrors.friendly("ERROR_EMAIL_ALREADY_IN_USE", "x", null, AuthProvider.EMAIL).startsWith("Couldn't create the account"))
        assertTrue(AuthErrors.friendly("ERROR_WEAK_PASSWORD", "x", null, AuthProvider.EMAIL).contains("8 characters"))
        assertTrue(AuthErrors.friendly("ERROR_TOO_MANY_REQUESTS", "x", null, AuthProvider.EMAIL).startsWith("Too many tries"))
        assertTrue(AuthErrors.friendly("ERROR_NETWORK_REQUEST_FAILED", "A network error (such as timeout) has occurred.", "com.google.firebase.FirebaseNetworkException", AuthProvider.EMAIL).startsWith("No internet"))
        assertTrue(AuthErrors.friendly("ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL", "x", null, AuthProvider.GITHUB).contains("different sign-in"))
    }
    @Test fun googleErrors() {
        assertTrue(AuthErrors.friendly(null, "no credentials available", "androidx.credentials.exceptions.NoCredentialException", AuthProvider.GOOGLE).startsWith("No Google account"))
        assertTrue(AuthErrors.friendly(null, "[28444] Developer console is not set up correctly.", "androidx.credentials.exceptions.GetCredentialCustomException", AuthProvider.GOOGLE).contains("isn't set up"))
        assertTrue(AuthErrors.friendly(null, "boom", null, AuthProvider.GOOGLE).startsWith("Sign-in failed"))
    }
    @Test fun inputChecks() {
        assertEquals("Enter your email.", AuthErrors.checkEmail(" "))
        assertNotNull(AuthErrors.checkEmail("mazhar@"))
        assertNull(AuthErrors.checkEmail("mazhar@gmail.com"))
        assertNull(AuthErrors.checkPassword("12345"))                       // sign-in: older accounts may have short passwords
        assertEquals("Password must be at least 8 characters.", AuthErrors.checkPassword("Ab1!x", "Ab1!x"))
        assertEquals("Password is too easy to guess. Mix letters, numbers or symbols.", AuthErrors.checkPassword("12345678", "12345678"))
        assertEquals("Passwords don't match.", AuthErrors.checkPassword("Axion2026!", "Axion2026?"))
        assertNull(AuthErrors.checkPassword("Axion2026!", "Axion2026!"))
    }
    @Test fun passwordStrength() {
        assertEquals(0, PasswordStrength.score("Ab1!"))
        assertEquals(1, PasswordStrength.score("password"))
        assertEquals(1, PasswordStrength.score("aaaaaaaa"))
        assertTrue(PasswordStrength.score("axion2026") >= 2)
        assertTrue(PasswordStrength.score("Axion-Lahore-2026!") == 4)
        assertEquals("Strong", PasswordStrength.label(4))
    }
    @Test fun noAccountEnumeration() {
        val a = AuthErrors.friendly("ERROR_USER_NOT_FOUND", "There is no user record corresponding to this identifier.", null, AuthProvider.EMAIL)
        val b = AuthErrors.friendly("ERROR_WRONG_PASSWORD", "The password is invalid", null, AuthProvider.EMAIL)
        assertEquals(a, b)
        assertFalse(AuthErrors.friendly("ERROR_EMAIL_ALREADY_IN_USE", "x", null, AuthProvider.EMAIL).contains("already exists"))
        assertTrue(AuthErrors.RESET_SENT.startsWith("If an account exists"))
    }
    @Test fun emailSendLimits() {
        var t = 1_000_000L
        val store = HashMap<String, String>()
        val l = AuthLimits({ t }, store)
        repeat(3) { assertEquals(0, l.emailWait("reset", "M@x.com")); l.emailSent("reset", "m@x.com ") }
        val w = l.emailWait("reset", "m@x.com")
        assertTrue(w in 1..AuthLimits.HOUR)
        assertEquals(0, l.emailsLeft("reset", "m@x.com"))
        assertEquals(0, l.emailWait("verify", "m@x.com"))          // a different kind is counted separately
        assertEquals(0, l.emailWait("reset", "other@x.com"))
        assertFalse(store.keys.any { it.contains("m@x.com") })      // emails stored only as hashes
        t += AuthLimits.HOUR + 1
        assertEquals(0, l.emailWait("reset", "m@x.com"))
        assertEquals("1:00", AuthLimits.clock(60_000)); assertEquals("0:07", AuthLimits.clock(6_200))
    }
    @Test fun wrongPasswordCooldown() {
        var t = 5_000_000L
        val l = AuthLimits({ t }, HashMap())
        repeat(4) { l.wrongPassword("a@b.com"); t += 1000 }
        assertEquals(0, l.signInWait("a@b.com"))
        l.wrongPassword("a@b.com")
        assertTrue(l.signInWait("a@b.com") in 1..AuthLimits.COOLDOWN)
        t += AuthLimits.COOLDOWN + 1
        assertEquals(0, l.signInWait("a@b.com"))
        repeat(5) { l.wrongPassword("c@d.com") }
        l.signedIn("c@d.com"); assertEquals(0, l.signInWait("c@d.com"))
    }
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
    @Test fun userNames() {
        assertEquals("Mazhar", AxionUser("u", "m@x.com", "Mazhar").shownName)
        assertEquals("mazhar.ansari", AxionUser("u", "mazhar.ansari@gmail.com", " ").shownName)
        assertEquals("GitHub user", AxionUser("u", null, null, provider = AuthProvider.GITHUB).shownName)
        assertEquals(AuthProvider.GITHUB, AuthProvider.fromFirebase("github.com"))
        assertEquals(AuthProvider.GOOGLE, AuthProvider.fromFirebase("google.com"))
        assertEquals(AuthProvider.EMAIL, AuthProvider.fromFirebase("password"))
    }
    @Test fun webClientIdIsAxionsOwn() {
        assertTrue(FirebaseAxionAuth.AXION_WEB_CLIENT_ID.startsWith("1071482495-"))   // axion-d5461, not Kodex's project
        assertFalse(FirebaseAxionAuth.AXION_WEB_CLIENT_ID.contains("249079131159"))
    }
    @Test fun fakeAuthFlow() = runTest {
        val a = FakeAuth()
        assertTrue(a.state.value is AuthState.SignedOut)
        val gh = a.signInWithGitHub(android.app.Activity())
        assertTrue(gh.isFailure); assertTrue(gh.exceptionOrNull()!!.message!!.startsWith("GitHub sign-in isn't switched on"))
        assertTrue(a.state.value is AuthState.SignedOut)
        a.signUpWithEmail("Mazhar", "m@x.com", "123456")
        assertEquals("Mazhar", a.state.value.user?.shownName)
        a.signOut(); assertNull(a.state.value.user)
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
    @Test fun privacyPolicyMentionsFirebase() {
        val all = com.mazharansari.axion.ui.settings.PolicyText.privacy.sections.joinToString(" ") { it.second }
        assertTrue(all.contains("Google Firebase Authentication"))
        assertTrue(all.contains("chats and keys stay on this device"))
        val html = File(System.getProperty("user.dir"), "../../axion_privacy_policy.html").takeIf { it.exists() }?.readText()
        if (html != null) assertTrue(html.contains("Google Firebase Authentication"))
    }
}
