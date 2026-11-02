package com.mazharansari.axion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.mazharansari.axion.agent.PdfWriter
import com.mazharansari.axion.data.*
import com.mazharansari.axion.ui.ChatViewModel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w404dp-h897dp-xxhdpi", application = AxionApp::class)
class ScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val shots = File(System.getProperty("user.dir"), "build/screens").apply { mkdirs() }
    private fun shot(name: String) { compose.waitForIdle(); captureScreenRoboImage(File(shots, "$name.png").path, RoborazziOptions()) }

    private fun seed(): String {
        val app = ApplicationProvider.getApplicationContext<AxionApp>()
        val fd = app.filesDir
        File(fd, "settings.json").writeText("""{"userName":"Mazhar Ansari","theme":"dark","mode":"AUTO"}""")
        fun conn(p: String, hint: String, models: List<String>) = Connector(java.util.UUID.randomUUID().toString(), p, "plain:" + android.util.Base64.encodeToString("test-key".toByteArray(), 2), hint, models, models, addedAt = 1)
        File(fd, "connectors.json").writeText(Json.encodeToString(ListSerializer(Connector.serializer()), listOf(
            conn("gemini", "AIza…x9Qk", listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemma-4-26b-a4b-it")),
            conn("groq", "gsk_…7hTn", listOf("openai/gpt-oss-120b", "moonshotai/kimi-k2")),
            conn("openrouter", "sk-o…2c1f", listOf("nvidia/nemotron-3-ultra-550b-a55b:free")),
        )))
        val report = File(fd, "workspace/outputs/Clawora Beauty Audit Report.pdf").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(86_000)) }
        val t0 = System.currentTimeMillis() - 120_000
        fun st(k: StepKind, title: String, target: String, out: String = "") = Step(java.util.UUID.randomUUID().toString(), k, title, target, out, StepStatus.DONE, t0, t0 + 2000)
        val steps = listOf(
            st(StepKind.SKILL, "Loaded skill", "website-audit"),
            st(StepKind.SEARCH, "Searched", "claworabeauty.store reviews", "1. Clawora Beauty  https://claworabeauty.store/ …"),
            st(StepKind.BROWSE, "Browsed", "claworabeauty.store", "Title: Original Korean Skincare in Pakistan — Clawora Beauty"),
            st(StepKind.BROWSE, "Browsed", "claworabeauty.store", "COSRX Advanced Snail 92 All In One Cream Price in Pakistan"),
            st(StepKind.BROWSE, "Browsed", "claworabeauty.store", "Shipping & Returns policy"),
            st(StepKind.COMMAND, "Bash", "ls uploads/ outputs/", "outputs:\nuploads:"),
            st(StepKind.COMMAND, "Bash", "grep -c '<img' outputs/home.html", "38"),
            st(StepKind.PDF, "Created PDF", "Clawora Beauty Audit Report.pdf"),
        )
        val conv = Conversation("demo", "claworabeauty.store ka full audit", listOf(
            Message("u1", Role.USER, "claworabeauty.store ka full audit karo aur PDF report bana ke do", createdAt = t0),
            Message("a1", Role.ASSISTANT, SAMPLE, steps = steps, artifacts = listOf(Artifact("Clawora Beauty Audit Report.pdf", "application/pdf", report.path, 86_000, 3)),
                reasoning = "Pehle website-audit skill load karta hoon, phir home, product aur policy pages dekhta hoon…", thinkingMs = 96_000,
                model = "Google Gemini · gemini-3.8-flash", createdAt = t0 + 96_000),
        ), createdAt = t0, updatedAt = System.currentTimeMillis())
        File(fd, "workspace/outputs/images").mkdirs()
        listOf(File(System.getProperty("user.dir"), "build/live/imagine_live.jpg"), File("/tmp/nova/poll.jpg")).filter { it.exists() }
            .forEachIndexed { i, f -> f.copyTo(File(fd, "workspace/outputs/images/image_$i.jpg"), overwrite = true) }
        File(fd, "chats").mkdirs(); File(fd, "chats/demo.json").writeText(Json.encodeToString(conv))
        return conv.id
    }

    @org.junit.Ignore("v0.2 click-through script: after its swipe-up step it no longer finds the chat list (chat layout changed in v0.3.1). Replaced by V031ScreensTest and V04ScreensTest.")

    @Test fun screens() {
        val id = seed()
        val sc = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        shot("01_home")
        compose.onNodeWithContentDescription("Attach").performClick(); shot("03_plus_popover")
        Espresso_back()
        compose.onNodeWithText("Auto").performClick(); shot("04_model_popover")
        Espresso_back()
        // v0.4: Imagine moved off the top bar into the + menu (mazhar, 1 Nov 2:08 PM)
        compose.onNodeWithText("Build").performClick(); shot("09_build")
        compose.onNodeWithText("Ask").performClick()
        sc.onActivity { ViewModelProvider(it)[ChatViewModel::class.java].open(id) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Thought for", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Bash").onFirst().performClick()
        shot("02_chat_thoughts_bash")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Top 3 fixes", substring = true))
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performTouchInput { swipeUp() }
        shot("02b_chat_pdf_card")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToIndex(0)
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Thought for", substring = true))
        compose.onNodeWithText("Thought for", substring = true).performClick(); shot("05_thoughts_sheet")
        Espresso_back()
        compose.onNodeWithContentDescription("Menu").performClick(); shot("06_drawer")
        compose.onNodeWithText("Connectors").performClick(); shot("07_connectors")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Hugging Face")); shot("07b_connectors_providers")
        Espresso_back()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick(); shot("08_settings")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Voice").performClick(); compose.mainClock.advanceTimeBy(800); captureScreenRoboImage(File(shots, "10_voice_library.png").path)
        compose.mainClock.autoAdvance = true
        Espresso_back()
        compose.onNodeWithText("Appearance").performClick(); shot("12_appearance")
        sc.close()
    }

    private fun Espresso_back() {
        androidx.test.espresso.Espresso.pressBack(); compose.waitForIdle()
    }

    /** Renders the PDF layout engine to PNG pages (the phone writes the same drawing into a real PDF). */
    @Test fun pdfLayoutPreview() {
        val md = File(System.getProperty("user.dir"), "build/live/live_gemini_audit.json").takeIf { it.exists() }?.let {
            Json { ignoreUnknownKeys = true }.decodeFromString(Conversation.serializer(), it.readText()).messages.last().text
        } ?: SAMPLE
        var bmp: Bitmap? = null
        val n = PdfWriter.render("Clawora Beauty — Quick Audit", md + "\n\n" + SAMPLE, object : PdfWriter.Pages {
            override fun begin(n: Int): Canvas { bmp = Bitmap.createBitmap(PdfWriter.PAGE_W * 2, PdfWriter.PAGE_H * 2, Bitmap.Config.ARGB_8888); val c = Canvas(bmp!!); c.drawColor(Color.WHITE); c.scale(2f, 2f); return c }
            override fun end(n: Int) { File(shots, "pdf_page_$n.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        })
        println("PDF pages rendered: $n")
        assert(n >= 1)
    }

    companion object { val SAMPLE = """Clawora Beauty ka audit mukammal ho gaya ✅ Poori report PDF mein upar hai. Short summary:

| Area | Score | Note |
| :--- | :--- | :--- |
| Design & mobile | 8/10 | Clean, fast, mobile-first |
| Trust | 9/10 | COD + WhatsApp confirm + policies |
| Product info | 7/10 | Ingredients hain, how-to-use kam |
| SEO | 8/10 | Titles/meta solid, alt text missing |
| Social proof | 4/10 | Reviews bohot kam |
| **Overall** | **7.2/10** | |

**Top 3 fixes**
1. Har product image pe **alt text** daalein ("COSRX Snail 92 Cream Pakistan").
2. Product pages pe **reviews + star rating** dikhayein, pehle 20 customers se WhatsApp pe review mangein.
3. Home page pe **Best Sellers** aur **Shop by Concern** sections add karein.""" }
}
