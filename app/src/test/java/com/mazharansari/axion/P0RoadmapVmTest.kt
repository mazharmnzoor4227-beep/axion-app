package com.mazharansari.axion

import androidx.test.core.app.ApplicationProvider
import com.mazharansari.axion.agent.NoModelException
import com.mazharansari.axion.data.*
import com.mazharansari.axion.llm.Mode
import com.mazharansari.axion.ui.ChatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P0-4 (HI-2): LOCAL / SERVER mode fails loudly instead of silently falling back to cloud keys. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AxionApp::class)
class P0RoadmapVmTest {
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as MutableStateFlow<T>

    private fun seed(vararg cs: Connector) { flow<List<Connector>>(app.graph.connectors, "_list").value = cs.toList() }
    private fun setMode(m: String) { app.graph.settings.update { it.copy(mode = m) } }
    private fun blob(s: String) = "plain:" + android.util.Base64.encodeToString(s.toByteArray(), 2)
    private fun groq() = Connector("c1", "groq", blob("k"), "gsk_…1", listOf("llama-3.3-70b-versatile"), listOf("llama-3.3-70b-versatile"), addedAt = 1)
    private fun local(modelId: String) = Connector("c2", "local", blob("local"), modelId, listOf(modelId), listOf(modelId), addedAt = 1, localModel = modelId)
    private fun server() = Connector("c3", "myserver", blob(""), "example.invalid", listOf("qwen2.5-coder:7b"), listOf("qwen2.5-coder:7b"), addedAt = 1, baseUrl = "https://example.invalid/v1")

    @Test fun localModeWithoutLocalModelRefusesInsteadOfUsingKeys() {
        seed(groq()); setMode("LOCAL")
        val vm = ChatViewModel(app)
        try { vm.attempts(Mode.AUTO); fail("Local mode with no local model must fail loudly") }
        catch (e: NoModelException) { assertTrue(e.message!!, e.message!!.contains("Local mode")) }
    }

    @Test fun serverModeWithoutServerRefusesInsteadOfUsingKeys() {
        seed(groq()); setMode("SERVER")
        val vm = ChatViewModel(app)
        try { vm.attempts(Mode.AUTO); fail("Server mode with no server must fail loudly") }
        catch (e: NoModelException) { assertTrue(e.message!!, e.message!!.contains("Server mode")) }
    }

    @Test fun localModeWithDeletedModelRefuses() {
        seed(groq(), local("no-such-model")); setMode("LOCAL")
        val vm = ChatViewModel(app)
        try { vm.attempts(Mode.AUTO); fail("Local mode with an unknown local model must fail loudly") }
        catch (e: NoModelException) { assertTrue(e.message!!, e.message!!.contains("could not start")) }
    }

    @Test fun localModeWithWorkingModelUsesOnlyIt() {
        seed(groq(), local("lfm3-1.7b")); setMode("LOCAL")
        assertEquals(listOf("Local model"), ChatViewModel(app).attempts(Mode.AUTO).map { it.provider })
    }

    @Test fun serverModeWithServerUsesOnlyIt() {
        seed(groq(), server()); setMode("SERVER")
        assertEquals(listOf("My server"), ChatViewModel(app).attempts(Mode.AUTO).map { it.provider })
    }
}
