package com.mazharansari.axion

import androidx.test.core.app.ApplicationProvider
import com.mazharansari.axion.agent.*
import com.mazharansari.axion.data.*
import com.mazharansari.axion.llm.*
import com.mazharansari.axion.skills.SkillRepo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** v0.3 regression (mazhar 10:10 AM, 1 Nov 2026): plain chat must never trigger tools; only an explicit request may. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AxionApp::class)
class AskRegressionTest {
    private val cap = File("/home/user/tab/.vendor-capability.json")
    private val out = File(System.getProperty("user.dir"), "build/live").apply { mkdirs() }

    @Test fun intentGate() {
        val cases = mapOf(
            "hello Kya Hal he" to Intent.CHAT, "tum kya kar sakte ho?" to Intent.CHAT, "can you run PowerShell commands?" to Intent.CHAT,
            "mera ek kaam karo, ek html page bana do" to Intent.ACTION, "Assalam o alaikum" to Intent.CHAT, "kya haal hai bhai" to Intent.CHAT,
            "aaj karachi ka mausam kaisa hai" to Intent.LOOKUP, "search latest iPhone price in Pakistan" to Intent.LOOKUP,
            "make me a PDF report of this" to Intent.ACTION, "what is photosynthesis?" to Intent.CHAT, "explain recursion in simple words" to Intent.CHAT,
            "can you make me an html page for my shop?" to Intent.ACTION, "ek poster bana do" to Intent.ACTION, "claworabeauty.store ka audit karo" to Intent.ACTION,
            "tumhara naam kya hai" to Intent.CHAT, "run ls in the workspace" to Intent.ACTION,
        )
        val wrong = cases.filter { (t, want) -> IntentGate.classify(t) != want }.map { (t, want) -> "$t → ${IntentGate.classify(t)} (want $want)" }
        println("INTENT GATE: ${cases.size - wrong.size}/${cases.size} right. Wrong: $wrong")
        assert(wrong.isEmpty()) { wrong.joinToString() }
    }

    private fun turn(app: AxionApp, prompt: String, intent: Intent, attempts: List<Attempt>): Pair<String, List<Step>> {
        val steps = LinkedHashMap<String, Step>()
        val sink = object : AgentSink {
            override fun text(delta: String) {}
            override fun reasoning(delta: String) {}
            override fun textToThoughts() {}
            override fun step(step: Step) { steps[step.id] = step }
            override fun artifact(a: Artifact) {}
            override fun model(label: String) {}
        }
        val agent = Agent(Workspace(File(app.filesDir, "workspace")), SkillRepo(app, File(app.filesDir, "skills")))
        val t0 = System.currentTimeMillis()
        val ans = runBlocking { agent.run(emptyList(), UserItem(prompt), { attempts }, sink, Profile(userName = "Mazhar"), intent) }
        println("  [$intent] ${(System.currentTimeMillis() - t0) / 1000}s steps=${steps.values.map { it.kind }}")
        return ans to steps.values.toList()
    }

    @Test fun askTranscripts() {
        assumeTrue(cap.exists())
        val o = Json.parseToJsonElement(cap.readText()).jsonObject
        val b = o["base_url"]!!.jsonPrimitive.content; val t = o["token"]!!.jsonPrimitive.content
        val fw = Providers.byId("groq")!!.copy(id = "fireworks", name = "Fireworks")
        // gpt-oss-120b: the same model mazhar's Groq key runs (Groq itself is unreachable from this box).
        val attempts = listOf(Attempt("gpt-oss-120b", "accounts/fireworks/models/gpt-oss-120b", OpenAiClient(fw, t, "$b/fireworks/inference/v1")))
        val app = ApplicationProvider.getApplicationContext<AxionApp>()
        val report = StringBuilder()
        val prompts = listOf("hello Kya Hal he", "tum kya kar sakte ho?", "can you run PowerShell commands?", "mera ek kaam karo, ek html page bana do")
        val failures = mutableListOf<String>()
        for (p in prompts) {
            val intent = IntentGate.classify(p)
            val (ans, steps) = turn(app, p, intent, attempts)
            report.appendLine("USER: $p\nGATE: $intent · tools offered: ${if (intent == Intent.CHAT) "none" else intent.name}\nTOOL CALLS: ${steps.map { "${it.kind} ${it.target}" }.ifEmpty { listOf("none") }}\nAXION: $ans\n")
            if (p != prompts.last() && steps.isNotEmpty()) failures += "$p used tools"
        }
        // Belt and braces: even if the gate ever let a greeting through with ALL tools offered, the prompt alone must keep it a chat.
        for (p in prompts.take(3)) {
            val (ans, steps) = turn(app, p, Intent.ACTION, attempts)
            report.appendLine("[all tools offered] USER: $p\nTOOL CALLS: ${steps.map { "${it.kind} ${it.target}" }.ifEmpty { listOf("none") }}\nAXION: $ans\n")
            if (steps.any { it.kind == StepKind.WRITE || it.kind == StepKind.COMMAND || it.kind == StepKind.PDF }) failures += "$p (all tools) acted"
        }
        File(out, "ask_regression.txt").writeText(report.toString())
        println(report)
        assert(failures.isEmpty()) { failures.joinToString() }
    }
}
