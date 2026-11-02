
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
import com.mazharansari.axion.build.PatchEngine
import com.mazharansari.axion.data.*
import com.mazharansari.axion.linux.*
import com.mazharansari.axion.local.DlState
import com.mazharansari.axion.local.LocalModel
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

/** v0.5 stage 2 renders (mazhar's phone size): Build projects, project workspace, preview/publish/APK, first-run popup, own models. */
@OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi", application = AxionApp::class)
class V05ScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val shots = File(System.getProperty("user.dir"), "build/screens_v05s2").apply { mkdirs() }
    private fun cap(name: String) {
        compose.mainClock.advanceTimeBy(1500)
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
        compose.onAllNodes(isRoot())[0].captureRoboImage(File(shots, "$name.png").path)
        for (i in 1 until roots) compose.onAllNodes(isRoot())[i].captureRoboImage(File(shots, "${name}__layer$i.png").path)
    }
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as MutableStateFlow<T>

    private fun seed(introShown: Boolean = true, own: Boolean = false) {
        val fd = app.filesDir
        org.robolectric.shadows.ShadowStatFs.registerStats(fd, 28_000_000, 1_650_000, 1_650_000)
        File(fd, "settings.json").writeText("""{"userName":"Mazhar Ansari","theme":"dark","mode":"AUTO","buildIntroShown":$introShown}""")
        fun conn(p: String, hint: String, models: List<String>, extra: (Connector) -> Connector = { it }) =
            extra(Connector("c-$p-$hint", p, "plain:" + android.util.Base64.encodeToString("test-key".toByteArray(), 2), hint, models, models, addedAt = 1))
        val conns = buildList {
            add(conn("groq", "gsk_…7Qa1", listOf("llama-3.3-70b-versatile")))
            add(conn("gemini", "AQ.…x9Qk", listOf("gemini-3.8-flash")))
            if (own) {
                add(conn("local", "Qwen2.5-Coder 1.5B", listOf("qwen2.5-coder-1.5b")) { it.copy(localModel = "qwen2.5-coder-1.5b") })
                add(conn("myserver", "1-2-3-4.sslip.io", listOf("qwen2.5-coder:7b", "qwen2.5-coder:14b")) { it.copy(baseUrl = "https://1-2-3-4.sslip.io/v1", pinnedModel = "qwen2.5-coder:7b") })
            }
        }
        File(fd, "connectors.json").writeText(Json.encodeToString(ListSerializer(Connector.serializer()), conns))
        val g = app.graph
        flow<List<Connector>>(g.connectors, "_list").value = conns
        File(g.linux.rootfs, "usr/bin").mkdirs(); File(g.linux.rootfs, "usr/bin/bash").writeText("#"); File(g.linux.rootfs, LinuxBase.ROOTFS_MARKER).writeText("x")
        flow<LinuxState>(g.linux, "_state").value = LinuxState.Ready
        val now = System.currentTimeMillis()
        flow<Map<String, ToolRecord>>(g.devtools, "_records").value = mapOf(
            "core" to ToolRecord("2.43.0", 0, installedAt = now),
            "web" to ToolRecord("Node 24.11.0", 333_952, "Node 24.11.0", now, now),
            "python" to ToolRecord("3.12.3", 77_420, "3.12.3", now, now),
            "agy" to ToolRecord("1.1.27", 195_352, installedAt = now),
        )
        listOf("mazhar.ansari34@gmail.com").forEach { e ->
            val st = File(app.cacheDir, "stage-$e").apply { mkdirs(); File(this, "antigravity-oauth-token").writeText("tok") }
            g.agy.recordSignedIn(e, st)
        }
        g.agy.setActive("mazhar.ansari34@gmail.com")
    }

    private val site = mapOf(
        "index.html" to "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>Clawora Beauty</title>\n<link rel=\"stylesheet\" href=\"styles.css\">\n</head>\n<body>\n<header class=\"hero\">\n  <h1>Clawora Beauty</h1>\n  <p>Korean skincare, delivered across Pakistan.</p>\n  <a class=\"btn\" href=\"#shop\">Shop now</a>\n</header>\n<main id=\"shop\" class=\"grid\"></main>\n<script src=\"app.js\"></script>\n</body>\n</html>\n",
        "styles.css" to ":root { --accent: #e8577e; --ink: #1b1b1f; }\nbody { margin: 0; font: 17px/1.5 system-ui, sans-serif; color: var(--ink); }\n.hero { padding: 72px 24px 40px; text-align: center; }\n.btn { padding: 14px 26px; border-radius: 999px; background: var(--accent); color: #fff; }\n",
        "app.js" to "const products = [[\"Snail Mucin Essence\", 3450], [\"Centella Toner\", 2900]];\nconst grid = document.getElementById(\"shop\");\nproducts.forEach(([name, price]) => {\n  grid.innerHTML += `<div class=\"card\"><b>\${name}</b>Rs \${price}</div>`;\n});\n",
        "assets/logo.svg" to "<svg xmlns=\"http://www.w3.org/2000/svg\"/>",
        "README.md" to "# Clawora Beauty\nStatic site built with Axion.\n",
    )

    /** 3 projects; returns the Clawora one (with files, a published URL and the PRoot screenshot from the box run). */
    private fun seedProjects(): Project {
        val store = app.graph.build.store
        store.list.value.forEach { store.delete(it.id) }
        val notes = store.create("Notes API", "other")
        File(store.folder(notes), "main.py").writeText("print('hi')\n")
        val apk = store.create("Pocket Budget", "android")
        File(store.folder(apk), "settings.gradle.kts").writeText("rootProject.name = \"PocketBudget\"\n")
        val p = store.create("Clawora Beauty", "web")
        site.forEach { (rel, text) -> File(store.folder(p), rel).apply { parentFile.mkdirs(); writeText(text) } }
        val shot = File(System.getProperty("axion.shot", "/tmp/s2proj/projects/clawora-site/.axion/shot.png"))
        if (shot.isFile) shot.copyTo(File(store.folder(p), ".axion/shot.png").apply { parentFile.mkdirs() }, overwrite = true)
        store.setDeploy(p.id, DeployTarget("netlify", siteId = "S1", url = "https://clawora-beauty.netlify.app", name = "clawora-beauty", at = System.currentTimeMillis()))
        return store.get(p.id)!!
    }

    private fun insets(sc: ActivityScenario<MainActivity>) = sc.onActivity { act ->
        val b = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 108, 0, 0)).setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 45))
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

    private fun buildTab(prep: (ChatViewModel) -> Unit = {}) = launch { vm -> prep(vm); vm.debugHomeTab = 2 }.first

    private fun setMessages(vm: ChatViewModel, list: List<Message>) {
        val f = vm.javaClass.getDeclaredField("messages\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") (f.get(vm) as androidx.compose.runtime.MutableState<List<Message>>).value = list
    }

    private fun buildChat(p: Project): List<Message> {
        val now = System.currentTimeMillis()
        val old = site["index.html"]!!.replace("<p>Korean skincare, delivered across Pakistan.</p>\n", "")
        val diff = PatchEngine.unified(old, site["index.html"]!!, "index.html")
        val streaming = site["styles.css"]!!.lines().take(3).joinToString("\n")
        val steps = listOf(
            Step("s1", StepKind.READ, "Listed", "/root/projects/${p.slug}", status = StepStatus.DONE, startedAt = now - 40_000, endedAt = now - 39_000),
            Step("s2", StepKind.WRITE, "Created (+19 −0)", "app.js", status = StepStatus.DONE, startedAt = now - 30_000, endedAt = now - 26_000, detail = site["app.js"]!!),
            Step("s3", StepKind.WRITE, "Edited (+1 −0)", "index.html", status = StepStatus.DONE, startedAt = now - 20_000, endedAt = now - 18_000, detail = diff),
            Step("s4", StepKind.COMMAND, "Bash", "axion-check .", output = "OK: . looks fine", status = StepStatus.DONE, startedAt = now - 14_000, endedAt = now - 12_000),
            Step("s5", StepKind.WRITE, "Writing", "styles.css", status = StepStatus.RUNNING, startedAt = now - 3_000, detail = streaming),
        )
        return listOf(
            Message("u1", Role.USER, "Build a one-page shop for Clawora Beauty: hero, products with prices in Rs, pink accent.", createdAt = now - 60_000),
            Message("a1", Role.ASSISTANT, "Plan: a hero, a product grid from app.js, and a pink theme. Writing the files now.", steps = steps, status = MsgStatus.STREAMING,
                model = "Antigravity · Gemini 3 Pro", createdAt = now - 50_000, thinkingStartedAt = now - 50_000),
        )
    }

    @Test fun projectsList() { seed(); seedProjects(); val sc = buildTab(); cap("01_build_projects"); sc.close() }

    @Test fun buildFirstRunPopup() { seed(introShown = false); seedProjects(); val sc = buildTab { it.debugBuildIntro = true }; cap("02_build_first_run_tools_popup"); sc.close() }

    @Test fun newProjectSheet() { seed(); seedProjects(); val sc = buildTab { it.debugNewProject = true }; cap("03_new_project"); sc.close() }

    @Test fun projectChatLiveCode() {
        seed(); val p = seedProjects()
        val (sc, vm) = launch { vm -> vm.debugHomeTab = 2; vm.openProject(p.id) }
        compose.mainClock.advanceTimeBy(600)
        sc.onActivity { setMessages(vm, buildChat(p)) }
        compose.mainClock.advanceTimeBy(800)
        // open the finished index.html edit to show its diff
        runCatching { compose.onAllNodesWithText("index.html", substring = true).onFirst().performClick() }
        cap("04_project_chat_live_code_and_diff"); sc.close()
    }

    @Test fun projectFiles() {
        seed(); val p = seedProjects()
        val sc = launch { vm -> vm.debugHomeTab = 2; vm.debugProjectTab = 1; vm.openProject(p.id) }.first
        runCatching { compose.onNodeWithText("assets").performClick() }
        cap("05_project_files"); sc.close()
    }

    @Test fun codeEditor() {
        seed(); val p = seedProjects()
        val sc = launch { vm -> vm.debugHomeTab = 2; vm.debugProjectTab = 1; vm.debugOpenFile = "index.html"; vm.openProject(p.id) }.first
        cap("06_code_editor"); sc.close()
    }

    @Test fun codeEditorEditing() {
        seed(); val p = seedProjects()
        val sc = launch { vm -> vm.debugHomeTab = 2; vm.debugProjectTab = 1; vm.debugOpenFile = "styles.css"; vm.debugEditing = true; vm.openProject(p.id) }.first
        cap("06b_code_editor_editing"); sc.close()
    }

    @Test fun preview() {
        seed(); val p = seedProjects()
        val sc = launch { vm -> vm.debugHomeTab = 2; vm.debugProjectTab = 2; vm.openProject(p.id) }.first
        cap("07_preview_and_publish"); sc.close()
    }

    @Test fun publishSheet() {
        seed(); val p = seedProjects()
        app.graph.build.tokens.set(com.mazharansari.axion.build.DeployProvider.NETLIFY, "nfp_testtoken123456")
        val sc = launch { vm -> vm.debugHomeTab = 2; vm.debugProjectTab = 2; vm.debugDeploySheet = true; vm.openProject(p.id) }.first
        cap("08_publish_sheet"); sc.close()
    }

    @Test fun projectMenu() {
        seed(); val p = seedProjects()
        val sc = launch { vm -> vm.debugHomeTab = 2; vm.debugProjectTab = 1; vm.debugProjectMenu = true; vm.openProject(p.id) }.first
        cap("09_project_menu"); sc.close()
    }

    @Test fun androidApkCard() {
        seed(); seedProjects()
        val apk = app.graph.build.store.list.value.first { it.kind == "android" }
        val sc = launch { vm -> vm.debugHomeTab = 2; vm.debugProjectTab = 2; vm.openProject(apk.id) }.first
        cap("10_android_apk_builder"); sc.close()
    }

    @Test fun localModels() {
        seed(own = true)
        val store = app.graph.localModels
        flow<Map<String, DlState>>(store, "_state").value = mapOf(
            LocalModel.QWEN_CODER_1_5B.id to DlState.Ready,
            LocalModel.GEMMA3_1B.id to DlState.Downloading(331_000_000, LocalModel.GEMMA3_1B.bytes),
        )
        val sc = launch { it.debugStart = Route.LocalModels }.first
        compose.mainClock.advanceTimeBy(1200)
        cap("11_local_models"); sc.close()
    }

    @Test fun myServer() { seed(own = true); val sc = launch { it.debugStart = Route.MyServer }.first; compose.mainClock.advanceTimeBy(1200); cap("12_my_server"); sc.close() }

    @Test fun serverGuide() { seed(); val sc = launch { it.debugStart = Route.ServerGuide }.first; compose.mainClock.advanceTimeBy(1200); cap("13_free_server_guide"); sc.close() }

    @Test fun connectorsOwnModels() {
        seed(own = true)
        val sc = launch { it.debugStart = Route.Connectors }.first
        compose.mainClock.advanceTimeBy(1200)
        runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Your own model", substring = true)) }
        cap("14_connectors_your_own_model"); sc.close()
    }

    @Test fun modelMenuOwnModels() {
        seed(own = true)
        val sc = launch { it.debugHomeTab = 0 }.first
        runCatching { compose.onAllNodesWithText("Auto").onFirst().performClick() }
        cap("15_ask_model_menu_local_server"); sc.close()
    }

    private val upd = com.mazharansari.axion.update.UpdateInfo(7, "0.5.1", "https://claworabeauty.store/uploads/dl/axion/Axion-0.5.1.apk", "ab".repeat(32), 16_384_000,
        "Faster Build previews, a fix for publishing to Vercel, and smaller downloads for local models.")

    @Test fun aboutUpdateAvailable() {
        seed(); app.graph.updater.debugSet(com.mazharansari.axion.update.UpdateState.Available(upd))
        val sc = launch { it.debugStart = Route.About }.first; compose.mainClock.advanceTimeBy(1200); cap("16_about_update_available"); sc.close()
    }

    @Test fun aboutUpdateDownloading() {
        seed(); app.graph.updater.debugSet(com.mazharansari.axion.update.UpdateState.Downloading(upd, 9_600_000, 16_384_000))
        val sc = launch { it.debugStart = Route.About }.first; compose.mainClock.advanceTimeBy(1200); cap("16b_about_update_downloading"); sc.close()
    }

    @Test fun aboutUpdateBadHash() {
        seed(); app.graph.updater.debugSet(com.mazharansari.axion.update.UpdateState.Failed("The downloaded file didn't match its checksum, so it was deleted. Try again.", upd))
        val sc = launch { it.debugStart = Route.About }.first; compose.mainClock.advanceTimeBy(1200); cap("16c_about_update_bad_hash"); sc.close()
    }

    @Test fun aboutUpToDate() {
        seed(); app.graph.updater.debugSet(com.mazharansari.axion.update.UpdateState.UpToDate(System.currentTimeMillis()))
        val sc = launch { it.debugStart = Route.About }.first; compose.mainClock.advanceTimeBy(1200); cap("16d_about_up_to_date"); sc.close()
    }

    @Test fun homeUpdateBanner() {
        seed(); app.graph.updater.dismissedCode = 0; app.graph.updater.debugSet(com.mazharansari.axion.update.UpdateState.Available(upd))
        val sc = launch { it.debugHomeTab = 0 }.first; cap("17_home_update_banner"); sc.close()
    }
}
