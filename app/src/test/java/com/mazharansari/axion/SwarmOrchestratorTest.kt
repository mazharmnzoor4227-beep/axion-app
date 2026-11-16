package com.mazharansari.axion

import com.mazharansari.axion.agent.ApprovalGate
import com.mazharansari.axion.agent.SubagentLimits
import com.mazharansari.axion.agent.SubagentOrchestrator
import com.mazharansari.axion.agent.SwarmPlan
import com.mazharansari.axion.agent.ToolCatalog
import com.mazharansari.axion.agent.WorkerFact
import com.mazharansari.axion.agent.WorkerResult
import com.mazharansari.axion.agent.WorkerRole
import com.mazharansari.axion.agent.WorkerStatus
import com.mazharansari.axion.agent.WorkerTask
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/**
 * Swarm (Phase 1) pure-logic tests (no model, no network, no Android framework):
 * role allow-lists enforced, depth cap, global worker cap, merge/dedupe still correct,
 * worker failure isolation, plan parsing, and the parent-level approval gate.
 */
class SwarmOrchestratorTest {

    // ── roles + allow-lists ───────────────────────────────────────────────────────

    @Test fun roleAllowListsAreExact() {
        assertEquals(setOf("web_search", "browse_url"), SubagentLimits.toolsFor(WorkerRole.RESEARCHER))
        assertEquals(setOf("read_file", "write_file", "run_command", "list_files"), SubagentLimits.toolsFor(WorkerRole.CODER))
        assertEquals(setOf("run_command", "read_file"), SubagentLimits.toolsFor(WorkerRole.TESTER))
        assertEquals(setOf("read_file"), SubagentLimits.toolsFor(WorkerRole.REVIEWER))
    }

