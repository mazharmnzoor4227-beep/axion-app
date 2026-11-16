package com.mazharansari.axion

import com.mazharansari.axion.agent.Attempt
import com.mazharansari.axion.agent.BotKind
import com.mazharansari.axion.agent.BotRegistry
import com.mazharansari.axion.agent.BotStatus
import com.mazharansari.axion.agent.SwarmVerify
import com.mazharansari.axion.agent.SubagentOrchestrator
import com.mazharansari.axion.agent.WorkerFact
import com.mazharansari.axion.agent.WorkerResult
import com.mazharansari.axion.agent.WorkerRole
import com.mazharansari.axion.agent.WorkerStatus
import com.mazharansari.axion.agent.WorkerTask
import com.mazharansari.axion.agent.WorkerUpdate
import com.mazharansari.axion.agent.buildWorkerConversation
import com.mazharansari.axion.agent.Workspace
import com.mazharansari.axion.data.BotCatalog
import com.mazharansari.axion.data.BotDefinition
import com.mazharansari.axion.data.BotDefinitionStore
import com.mazharansari.axion.data.MsgStatus
import com.mazharansari.axion.data.Role
import com.mazharansari.axion.data.preferProvider
import com.mazharansari.axion.data.switchesForBot
import com.mazharansari.axion.llm.LlmClient
import com.mazharansari.axion.llm.LlmRequest
import com.mazharansari.axion.work.RunRegistry
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext

/**
 * Swarm Phase 1 refinements (no model, no network, no Android framework):
 * named parent bots (no hardcoded CEO), worker transcripts, verify-before-deliver,
 * the Bot Store (install/create/run-as-bot), and the provider preference.
 */
class SwarmPhase1Test {

    // ── named parents: no hardcoded CEO ────────────────────────────────────────

    @Test fun botKindsAreParentAndWorkerOnly() {
        // There is no CEO class: a bot is a parent for its run by relationship + name.
        assertEquals(setOf(BotKind.PARENT, BotKind.WORKER), BotKind.values().toSet())
    }

    @Test fun registerParentShowsGivenNameAndCountsSteps() {
        val reg = BotRegistry()
        reg.registerParent("run-1", "conv-1", "Researcher", "Find the best phone")
        val p = reg.bots.value.single()
        assertEquals(BotKind.PARENT, p.kind)
        assertEquals("Researcher", p.name)
        assertEquals("conv-1", p.conversationId)
        assertEquals(BotStatus.WORKING, p.status)
        reg.parentStep("run-1"); reg.parentStep("run-1")
        assertEquals(2, reg.bots.value.single().stepsUsed)
        reg.parentFinished("run-1", BotStatus.DONE, "Found it")
        val done = reg.bots.value.single()
        assertEquals(BotStatus.DONE, done.status)
        assertEquals("Found it", done.summary)
    }

    @Test fun workerCardStartsStartingThenWorkingThenDone() {
        val reg = BotRegistry()
        reg.registerParent("run-1", "conv-1", "Axion", "Research this")
        reg.onWorkerUpdate(WorkerUpdate("r1-w1", "w1", WorkerRole.RESEARCHER, "angle one", WorkerStatus.RUNNING, parentId = "conv-1"))
        val w = reg.bots.value.first { it.kind == BotKind.WORKER }
        assertEquals(BotStatus.STARTING, w.status)
        assertEquals("conv-1", w.parentId)
        assertEquals(1, reg.liveWorkerCount("conv-1"))
        assertEquals(1, reg.workersOf("conv-1").size)
        // The worker's first real step promotes it to WORKING.
        reg.onWorkerProgress("w1", 1, "Searched: angle one → 3 results")
        assertEquals(BotStatus.WORKING, reg.bots.value.first { it.kind == BotKind.WORKER }.status)
        reg.onWorkerUpdate(WorkerUpdate("r1-w1", "w1", WorkerRole.RESEARCHER, "angle one", WorkerStatus.DONE, 2, "3 facts", parentId = "conv-1"))
        val done = reg.bots.value.first { it.kind == BotKind.WORKER }
        assertEquals(BotStatus.DONE, done.status)
        assertEquals("3 facts", done.summary)
        assertEquals(0, reg.liveWorkerCount("conv-1"))
    }

