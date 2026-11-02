package com.mazharansari.axion

import androidx.test.core.app.ApplicationProvider
import com.mazharansari.axion.agent.*
import com.mazharansari.axion.data.*
import com.mazharansari.axion.llm.*
import com.mazharansari.axion.skills.SkillRepo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** v0.5.1 — mazhar 3:36 PM: "dusra do" sat in "Thinking for 26 s…" and no answer ever came. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AxionApp::class)
class V051WatchdogTest {
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()
    // Short watchdog windows so the test runs in well under a second per case (the app uses 8 s / 10 s / 45 s).
    private fun agent() = Agent(Workspace(File(app.filesDir, "workspace")), SkillRepo(app, File(app.filesDir, "skills")),
        firstContentMs = 300, stallMs = 300, thinkCapMs = 900)

    private class Rec : AgentSink {
        val text = StringBuilder(); val reasoning = StringBuilder(); val models = mutableListOf<String>(); val switches = mutableListOf<String>(); var discards = 0
        override fun text(delta: String) { text.append(delta) }
        override fun reasoning(delta: String) { reasoning.append(delta) }
        override fun textToThoughts() {}
        override fun step(step: Step) {}
        override fun artifact(a: Artifact) {}
        override fun model(label: String) { models += label }
        override fun switching(label: String) { switches += label }
        override fun discardRound() { text.setLength(0); discards++ }
    }

    private fun client(block: suspend kotlinx.coroutines.flow.FlowCollector<LlmEvent>.(LlmRequest) -> Unit) = object : LlmClient {
        override fun stream(req: LlmRequest) = flow { block(req) }
    }
    private val good = client { emit(LlmEvent.Text("Ek aur kahani: ")); emit(LlmEvent.Text("ek din ek ladka…")); emit(LlmEvent.Finished("stop")) }
    private val silent = client { delay(60_000); emit(LlmEvent.Text("too late")) }

