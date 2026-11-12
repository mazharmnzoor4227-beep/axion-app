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
import com.mazharansari.axion.build.DeployProvider
import com.mazharansari.axion.build.JobState
import com.mazharansari.axion.data.*
import com.mazharansari.axion.linux.*
import com.mazharansari.axion.security.SecureWindow
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** v0.5.2 renders at mazhar's screen (720×1600, 1.75x): policies, Delete all data, key export warning, secret-scan block. */
@OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-280dpi", application = AxionApp::class)
class V052ScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val shots = File(System.getProperty("user.dir"), "build/screens_v052").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as MutableStateFlow<T>
    private val groqKey = "gsk_" + "A1b2C3d4E5f6G7h8J9k0L1m2N3p4Q5r6S7t8U9v0W1x2Y3z4"

    private fun cap(name: String) {
        compose.mainClock.advanceTimeBy(1500)
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
        compose.onAllNodes(isRoot())[0].captureRoboImage(File(shots, "$name.png").path)
        for (i in 1 until roots) compose.onAllNodes(isRoot())[i].captureRoboImage(File(shots, "${name}__layer$i.png").path)
    }

    private fun seed() {
        val fd = app.filesDir
        File(fd, "settings.json").writeText("""{"userName":"Mazhar Ansari","theme":"dark","mode":"AUTO","buildIntroShown":true}""")
        // pre-0.5.2 hints ("gsk_…7Qa1") are migrated to last-4 on load
        val conns = listOf(
            Connector("c-groq", "groq", KeyVault.encrypt(groqKey), "gsk_…" + groqKey.takeLast(4), listOf("llama-3.3-70b-versatile"), listOf("llama-3.3-70b-versatile"), addedAt = 1),
            Connector("c-gem", "gemini", KeyVault.encrypt("AIzaSyA1b2C3d4E5f6G7h8J9k0L1m2N3p4Q5r6S"), "AIza…Q5r6S".takeLast(9), listOf("gemini-3.8-flash"), listOf("gemini-3.8-flash"), addedAt = 2),
        )
        File(fd, "connectors.json").writeText(Json.encodeToString(ListSerializer(Connector.serializer()), conns))
        val g = app.graph
        g.settings.update { it.copy(userName = "Mazhar Ansari", buildIntroShown = true) }   // the store was loaded before this file was written
        flow<List<Connector>>(g.connectors, "_list").value = conns.map { it.copy(keyHint = KeyHint.migrate(it.keyHint)) }
        File(g.linux.rootfs, "usr/bin").mkdirs(); File(g.linux.rootfs, "usr/bin/bash").writeText("#"); File(g.linux.rootfs, LinuxBase.ROOTFS_MARKER).writeText("x")
        flow<LinuxState>(g.linux, "_state").value = LinuxState.Ready
        val now = System.currentTimeMillis()
        flow<Map<String, ToolRecord>>(g.devtools, "_records").value = mapOf(
            "core" to ToolRecord("2.43.0", 0, installedAt = now), "web" to ToolRecord("Node 24.11.0", 333_952, "Node 24.11.0", now, now),
            "agy" to ToolRecord("1.1.27", 195_352, installedAt = now))
        val st = File(app.cacheDir, "stage-m").apply { mkdirs(); File(this, "antigravity-oauth-token").writeText("tok") }
        g.agy.recordSignedIn("mazhar.ansari34@gmail.com", st); g.agy.setActive("mazhar.ansari34@gmail.com")
    }

    private fun insets(sc: ActivityScenario<MainActivity>) = sc.onActivity { act ->
        val b = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 63, 0, 0)).setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 30))
        ViewCompat.dispatchApplyWindowInsets(act.window.decorView, b.build())
    }

    private fun launch(prep: (ChatViewModel) -> Unit = {}): Pair<ActivityScenario<MainActivity>, ChatViewModel> {
        val sc = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle(); insets(sc)
        var vm: ChatViewModel? = null
        sc.onActivity { vm = ViewModelProvider(it)[ChatViewModel::class.java]; prep(vm!!) }
        compose.mainClock.advanceTimeBy(1200)
        return sc to vm!!
    }

    private fun scrollTo(text: String) = runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true)) }

    @Test fun aboutPrivacySection() {
        seed(); val (sc, _) = launch { it.debugStart = Route.About }
        scrollTo("Delete all data"); cap("01_about_privacy_security"); sc.close()
    }

    // Robolectric never goes idle with a text field inside a dialog window, so the renders draw the same field as text.
    @Test fun deleteAllDialog() {
        seed(); val (sc, _) = launch { it.debugStart = Route.About; it.debugDeleteAll = true; it.debugNoClear = true; it.debugNoField = true }
        cap("05_delete_all_data_dialog"); sc.close()
    }
    @Test fun deleteAllDialogTyped() {
        seed(); val (sc, _) = launch { it.debugStart = Route.About; it.debugDeleteAll = true; it.debugNoClear = true; it.debugNoField = true; it.debugTyped = "DELETE" }
        cap("05b_delete_all_data_typed"); sc.close()
    }

    @Test fun keyExportWarning() {
        seed(); val (sc, _) = launch { it.debugStart = Route.KeyBackup; it.debugExportWarning = true }
        assertTrue("key screens block screenshots", SecureWindow.debugActive)
        sc.onActivity { assertTrue(it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0) }
        cap("06_key_export_warning"); sc.close()
    }

    @Test fun keyExportScreen() {
        seed(); val (sc, _) = launch { it.debugStart = Route.KeyBackup }
        cap("06b_key_export_screen"); sc.close()
    }

    @Test fun connectorsMasked() {
        seed(); val (sc, _) = launch { it.debugStart = Route.Connectors }
        sc.onActivity { assertTrue(it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0) }
        runCatching { compose.onAllNodesWithText("Groq").onFirst().performClick() }
        cap("07_connectors_keys_masked_last4"); sc.close()
    }

    @Test fun nonSecretScreenAllowsScreenshots() {
        seed(); val (sc, _) = launch { it.debugStart = Route.About }
        sc.onActivity { assertTrue(it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE == 0) }
        sc.close()
    }

    /** The real publish path: a key in app.js → blocked before anything is uploaded. */
    @Test fun publishBlockedBySecretScan() {
        seed()
        val g = app.graph
        val store = g.build.store
        store.list.value.forEach { store.delete(it.id) }
        val p = store.create("Clawora Beauty", "web")
        File(store.folder(p), "index.html").writeText("<!doctype html><html><head><title>Clawora Beauty</title></head><body><h1>Clawora Beauty</h1><script src=\"app.js\"></script></body></html>")
        File(store.folder(p), "app.js").writeText("// product search\nconst AI_KEY = \"$groqKey\";\nfetch('https://api.groq.com/openai/v1/chat/completions', { headers: { Authorization: 'Bearer ' + AI_KEY } });\n")
        g.build.tokens.set(DeployProvider.NETLIFY, "nfp_" + "Zx9Yw8Vu7Ts6Rq5Po4Nm3Lk2Ji1Hg0FeDcBa")
        g.build.deploy(store.get(p.id)!!, DeployProvider.NETLIFY)
        val end = System.currentTimeMillis() + 10_000
        while (g.build.jobs.value["${p.id}:deploy"] !is JobState.Failed && System.currentTimeMillis() < end) Thread.sleep(20)
        val job = g.build.jobs.value["${p.id}:deploy"] as JobState.Failed
        assertTrue(job.message, job.message.contains("app.js, line 2")); assertFalse(job.message.contains(groqKey))
        println("projects before launch: ${store.list.value.map { it.name }}")
        val (sc, vm) = launch { vm -> vm.debugHomeTab = 2; vm.debugProjectTab = 2; vm.openProject(p.id); println("in prep: ${vm.projectId} get=${store.get(p.id)?.name}") }
        println("after launch: projectId=${vm.projectId} list=${store.list.value.map { it.name }} ready=${vm.buildReady()}")
        scrollTo("Publishing stopped"); cap("08_publish_blocked_secret_found")
        File(shots, "08_publish_blocked_message.txt").writeText(job.message)
        sc.close()
    }

    /** A pasted key in chat is masked; the first-word time shows next to the model name. */
    @Test fun chatMaskedKeyAndTiming() {
        seed()
        val (sc, vm) = launch { it.debugHomeTab = 0 }
        val now = System.currentTimeMillis()
        val list = listOf(
            Message("u1", Role.USER, com.mazharansari.axion.security.SecretScanner.scrub("Ye key check karo: $groqKey", emptyList()), createdAt = now - 20_000),
            Message("a1", Role.ASSISTANT, "Maine key chat mein nahi bheji — Axion ne usay chhupa diya. Keys hamesha Settings → Connectors mein daalein, wahan wo encrypted rehti hain.",
                model = "Groq · llama-3.3-70b-versatile", firstWordMs = 640, prepMs = 9, thinkingMs = 1_800, createdAt = now - 18_000),
        )
        sc.onActivity {
            val f = vm.javaClass.getDeclaredField("messages\$delegate").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") (f.get(vm) as androidx.compose.runtime.MutableState<List<Message>>).value = list
        }
        cap("09_chat_key_masked_first_word_time"); sc.close()
    }

    // ── v0.5.2 real-phone fixes ──
    private fun noAgy() {
        val g = app.graph
        flow<Map<String, ToolRecord>>(g.devtools, "_records").value = mapOf("core" to ToolRecord("2.43.0", 0, installedAt = 1))
    }
    private val setupLines = listOf(
        "Axion 0.5.2 · Installing Antigravity CLI · Sun Nov 01 17:40:12 GMT+05:00 2026 · Android 14 (arm64-v8a)",
        "[linux] Downloading Ubuntu 24.04 · 28 of 28 MB", "[linux] Checking the download", "[linux] Unpacking Ubuntu 24.04 · 3412 files", "[linux] Linux is ready",
        "[agy] download https://storage.googleapis.com/antigravity-public/antigravity-cli/1.1.27-5211191891591168/linux-arm/cli_linux_arm64.tar.gz")

    @Test fun agySetupLivePanel() {
        seed(); noAgy()
        val (sc, _) = launch { it.debugStart = Route.Antigravity
            it.g.devtools.debugProgress("agy", listOf(DevTools.Step.LINUX, DevTools.Step.DOWNLOAD, DevTools.Step.VERIFY, DevTools.Step.START), DevTools.Step.DOWNLOAD, 42, "Downloading Antigravity · 22 of 50 MB", setupLines) }
        println("DBG records=" + app.graph.devtools.records.value.keys)
        cap("10_antigravity_setup_live_panel"); sc.close()
    }

    @Test fun agySetupFailedPanel() {
        seed(); noAgy()
        val (sc, _) = launch { it.debugStart = Route.Antigravity
            it.g.devtools.debugProgress("agy", listOf(DevTools.Step.DOWNLOAD, DevTools.Step.VERIFY, DevTools.Step.START), DevTools.Step.DOWNLOAD, 30, "Downloading", setupLines)
            it.g.devtools.debugFail("agy", "Download stopped: timeout. Check the internet and tap install again — it resumes.", setupLines + listOf("[agy] downloaded 18874368 bytes", "[axion] failed: IllegalStateException: Download stopped: timeout. Check the internet and tap install again — it resumes.")) }
        scrollTo("Retry"); cap("11_antigravity_setup_failed_view_log_retry"); sc.close()
    }

    /** The log dialog with a REAL log: the apt path (other tools) with DNS broken inside Linux, from the arm64 reproduction. */
    @Test fun installLogDialog() {
        seed()
        val real = listOf(
            "Err:4 https://mirror.sg.gs/ubuntu-ports noble-security InRelease",
            "  Temporary failure resolving 'mirror.sg.gs'",
            "Reading package lists...",
            "E: Failed to fetch https://mirror.sg.gs/ubuntu-ports/dists/noble/InRelease  Temporary failure resolving 'mirror.sg.gs'",
            "E: Failed to fetch https://mirror.sg.gs/ubuntu-ports/dists/noble-updates/InRelease  Temporary failure resolving 'mirror.sg.gs'",
            "E: Failed to fetch https://mirror.sg.gs/ubuntu-ports/dists/noble-backports/InRelease  Temporary failure resolving 'mirror.sg.gs'",
            "E: Failed to fetch https://mirror.sg.gs/ubuntu-ports/dists/noble-security/InRelease  Temporary failure resolving 'mirror.sg.gs'",
            "E: Some index files failed to download. They have been ignored, or old ones used instead.",
            "Err:1 https://mirror.sg.gs/ubuntu-ports noble InRelease",
            "  Temporary failure resolving 'mirror.sg.gs'",
            "Err:2 https://mirror.sg.gs/ubuntu-ports noble-updates InRelease",
            "  Temporary failure resolving 'mirror.sg.gs'",
            "Err:3 https://mirror.sg.gs/ubuntu-ports noble-backports InRelease",
            "  Temporary failure resolving 'mirror.sg.gs'",
            "Err:4 https://mirror.sg.gs/ubuntu-ports noble-security InRelease",
            "  Temporary failure resolving 'mirror.sg.gs'",
            "Reading package lists...",
            "E: Failed to fetch https://mirror.sg.gs/ubuntu-ports/dists/noble/InRelease  Temporary failure resolving 'mirror.sg.gs'",
            "E: Failed to fetch https://mirror.sg.gs/ubuntu-ports/dists/noble-updates/InRelease  Temporary failure resolving 'mirror.sg.gs'",
            "E: Failed to fetch https://mirror.sg.gs/ubuntu-ports/dists/noble-backports/InRelease  Temporary failure resolving 'mirror.sg.gs'",
            "E: Failed to fetch https://mirror.sg.gs/ubuntu-ports/dists/noble-security/InRelease  Temporary failure resolving 'mirror.sg.gs'",
            "E: Some index files failed to download. They have been ignored, or old ones used instead.",
            "mirror https://mirror.sg.gs/ubuntu-ports failed, trying the next one",
            "apt-get update from http://ports.ubuntu.com/ubuntu-ports",
            "Err:1 http://ports.ubuntu.com/ubuntu-ports noble InRelease",
            "  Temporary failure resolving 'ports.ubuntu.com'",
            "Err:2 http://ports.ubuntu.com/ubuntu-ports noble-updates InRelease",
            "  Temporary failure resolving 'ports.ubuntu.com'",
            "Err:3 http://ports.ubuntu.com/ubuntu-ports noble-backports InRelease",
            "  Temporary failure resolving 'ports.ubuntu.com'",
            "Err:4 http://ports.ubuntu.com/ubuntu-ports noble-security InRelease",
            "  Temporary failure resolving 'ports.ubuntu.com'",
            "Reading package lists...",
            "E: Failed to fetch http://ports.ubuntu.com/ubuntu-ports/dists/noble/InRelease  Temporary failure resolving 'ports.ubuntu.com'",
            "E: Failed to fetch http://ports.ubuntu.com/ubuntu-ports/dists/noble-updates/InRelease  Temporary failure resolving 'ports.ubuntu.com'",
            "E: Failed to fetch http://ports.ubuntu.com/ubuntu-ports/dists/noble-backports/InRelease  Temporary failure resolving 'ports.ubuntu.com'",
            "E: Failed to fetch http://ports.ubuntu.com/ubuntu-ports/dists/noble-security/InRelease  Temporary failure resolving 'ports.ubuntu.com'",
            "E: Some index files failed to download. They have been ignored, or old ones used instead.",
            "Err:1 http://ports.ubuntu.com/ubuntu-ports noble InRelease",
            "  Temporary failure resolving 'ports.ubuntu.com'",
            "Err:2 http://ports.ubuntu.com/ubuntu-ports noble-updates InRelease",
            "  Temporary failure resolving 'ports.ubuntu.com'",
            "Err:3 http://ports.ubuntu.com/ubuntu-ports noble-backports InRelease",
            "  Temporary failure resolving 'ports.ubuntu.com'",
            "Err:4 http://ports.ubuntu.com/ubuntu-ports noble-security InRelease",
            "  Temporary failure resolving 'ports.ubuntu.com'",
            "Reading package lists...",
            "E: Failed to fetch http://ports.ubuntu.com/ubuntu-ports/dists/noble/InRelease  Temporary failure resolving 'ports.ubuntu.com'",
            "E: Failed to fetch http://ports.ubuntu.com/ubuntu-ports/dists/noble-updates/InRelease  Temporary failure resolving 'ports.ubuntu.com'",
            "E: Failed to fetch http://ports.ubuntu.com/ubuntu-ports/dists/noble-backports/InRelease  Temporary failure resolving 'ports.ubuntu.com'",
            "E: Failed to fetch http://ports.ubuntu.com/ubuntu-ports/dists/noble-security/InRelease  Temporary failure resolving 'ports.ubuntu.com'",
            "E: Some index files failed to download. They have been ignored, or old ones used instead.",
            "mirror http://ports.ubuntu.com/ubuntu-ports failed, trying the next one",
            "--- network check ---",
            "resolv.conf: nameserver 10.255.255.1 ",
            "lookup ports.ubuntu.com: ",
            "lookup mirror.sg.gs: ",
            "https test: 000",
            "---------------------",
            "ERROR: Could not reach the Ubuntu package servers (internet or DNS problem inside Linux). Tap View log for details, then Retry.")
        val (sc, _) = launch { it.debugStart = Route.DevTools; it.debugToolLog = "python"
            it.g.devtools.debugFail("python", "Could not reach the Ubuntu package servers (internet or DNS problem inside Linux). Tap View log for details, then Retry.", real) }
        cap("12_install_log_dialog_real_output"); sc.close()
    }

    @Test fun agyReadyCollapses() {
        seed(); noAgy()
        val (sc, vm) = launch { it.debugStart = Route.Antigravity
            it.g.devtools.debugProgress("agy", listOf(DevTools.Step.DOWNLOAD, DevTools.Step.VERIFY, DevTools.Step.START), DevTools.Step.START, 97, "Checking Antigravity starts", setupLines + "1.1.27") }
        compose.mainClock.advanceTimeBy(800)
        sc.onActivity {
            flow<Map<String, ToolRecord>>(vm.g.devtools, "_records").value = mapOf("core" to ToolRecord("2.43.0", 0, installedAt = 1), "agy" to ToolRecord("1.1.27", 195_352, installedAt = 2))
            flow<Map<String, ToolStatus>>(vm.g.devtools, "_status").value = mapOf("agy" to ToolStatus.Idle)
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onAllNodes(isRoot())[0].captureRoboImage(File(shots, "13_antigravity_ready.png").path); sc.close()
    }

    @Test fun modelPickerHasFreeSlow() {
        seed(); val (sc, _) = launch { it.debugHomeTab = 0; it.debugPopover = 2 }
        cap("14_model_picker_free_slow"); sc.close()
    }

    @Test fun freshInstallShowsYou() {
        seed(); File(app.filesDir, "settings.json").writeText("""{"userName":"","theme":"dark","mode":"AUTO","buildIntroShown":true}""")
        app.graph.settings.update { it.copy(userName = "") }
        val (sc, _) = launch { it.debugStart = Route.Settings }
        cap("15_settings_fresh_install_you"); sc.close()
    }

    @Test fun buildToolsPopupNothingTicked() {
        seed(); val (sc, _) = launch { it.debugHomeTab = 2; it.debugBuildIntro = true }
        cap("16_build_tools_popup_nothing_ticked"); sc.close()
    }

    @Test fun newProjectAndroidIcon() {
        seed(); val (sc, _) = launch { it.debugHomeTab = 2; it.debugNewProject = true }
        cap("17_new_project_android_icon"); sc.close()
    }

    @Test fun buildChatShowsAntigravitySetup() {
        seed(); noAgy()
        val (sc, _) = launch { vm ->
            val store = vm.g.build.store
            store.list.value.forEach { store.delete(it.id) }
            val p = store.create("Clawora Beauty", "web")
            vm.debugHomeTab = 2; vm.debugProjectTab = 0; vm.openProject(p.id) }
        cap("18_build_chat_antigravity_setup_card"); sc.close()
    }

    @Test fun solidTopBarWhileScrolling() {
        seed()
        val t0 = System.currentTimeMillis() - 60_000
        val msgs = (1..6).flatMap { i -> listOf(
            Message("u$i", Role.USER, "Question $i: tell me something long about Karachi", createdAt = t0 + i * 1000L),
            Message("a$i", Role.ASSISTANT, "Karachi is the largest city in Pakistan. ".repeat(14), model = "Groq · llama-3.3-70b-versatile", createdAt = t0 + i * 1000L + 500)) }
        val conv = Conversation("scroll", "Karachi", msgs, createdAt = t0, updatedAt = t0)
        File(app.filesDir, "chats").mkdirs(); File(app.filesDir, "chats/scroll.json").writeText(Json.encodeToString(Conversation.serializer(), conv))
        val sc = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        sc.onActivity { ViewModelProvider(it)[ChatViewModel::class.java].open("scroll") }
        val ok = runCatching { compose.waitUntil(8000) { compose.onAllNodesWithText("Question 6", substring = true).fetchSemanticsNodes().isNotEmpty() } }
        sc.onActivity { val vm = ViewModelProvider(it)[ChatViewModel::class.java]; println("DBG19 ok=${ok.isSuccess} conv=${vm.conversationId} msgs=${vm.messages.size} load=${kotlinx.coroutines.runBlocking { runCatching { app.graph.chats.load("scroll")?.messages?.size }.toString() }}") }
        runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeDown(startY = centerY - 300f, endY = centerY + 300f) } }
        cap("19_top_bar_solid_while_scrolling"); sc.close()
    }
}
