package com.mazharansari.axion

import androidx.test.core.app.ApplicationProvider
import com.mazharansari.axion.agent.*
import com.mazharansari.axion.data.*
import com.mazharansari.axion.llm.*
import com.mazharansari.axion.skills.SkillRepo
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v0.7.0 unified tools (owner: "ask ho ya build, jo bolun wo ho jaye"): the intent label no longer decides
 * whether Axion CAN act in a chat. Small talk still gets no tools; any real chat or lookup request is
 * offered the web + phone tools (when the phone group is switched on); file/command work still needs ACTION.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AxionApp::class)
class UnifiedToolsTest {
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()

    private class Rec : AgentSink {
        override fun text(delta: String) {}
        override fun reasoning(delta: String) {}
        override fun textToThoughts() {}
        override fun step(step: Step) {}
        override fun artifact(a: Artifact) {}
        override fun model(label: String) {}
    }

    private class Fake(val name: String) : Tool {
        override val spec = ToolSpec(name, "fake $name", schema { str("q", "q", required = false) })
        override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, name, "")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolOutput("ran $name")
    }

    private val pool = listOf("web_search", "browse_url", "http_request", "phone_screen", "phone_tap", "run_command", "write_file").map { Fake(it) }
    private val webAndPhone = setOf("web_search", "browse_url", "http_request", "phone_screen", "phone_tap")

    private fun offeredTools(text: String, intent: Intent, sw: Map<String, Boolean>): Set<String> {
        val seen = mutableListOf<LlmRequest>()
        val client = object : LlmClient {
            override fun stream(req: LlmRequest) = flow { seen += req; emit(LlmEvent.Text("ok")); emit(LlmEvent.Finished("stop")) }
        }
        val agent = Agent(
            Workspace(File(app.filesDir, "workspace")), SkillRepo(app, File(app.filesDir, "skills")),
            baseTools = pool, extraTools = { emptyList() }, toolSwitches = { sw },
        )
        runBlocking { agent.run(emptyList(), UserItem(text), { listOf(Attempt("Fake", "m", client)) }, Rec(), intent = intent) }
        assertTrue("the model was never called", seen.isNotEmpty())
        return seen[0].tools.map { it.name }.toSet()
    }

    @Test fun smallTalkStillGetsNoTools() {
        // Even with the phone switched on, a greeting stays a greeting: instant, cheap, tool-free (v0.3 rule).
        assertEquals(emptySet<String>(), offeredTools("Assalam o alaikum", Intent.CHAT, mapOf("phone" to true)))
        assertEquals(emptySet<String>(), offeredTools("hello Kya Hal he", Intent.CHAT, mapOf("phone" to true)))
    }

    @Test fun realChatRequestGetsWebAndPhone() {
        val got = offeredTools("What is the difference between a virus and a bacterium in detail?", Intent.CHAT, mapOf("phone" to true))
        assertEquals(webAndPhone, got)
    }

    @Test fun lookupGetsWebAndPhoneToo() {
        // The owner's exact case: "browser kholo, research karke batao" classifies as a lookup — the phone must be there.
        val got = offeredTools("aaj karachi ka mausam kaisa hai", Intent.LOOKUP, mapOf("phone" to true))
        assertEquals(webAndPhone, got)
    }

    @Test fun phoneSwitchedOffStaysOutOfChatAndLookup() {
        // The Tools library switch (and with it the consent) is the real gate — off means off in every intent.
        val webOnly = setOf("web_search", "browse_url", "http_request")
        assertEquals(webOnly, offeredTools("aaj karachi ka mausam kaisa hai", Intent.LOOKUP, emptyMap()))
        assertEquals(webOnly, offeredTools("What is the difference between a virus and a bacterium in detail?", Intent.CHAT, emptyMap()))
    }

    @Test fun actionStillGetsEverything() {
        val got = offeredTools("make a pdf of this now", Intent.ACTION, mapOf("phone" to true))
        assertEquals(pool.map { it.spec.name }.toSet(), got)
    }

    @Test fun catalogAndGatePhoneListsMatch() {
        // IntentGate.phoneTools must name exactly the ToolCatalog "phone" group, or a tool would silently vanish from chat.
        assertEquals(ToolCatalog.groups.first { it.id == "phone" }.tools, IntentGate.phoneTools)
        assertEquals(IntentGate.lookupTools, ToolCatalog.groups.first { it.id == "web" }.tools)
    }
}