    @Test fun silentModelSwitchesToNext() = runBlocking {
        val r = Rec(); val t0 = System.currentTimeMillis()
        val out = agent().run(emptyList(), UserItem("dusra do"), { listOf(Attempt("Groq", "openai/gpt-oss-20b", silent), Attempt("Groq", "llama-3.3-70b-versatile", good)) }, r, intent = Intent.CHAT)
        assertEquals("Ek aur kahani: ek din ek ladka…", out)
        assertEquals(listOf("Groq · llama-3.3-70b-versatile"), r.switches)
        assertTrue("switched in ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 3_000)
    }

    @Test fun stallMidAnswerSwitchesAndRestarts() = runBlocking {
        val stalls = client { emit(LlmEvent.Text("Ek din ")); delay(60_000); emit(LlmEvent.Text("never")) }
        val r = Rec()
        val out = agent().run(emptyList(), UserItem("ek kahani sunao"), { listOf(Attempt("Groq", "openai/gpt-oss-20b", stalls), Attempt("Gemini", "gemini-3.8-flash", good)) }, r, intent = Intent.CHAT)
        assertEquals("Ek aur kahani: ek din ek ladka…", out)
        assertEquals("Ek aur kahani: ek din ek ladka…", r.text.toString())   // the stalled half-answer was dropped, not glued on
        assertEquals(1, r.discards)
    }

    /** Small talk hides reasoning, so endless hidden reasoning is NOT progress: it must switch. */
    @Test fun hiddenReasoningLoopOnSmallTalkSwitches() = runBlocking {
        val thinker = client { repeat(1000) { emit(LlmEvent.Reasoning("hmm ")); delay(50) } }
        val r = Rec()
        val out = agent().run(emptyList(), UserItem("hi bhai"), { listOf(Attempt("Groq", "openai/gpt-oss-20b", thinker), Attempt("Groq", "llama-3.1-8b-instant", good)) }, r, intent = Intent.CHAT)
        assertTrue(out.startsWith("Ek aur"))
        assertEquals("", r.reasoning.toString())
    }

    /** Visible thinking counts as alive — but only up to the think cap, then the next model answers. */
    @Test fun endlessVisibleThinkingIsCapped() = runBlocking {
        val thinker = client { repeat(1000) { emit(LlmEvent.Reasoning("step ")); delay(50) } }
        val r = Rec()
        val out = agent().run(emptyList(), UserItem("Explain how a car engine works in simple words"), { listOf(Attempt("OpenRouter", "deepseek-r1", thinker), Attempt("Groq", "llama", good)) }, r, intent = Intent.CHAT)
        assertTrue(out.startsWith("Ek aur")); assertTrue(r.reasoning.isNotEmpty()); assertEquals(1, r.switches.size)
    }

    /** The last model in the rotation stalling ends with an error — never an endless Thinking. */
    @Test fun lastModelStallingEndsWithError() = runBlocking {
        val r = Rec(); val t0 = System.currentTimeMillis()
        val e = runCatching { agent().run(emptyList(), UserItem("dusra do"), { listOf(Attempt("Groq", "openai/gpt-oss-20b", silent)) }, r, intent = Intent.CHAT) }.exceptionOrNull()
        assertTrue("got $e", e is LlmException)
        assertTrue(e!!.message!!.contains("sent no answer"))
        assertTrue(System.currentTimeMillis() - t0 < 3_000)
    }

    @Test fun reasoningOnlyReplyIsShownAsTheAnswer() = runBlocking {
        val onlyReasoning = client { emit(LlmEvent.Reasoning("Ek baar ki baat hai, ek chhota sa gaon tha…")); emit(LlmEvent.Finished("stop")) }
        val r = Rec()
        val out = agent().run(emptyList(), UserItem("ek kahani sunao jo lambi ho aur mazedaar ho"), { listOf(Attempt("Groq", "openai/gpt-oss-20b", onlyReasoning)) }, r, intent = Intent.CHAT)
        assertEquals("Ek baar ki baat hai, ek chhota sa gaon tha…", out)
        assertEquals(out, r.text.toString())
        // Small talk too (reasoning hidden there): the text still becomes the answer.
        val r2 = Rec()
        assertEquals("Ek baar ki baat hai, ek chhota sa gaon tha…", agent().run(emptyList(), UserItem("hi bhai"), { listOf(Attempt("Groq", "openai/gpt-oss-20b", onlyReasoning)) }, r2, intent = Intent.CHAT))
    }

    @Test fun emptyReplyFailsOverInsteadOfShowingNothing() = runBlocking {
        val empty = client { emit(LlmEvent.Finished("stop")) }
        val out = agent().run(emptyList(), UserItem("dusra do"), { listOf(Attempt("A", "a", empty), Attempt("B", "b", good)) }, Rec(), intent = Intent.CHAT)
        assertTrue(out.startsWith("Ek aur"))
    }

    /** Stop still stops at once while a model sits silent (the watchdog must not turn Stop into "switch model"). */
    @Test fun stopStillWorks() = runBlocking {
        val r = Rec()
        val job = launch(Dispatchers.Default) { agent().run(emptyList(), UserItem("dusra do"), { listOf(Attempt("A", "a", silent), Attempt("B", "b", silent)) }, r, intent = Intent.CHAT) }
        delay(100); val t0 = System.currentTimeMillis(); job.cancelAndJoin()
        assertTrue(System.currentTimeMillis() - t0 < 300); assertTrue(r.switches.isEmpty())
    }

    @Test fun groqReasoningDeltasParsed() {
        val c = OpenAiClient(Providers.byId("groq")!!, "k")
        assertEquals("think", c.reasoningDelta(buildJsonObject { put("reasoning", "think") }))
        assertEquals("rc", c.reasoningDelta(buildJsonObject { put("reasoning_content", "rc") }))
        assertEquals("a b", c.reasoningDelta(buildJsonObject { putJsonArray("reasoning_details") { addJsonObject { put("type", "reasoning.text"); put("text", "a ") }; addJsonObject { put("text", "b") } } }))
        assertNull(c.reasoningDelta(buildJsonObject { put("content", "x") }))
    }

    @Test fun shortPromptsAskForLowReasoning() {
        val groq = OpenAiClient(Providers.byId("groq")!!, "k")
        val low = groq.buildBody(LlmRequest("openai/gpt-oss-20b", listOf(UserItem("dusra do")), effort = "low"))
        assertEquals("low", low["reasoning_effort"]!!.jsonPrimitive.content)
        assertNull(low["include_reasoning"])   // thoughts still stream into the panel
        val small = groq.buildBody(LlmRequest("openai/gpt-oss-20b", listOf(UserItem("hi bhai")), quick = true))
        assertEquals("low", small["reasoning_effort"]!!.jsonPrimitive.content); assertEquals(false, small["include_reasoning"]!!.jsonPrimitive.boolean)
        assertNull(groq.buildBody(LlmRequest("llama-3.3-70b-versatile", listOf(UserItem("x")), effort = "low"))["reasoning_effort"])
        val or = OpenAiClient(Providers.byId("openrouter")!!, "k").buildBody(LlmRequest("openai/gpt-oss-20b:free", listOf(UserItem("x")), effort = "low"))
        assertEquals("low", or["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test fun agentSendsLowEffortForShortChat() = runBlocking {
        var seen: LlmRequest? = null
        val spy = client { seen = it; emit(LlmEvent.Text("ok")); emit(LlmEvent.Finished("stop")) }
        agent().run(emptyList(), UserItem("dusra do"), { listOf(Attempt("Groq", "openai/gpt-oss-20b", spy)) }, Rec(), intent = Intent.CHAT)
        assertEquals("low", seen!!.effort); assertFalse(seen!!.quick)
    }

    // ---- 1c: short follow-ups use the conversation and reply in the latest message's script ----
    @Test fun followUpsDetected() {
        for (s in listOf("dusra do", "Doosra do", "ek aur", "aur", "aur sunao", "one more", "another", "again", "phir se", "ek aur do!", "دوسرا دو")) assertTrue(s, IntentGate.isFollowUp(s))
        for (s in listOf("hi bhai", "kaise ho", "Mere store ki sales report PDF bana do", "order kab aayega")) assertFalse(s, IntentGate.isFollowUp(s))
        assertFalse(IntentGate.isSmallTalk("dusra do"))   // a follow-up gets a full answer, not a one-liner
        assertEquals(Intent.CHAT, IntentGate.classify("dusra do"))
    }

    @Test fun followUpUsesHistoryAndRomanScript() = runBlocking {
        var req: LlmRequest? = null
        val spy = client { req = it.copy(items = it.items.toList()); emit(LlmEvent.Text("Theek hai, ek aur kahani…")); emit(LlmEvent.Finished("stop")) }
        val history = listOf(UserItem("Ek Urdu kahani sunao"), AssistantItem("ایک دفعہ کا ذکر ہے، ایک چھوٹے سے گاؤں میں…"))
        agent().run(history, UserItem("dusra do"), { listOf(Attempt("Groq", "openai/gpt-oss-20b", spy)) }, Rec(), intent = IntentGate.classify("dusra do"))
        val r = req!!
        val sys = (r.items.first() as SystemItem).text
        assertTrue(sys.contains("FOLLOW-UP")); assertTrue(sys.contains("SCRIPT NOW: the latest message is written in English letters"))
        assertTrue(r.items.any { it is AssistantItem && it.text.startsWith("ایک دفعہ") })   // previous story is in context
        assertEquals("dusra do", (r.items.last() as UserItem).text)
    }

    @Test fun scriptHintOnlyWhenNeeded() {
        assertEquals("", ScriptHint.note("Explain recursion", listOf(UserItem("hi"), AssistantItem("Hello!")), false))
        assertTrue(ScriptHint.note("اور سناؤ", listOf(UserItem("tell me a story"), AssistantItem("Once upon a time")), false).contains("Urdu script"))
        assertEquals(ScriptHint.Script.LATIN, ScriptHint.of("dusra do"))
        assertEquals(ScriptHint.Script.ARABIC, ScriptHint.of("دوسرا دو"))
    }
}