    @Test fun checkAllowedRejectsOutOfRoleTools() {
        // A reviewer may only read — never write, never run shell.
        try {
            SubagentLimits.checkAllowed(WorkerRole.REVIEWER, "write_file")
            fail("reviewer must not write")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("write_file"))
        }
        // A coder may not search the web.
        try {
            SubagentLimits.checkAllowed(WorkerRole.CODER, "web_search")
            fail("coder must not search the web")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // A researcher may never run shell.
        try {
            SubagentLimits.checkAllowed(WorkerRole.RESEARCHER, "run_command")
            fail("researcher must not run shell")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // In-role tools pass.
        SubagentLimits.checkAllowed(WorkerRole.CODER, "write_file")
        SubagentLimits.checkAllowed(WorkerRole.TESTER, "run_command")
        SubagentLimits.checkAllowed(WorkerRole.REVIEWER, "read_file")
        SubagentLimits.checkAllowed(WorkerRole.RESEARCHER, "browse_url")
    }

    @Test fun workerTaskDefaultsToItsRoleAllowList() {
        val coder = WorkerTask("w1", "t", "a", role = WorkerRole.CODER)
        assertEquals(SubagentLimits.CODER_TOOLS, coder.allowedTools)
        assertEquals(SubagentLimits.MAX_STEPS_PER_WORKER, coder.maxSteps)
        val reviewer = WorkerTask("w2", "t", "a", role = WorkerRole.REVIEWER)
        assertEquals(SubagentLimits.REVIEWER_TOOLS, reviewer.allowedTools)
    }

    // ── caps: fan-out, depth, global ────────────────────────────────────────────

    @Test fun splitCapsWorkersAtFour() {
        val tasks = SubagentOrchestrator.splitAngles("phones", listOf("a", "b", "c", "d", "e"))
        assertEquals(SubagentLimits.MAX_WORKERS, tasks.size)
        assertEquals(listOf("w1", "w2", "w3", "w4"), tasks.map { it.id })
        assertEquals(listOf("a", "b", "c", "d"), tasks.map { it.angle })
    }

    @Test fun parseWorkerSpecsAssignsRoles() {
        val tasks = SubagentOrchestrator.parseWorkerSpecs(
            "CODER: build the login screen\nTESTER: run the unit tests\nCompare prices online",
            "task",
        )
        assertEquals(3, tasks.size)
        assertEquals(WorkerRole.CODER, tasks[0].role)
        assertEquals("build the login screen", tasks[0].brief)
        assertEquals(WorkerRole.TESTER, tasks[1].role)
        assertEquals(WorkerRole.RESEARCHER, tasks[2].role)
        assertEquals("Compare prices online", tasks[2].brief)
    }

    @Test fun parseWorkerSpecsCapsAtFour() {
        val text = (1..6).joinToString("\n") { "RESEARCHER: angle $it" }
        assertEquals(4, SubagentOrchestrator.parseWorkerSpecs(text, "t").size)
    }

    @Test fun depthCapStopsRecursion() = runBlocking {
        var maxDepthSeen = 0
        var runs = 0
        val root = WorkerTask("w1", "t", "a", depth = 0)
        val result = SubagentOrchestrator.fanOut(listOf(root)) { task ->
            runs++
            maxDepthSeen = maxOf(maxDepthSeen, task.depth)
            WorkerResult(
                task.id, task.id,
                facts = listOf(WorkerFact("fact at depth ${task.depth}", "", task.id)),
                angle = task.angle, role = task.role, depth = task.depth,
                spawn = listOf(WorkerTask("${task.id}.1", "t", "deeper", depth = task.depth + 1, parentId = task.id)),
            )
        }
        assertTrue("no worker may run deeper than MAX_DEPTH", maxDepthSeen <= SubagentLimits.MAX_DEPTH)
        assertTrue("runs stay bounded by the global cap", runs <= SubagentLimits.MAX_WORKERS_PER_RUN)
        assertEquals("root + two depths, then the cap stops it", 3, runs)
        assertEquals(runs, result.workersRun)
    }

    @Test fun globalWorkerCapIsSix() = runBlocking {
        var runs = 0
        val roots = (1..4).map { WorkerTask("w$it", "t", "angle $it", depth = 0) }
        val result = SubagentOrchestrator.fanOut(roots) { task ->
            runs++
            val spawn = if (task.depth == 0) listOf(
                WorkerTask("${task.id}.1", "t", "sub 1", depth = 1, parentId = task.id),
                WorkerTask("${task.id}.2", "t", "sub 2", depth = 1, parentId = task.id),
            ) else emptyList()
            WorkerResult(
                task.id, task.id,
                facts = listOf(WorkerFact("fact ${task.id}", "", task.id)),
                angle = task.angle, role = task.role, depth = task.depth, spawn = spawn,
            )
        }
        assertEquals("only 6 workers may ever run in one parent run", 6, runs)
        assertEquals(6, result.workersRun)
        assertTrue("refused spawns are reported, not hidden",
            result.failures.any { it.reason.contains("not started") })
    }

    // ── merge + isolation still hold ────────────────────────────────────────────

    @Test fun mergeStillDedupesAcrossDepths() {
        val r = SubagentOrchestrator.merge(listOf(
            WorkerResult("w1", "w1", facts = listOf(WorkerFact("Same claim", "https://x.example/a", "w1"))),
            WorkerResult("w1.1", "w1.1", facts = listOf(WorkerFact("same claim!", "https://y.example/b", "w1.1"))),
        ))
        assertEquals(1, r.facts.size)
        assertEquals("w1", r.facts[0].workerId) // first worker wins, attribution kept
    }

    @Test fun workerFailureIsIsolated() = runBlocking {
        val tasks = SubagentOrchestrator.splitAngles("t", listOf("a", "b"))
        val result = SubagentOrchestrator.fanOut(tasks) { task ->
            if (task.id == "w1") throw RuntimeException("boom")
            WorkerResult(task.id, task.id, facts = listOf(WorkerFact("ok", "https://x.example", task.id)), angle = task.angle)
        }
        assertEquals(1, result.workersFailed)
        assertEquals(1, result.facts.size)
        assertEquals("a", result.failures.single().angle)
    }

    @Test fun workerUpdatesAreUniqueAcrossFanOuts() = runBlocking {
        // One worker emits two updates (RUNNING then DONE) with the same card id — that is
        // how the card upserts. Uniqueness must hold across fan-outs, for the RUNNING card
        // of each worker, so two swarms never overwrite each other's cards.
        val seen = mutableSetOf<String>()
        val tasks = SubagentOrchestrator.splitAngles("t", listOf("a"))
        repeat(2) {
            SubagentOrchestrator.fanOut(
                tasks,
                { task -> WorkerResult(task.id, task.id, angle = task.angle) },
                onWorker = { u ->
                    if (u.status == WorkerStatus.RUNNING) assertTrue("worker card ids must be unique across swarms", seen.add(u.updateId))
                },
            )
        }
        assertEquals(2, seen.size)
    }

    // ── plan parsing (pure) ─────────────────────────────────────────────────────

    @Test fun parseWriteBlocksReadsFencedContent() {
        val plan = """
            READ: outputs/a.kt
            WRITE: outputs/hello.kt
            ```kotlin
            fun main() { println("hi") }
            ```
            WRITE: outputs/notes.md
            ```markdown
            # Notes
            hello
            ```
        """.trimIndent()
        val blocks = SwarmPlan.parseWriteBlocks(plan)
        assertEquals(listOf("outputs/hello.kt", "outputs/notes.md"), blocks.map { it.path })
        assertTrue(blocks[0].content.contains("fun main()"))
        assertTrue(blocks[1].content.contains("# Notes"))
        assertEquals(listOf("outputs/a.kt"), SwarmPlan.parseReadPaths(plan))
    }

    @Test fun parseSpawnLinesCapsAtTwo() {
        val parent = WorkerTask("w1", "t", "a", role = WorkerRole.CODER)
        val spawns = SwarmPlan.parseSpawnLines(
            "SPAWN: TESTER: test the login\nSPAWN: REVIEWER: review the login\nSPAWN: RESEARCHER: more facts",
            parent,
        )
        assertEquals(SubagentLimits.MAX_SPAWN_PER_WORKER, spawns.size)
        assertEquals(WorkerRole.TESTER, spawns[0].role)
        assertEquals("test the login", spawns[0].brief)
        assertEquals(WorkerRole.REVIEWER, spawns[1].role)
        assertEquals(1, spawns[0].depth)
        assertEquals("w1", spawns[0].parentId)
    }

    @Test fun parseCommandsSkipsBlanksAndComments() {
        val cmds = SwarmPlan.parseCommands("\n# check\n./gradlew test\n\nls outputs\n")
        assertEquals(listOf("./gradlew test", "ls outputs"), cmds)
    }

    // ── parent stays in charge ──────────────────────────────────────────────────

    @Test fun swarmToolIsApprovalGated() {
        val req = ApprovalGate.requestFor("subagent_swarm", buildJsonObject { put("task", "build a site") })
        assertNotNull("the swarm must ask the user once at the parent level", req)
        assertEquals("subagent swarm", req!!.pattern)
        assertFalse("a swarm is rememberable, not a hard denylist hit", req.hard)
    }

    @Test fun swarmToolIsInResearchGroup() {
        val group = ToolCatalog.groupOf("subagent_swarm")
        assertNotNull("subagent_swarm must belong to a Tools library group", group)
        assertEquals("research", group!!.id)
    }
}
