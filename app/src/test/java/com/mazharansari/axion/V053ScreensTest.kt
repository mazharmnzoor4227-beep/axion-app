package com.mazharansari.axion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.mazharansari.axion.auth.*
import com.mazharansari.axion.linux.*
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** v0.5.3 renders at mazhar's screen (720×1600): sign-in screen, email form, Settings → Account, Storage. Flows run on a FakeAuth. */
@OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-280dpi", application = AxionApp::class)
class V053ScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val shots = File(System.getProperty("user.dir"), "build/screens_v053").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as MutableStateFlow<T>

    @After fun reset() { AuthHub.override = null; com.mazharansari.axion.ui.auth.Onboarding.forceInTests = false; com.mazharansari.axion.ui.auth.Onboarding.freezeIntroAt = null }

    private fun cap(name: String) {
        compose.mainClock.advanceTimeBy(1500)
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
        compose.onAllNodes(isRoot())[0].captureRoboImage(File(shots, "$name.png").path)
        for (i in 1 until roots) compose.onAllNodes(isRoot())[i].captureRoboImage(File(shots, "${name}__layer$i.png").path)
    }
    private fun seed(guest: Boolean, intro: Boolean = true, perms: Boolean = true) {
        File(app.filesDir, "settings.json").writeText("""{"theme":"dark","mode":"AUTO","buildIntroShown":true,"guestChosen":$guest,"introShown":$intro,"permsShown":$perms}""")
        val f: (com.mazharansari.axion.Graph) -> Unit = { g -> g.settings.update { it.copy(buildIntroShown = true, guestChosen = guest, userName = "", introShown = intro, permsShown = perms) } }
        f(app.graph); lastSeed = f
    }
    private var lastSeed: ((com.mazharansari.axion.Graph) -> Unit)? = null

    @Test fun firstRunIntroThenPermissionsThenSignIn() {
        com.mazharansari.axion.ui.auth.Onboarding.forceInTests = true
        com.mazharansari.axion.ui.auth.Onboarding.freezeIntroAt = 0.55f
        AuthHub.override = FakeAuth(); seed(guest = false, intro = false, perms = false)
        val (sc, _) = launch()
        compose.onNodeWithText("Axion").assertExists()
        cap("00a_intro_animation_mid")
        com.mazharansari.axion.ui.auth.Onboarding.freezeIntroAt = 1f
        sc.close()
        val (sc2, _) = launch()
        cap("00b_intro_animation_end")
        compose.onNodeWithText("Skip").performClick(); compose.mainClock.advanceTimeBy(800)
        assertTrue(app.graph.settings.state.value.introShown)
        compose.onNodeWithText("Stay in the loop").assertExists()
        cap("00c_permission_notifications")
        compose.onNodeWithText("Not now").performClick(); compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("Talk to Axion").assertExists()
        cap("00d_permission_microphone")
        compose.onNodeWithText("Not now").performClick(); compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("Show Axion a photo").assertExists()
        cap("00e_permission_camera")
        compose.onNodeWithText("Not now").performClick(); compose.mainClock.advanceTimeBy(800)
        assertTrue(app.graph.settings.state.value.permsShown)
        compose.onNodeWithText("Continue with Google").assertExists()
        sc2.close()
    }

    @Test fun passwordStrengthAndLimits() {
        AuthHub.override = FakeAuth(); seed(guest = false)
        val (sc, _) = launch()
        compose.onNodeWithText("Continue with Email").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Create account").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Mazhar")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("mazhar@example.com")
        compose.onAllNodes(hasSetTextAction())[2].performTextInput("axion2026")
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Fair").assertExists()
        cap("04b_create_account_strength_meter")
        sc.close()
        // 5 wrong passwords → cooldown with countdown
        val lim = AuthHub.limits(app)
        repeat(5) { lim.wrongPassword("locked@example.com") }
        assertTrue(lim.signInWait("locked@example.com") > 0)
    }
    private fun insets(sc: ActivityScenario<MainActivity>) = sc.onActivity { act ->
        val b = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 63, 0, 0)).setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 30))
        ViewCompat.dispatchApplyWindowInsets(act.window.decorView, b.build())
    }
    private fun launch(prep: (ChatViewModel) -> Unit = {}): Pair<ActivityScenario<MainActivity>, ChatViewModel> {
        val sc = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle(); insets(sc)
        var vm: ChatViewModel? = null
        sc.onActivity { vm = ViewModelProvider(it)[ChatViewModel::class.java]; lastSeed?.invoke(vm!!.g); prep(vm!!) }
        compose.mainClock.advanceTimeBy(1200)
        return sc to vm!!
    }

    @Test fun signInScreenOnOpen_thenGuest() {
        AuthHub.override = FakeAuth(); seed(guest = false)
        val (sc, vm) = launch()
        compose.onNodeWithText("Continue with Google").assertExists()
        compose.onNodeWithText("Continue with Email").assertExists()
        compose.onNodeWithText("Continue with GitHub").assertExists()
        compose.onNodeWithText("Maybe later").assertExists()
        cap("01_sign_in_screen")
        compose.onNodeWithText("Maybe later").performClick(); compose.mainClock.advanceTimeBy(800)
        println("GUEST>> " + app.graph.settings.state.value.guestChosen + " vm=" + (vm.g === app.graph))
        assertTrue(vm.g.settings.state.value.guestChosen)
        compose.onAllNodesWithText("Continue with Google").assertCountEquals(0)
        sc.close()
    }

    @Test fun guestDoesNotSeeSignInAgain() {
        AuthHub.override = FakeAuth(); seed(guest = true)
        val (sc, _) = launch()
        compose.onAllNodesWithText("Continue with Google").assertCountEquals(0); sc.close()
    }

    @Test fun signedInSkipsSignIn() {
        AuthHub.override = FakeAuth(AuthState.SignedIn(AxionUser("g1", "mazhar@gmail.com", "Mazhar Ansari", null, true, AuthProvider.GOOGLE))); seed(guest = false)
        val (sc, _) = launch()
        compose.onAllNodesWithText("Continue with Google").assertCountEquals(0); sc.close()
    }

    @Test fun githubNotEnabledShowsFriendlyError() {
        AuthHub.override = FakeAuth(githubEnabledFlag = false); seed(guest = false)
        val (sc, _) = launch()
        compose.onNodeWithText("Continue with GitHub").performClick(); compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("GitHub sign-in isn't switched on", substring = true).assertExists()
        cap("02_github_not_enabled_error"); sc.close()
    }

    @Test fun emailForms() {
        val fake = FakeAuth(); AuthHub.override = fake; seed(guest = false)
        val (sc, _) = launch()
        println("TREE>> " + runCatching { compose.onAllNodes(isRoot())[0].printToString(maxDepth = 30) }.getOrElse { it.toString() })
        compose.onNodeWithText("Continue with Email").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Forgot password?").assertExists()
        cap("03_email_sign_in")
        compose.onNodeWithText("Sign in", useUnmergedTree = true).let { }   // tab exists
        compose.onAllNodesWithText("Sign in")[1].performClick(); compose.mainClock.advanceTimeBy(600)   // empty → check message
        compose.onNodeWithText("Enter your email.").assertExists()
        compose.onNodeWithText("Create account").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Confirm password").assertExists()
        cap("04_email_create_account")
        compose.onAllNodesWithText("Create account")[0].performClick(); compose.mainClock.advanceTimeBy(300)
        compose.onAllNodesWithText("Sign in")[0].performClick(); compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Forgot password?").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Send reset link").assertExists()
        cap("05_forgot_password")
        sc.close()
    }

    @Test fun accountInSettings() {
        val fake = FakeAuth(AuthState.SignedIn(AxionUser("g1", "mazharalam4447@gmail.com", "Mazhar Ansari", null, true, AuthProvider.GOOGLE))); AuthHub.override = fake; seed(guest = false)
        val (sc, vm) = launch { it.debugStart = Route.Settings }
        compose.onNodeWithText("Mazhar Ansari").assertExists()
        cap("06_settings_account_row")
        sc.onActivity { vm.debugStart = Route.Account }; compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("Signed in with Google").assertExists()
        compose.onNodeWithText("Sign out").assertExists()
        cap("07_account_screen")
        compose.onNodeWithText("Sign out").performClick(); compose.mainClock.advanceTimeBy(600)
        cap("08_sign_out_confirm")
        sc.close()
    }

    @Test fun accountAsGuest() {
        AuthHub.override = FakeAuth(); seed(guest = true)
        val (sc, _) = launch { it.debugStart = Route.Account }
        compose.onNodeWithText("Guest").assertExists()
        cap("09_account_guest"); sc.close()
    }

    @Test fun storageScreen() {
        AuthHub.override = FakeAuth(); seed(guest = true)
        val mb = 1048576L
        val items = listOf(
            StorageItem("app", StorageKind.APP, "Axion app", "The app itself. Remove it from Android Settings to free this.", 21 * mb),
            StorageItem("linux", StorageKind.LINUX, "Linux (Ubuntu)", "Ubuntu 24.04 and its base tools", 96 * mb, "Delete", "Delete Linux?"),
            StorageItem("agy", StorageKind.AGY, "Antigravity CLI", "agy 1.1.27 · your Antigravity sign-ins are kept", 191 * mb, "Delete", "x"),
            StorageItem("tool:python", StorageKind.TOOL, "Python", "3.12.3", 78 * mb, "Delete", "x"),
            StorageItem("tool:web", StorageKind.TOOL, "Web", "Node 24.11.0", 326 * mb, "Delete", "x"),
            StorageItem("chats", StorageKind.CHATS, "Chats", "42 chats", (3.4 * mb).toLong(), "Delete all", "x"),
            StorageItem("files", StorageKind.FILES, "Files and projects", "Uploads, created files and Build projects. Manage them in Library and Build.", 58 * mb),
            StorageItem("model:qwen", StorageKind.MODEL, "Qwen2.5-Coder 1.5B", "Local model · 1.5B · Q4_K_M", 1066 * mb, "Delete", "x"),
            StorageItem("cache", StorageKind.CACHE, "Cache", "Temporary files, downloads and Linux package caches. Safe to clear.", 64 * mb, "Clear cache"),
        )
        val (sc, _) = launch { it.debugStorage = items to 18_400 * mb; it.debugStart = Route.Storage }
        compose.onNodeWithText("Clear cache (64 MB)").assertExists()
        cap("10_storage_screen")
        runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Clear cache (64 MB)")) }
        cap("10b_storage_screen_bottom")
        sc.close()
    }

    @Test fun storageRealScan() = runBlocking {
        AuthHub.override = FakeAuth(); seed(guest = true)
        val g = app.graph
        File(g.linux.rootfs, "usr/bin").mkdirs(); File(g.linux.rootfs, "usr/bin/bash").writeBytes(ByteArray(2 shl 20)); File(g.linux.rootfs, LinuxBase.ROOTFS_MARKER).writeText("x")
        File(g.linux.rootfs, "var/cache/apt/archives").mkdirs(); File(g.linux.rootfs, "var/cache/apt/archives/x.deb").writeBytes(ByteArray(1 shl 20))
        File(g.linux.rootfs, "root/.local/bin").mkdirs(); File(g.linux.rootfs, "root/.local/bin/agy").writeBytes(ByteArray(3 shl 20))
        flow<Map<String, ToolRecord>>(g.devtools, "_records").value = mapOf("agy" to ToolRecord("1.1.27", 3072, installedAt = 1), "python" to ToolRecord("3.12.3", 512, installedAt = 1))
        File(app.cacheDir, "junk.bin").writeBytes(ByteArray(1 shl 20))
        val items = g.storage.scan()
        val byId = items.associateBy { it.id }
        assertEquals(3L shl 20, byId["agy"]!!.bytes)
        assertEquals(512L * 1024, byId["tool:python"]!!.bytes)
        assertTrue(byId["cache"]!!.bytes >= 2L shl 20)
        assertTrue(byId.containsKey("linux")); assertTrue(byId.containsKey("chats"))
        g.storage.delete(byId["cache"]!!)
        assertFalse(File(app.cacheDir, "junk.bin").exists())
        assertFalse(File(g.linux.rootfs, "var/cache/apt/archives/x.deb").exists())
        assertTrue(File(g.linux.rootfs, "usr/bin/bash").exists())
        g.storage.delete(byId["agy"]!!)
        assertFalse(File(g.linux.rootfs, "root/.local/bin/agy").exists())
        assertNull(g.devtools.records.value["agy"]?.version)
    }
}
