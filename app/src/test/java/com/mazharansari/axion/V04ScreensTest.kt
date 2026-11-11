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
import com.mazharansari.axion.antigravity.AgyLoginState
import com.mazharansari.axion.data.*
import com.mazharansari.axion.linux.*
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** v0.4 stage 1 renders at mazhar's phone size (411×914 dp, rendered 1080 px wide), his status/gesture bars. */
@OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi", application = AxionApp::class)
class V04ScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val shots = File(System.getProperty("user.dir"), "build/screens_v04s1").apply { mkdirs() }
    /**
     * captureScreenRoboImage drew stale windows from earlier tests in this JVM, so: the activity's own window, then any
     * dialog window (bottom sheets) composited on top of it.
     */
    private fun cap(name: String) {
        compose.mainClock.advanceTimeBy(1500)
        val out = File(shots, "$name.png")
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
        compose.onAllNodes(isRoot())[0].captureRoboImage(out.path)
        for (i in 1 until roots) {
            val layer = File(shots, "${name}__layer$i.png")
            compose.onAllNodes(isRoot())[i].captureRoboImage(layer.path)   // composited onto the base after the run
        }
    }
    private var current: ActivityScenario<MainActivity>? = null
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as MutableStateFlow<T>

    private fun seed(linux: Boolean = true) {
        val fd = app.filesDir
        org.robolectric.shadows.ShadowStatFs.registerStats(fd, 28_000_000, 1_650_000, 1_650_000)   // ≈6.3 GB free, like a real phone
        File(fd, "settings.json").writeText("""{"userName":"Mazhar Ansari","theme":"dark","mode":"AUTO"}""")
        fun conn(p: String, hint: String, models: List<String>) = Connector("c-$p-$hint", p, "plain:" + android.util.Base64.encodeToString("test-key".toByteArray(), 2), hint, models, models, addedAt = 1)
        val conns = listOf(
            conn("groq", "gsk_…7Qa1", listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "qwen/qwen3-32b")),
            conn("groq", "gsk_…m2Xe", listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b")),
            conn("gemini", "AQ.…x9Qk", listOf("gemini-3.8-flash", "gemini-3.7-flash")),
            conn("openrouter", "sk-or-…41c", listOf("deepseek/deepseek-chat-v3:free")),
        )
        File(fd, "connectors.json").writeText(Json.encodeToString(ListSerializer(Connector.serializer()), conns))
        val g = app.graph
        flow<List<Connector>>(g.connectors, "_list").value = conns   // the quota thread may have loaded the repo before this seed
        if (linux) {
            File(g.linux.rootfs, "usr/bin").mkdirs(); File(g.linux.rootfs, "usr/bin/bash").writeText("#"); File(g.linux.rootfs, LinuxBase.ROOTFS_MARKER).writeText("x")
            flow<LinuxState>(g.linux, "_state").value = LinuxState.Ready
            val now = System.currentTimeMillis()
            flow<Map<String, ToolRecord>>(g.devtools, "_records").value = mapOf(
                "core" to ToolRecord("2.43.0", 0, installedAt = now),
                "python" to ToolRecord("3.12.3", 77_420, "3.12.3", now, now),
                "web" to ToolRecord("Node 24.11.0", 333_952, "Node 26.13.0", now, now),
                "php" to ToolRecord("8.4.27 · Composer 2.11.1", 24_916, "8.4.27 · Composer 2.11.1", now, now),
                "agy" to ToolRecord("1.1.27", 195_352, installedAt = now),
            )
            flow<Map<String, ToolStatus>>(g.devtools, "_status").value = mapOf("cpp" to ToolStatus.Working("install", 46, "Installing gcc, g++, make and cmake…"))
        } else {
            flow<LinuxState>(g.linux, "_state").value = LinuxState.NotInstalled
        }
    }

    private fun seedAgy() {
        val g = app.graph
        listOf("mazhar.ansari34@gmail.com", "claworabeauty@gmail.com", "mazharmnzoor4227@gmail.com").forEach { e ->
            val st = File(app.cacheDir, "stage-$e").apply { mkdirs(); File(this, "antigravity-oauth-token").writeText("tok") }
            g.agy.recordSignedIn(e, st)
        }
        g.agy.setActive("mazhar.ansari34@gmail.com")
        g.agy.setTier("mazhar.ansari34@gmail.com", "free"); g.agy.setTier("claworabeauty@gmail.com", "pro")
        g.agy.markQuota("claworabeauty@gmail.com", null)
        g.agy.markAuthExpired("mazharmnzoor4227@gmail.com")
        g.agy.setUseInChat("fallback")
        val now = System.currentTimeMillis()
        g.quota.recordAgyProbe("mazhar.ansari34@gmail.com", """{"tier":"free","groups":[{"label":"Gemini models","buckets":[{"label":"Weekly","models":["gemini-3.8-flash"],"remaining_percentage":64,"reset_time":"${java.time.Instant.ofEpochMilli(now + 4 * 86_400_000L + 3 * 3_600_000L)}"}]},{"label":"Claude models","buckets":[{"label":"Weekly","remaining_fraction":0.22,"reset_time":"${java.time.Instant.ofEpochMilli(now + 4 * 86_400_000L + 3 * 3_600_000L)}"}]}]}""")
        g.quota.recordAgyProbe("claworabeauty@gmail.com", """{"tier":"pro","groups":[{"label":"Gemini models","buckets":[{"label":"5 hour","remaining_percentage":0,"reset_time":"${java.time.Instant.ofEpochMilli(now + 52 * 60_000L)}"},{"label":"Weekly","remaining_percentage":41,"reset_time":"${java.time.Instant.ofEpochMilli(now + 2 * 86_400_000L)}"}]}]}""")
        g.quota.recordAgyProbe("mazharmnzoor4227@gmail.com", "Usage\n  Model quota     ▓▓▓▓░░  see dashboard\n  (signed out)")
        repeat(7) { g.quota.recordAgyRun("mazhar.ansari34@gmail.com", true, 4200, 900) }
    }

    private fun seedKeys() {
        val g = app.graph; val now = System.currentTimeMillis()
        val ids = g.connectors.list.value.map { it.id to it.providerId }
        ids.forEach { (id, p) ->
            when (p) {
                "groq" -> repeat(if (id.contains("7Qa1")) 46 else 9) { g.quota.onResponse(id, "groq", "llama-3.3-70b-versatile", 200, mapOf(
                    "x-ratelimit-limit-requests" to "1000", "x-ratelimit-remaining-requests" to "${if (id.contains("7Qa1")) 954 - it else 991 - it}", "x-ratelimit-reset-requests" to "1h12m",
                    "x-ratelimit-limit-tokens" to "12000", "x-ratelimit-remaining-tokens" to "9800", "x-ratelimit-reset-tokens" to "7.6s"), null, now - (46 - it) * 60_000L) }
                "gemini" -> { repeat(14) { g.quota.onResponse(id, "gemini", "gemini-3.8-flash", 200, emptyMap(), null, now - it * 300_000L) }
                    g.quota.onResponse(id, "gemini", "gemini-3.8-flash", 429, emptyMap(), javaClass.classLoader!!.getResource("gemini429_real.json")!!.readText()) }
                else -> repeat(3) { g.quota.onResponse(id, p, "deepseek/deepseek-chat-v3:free", 200, emptyMap(), null, now - it * 600_000L) }
            }
        }
    }

    private fun insets(sc: ActivityScenario<MainActivity>) {
        sc.onActivity { act ->
            val b = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 108, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 45))
            ViewCompat.dispatchApplyWindowInsets(act.window.decorView, b.build())
        }
    }

    private fun launch(prep: (ChatViewModel) -> Unit = {}): Pair<ActivityScenario<MainActivity>, ChatViewModel> {
        val sc = ActivityScenario.launch(MainActivity::class.java)
        current = sc
        compose.waitForIdle(); insets(sc)
        var vm: ChatViewModel? = null
        sc.onActivity { vm = ViewModelProvider(it)[ChatViewModel::class.java]; prep(vm!!) }
        compose.mainClock.advanceTimeBy(800)
        return sc to vm!!
    }

    private fun route(r: Route, prep: (ChatViewModel) -> Unit = {}): ActivityScenario<MainActivity> {
        val (sc, _) = launch { vm -> prep(vm); vm.debugStart = r }
        compose.mainClock.advanceTimeBy(1200)
        return sc
    }

    @Test fun devTools() { seed(); val sc = route(Route.DevTools); cap("01_developer_tools"); sc.close() }

    @Test fun devToolsFirstRun() {
        seed(linux = false)
        println("FIRSTRUN rootfs=${app.graph.linux.rootfs} exists=${app.graph.linux.rootfs.exists()} state=${app.graph.linux.state.value}")
        val sc = route(Route.DevTools)
        println("FIRSTRUN after state=${app.graph.linux.state.value} notSetUp=${compose.onAllNodesWithText("Not set up yet", substring = true).fetchSemanticsNodes().size} ready=${compose.onAllNodesWithText("Ready in PRoot", substring = true).fetchSemanticsNodes().size} roots=${compose.onAllNodes(isRoot()).fetchSemanticsNodes().size}")
        cap("01b_developer_tools_first_run"); sc.close()
    }

    @Test fun toolSheet() { seed(); val sc = route(Route.DevTools) { it.debugToolSheet = Toolchain.ANDROID }; cap("02_toolchain_install_sheet"); sc.close() }

    @Test fun toolSheetInstalling() { seed(); val sc = route(Route.DevTools) { it.debugToolSheet = Toolchain.CPP }; cap("02b_toolchain_installing"); sc.close() }

    @Test fun antigravity() { seed(); seedAgy(); val sc = route(Route.Antigravity); cap("03_antigravity_accounts"); sc.close() }

    @Test fun antigravityLogin() {
        seed(); seedAgy()
        val sc = route(Route.Antigravity) { it.debugAgyLogin = AgyLoginState.AwaitingCode("https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=1071006060591.apps.googleusercontent.com&code_challenge=x&state=eqPPegReyKP37OxuRsM0EQ") }
        cap("03b_antigravity_sign_in"); sc.close()
    }

    @Test fun quota() { seed(); seedAgy(); seedKeys(); val sc = route(Route.Quota); cap("04_quota_dashboard")
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp(startY = bottom - 100f, endY = top + 100f) }
        cap("04b_quota_dashboard_keys"); sc.close() }

    @Test fun keyBackup() { seed(); val sc = route(Route.KeyBackup); cap("05_key_export"); sc.close() }

    @Test fun homeAskBuild() { seed(); val (sc, _) = launch(); cap("06_home_ask_build"); sc.close() }

    @Test fun homeBuildTab() { seed(); val (sc, _) = launch { it.debugHomeTab = 2 }; cap("06b_build_setup_card"); sc.close() }

    @Test fun plusPopoverScrim() {
        seed()
        val id = seedChat()
        val (sc, _) = launch { vm -> vm.debugPopover = 1; vm.open(id) }
        compose.mainClock.advanceTimeBy(800)
        cap("07_plus_popover_scrim"); sc.close()
    }

    @Test fun modelPopoverScrim() {
        seed()
        val id = seedChat()
        val (sc, _) = launch { vm -> vm.debugPopover = 2; vm.open(id) }
        compose.mainClock.advanceTimeBy(800)
        cap("07b_ask_model_picker_no_antigravity"); sc.close()
    }

    /** Composer focused (keyboard up) → tap ☰: the composer loses focus, so the keyboard can't sit over the menu. */
    @Test fun drawerClosesKeyboard() {
        seed()
        val (sc, vm) = launch()
        val field = compose.onAllNodes(hasSetTextAction()).onFirst()
        field.performClick(); compose.mainClock.advanceTimeBy(300)
        field.assertIsFocused()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onAllNodes(hasSetTextAction()).onFirst().assertIsNotFocused()
        cap("08_drawer_keyboard_closed"); sc.close()
    }

    @Test fun callNoKey() {
        val fd = app.filesDir
        File(fd, "settings.json").writeText("""{"userName":"Mazhar Ansari","theme":"dark"}""")
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val sc = route(Route.VoiceCall)
        cap("10_call_needs_gemini_key"); sc.close()
    }

    @Test fun buildPicker() {
        seed(); seedAgy()
        val t0 = System.currentTimeMillis() - 60_000
        val conv = Conversation("b04", "Habit tracker", listOf(
            Message("u1", Role.USER, "Build a habit tracker web app with streaks", createdAt = t0),
            Message("a1", Role.ASSISTANT, "Bana diya — `habit-tracker/index.html` ready hai. Streaks localStorage mein save hoti hain.", thinkingMs = 42_000, model = "Antigravity · mazhar.ansari34", createdAt = t0 + 1000),
        ), createdAt = t0, updatedAt = System.currentTimeMillis(), section = "build")
        File(app.filesDir, "chats").mkdirs(); File(app.filesDir, "chats/b04.json").writeText(Json.encodeToString(Conversation.serializer(), conv))
        val (sc, _) = launch { vm -> vm.debugPopover = 2; vm.open("b04") }
        compose.mainClock.advanceTimeBy(800)
        cap("11_build_picker_antigravity"); sc.close()
    }

    private fun seedChat(): String {
        val t0 = System.currentTimeMillis() - 60_000
        val conv = Conversation("v04", "Salam", listOf(
            Message("u1", Role.USER, "hi bhai", createdAt = t0),
            Message("a1", Role.ASSISTANT, "Walaikum salam bhai! Kaise ho? Batao aaj kya karna hai — website, app, ya koi sawal?", thinkingMs = 900, model = "Groq · llama-3.3-70b-versatile", createdAt = t0 + 1000),
        ), createdAt = t0, updatedAt = System.currentTimeMillis())
        File(app.filesDir, "chats").mkdirs(); File(app.filesDir, "chats/v04.json").writeText(Json.encodeToString(Conversation.serializer(), conv))
        return conv.id
    }
}
