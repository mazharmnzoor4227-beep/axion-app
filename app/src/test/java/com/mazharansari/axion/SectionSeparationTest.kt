package com.mazharansari.axion

import androidx.test.core.app.ApplicationProvider
import com.mazharansari.axion.agent.NoModelException
import com.mazharansari.axion.data.*
import com.mazharansari.axion.llm.Mode
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Section
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** v0.4 (mazhar 2:28 PM): Ask = API keys only, Build = Antigravity only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AxionApp::class)
class SectionSeparationTest {
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as MutableStateFlow<T>

    private fun agyReadyEverything() {
        val g = app.graph
        File(g.linux.rootfs, "usr/bin").mkdirs(); File(g.linux.rootfs, "usr/bin/bash").writeText("#"); File(g.linux.rootfs, com.mazharansari.axion.linux.LinuxBase.ROOTFS_MARKER).writeText("x")
        flow<Map<String, com.mazharansari.axion.linux.ToolRecord>>(g.devtools, "_records").value = mapOf("agy" to com.mazharansari.axion.linux.ToolRecord("1.1.27"))
        val st = File(app.cacheDir, "st").apply { mkdirs(); File(this, "antigravity-oauth-token").writeText("t") }
        g.agy.recordSignedIn("a@gmail.com", st)
        g.agy.setUseInChat("first")   // the old v0.4-draft setting must not leak Antigravity into Ask
        flow<List<Connector>>(g.connectors, "_list").value = listOf(Connector("c1", "groq", "plain:" + android.util.Base64.encodeToString("k".toByteArray(), 2), "gsk_…1", listOf("llama-3.3-70b-versatile"), listOf("llama-3.3-70b-versatile"), addedAt = 1))
    }

    @Test fun askNeverUsesAntigravity_buildOnlyUsesIt() {
        agyReadyEverything()
        val vm = ChatViewModel(app)
        assertTrue(vm.buildReady())
        val ask = vm.attempts(Mode.AUTO).map { it.provider }
        assertFalse(ask.toString(), ask.any { it.contains("Antigravity") })
        assertEquals("Free (slow) is the last resort", com.mazharansari.axion.llm.FreeFallback.NAME, ask.last())
        val build = vm.buildAttempts().map { it.provider }
        assertEquals(listOf("Antigravity"), build)
    }

    @Test fun buildWithoutAntigravityRefusesInsteadOfUsingKeys() {
        val vm = ChatViewModel(app)
        assertFalse(vm.buildReady())
        try { vm.buildAttempts(); fail("Build must not fall back to API keys") } catch (e: NoModelException) { assertTrue(e.message!!.contains("Antigravity")) }
    }

    @Test fun sectionsSaveSeparately() {
        val vm = ChatViewModel(app)
        vm.newChat(Section.BUILD); assertEquals(Section.BUILD, vm.section)
        vm.newChat(Section.ASK); assertEquals(Section.ASK, vm.section)
        assertEquals(Section.BUILD, Section.of("build")); assertEquals(Section.ASK, Section.of(null))
    }
}
