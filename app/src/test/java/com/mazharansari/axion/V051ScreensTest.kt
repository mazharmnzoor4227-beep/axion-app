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
import com.mazharansari.axion.data.*
import com.mazharansari.axion.linux.*
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.home.ChatGap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * v0.5.1 chat ↔ composer gap, before/after, at mazhar's exact screen (720×1600 px, 1.75x = 411×914 dp).
 * "before" = the v0.5.0 spacing (ChatGap.debugLegacy), "after" = this release. Keyboard = real IME insets (582 px,
 * the height measured in his Grok recording); the keyboard itself isn't drawn by Robolectric.
 */
@OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-280dpi", application = AxionApp::class)
class V051ScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val shots = File(System.getProperty("user.dir"), "build/screens_v051").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as MutableStateFlow<T>

    @After fun reset() { ChatGap.debugLegacy = false }

    private fun seed() {
        val fd = app.filesDir
        File(fd, "settings.json").writeText("""{"userName":"Mazhar Ansari","theme":"dark","mode":"AUTO","buildIntroShown":true}""")
        val conns = listOf(Connector("c-groq", "groq", "plain:" + android.util.Base64.encodeToString("k".toByteArray(), 2), "gsk_…7Qa1", listOf("openai/gpt-oss-20b"), listOf("openai/gpt-oss-20b"), addedAt = 1))
        File(fd, "connectors.json").writeText(Json.encodeToString(ListSerializer(Connector.serializer()), conns))
        flow<List<Connector>>(app.graph.connectors, "_list").value = conns
        // Build needs Linux + Antigravity "ready" to open a project (same seeding as V05ScreensTest).
        val g = app.graph
        File(g.linux.rootfs, "usr/bin").mkdirs(); File(g.linux.rootfs, "usr/bin/bash").writeText("#"); File(g.linux.rootfs, LinuxBase.ROOTFS_MARKER).writeText("x")
        flow<LinuxState>(g.linux, "_state").value = LinuxState.Ready
        val now = System.currentTimeMillis()
        flow<Map<String, ToolRecord>>(g.devtools, "_records").value = mapOf(
            "core" to ToolRecord("2.43.0", 0, installedAt = now),
            "web" to ToolRecord("Node 24.11.0", 333_952, "Node 24.11.0", now, now),
            "agy" to ToolRecord("1.1.27", 195_352, installedAt = now),
        )
        val st = File(app.cacheDir, "stage-m").apply { mkdirs(); File(this, "antigravity-oauth-token").writeText("tok") }
        g.agy.recordSignedIn("mazhar.ansari34@gmail.com", st); g.agy.setActive("mazhar.ansari34@gmail.com")
    }

    private fun insets(sc: ActivityScenario<MainActivity>, ime: Int) = sc.onActivity { act ->
        val b = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 63, 0, 0)).setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 30))
        if (ime > 0) b.setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime)).setVisible(WindowInsetsCompat.Type.ime(), true)
        ViewCompat.dispatchApplyWindowInsets(act.window.decorView, b.build())
    }

    private fun setMessages(vm: ChatViewModel, list: List<Message>) {
        val f = vm.javaClass.getDeclaredField("messages\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") (f.get(vm) as androidx.compose.runtime.MutableState<List<Message>>).value = list
    }

    private val story = "ایک دفعہ کا ذکر ہے، ایک چھوٹے سے گاؤں میں ایک لڑکا رہتا تھا جس کا نام علی تھا۔ وہ ہر صبح اپنے دادا کے ساتھ کھیتوں میں جاتا اور پرندوں کی آوازیں سنتا۔ ایک دن اسے درخت کے نیچے ایک پرانا صندوق ملا۔"
    private val roman = "Theek hai, ek aur kahani:\n\nEk baar ek chhoti si machhli thi jiska naam Noori tha. Wo samundar ke kinare rehti thi aur har shaam suraj ko doobta dekhti thi. Ek din ek toofan aaya aur Noori raasta bhool gayi. Ek budhe kachhuye ne usse ghar ka raasta dikhaya, aur tab se dono pakke dost ban gaye.\n\nSabaq: musibat mein madad karne wala hi asli dost hota hai."

    private fun chat(state: String): List<Message> {
        val now = System.currentTimeMillis()
        val base = listOf(
            Message("uA", Role.USER, "Assalam o alaikum bhai", createdAt = now - 300_000),
            Message("aA", Role.ASSISTANT, "Walaikum assalam! Kaise ho? Aaj kya karna hai — kahani, kaam ya kuch aur?", model = "Groq · llama-3.3-70b-versatile", createdAt = now - 290_000),
            Message("u0", Role.USER, "Mujhe ek Urdu kahani sunao", createdAt = now - 120_000),
            Message("a0", Role.ASSISTANT, story + "\n\n" + story + "\n\n" + story, model = "Groq · openai/gpt-oss-20b", thinkingMs = 3_000, createdAt = now - 110_000),
            Message("u1", Role.USER, "dusra do", createdAt = now - 13_000),
        )
        return base + when (state) {
            "thinking" -> Message("a1", Role.ASSISTANT, status = MsgStatus.STREAMING, model = "Groq · openai/gpt-oss-20b", thinkingStartedAt = now - 12_000, createdAt = now - 12_000)
            "switching" -> Message("a1", Role.ASSISTANT, status = MsgStatus.STREAMING, model = "Groq · llama-3.3-70b-versatile", thinkingStartedAt = now - 9_000, createdAt = now - 9_000, notice = "Switching to Groq · llama-3.3-70b-versatile…")
            else -> Message("a1", Role.ASSISTANT, roman, model = "Groq · llama-3.3-70b-versatile", thinkingMs = 9_000, createdAt = now - 9_000)
        }
    }

    private fun shot(name: String, state: String, legacy: Boolean, ime: Int, build: Boolean = false) {
        seed(); ChatGap.debugLegacy = legacy
        val project = if (build) app.graph.build.store.let { store -> store.list.value.forEach { p -> store.delete(p.id) }; store.create("Clawora Beauty", "web") } else null
        val sc = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle(); insets(sc, ime)
        var vm: ChatViewModel? = null
        sc.onActivity {
            vm = ViewModelProvider(it)[ChatViewModel::class.java]
            if (project != null) { vm!!.debugHomeTab = 2; vm!!.openProject(project.id) }
        }
        compose.mainClock.advanceTimeBy(800)
        sc.onActivity { setMessages(vm!!, chat(state)) }
        compose.mainClock.advanceTimeBy(1200)
        runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 20_000f) } }
        compose.mainClock.advanceTimeBy(1500)
        compose.onAllNodes(isRoot())[0].captureRoboImage(File(shots, "$name.png").path)
        sc.close()
    }

    private val KB = 582

    @Test fun askThinkingBefore() = shot("ask_thinking_kbclosed_before", "thinking", true, 0)
    @Test fun askThinkingAfter() = shot("ask_thinking_kbclosed_after", "thinking", false, 0)
    @Test fun askThinkingKbBefore() = shot("ask_thinking_kbopen_before", "thinking", true, KB)
    @Test fun askThinkingKbAfter() = shot("ask_thinking_kbopen_after", "thinking", false, KB)
    @Test fun askDoneBefore() = shot("ask_answer_kbclosed_before", "done", true, 0)
    @Test fun askDoneAfter() = shot("ask_answer_kbclosed_after", "done", false, 0)
    @Test fun askDoneKbBefore() = shot("ask_answer_kbopen_before", "done", true, KB)
    @Test fun askDoneKbAfter() = shot("ask_answer_kbopen_after", "done", false, KB)
    @Test fun askSwitching() = shot("ask_switching_model_after", "switching", false, 0)
    @Test fun buildThinkingBefore() = shot("build_thinking_kbopen_before", "thinking", true, KB, build = true)
    @Test fun buildThinkingAfter() = shot("build_thinking_kbopen_after", "thinking", false, KB, build = true)
    @Test fun buildDoneAfter() = shot("build_answer_kbclosed_after", "done", false, 0, build = true)
}

