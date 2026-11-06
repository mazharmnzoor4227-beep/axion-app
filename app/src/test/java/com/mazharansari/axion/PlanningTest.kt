package com.mazharansari.axion.agent

import com.mazharansari.axion.data.PlanStatus
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlanningTest {
    @Test fun heuristicOnlyPlansRealMultiStepActions() {
        assertFalse(Planner.needsPlan("hi bhai", Intent.CHAT))
        assertFalse(Planner.needsPlan("make a pdf", Intent.ACTION))
        assertFalse(Planner.needsPlan("search latest news on bitcoin and summarise it for me please now", Intent.LOOKUP))
        assertTrue(Planner.needsPlan("Build a website for my shop, then add a contact form and after that deploy it to netlify", Intent.ACTION))
        assertTrue(Planner.needsPlan("pehle is file ko parho phir summary banao aur phir PDF bhi bana do please", Intent.ACTION))
        assertTrue(Planner.needsPlan("1. read the csv\n2. clean the data\n3. make a chart", Intent.ACTION))
        // v0.6.1: short multi-step orders plan too; a single small ask still does not
        assertTrue(Planner.needsPlan("logo banao aur home page par lagao", Intent.ACTION))
        assertFalse(Planner.needsPlan("ek photo bhej do please abhi", Intent.ACTION))
    }
    @Test fun planSetAndUpdateStatuses() {
        val s = PlanState()
        assertTrue(s.set(listOf("Read file", " Write page ", "Check preview")).startsWith("Plan set"))
        assertEquals(listOf(PlanStatus.RUNNING, PlanStatus.PENDING, PlanStatus.PENDING), s.steps.map { it.status })
        s.update(2, "running"); assertEquals(listOf(PlanStatus.DONE, PlanStatus.RUNNING, PlanStatus.PENDING), s.steps.map { it.status })   // starting step 2 closes step 1
        s.update(2, "done"); s.update(3, "failed", "preview broke")
        assertEquals(PlanStatus.FAILED, s.steps[2].status); assertEquals("preview broke", s.steps[2].note)
        assertTrue(s.update(9, "done").contains("does not exist")); assertTrue(s.update(1, "weird").startsWith("Unknown status"))
        assertEquals("Write page", s.steps[1].text)
    }
    @Test fun finishResolvesEverything() {
        // v0.6.1: only steps that actually ran may end DONE — a step that never ran ends SKIPPED, never silently "done"
        val s = PlanState(); s.set(listOf("a", "b", "c")); s.update(1, "done")
        s.finish(ok = true)
        assertEquals(listOf(PlanStatus.DONE, PlanStatus.SKIPPED, PlanStatus.SKIPPED), s.steps.map { it.status })
        val r = PlanState(); r.set(listOf("a", "b")); r.finish(ok = true)
        assertEquals(PlanStatus.DONE, r.steps[0].status)   // the step that was running when the answer came counts as done
        val f = PlanState(); f.set(listOf("a", "b")); f.finish(ok = false, reason = "tool gave up")
        assertEquals(PlanStatus.FAILED, f.steps[0].status); assertEquals(PlanStatus.PENDING, f.steps[1].status)
    }
    @Test fun parseStepsAcceptsArrayJsonStringAndTextList() {
        assertEquals(listOf("a", "b"), Planner.parseSteps(buildJsonObject { putJsonArray("steps") { add("a"); add("b") } }))
        assertEquals(listOf("a", "b"), Planner.parseSteps(buildJsonObject { put("steps", "[\"a\",\"b\"]") }))
        assertEquals(listOf("one", "two"), Planner.parseSteps(buildJsonObject { put("steps", "1. one\n2) two") }))
        assertTrue(Planner.parseSteps(buildJsonObject { }).isEmpty())
        assertEquals(8, PlanState().also { it.set((1..20).map { n -> "s$n" }) }.steps.size)
        assertTrue(PlanState().set(emptyList()).startsWith("Plan not set"))
    }
}
