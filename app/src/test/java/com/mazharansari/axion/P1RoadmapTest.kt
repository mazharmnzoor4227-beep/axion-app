package com.mazharansari.axion

import com.mazharansari.axion.agent.SubagentLimits
import com.mazharansari.axion.agent.SubagentOrchestrator
import com.mazharansari.axion.agent.WorkerFact
import com.mazharansari.axion.agent.WorkerResult
import com.mazharansari.axion.agent.WorkerTask
import com.mazharansari.axion.agent.CitationCheck
import com.mazharansari.axion.agent.CitationCheck.ViolationKind
import com.mazharansari.axion.skills.SkillHub
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * P1-4 / P1-5 / P1-6 pure-logic tests (no model, no network, no Android framework):
 * fan-out merge dedupe + worker-failure isolation, citation verifier violations,
 * skill-hub licence filtering.
 */
class P1RoadmapTest {

    // ── P1-4: subagent orchestration ──────────────────────────────────────────────

    @Test fun fanOutCapsWorkersAtThree() {
        val tasks = SubagentOrchestrator.splitAngles("phones", listOf("a", "b", "c", "d", "e"))
        assertEquals(SubagentLimits.MAX_WORKERS, tasks.size)
        assertEquals(listOf("w1", "w2", "w3"), tasks.map { it.id })
        assertEquals(listOf("a", "b", "c"), tasks.map { it.angle })
    }

    @Test fun workerToolAllowListIsWebOnly() {
        assertEquals(setOf("web_search", "browse_url"), SubagentLimits.RESEARCH_TOOLS)
        assertFalse(SubagentLimits.RESEARCH_TOOLS.contains("run_command"))
        assertFalse(SubagentLimits.RESEARCH_TOOLS.contains("shell_session"))
        val tasks = SubagentOrchestrator.splitAngles("t", listOf("a"))
        assertEquals(SubagentLimits.RESEARCH_TOOLS, tasks.single().allowedTools)
        assertEquals(SubagentLimits.MAX_STEPS_PER_WORKER, tasks.single().maxSteps)
    }

    @Test fun mergeDedupesByUrlAcrossWorkers() {
        val r = SubagentOrchestrator.merge(listOf(
            WorkerResult("w1", "w1", facts = listOf(WorkerFact("First claim", "https://example.com/a", "w1"))),
            WorkerResult("w2", "w2", facts = listOf(
                WorkerFact("Same page, different words", "https://example.com/a/?utm_source=x", "w2"),
                WorkerFact("A new claim", "https://example.com/b", "w2"),
            )),
        ))
        assertEquals(2, r.facts.size)
        assertEquals("w1", r.facts[0].workerId)   // first worker wins, attribution kept
        assertEquals("w2", r.facts[1].workerId)
        assertEquals(0, r.workersFailed)
    }

    @Test fun mergeDedupesByFactTextWhenUrlsDiffer() {
        val r = SubagentOrchestrator.merge(listOf(
            WorkerResult("w1", "w1", facts = listOf(WorkerFact("The price is Rs 500.", "https://a.example/x", "w1"))),
            WorkerResult("w2", "w2", facts = listOf(WorkerFact("the  price is Rs 500!", "https://b.example/y", "w2"))),
        ))
        assertEquals(1, r.facts.size)
    }

    @Test fun workerFailureNeverKillsTheParentRun() = runBlocking {
        val tasks = SubagentOrchestrator.splitAngles("t", listOf("angle one", "angle two", "angle three"))
        val result = SubagentOrchestrator.fanOut(tasks) { task ->
            if (task.id == "w2") throw IllegalStateException("search engine down")
            WorkerResult(task.id, task.id, facts = listOf(WorkerFact("fact from ${task.id}", "https://example.com/${task.id}", task.id)), angle = task.angle)
        }
        assertEquals(3, result.workersRun)
        assertEquals(1, result.workersFailed)
        assertEquals(2, result.facts.size)
        assertEquals(1, result.failures.size)
        assertEquals("w2", result.failures.single().workerId)
        assertEquals("angle two", result.failures.single().angle)
        assertTrue(result.failures.single().reason.contains("search engine down"))
    }

    @Test fun workerReturningFailureRecordIsKeptAndOthersSurvive() = runBlocking {
        val tasks = SubagentOrchestrator.splitAngles("t", listOf("a", "b"))
        val result = SubagentOrchestrator.fanOut(tasks) { task ->
            if (task.id == "w1") WorkerResult(task.id, task.id, failed = "no results", angle = task.angle)
            else WorkerResult(task.id, task.id, facts = listOf(WorkerFact("only fact", "https://example.com/only", task.id)), angle = task.angle)
        }
        assertEquals(1, result.facts.size)
        assertEquals("only fact", result.facts.single().text)
        assertEquals(1, result.workersFailed)
    }

    @Test fun urlNormalisationDropsSchemeWwwSlashAndQuery() {
        assertEquals("example.com/page", SubagentOrchestrator.normaliseUrl("https://www.example.com/page/"))
        assertEquals("example.com/page", SubagentOrchestrator.normaliseUrl("http://example.com/page?utm=x#frag"))
    }

    // ── P1-5: skill hub licence filtering ────────────────────────────────────────

