package com.mazharansari.axion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.mazharansari.axion.data.Connector
import com.mazharansari.axion.ui.components.ScreenHeader
import com.mazharansari.axion.ui.components.Pill
import com.mazharansari.axion.ui.components.Gk
import com.mazharansari.axion.ui.theme.AxionTheme
import com.mazharansari.axion.ui.theme.P
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi", application = AxionApp::class)
class V03ScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val shots = File(System.getProperty("user.dir"), "build/screens_v3").apply { mkdirs() }
    private fun shot(name: String) { compose.waitForIdle(); captureScreenRoboImage(File(shots, "$name.png").path, RoborazziOptions()) }

    private fun seed() {
        val fd = ApplicationProvider.getApplicationContext<AxionApp>().filesDir
        File(fd, "settings.json").writeText("""{"userName":"Mazhar Ansari","theme":"dark","mode":"AUTO"}""")
        fun conn(p: String, hint: String, models: List<String>) = Connector(java.util.UUID.randomUUID().toString(), p, "plain:" + android.util.Base64.encodeToString("test-key".toByteArray(), 2), hint, models, models, addedAt = 1)
        File(fd, "connectors.json").writeText(Json.encodeToString(ListSerializer(Connector.serializer()), listOf(conn("gemini", "AIza…x9Qk", listOf("gemini-3.8-flash")))))
    }

    @Config(qualifiers = "w404dp-h897dp-xxhdpi")
    @org.junit.Ignore("v0.3 script expects a lazy list on Voice Library; since v0.3.1 that page is a plain scrolling column. Covered by V04ScreensTest.voiceSettings.")
    @Test fun voiceLibrary() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Menu").performClick()
            compose.onNodeWithContentDescription("Settings").performClick(); compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Voice").performClick(); compose.mainClock.advanceTimeBy(900)
            captureScreenRoboImage(File(shots, "v3_voice_library_top.png").path)
            val list = compose.onAllNodes(hasScrollToNodeAction()).onFirst()
            list.performScrollToIndex(2); compose.mainClock.advanceTimeBy(500); captureScreenRoboImage(File(shots, "v3_voice_library_kodex.png").path)
            list.performScrollToIndex(4); compose.mainClock.advanceTimeBy(500); captureScreenRoboImage(File(shots, "v3_voice_library_natural.png").path)
            list.performScrollToIndex(6); compose.mainClock.advanceTimeBy(500); captureScreenRoboImage(File(shots, "v3_voice_library_gemini.png").path)
            compose.mainClock.autoAdvance = true
        }
    }

    /** The real call screen (debug hook shows a mid-call state without opening the mic). */
    @Test fun callScreen() {
        seed()
        val app = ApplicationProvider.getApplicationContext<AxionApp>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            compose.waitForIdle()
            sc.onActivity { androidx.lifecycle.ViewModelProvider(it)[com.mazharansari.axion.ui.ChatViewModel::class.java].debugCall =
                com.mazharansari.axion.voice.SameVoiceCall.State(userText = "Aaj Karachi ka mausam kaisa hai?", replyText = "Aaj Karachi mein dhoop hai, takreeban 31 degree. Shaam ko thodi thandi hawa chalegi.") }
            compose.onNodeWithContentDescription("Voice mode").performClick()
            compose.mainClock.autoAdvance = false; compose.mainClock.advanceTimeBy(900)
            captureScreenRoboImage(File(shots, "v3_call_same_voice.png").path)
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun connectorsLogos() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Menu").performClick()
            compose.onNodeWithText("Connectors").performClick(); compose.waitForIdle(); Thread.sleep(300)
            shot("v3_connectors_logos")
            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Anthropic")); compose.waitForIdle()
            shot("v3_connectors_logos_2")
        }
    }


    @Test fun expandableSteps() {
        seed()
        val fd = ApplicationProvider.getApplicationContext<AxionApp>().filesDir
        val t0 = System.currentTimeMillis() - 60_000
        val html = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"utf-8\">\n  <title>Clawora Beauty</title>\n  <style>\n    /* mobile-first */\n    body { font-family: system-ui; margin: 0; background: #fff7f9; }\n    .hero { padding: 48px 20px; text-align: center; }\n  </style>\n</head>\n<body>\n  <section class=\"hero\">\n    <h1>Original Korean Skincare</h1>\n    <p>Cash on Delivery all over Pakistan</p>\n    <a class=\"btn\" href=\"#shop\">Shop now</a>\n  </section>\n  <script>\n    const year = new Date().getFullYear(); // footer year\n    document.title = 'Clawora ' + year;\n  </script>\n</body>\n</html>"
        fun st(k: com.mazharansari.axion.data.StepKind, title: String, target: String, out: String = "", detail: String = "") =
            com.mazharansari.axion.data.Step(java.util.UUID.randomUUID().toString(), k, title, target, out, com.mazharansari.axion.data.StepStatus.DONE, t0, t0 + 1500, detail)
        val steps = listOf(
            st(com.mazharansari.axion.data.StepKind.COMMAND, "Bash", "mkdir -p outputs/clawora && ls outputs", "clawora"),
            st(com.mazharansari.axion.data.StepKind.WRITE, "Created file", "outputs/clawora/index.html", "Wrote 812 bytes", html),
        )
        val conv = com.mazharansari.axion.data.Conversation("demo2", "html page", listOf(
            com.mazharansari.axion.data.Message("u1", com.mazharansari.axion.data.Role.USER, "mera ek kaam karo, ek html page bana do Clawora Beauty ke liye", createdAt = t0),
            com.mazharansari.axion.data.Message("a1", com.mazharansari.axion.data.Role.ASSISTANT, "Ho gaya! Page **outputs/clawora/index.html** mein hai — hero section, COD line aur Shop button ke saath.",
                steps = steps, reasoning = "User ne Clawora Beauty ke liye simple landing page manga hai. Pehle folder banata hoon, phir mobile-first index.html likhta hoon.",
                thinkingMs = 9_000, model = "Groq · openai/gpt-oss-120b", createdAt = t0 + 9_000),
        ), createdAt = t0, updatedAt = System.currentTimeMillis())
        File(fd, "chats").mkdirs(); File(fd, "chats/demo2.json").writeText(Json.encodeToString(com.mazharansari.axion.data.Conversation.serializer(), conv))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            compose.waitForIdle()
            sc.onActivity { androidx.lifecycle.ViewModelProvider(it)[com.mazharansari.axion.ui.ChatViewModel::class.java].open("demo2") }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Thought for", substring = true).fetchSemanticsNodes().isNotEmpty() }
            shot("v3_steps_collapsed")
            compose.onNodeWithContentDescription("Expand").performClick(); compose.waitForIdle(); Thread.sleep(400)
            shot("v3_steps_file_expanded")
            compose.onNodeWithText("Thought for", substring = true).performClick(); compose.waitForIdle(); Thread.sleep(500)
            shot("v3_thoughts_sheet_whole_run")
        }
    }

    /** Model popover with the keyboard open (IME inset injected like on his phone): bottom anchored above the composer, top under the top bar. */
    @Test fun popoverWithKeyboard() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            compose.waitForIdle()
            sc.onActivity { a ->
                val root = a.window.decorView
                val ins = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 96, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 132))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 1050))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
                ViewCompat.dispatchApplyWindowInsets(root, ins)
            }
            compose.waitForIdle()
            compose.onNodeWithText("Auto").performClick(); compose.waitForIdle(); Thread.sleep(400)
            shot("v3_model_popover_keyboard")
            androidx.test.espresso.Espresso.pressBack(); compose.waitForIdle()
            compose.onNodeWithContentDescription("Attach").performClick(); compose.waitForIdle(); Thread.sleep(400)
            shot("v3_plus_popover_keyboard")
        }
    }
}