    @Test fun workerTranscriptHasBriefAndResult() {
        val c = buildWorkerConversation("r1-w1", WorkerRole.CODER, "Write hello.py",
            listOf("Read main.py", "Wrote hello.py (12 chars)"), MsgStatus.DONE, "hello.py written", 1000L)
        assertEquals("worker", c.section)
        assertEquals("worker-r1-w1", c.id)
        assertEquals(2, c.messages.size)
        assertEquals(Role.USER, c.messages[0].role)
        assertEquals("Write hello.py", c.messages[0].text)
        val a = c.messages[1]
        assertEquals(Role.ASSISTANT, a.role)
        assertTrue(a.text.contains("Wrote hello.py"))
        assertTrue(a.text.contains("Result: hello.py written"))
    }

    // ── verify-before-deliver ──────────────────────────────────────────────────

    private fun tempWorkspace(): Pair<Workspace, File> {
        val dir = File("/tmp/swarm-verify-${System.nanoTime()}").apply { mkdirs() }
        return Workspace(dir) to dir
    }

    @Test fun researchFactWithoutUrlFails() {
        val (ws, dir) = tempWorkspace()
        try {
            val fact = WorkerFact(text = "The price is Rs 1,599", sourceUrl = "", workerId = "w1")
            val result = com.mazharansari.axion.agent.FanOutResult(facts = listOf(fact), failures = emptyList(), workersRun = 1, workersFailed = 0)
            val checks = SwarmVerify.verify(result, ws).checks
            assertEquals(1, checks.size)
            assertFalse(checks[0].ok)
            assertEquals("research", checks[0].kind)
        } finally { dir.deleteRecursively() }
    }

    @Test fun researchFactWithUrlPasses() {
        val (ws, dir) = tempWorkspace()
        try {
            val fact = WorkerFact(text = "The price is Rs 1,599", sourceUrl = "https://example.com/p", workerId = "w1")
            val result = com.mazharansari.axion.agent.FanOutResult(facts = listOf(fact), failures = emptyList(), workersRun = 1, workersFailed = 0)
            assertTrue(SwarmVerify.verify(result, ws).checks.single().ok)
        } finally { dir.deleteRecursively() }
    }

    @Test fun coderWriteIsReadBack() {
        val (ws, dir) = tempWorkspace()
        try {
            val role = listOf(WorkerResult(taskId = "w1", workerId = "w1", role = WorkerRole.CODER))
            val missing = WorkerFact(text = "Wrote out/x.txt (5 chars)", sourceUrl = "", workerId = "w1")
            val r1 = com.mazharansari.axion.agent.FanOutResult(facts = listOf(missing), failures = emptyList(), workersRun = 1, workersFailed = 0)
            assertFalse(SwarmVerify.verify(r1, ws, role).checks.single().ok)
            File(dir, "out").mkdirs(); File(dir, "out/x.txt").writeText("hello")
            assertTrue(SwarmVerify.verify(r1, ws, role).checks.single().ok)
        } finally { dir.deleteRecursively() }
    }

    @Test fun testerResultNeedsExitCode() {
        val (ws, dir) = tempWorkspace()
        try {
            val role = listOf(WorkerResult(taskId = "w1", workerId = "w1", role = WorkerRole.TESTER))
            val noExit = WorkerFact(text = "Ran: ./gradlew test", sourceUrl = "", workerId = "w1")
            val r1 = com.mazharansari.axion.agent.FanOutResult(facts = listOf(noExit), failures = emptyList(), workersRun = 1, workersFailed = 0)
            assertFalse(SwarmVerify.verify(r1, ws, role).checks.single().ok)
            val withExit = WorkerFact(text = "Ran: ./gradlew test → exit 1", sourceUrl = "", workerId = "w1")
            val r2 = com.mazharansari.axion.agent.FanOutResult(facts = listOf(withExit), failures = emptyList(), workersRun = 1, workersFailed = 0)
            assertTrue(SwarmVerify.verify(r2, ws, role).checks.single().ok)
        } finally { dir.deleteRecursively() }
    }

