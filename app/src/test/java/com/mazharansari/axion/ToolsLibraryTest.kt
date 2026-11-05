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

/** Phase 3.4: every real tool is in the library; a switched-off tool is not offered, and the agent politely says it is off; "Used tool" is reported. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AxionApp::class)
class ToolsLibraryTest {
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()
    private class Rec : AgentSink {
        val text = StringBuilder(); val used = mutableListOf<String>()
        override fun text(delta: String) { text.append(delta) }
        override fun reasoning(delta: String) {}
        override fun textToThoughts() {}
        override fun step(step: Step) {}
        override fun artifact(a: Artifact) {}
        override fun model(label: String) {}
        override fun usedTool(title: String) { used += title }
    }
    private class Fake(val name: String) : Tool {
        var calls = 0
        override val spec = ToolSpec(name, "fake $name", schema { str("q", "q", required = false) })
        override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, name, "")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolOutput("ran $name").also { calls++ }
    }
    private fun agent(tools: List<Tool>, sw: Map<String, Boolean>) = Agent(Workspace(File(app.filesDir, "workspace")), SkillRepo(app, File(app.filesDir, "skills")), baseTools = tools, extraTools = { emptyList() }, toolSwitches = { sw })

    @Test fun everyRealToolBelongsToAGroupAndNamesAreUnique() {
        val names = (Agent.defaultTools() + GenerateImageTool(app.graph.imageGen)).map { it.spec.name }
        assertEquals(names.size, names.toSet().size)
        names.forEach { assertNotNull("tool $it has no group in the Tools library", ToolCatalog.groupOf(it)) }
        ToolCatalog.groups.flatMap { it.tools }.forEach { assertTrue("catalog lists $it but no such tool exists", it in names) }
        assertEquals("connectors", ToolCatalog.groupOf("mcp_gmail_send")!!.id)
        assertEquals(ToolCatalog.groups.size, ToolCatalog.groups.map { it.id }.toSet().size)
        ToolCatalog.groups.forEach { assertTrue(it.title.isNotBlank() && it.description.length > 20) }
    }
    @Test fun defaultsPhoneOffEverythingElseOn() {
        ToolCatalog.groups.forEach { assertEquals(it.id != "phone", ToolCatalog.isEnabled(it, emptyMap())) }
        assertTrue(ToolCatalog.isEnabled("plan_set", mapOf("web" to false))); assertFalse(ToolCatalog.isEnabled("web_search", mapOf("web" to false))); assertTrue(ToolCatalog.isEnabled("phone_tap", mapOf("phone" to true)))
    }
    @Test fun disabledToolIsNotOfferedAndTheModelIsToldToRefusePolitely() = runBlocking {
        val seen = mutableListOf<LlmRequest>()
        val client = object : LlmClient { override fun stream(req: LlmRequest) = flow { seen += req; emit(LlmEvent.Text("PDF tool is off. Turn it on in Settings → Tools library.")); emit(LlmEvent.Finished("stop")) } }
        val pdf = Fake("create_pdf"); val web = Fake("web_search")
        val rec = Rec()
        val out = agent(listOf(pdf, web), mapOf("documents" to false)).run(emptyList(), UserItem("make a pdf report of my notes please"), { listOf(Attempt("Fake", "m", client)) }, rec, intent = Intent.ACTION)
        assertEquals(listOf("web_search"), seen[0].tools.map { it.name })
        val sys = (seen[0].items.first() as SystemItem).text
        assertTrue(sys, sys.contains("TOOLS SWITCHED OFF") && sys.contains("PDF reports") && sys.contains("Tools library"))
        assertTrue(out.contains("off"))
    }
    @Test fun aCallToASwitchedOffToolIsRefusedNotRun() = runBlocking {
        var n = 0
        val client = object : LlmClient { override fun stream(req: LlmRequest) = flow { n++
            if (n == 1) emit(LlmEvent.ToolCalls(listOf(ToolCall("c1", "create_pdf", "{}")))) else { emit(LlmEvent.Text("That tool is off.")); emit(LlmEvent.Finished("stop")) } } }
        val seen = mutableListOf<ChatItem>()
        val pdf = Fake("create_pdf")
        val rec = Rec()
        val tracking = object : LlmClient { override fun stream(req: LlmRequest) = client.stream(req).also { seen += req.items } }
        agent(listOf(pdf, Fake("web_search")), mapOf("documents" to false)).run(emptyList(), UserItem("make a pdf of this now"), { listOf(Attempt("Fake", "m", tracking)) }, rec, intent = Intent.ACTION)
        assertEquals(0, pdf.calls)
        val res = seen.filterIsInstance<ToolResultItem>().first { it.name == "create_pdf" }.content
        assertTrue(res, res.contains("switched OFF") && res.contains("Tools library"))
        assertTrue(rec.used.isEmpty())
    }
    @Test fun usedToolIsReportedByGroupTitle() = runBlocking {
        var n = 0
        val client = object : LlmClient { override fun stream(req: LlmRequest) = flow { n++
            if (n == 1) emit(LlmEvent.ToolCalls(listOf(ToolCall("c1", "web_search", "{}")))) else { emit(LlmEvent.Text("Done.")); emit(LlmEvent.Finished("stop")) } } }
        val rec = Rec()
        agent(listOf(Fake("web_search")), emptyMap()).run(emptyList(), UserItem("search the web for karachi weather today"), { listOf(Attempt("Fake", "m", client)) }, rec, intent = Intent.ACTION)
        assertEquals(listOf("Web search & pages"), rec.used)
    }
    @Test fun phoneToolsAreHiddenUntilSwitchedOnThenGetTheirRules() = runBlocking {
        val seen = mutableListOf<LlmRequest>()
        val client = object : LlmClient { override fun stream(req: LlmRequest) = flow { seen += req; emit(LlmEvent.Text("ok")); emit(LlmEvent.Finished("stop")) } }
        val tools = com.mazharansari.axion.phone.PhoneTools.all()
        agent(tools, emptyMap()).run(emptyList(), UserItem("open the browser and search for weather"), { listOf(Attempt("Fake", "m", client)) }, Rec(), intent = Intent.ACTION)
        assertTrue(seen[0].tools.none { it.name.startsWith("phone_") })
        agent(tools, mapOf("phone" to true)).run(emptyList(), UserItem("open the browser and search for weather"), { listOf(Attempt("Fake", "m", client)) }, Rec(), intent = Intent.ACTION)
        assertEquals(8, seen[1].tools.count { it.name.startsWith("phone_") })
        assertTrue((seen[1].items.first() as SystemItem).text.contains("NEVER operate banking"))
    }
    @Test fun romanUrduPhoneRequestRoutesToActionWithTools() {
        assertEquals(Intent.ACTION, IntentGate.classify("browser khol kar ye topic search karo"))
        assertEquals(Intent.ACTION, IntentGate.classify("Open the browser and search for weather"))
        assertEquals(Intent.ACTION, IntentGate.classify("is video ko reel size mein kaato aur captions lagao"))
        assertEquals(Intent.CHAT, IntentGate.classify("what does open source mean?"))
    }
}