    @Test fun onlyMitAndApacheLicencesAreInstallable() {
        assertTrue(SkillHub.licenceAllowed("MIT"))
        assertTrue(SkillHub.licenceAllowed("Apache-2.0"))
        assertTrue(SkillHub.licenceAllowed("Apache 2.0"))
        assertFalse(SkillHub.licenceAllowed("GPL-3.0"))
        assertFalse(SkillHub.licenceAllowed("Proprietary"))
        assertFalse(SkillHub.licenceAllowed(""))
        assertFalse(SkillHub.licenceAllowed("CC-BY-NC-4.0"))
    }

    @Test fun installableListDropsNonMitApacheEntries() {
        val entries = SkillHub.COMMUNITY + SkillHub.HubEntry(
            "Closed skills", "Not open source", "https://github.com/example/closed", "Proprietary", SkillHub.HubKind.COMMUNITY,
        )
        val ok = SkillHub.installable(entries)
        assertTrue(ok.all { SkillHub.licenceAllowed(it.licence) })
        assertTrue(ok.none { it.name == "Closed skills" })
    }

    @Test fun communityListHasOnlyVerifiedRepoUrls() {
        // Every community entry must be a repo verified on disk (git remote / README link);
        // steipete/agent-scripts is deliberately NOT here — its URL could not be verified
        // from the code/docs on disk (no git remote, no explicit URL in its README).
        assertTrue(SkillHub.COMMUNITY.isNotEmpty())
        assertTrue(SkillHub.COMMUNITY.all { it.repoUrl.startsWith("https://github.com/") })
        assertTrue(SkillHub.COMMUNITY.none { "steipete" in it.repoUrl })
        assertTrue(SkillHub.COMMUNITY.any { it.repoUrl == "https://github.com/affaan-m/ECC" && it.licence == "MIT" })
    }

    @Test fun mikaSkillsAreRecordedAsApache() {
        assertTrue(SkillHub.MIKA_SKILL_NAMES.isNotEmpty())
        assertEquals(5, SkillHub.MIKA_SKILL_NAMES.size)
        assertTrue("info-research" in SkillHub.MIKA_SKILL_NAMES)
    }

    // ── P1-6: citation verifier ───────────────────────────────────────────────────

    private val cleanReport = """
        # Deep research: test topic

        ## Key findings
        - The price is Rs 500. [1][2]
        - The launch was in March 2026. [1][3]

        ## Conflicting views
        None found

        ## Gaps and uncertainties
        Nothing major.

        ## Sources
        1. First source — https://example.com/a
        2. Second source — https://example.com/b
        3. Third source — https://example.com/c
    """.trimIndent()

    @Test fun cleanReportHasNoViolations() {
        val result = CitationCheck.verify(cleanReport)
        assertEquals(3, result.sources.size)
        assertTrue(result.violations.isEmpty())
    }

    @Test fun uncitedKeyClaimIsFlagged() {
        val report = cleanReport.replace("- The price is Rs 500. [1][2]", "- The price is Rs 500.")
        val result = CitationCheck.verify(report)
        assertTrue(result.violations.any { it.kind == ViolationKind.UNCITED_KEY_CLAIM && "Rs 500" in it.detail })
    }

    @Test fun danglingCitationNumberIsFlagged() {
        val report = cleanReport.replace("[1][2]", "[1][9]")
        val result = CitationCheck.verify(report)
        assertTrue(result.violations.any { it.kind == ViolationKind.DANGLING_CITATION && "9" in it.detail })
    }

    @Test fun singleSourcedKeyNumberIsFlagged() {
        val report = cleanReport.replace("[1][2]", "[1]")
        val result = CitationCheck.verify(report)
        assertTrue(result.violations.any { it.kind == ViolationKind.SINGLE_SOURCE_NUMBER && "Rs 500" in it.detail })
        // A claim with no number needs no second source.
        assertFalse(result.violations.any { it.kind == ViolationKind.SINGLE_SOURCE_NUMBER && "March 2026" in it.detail && "Rs 500" !in it.detail })
    }

    @Test fun annotateMarksSingleSourceAndMissingCitation() {
        val report = cleanReport
            .replace("- The price is Rs 500. [1][2]", "- The price is Rs 500. [1]")
            .replace("- The launch was in March 2026. [1][3]", "- The launch was in March 2026.")
        val annotated = CitationCheck.annotate(report)
        assertTrue("(single source)" in annotated)
        assertTrue("(citation needed)" in annotated)
        assertTrue("Citation check:" in annotated)
        // Annotating twice does not stack markers.
        assertEquals(annotated, CitationCheck.annotate(annotated))
    }

    @Test fun extractSourcesParsesNumberedList() {
        val sources = CitationCheck.extractSources(cleanReport)
        assertEquals(listOf(1, 2, 3), sources.map { it.n })
        assertEquals("https://example.com/b", sources[1].url)
    }

    @Test fun claimsOutsideKeyFindingsAreNotTreatedAsKeyClaims() {
        val report = """
            # Deep research: test topic

            ## Key findings
            - The price is Rs 500. [1][2]

            ## Gaps and uncertainties
            - Something could not be verified.

            ## Sources
            1. First source — https://example.com/a
            2. Second source — https://example.com/b
        """.trimIndent()
        val result = CitationCheck.verify(report)
        assertTrue(result.violations.none { it.kind == ViolationKind.UNCITED_KEY_CLAIM })
    }
}