    @Test fun reviewerChecksReviewedFileExists() {
        val (ws, dir) = tempWorkspace()
        try {
            val role = listOf(WorkerResult(taskId = "w1", workerId = "w1", role = WorkerRole.REVIEWER))
            val missing = WorkerFact(text = "Review: 2 findings", sourceUrl = "src/Nope.kt", workerId = "w1")
            val r1 = com.mazharansari.axion.agent.FanOutResult(facts = listOf(missing), failures = emptyList(), workersRun = 1, workersFailed = 0)
            assertFalse(SwarmVerify.verify(r1, ws, role).checks.single().ok)
            File(dir, "src").mkdirs(); File(dir, "src/Real.kt").writeText("fun x() {}")
            val present = WorkerFact(text = "Review: 2 findings", sourceUrl = "src/Real.kt", workerId = "w1")
            val r2 = com.mazharansari.axion.agent.FanOutResult(facts = listOf(present), failures = emptyList(), workersRun = 1, workersFailed = 0)
            assertTrue(SwarmVerify.verify(r2, ws, role).checks.single().ok)
        } finally { dir.deleteRecursively() }
    }

    @Test fun fixSpecsAreCappedAtTwo() {
        val (ws, dir) = tempWorkspace()
        try {
            val facts = (1..3).map { WorkerFact(text = "Claim $it", sourceUrl = "", workerId = "w$it") }
            val result = com.mazharansari.axion.agent.FanOutResult(facts = facts, failures = emptyList(), workersRun = 3, workersFailed = 0)
            val problems = SwarmVerify.verify(result, ws).checks.filter { !it.ok }
            assertEquals(3, problems.size)
            val fixes = SwarmVerify.fixSpecs(problems, "prices")
            assertEquals(2, fixes.size)
            assertTrue(fixes.all { it.role == WorkerRole.RESEARCHER })
        } finally { dir.deleteRecursively() }
    }

    @Test fun mergeFactsDedupes() {
        val a = listOf(WorkerFact(text = "Same claim", sourceUrl = "https://a.example", workerId = "w1"))
        val b = listOf(
            WorkerFact(text = "Same claim", sourceUrl = "https://a.example", workerId = "w2"),
            WorkerFact(text = "New claim", sourceUrl = "https://b.example", workerId = "w2"),
        )
        assertEquals(2, SwarmVerify.mergeFacts(a, b).size)
    }

    // ── per-role model routing + parent linkage ───────────────────────────────

    @Test fun workerRoleModelHints() {
        assertEquals("fast", WorkerRole.RESEARCHER.modelHint)
        assertEquals("strong", WorkerRole.CODER.modelHint)
        assertEquals("fast", WorkerRole.TESTER.modelHint)
        assertEquals("strong", WorkerRole.REVIEWER.modelHint)
    }

