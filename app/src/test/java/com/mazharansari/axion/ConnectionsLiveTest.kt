package com.mazharansari.axion

import androidx.test.core.app.ApplicationProvider
import com.mazharansari.axion.agent.*
import com.mazharansari.axion.data.*
import com.mazharansari.axion.llm.*
import com.mazharansari.axion.mcp.McpRepo
import com.mazharansari.axion.skills.SkillRepo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** v0.3: one real agent turn that uses a remote MCP connector, an UPLOADED skill and the hidden shell together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AxionApp::class)
class ConnectionsLiveTest {
    private val cap = File("/home/user/tab/.vendor-capability.json")
    private val out = File(System.getProperty("user.dir"), "build/live").apply { mkdirs() }

    @Test fun mcpSkillShellTogether() {
        assumeTrue(cap.exists())
        val o = Json.parseToJsonElement(cap.readText()).jsonObject
        val b = o["base_url"]!!.jsonPrimitive.content; val t = o["token"]!!.jsonPrimitive.content
        val app = ApplicationProvider.getApplicationContext<AxionApp>()
        val skills = SkillRepo(app, File(app.filesDir, "skills"))
        runBlocking { skills.importFile("clawora-pricing.md", "---\nname: clawora-pricing\ndescription: Clawora Beauty pricing rules\n---\n\nRule: every price is in PKR, delivery is FREE, payment is Cash on Delivery. Secret check word: MANGO-42.".toByteArray()) }
        val mcp = McpRepo(app.filesDir)
        val added = runBlocking { mcp.add("DeepWiki", "https://mcp.deepwiki.com/mcp", "") }
        println("MCP ADD: $added")
        val mcpTools = runBlocking { mcp.tools() }
        println("MCP TOOLS: " + mcpTools.joinToString { it.spec.name })
        val agent = Agent(Workspace(File(app.filesDir, "workspace")), skills, extraTools = { mcpTools })
        val attempts = listOf(
            Attempt("Fireworks", "accounts/fireworks/models/gpt-oss-120b", OpenAiClient(Providers.byId("groq")!!.copy(id = "fireworks", name = "Fireworks"), t, "$b/fireworks/inference/v1")),
        )
        val steps = LinkedHashMap<String, Step>(); val text = StringBuilder()
        val sink = object : AgentSink {
            override fun text(delta: String) { text.append(delta) }
            override fun reasoning(delta: String) {}
            override fun textToThoughts() {}
            override fun step(step: Step) { steps[step.id] = step; if (step.status != StepStatus.RUNNING) println("  STEP ${step.status} ${step.kind} ${step.title} ${step.target.take(100)} -> ${step.output.take(160).replace("\n", " ")}") }
            override fun artifact(a: Artifact) {}
            override fun model(label: String) { println("  MODEL $label") }
        }
        val prompt = "Do all three, using your tools: (1) load the skill clawora-pricing and tell me its secret check word; " +
            "(2) run the shell command `uname -s; echo shell-ok` and show the output; " +
            "(3) use the DeepWiki tool to ask, about the GitHub repo facebook/react, what it is, in one line."
        val answer = runBlocking { agent.run(emptyList(), UserItem(prompt), { attempts }, sink, Profile(userName = "Mazhar"), IntentGate.classify(prompt)) }
        val report = buildString {
            appendLine("PROMPT: $prompt"); appendLine("MCP TOOLS: " + mcpTools.joinToString { it.spec.name })
            steps.values.forEach { appendLine(" - ${it.status} ${it.kind} ${it.title} ${it.target.take(120)} -> ${it.output.take(300).replace("\n", " ")}") }
            appendLine("ANSWER:\n$answer")
        }
        File(out, "v03_connections.txt").writeText(report)
        println(report)
        assert(steps.values.any { it.kind == StepKind.COMMAND && it.status == StepStatus.DONE })
        assert(steps.values.any { it.kind == StepKind.MCP })
        assert(answer.contains("MANGO-42"))
    }
}
