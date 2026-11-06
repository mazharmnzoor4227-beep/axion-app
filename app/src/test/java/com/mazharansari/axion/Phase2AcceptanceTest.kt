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
 * Phase 2 acceptance: one long multi-step task → the agent shows a plan, completes all steps, survives one deliberately failing tool
 * (retry once → alternative → carries on) and, at the end, still has a decision made at the very start of the session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AxionApp::class)
class Phase2AcceptanceTest {
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()
    private class Rec : AgentSink {
        val text = StringBuilder(); val plans = mutableListOf<List<PlanStep>>(); val steps = mutableListOf<Step>()
        override fun text(delta: String) { text.append(delta) }
        override fun reasoning(delta: String) {}
        override fun textToThoughts() {}
        override fun step(step: Step) { steps += step }
        override fun artifact(a: Artifact) {}
        override fun model(label: String) {}
        override fun plan(steps: List<PlanStep>) { plans += steps }
    }
    private class FakeTool(val name: String, val behave: (Int) -> ToolOutput) : Tool {
        var calls = 0
        override val spec = ToolSpec(name, "fake $name", schema { str("x", "x", required = false) })
        override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, name, "")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = behave(++calls)
    }
    private fun call(id: String, name: String, json: String) = LlmEvent.ToolCalls(listOf(ToolCall(id, name, json)))

    @Test fun planThenStepsThenSurviveFailingToolThenRemember() = runBlocking {
        val flaky = FakeTool("flaky_tool") { ToolOutput("500 server exploded", isError = true) }
        val backup = FakeTool("backup_tool") { ToolOutput("page written") }
        val seen = mutableListOf<LlmRequest>()
        val client = object : LlmClient {
            override fun stream(req: LlmRequest) = flow {
                seen += req
                when (seen.size) {
                    1 -> emit(call("p", "plan_set", """{"steps":["Write the page","Add search","Export to PDF"]}"""))
                    2 -> emit(call("u1", "plan_update", """{"index":1,"status":"running"}"""))
                    3 -> emit(call("f1", "flaky_tool", "{}"))                        // fails, retried once, fails again
                    4 -> emit(call("b1", "backup_tool", "{}"))                       // the model switches approach after the system note
                    5 -> emit(call("u2", "plan_update", """{"index":1,"status":"done"}"""))
                    else -> { emit(LlmEvent.Text("All done: the Zed notes app uses the blue theme.")); emit(LlmEvent.Finished("stop")) }
                }
            }
        }
        val history = buildList<ChatItem> {
            add(UserItem("Please use a blue theme and call the app Zed. Build me a notes app step by step.")); add(AssistantItem("Sure: blue theme, called Zed."))
            repeat(30) { add(UserItem("Turn $it: add feature $it, explained at length " + "x".repeat(600))); add(AssistantItem("Added feature $it. " + "y".repeat(800))) }
        }
        val agent = Agent(Workspace(File(app.filesDir, "workspace")), SkillRepo(app, File(app.filesDir, "skills")), baseTools = listOf(flaky, backup), extraTools = { emptyList() })
        val rec = Rec()
        val out = agent.run(history, UserItem("Now build the whole notes app: write the page, then add search, and finally export everything to PDF."),
            { listOf(Attempt("Fake", "fake-model", client)) }, rec, intent = Intent.ACTION)

        assertEquals("All done: the Zed notes app uses the blue theme.", out)
        // 1. a plan came first: the very first request offered ONLY plan_set
        assertEquals(listOf("plan_set"), seen[0].tools.map { it.name })
        assertTrue(rec.plans.first().size == 3)
        // 2. plan steps end honestly (v0.6.1): the step the model reported done is DONE; steps it never reported are SKIPPED, not silently "done"
        assertEquals(PlanStatus.DONE, rec.plans.last()[0].status)
        assertTrue(rec.plans.last().drop(1).all { it.status == PlanStatus.SKIPPED })
        // 3. the failing tool was retried exactly once, then the model was told to try another way, and the task still finished
        assertEquals(2, flaky.calls); assertEquals(1, backup.calls)
        assertTrue(rec.steps.any { it.title == "Retrying…" })
        val noteSeenByModel = seen[3].items.filterIsInstance<ToolResultItem>().first { it.name == "flaky_tool" }.content
        assertTrue(noteSeenByModel, noteSeenByModel.contains("DIFFERENT approach"))
        // 4. the decision from the start of the session is still in what the model sees on the last request, though the history was far over budget
        val last = seen.last().items; val sys = (last.first() as SystemItem).text
        assertTrue(sys, sys.contains("blue theme") && sys.contains("Zed"))
        assertTrue("history was compressed", last.sumOf { HistoryCompressor.tokens(it) } < 9000)
        // 5. never hangs or loops: bounded number of model calls
        assertTrue(seen.size <= 8)
    }

    @Test fun simpleRequestGetsNoPlanAndNoPlanTools() = runBlocking {
        val seen = mutableListOf<LlmRequest>()
        val client = object : LlmClient { override fun stream(req: LlmRequest) = flow { seen += req; emit(LlmEvent.Text("hi")); emit(LlmEvent.Finished("stop")) } }
        val agent = Agent(Workspace(File(app.filesDir, "workspace")), SkillRepo(app, File(app.filesDir, "skills")), baseTools = listOf(FakeTool("t") { ToolOutput("x") }), extraTools = { emptyList() })
        val rec = Rec(); agent.run(emptyList(), UserItem("make a pdf of my notes"), { listOf(Attempt("Fake", "m", client)) }, rec, intent = Intent.ACTION)
        assertTrue(seen[0].tools.none { it.name.startsWith("plan_") }); assertTrue(rec.plans.isEmpty())
    }

    @Test fun repeatedFailuresEndWithAnExplanationNotALoop() = runBlocking {
        val broken = FakeTool("broken") { ToolOutput("always fails", isError = true) }
        val seen = mutableListOf<LlmRequest>()
        val client = object : LlmClient {
            override fun stream(req: LlmRequest) = flow {
                seen += req
                if (req.tools.isNotEmpty()) emit(call("c${seen.size}", "broken", """{"x":"${seen.size}"}""")) else { emit(LlmEvent.Text("I could not do it: the tool kept failing (always fails).")); emit(LlmEvent.Finished("stop")) }
            }
        }
        val agent = Agent(Workspace(File(app.filesDir, "workspace")), SkillRepo(app, File(app.filesDir, "skills")), baseTools = listOf(broken), extraTools = { emptyList() })
        val out = agent.run(emptyList(), UserItem("please run the broken thing for me now"), { listOf(Attempt("Fake", "m", client)) }, Rec(), intent = Intent.ACTION)
        assertTrue(out, out.contains("could not do it")); assertTrue(seen.size <= 6); assertTrue(broken.calls <= 6)
    }
}