    @Test fun fanOutPropagatesParentId() = runBlocking {
        val seen = mutableListOf<String?>()
        SubagentOrchestrator.fanOut(
            listOf(WorkerTask(id = "w1", topic = "t", angle = "a1", role = WorkerRole.RESEARCHER)),
            runWorker = { WorkerResult(it.id, it.id, facts = listOf(WorkerFact(text = "fact one", sourceUrl = "https://example.com", workerId = it.id)), angle = it.angle, role = it.role) },
            onWorker = { seen += it.parentId },
            parentId = "conv-9",
        )
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it == "conv-9" })
    }

    @Test fun runRegistryUpdateTitle() = runBlocking {
        val reg = RunRegistry()
        val job = coroutineContext[Job]!!
        reg.register("run-1", RunRegistry.Kind.ASK, "conv-1", "Working", job)
        reg.updateTitle("run-1", "Working · 2 workers")
        assertEquals("Working · 2 workers", reg.active.value.single().title)
        reg.unregister("run-1")
        assertTrue(reg.active.value.isEmpty())
    }

    // ── Bot Store ────────────────────────────────────────────────────────────

    @Test fun botStoreSeedsAreRealAndLicensed() {
        assertTrue(BotCatalog.seeds.size in 4..6)
        BotCatalog.seeds.forEach { def ->
            assertTrue(def.name.isNotBlank())
            assertTrue(def.description.isNotBlank())
            assertTrue(def.sourceName.isNotBlank())
            assertTrue(def.sourceUrl.startsWith("https://github.com/"))
            assertTrue(def.license.isNotBlank())
            assertTrue(def.id.startsWith("store-"))
            assertTrue(def.promptBlock().contains(def.name))
        }
        // Never in the store: Andee, Locus-AGPL, Aether-GPL, Operit-LGPL.
        val all = BotCatalog.seeds.joinToString(" ") { it.sourceName + " " + it.sourceUrl }
        assertFalse(all.contains("Andee", ignoreCase = true))
        assertFalse(all.contains("Locus", ignoreCase = true))
        assertFalse(all.contains("Aether", ignoreCase = true))
        assertFalse(all.contains("Operit", ignoreCase = true))
    }

    @Test fun botStoreInstallCreateRemovePersist() {
        val dir = File("/tmp/botstore-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = BotDefinitionStore(dir)
            assertTrue(store.definitions.value.isEmpty())
            val seed = BotCatalog.seeds.first()
            assertTrue(store.install(seed))
            assertFalse(store.install(seed)) // idempotent — installing twice is not a second bot
            assertEquals(1, store.definitions.value.size)
            // The user creates a bot and names it anything.
            val created = store.create("CEO", "Runs the team", "You are the boss bot.", "Google Gemini")
            assertTrue(created.id.startsWith("user-"))
            assertEquals("CEO", created.name)
            assertEquals("Google Gemini", created.suggestedProvider)
            assertEquals(2, store.definitions.value.size)
            // Persist round-trip: a new store on the same folder loads the same bots.
            val reloaded = BotDefinitionStore(dir)
            assertEquals(2, reloaded.definitions.value.size)
            assertTrue(reloaded.isInstalled(seed.id))
            reloaded.remove(seed.id)
            assertEquals(1, reloaded.definitions.value.size)
            assertFalse(reloaded.isInstalled(seed.id))
        } finally { dir.deleteRecursively() }
    }

    @Test fun switchesForBotHonoursAllowList() {
        val reviewer = BotCatalog.seeds.first { it.id == "store-reviewer" }
        val sw = switchesForBot(reviewer)
        assertTrue(sw["files"] == true)
        assertTrue(sw["commands"] == true)
        assertTrue(sw["web"] == false)
        assertTrue(sw["research"] == false)
        // An empty allow-list means unrestricted (the default for a user-created bot).
        val open = switchesForBot(BotDefinition(id = "x", name = "Open", description = "", persona = ""))
        assertTrue(open.values.all { it })
        assertTrue(open.containsKey("web"))
    }

    @Test fun preferProviderPutsSuggestedFirst() {
        val dead = object : LlmClient { override fun stream(req: LlmRequest) = emptyFlow<com.mazharansari.axion.llm.LlmEvent>() }
        val attempts = listOf(
            Attempt("Groq", "m1", dead),
            Attempt("Google Gemini", "m2", dead),
            Attempt("OpenRouter", "m3", dead),
        )
        val reordered = preferProvider(attempts, "Google Gemini")
        assertEquals("Google Gemini", reordered.first().provider)
        assertEquals(listOf("Groq", "OpenRouter"), reordered.drop(1).map { it.provider })
        // No preference = order untouched; unknown name = order untouched.
        assertEquals(attempts, preferProvider(attempts, null))
        assertEquals(attempts, preferProvider(attempts, "Nobody"))
    }
}
