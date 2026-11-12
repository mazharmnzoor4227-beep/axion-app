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
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** v0.6 Phase 3: Tools library, rendered at mazhar's screen size. */
@OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-280dpi", application = AxionApp::class)
class V06ScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val shots = File(System.getProperty("user.dir"), "build/screens_v06").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()
    private fun cap(name: String) { compose.mainClock.advanceTimeBy(1200); compose.onAllNodes(isRoot())[0].captureRoboImage(File(shots, "$name.png").path) }
    private fun launch(route: Route): Pair<ActivityScenario<MainActivity>, ChatViewModel> {
        File(app.filesDir, "settings.json").writeText("""{"theme":"dark","mode":"AUTO","buildIntroShown":true,"guestChosen":true,"introShown":true,"permsShown":true}""")
        app.graph.settings.update { it.copy(buildIntroShown = true, guestChosen = true, introShown = true, permsShown = true, phoneConsent = false, toolSwitches = emptyMap()) }
        val sc = ActivityScenario.launch(MainActivity::class.java); compose.waitForIdle()
        sc.onActivity { act ->
            val b = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 63, 0, 0)).setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 30))
            ViewCompat.dispatchApplyWindowInsets(act.window.decorView, b.build())
        }
        var vm: ChatViewModel? = null
        sc.onActivity { vm = ViewModelProvider(it)[ChatViewModel::class.java]; it.let { vm!!.debugStart = route } }
        compose.mainClock.advanceTimeBy(1200)
        return sc to vm!!
    }

    private fun scrollTo(text: String) { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)); compose.mainClock.advanceTimeBy(300) }

    @Test fun toolsLibraryShowsEveryToolAndTheSwitchWorks() {
        val (sc, vm) = launch(Route.ToolsLibrary)
        compose.onNodeWithText("Tools library").assertExists()
        listOf("Web search & pages", "Files & workspace", "PDF reports", "Image generation", "Video editing", "Run commands", "Skills", "Connectors (MCP)").forEach { compose.onAllNodesWithText(it).onFirst().assertExists() }
        cap("01_tools_library")
        compose.onNodeWithText("PDF reports").performClick(); compose.mainClock.advanceTimeBy(500)
        assertEquals(false, vm.g.settings.state.value.toolSwitches["documents"])
        compose.onNodeWithText("PDF reports").performClick(); compose.mainClock.advanceTimeBy(500)
        assertEquals(true, vm.g.settings.state.value.toolSwitches["documents"])
        sc.close()
    }


}
