package com.mazharansari.axion

import androidx.test.core.app.ApplicationProvider
import com.mazharansari.axion.agent.*
import com.mazharansari.axion.data.*
import com.mazharansari.axion.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/**
 * Real end-to-end agent turns against live model APIs (this build box reaches them through a key proxy; the app itself
 * calls providers directly with the user's own key). Writes the resulting conversations to build/live/ for the report.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = AxionApp::class)
class LiveAgentTest {
    private val cap = File("/home/user/tab/.vendor-capability.json")
    private val out = File(System.getProperty("user.dir"), "build/live").apply { mkdirs() }

    private fun proxy(): Pair<String, String> {
        val o = Json.parseToJsonElement(cap.readText()).jsonObject
        return o["base_url"]!!.jsonPrimitive.content to o["token"]!!.jsonPrimitive.content
    }

    class RecordingSink : AgentSink {
        val text = StringBuilder(); val reasoning = StringBuilder(); val steps = LinkedHashMap<String, Step>(); val artifacts = mutableListOf<Artifact>()
        val models = mutableListOf<String>(); var roundStart = 0
        override fun text(delta: String) { text.append(delta) }
        override fun reasoning(delta: String) { reasoning.append(delta) }
        override fun textToThoughts() { val t = text.substring(roundStart); text.setLength(roundStart); reasoning.append(t) }
        override fun step(step: Step) { steps[step.id] = step; roundStart = text.length; if (step.status != StepStatus.RUNNING) println("  STEP ${step.status} ${step.title} ${step.target.take(90)}") }
        override fun artifact(a: Artifact) { artifacts += a; println("  ARTIFACT ${a.name} ${a.size} bytes ${a.pages} pages") }
        override fun model(label: String) { models += label; println("  MODEL $label") }
    }

    private fun runTurn(name: String, prompt: String, attempts: List<Attempt>): RecordingSink {
        val app = ApplicationProvider.getApplicationContext<AxionApp>()
        val ws = Workspace(File(app.filesDir, "workspace"))
        val agent = Agent(ws, com.mazharansari.axion.skills.SkillRepo(app, File(app.filesDir, "skills")))
        val sink = RecordingSink()
        val failures = mutableListOf<String>()
        val wrapped = attempts.map { a -> a.copy(onFail = { e -> failures += "${a.provider}/${a.model}: ${e.kind} ${e.message?.take(140)}"; println("  FAILOVER ${a.provider}/${a.model}: ${e.kind} ${e.message?.take(140)}") }) }
        val t0 = System.currentTimeMillis()
        var error: String? = null
        val final = runBlocking { runCatching { agent.run(emptyList(), UserItem(prompt), { wrapped }, sink, Profile(userName = "Mazhar")) }.onFailure { error = it.toString() }.getOrNull() }
        val ms = System.currentTimeMillis() - t0
        val now = System.currentTimeMillis()
        val conv = Conversation(UUID.randomUUID().toString(), prompt.take(48), listOf(
            Message(UUID.randomUUID().toString(), Role.USER, prompt, createdAt = t0),
            Message(UUID.randomUUID().toString(), Role.ASSISTANT, sink.text.toString(), steps = sink.steps.values.toList(), artifacts = sink.artifacts,
                reasoning = sink.reasoning.toString(), thinkingMs = ms, model = sink.models.lastOrNull().orEmpty(), createdAt = now,
                status = if (error == null) MsgStatus.DONE else MsgStatus.ERROR, error = error.orEmpty()),
        ), createdAt = t0, updatedAt = now)
        File(out, "$name.json").writeText(Json { prettyPrint = true }.encodeToString(conv))
        sink.artifacts.forEach { a -> File(a.path).copyTo(File(out, a.name), overwrite = true) }
        File(out, "$name.txt").writeText(buildString {
            appendLine("PROMPT: $prompt"); appendLine("TIME: ${ms / 1000}s"); appendLine("MODELS TRIED: ${sink.models}"); appendLine("FAILOVERS: $failures")
            appendLine("STEPS:"); sink.steps.values.forEach { appendLine(" - ${it.status} ${it.kind} ${it.title} ${it.target.take(120)}") }
            appendLine("ARTIFACTS: ${sink.artifacts.map { "${it.name} ${it.size}B ${it.pages}p" }}"); appendLine("ERROR: $error"); appendLine("ANSWER:\n${final ?: sink.text}")
        })
        println("=== $name done in ${ms / 1000}s, error=$error\n${final ?: sink.text}")
        return sink
    }

    @Test fun geminiAuditToPdf() {
        assumeTrue(cap.exists())
        val (b, t) = proxy()
        val bearer = "Authorization" to "Bearer $t"
        val studio = "$b/google-ai/v1beta"
        val vertex = "$b/google-vertex/v1/projects/flik-ai-staging/locations/global/publishers/google"
        // Same order the app uses for one Gemini connector: best flash first, failing over on quota.
        val attempts = listOf(
            Attempt("Gemini (AI Studio free tier)", "gemini-3.5-flash", GeminiClient(t, studio, bearer)),
            Attempt("Gemini (AI Studio free tier)", "gemini-3-flash-preview", GeminiClient(t, studio, bearer)),
            Attempt("Gemini (AI Studio free tier)", "gemma-4-26b-a4b-it", GeminiClient(t, studio, bearer)),
        )
        val s = runTurn("live_gemini_audit",
            "claworabeauty.store ka quick audit karo: home page aur ek product page browse karo, phir ek chhoti PDF report banao (score table + top 5 fixes). Jawab Roman Urdu mein.",
            attempts)
        assert(s.steps.values.any { it.kind == StepKind.BROWSE })
    }

    @Test fun skillsFromGitHub() {
        val app = ApplicationProvider.getApplicationContext<AxionApp>()
        val repo = com.mazharansari.axion.skills.SkillRepo(app, File(app.filesDir, "skills"))
        val found = runBlocking { repo.scanGitHub("https://github.com/anthropics/skills") }
        println("SKILLS FOUND: ${found.size}: " + found.take(12).joinToString { it.name })
        val installed = runBlocking { repo.install(found.filter { it.name in setOf("pdf", "docx", "frontend-design") }, "https://github.com/anthropics/skills") }
        println("INSTALLED: " + installed.joinToString { it.name + " (" + it.description.take(50) + ")" })
        assert(found.isNotEmpty())
        assert(repo.body("pdf") != null)
    }

    @Test fun imagineNoKey() {
        val app = ApplicationProvider.getApplicationContext<AxionApp>()
        val gen = ImageGen({ emptyMap() }, File(app.filesDir, "workspace/outputs/images"))
        var r: ImageGen.Result? = null
        for (i in 0 until 3) {   // free tier allows 1 image / 45 s per IP
            r = runCatching { runBlocking { gen.generate("Korean snail cream jar on wet white marble, soft morning light, luxury product photo", "1:1") } }
                .onFailure { println("  imagine attempt $i: ${it.message}"); Thread.sleep(46_000) }.getOrNull()
            if (r != null) break
        }
        println("IMAGE: ${r?.provider} ${r?.file?.length()} bytes")
        r!!.file.copyTo(File(out, "imagine_live.jpg"), overwrite = true)
        assert(r.file.length() > 10_000)
    }

    @Test fun openAiCompatibleShell() {
        assumeTrue(cap.exists())
        val (b, t) = proxy()
        val fw = Providers.byId("groq")!!.copy(id = "fireworks", name = "Fireworks")
        val attempts = listOf(
            Attempt("Cerebras (free tier)", "gpt-oss-120b", OpenAiClient(Providers.byId("cerebras")!!, t, "$b/cerebras/v1")),
            Attempt("Groq (free tier)", "openai/gpt-oss-120b", OpenAiClient(Providers.byId("groq")!!, t, "$b/groq/openai/v1")),
            Attempt("Fireworks", "accounts/fireworks/models/gpt-oss-120b", OpenAiClient(fw, t, "$b/fireworks/inference/v1")),
        )
        val s = runTurn("live_openai_shell",
            "Use run_command to create a folder outputs/demo with a file hello.txt containing 'Axion works', then zip-free check: list the folder with ls -la and tell me the file size.",
            attempts)
        assert(s.steps.values.any { it.kind == StepKind.COMMAND })
    }
}
