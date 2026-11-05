package com.mazharansari.axion.agent

import com.mazharansari.axion.agent.CommandStatusLogic.Kind
import com.mazharansari.axion.data.StepStatus
import org.junit.Assert.*
import org.junit.Test

class CommandStatusTest {
    @Test fun runningHasNoExit() = assertEquals(Kind.RUNNING, CommandStatusLogic.status(StepStatus.RUNNING, null, "partial").kind)
    @Test fun exitZeroIsDone() { val s = CommandStatusLogic.status(StepStatus.DONE, 0, "ok"); assertEquals(Kind.OK, s.kind); assertEquals("Done · exit 0", s.label) }
    @Test fun exitCodeFailure() { val s = CommandStatusLogic.status(StepStatus.ERROR, 2, "x"); assertEquals(Kind.FAILED, s.kind); assertEquals("Failed · exit 2", s.label); assertEquals(2, s.exit) }
    @Test fun oldStepsParseHeader() { val s = CommandStatusLogic.status(StepStatus.ERROR, null, "[exit code 127]\nbash: foo: command not found"); assertEquals(Kind.FAILED, s.kind); assertEquals(127, s.exit) }
    @Test fun timeout() { assertEquals(Kind.TIMEOUT, CommandStatusLogic.status(StepStatus.ERROR, -1, "[timed out after 60s]\nzzz").kind); assertEquals("Timed out after 60 s", CommandStatusLogic.status(StepStatus.ERROR, null, "[timed out after 60s]\nzzz").label) }
    @Test fun stopped() = assertEquals(Kind.STOPPED, CommandStatusLogic.status(StepStatus.ERROR, null, "Stopped").kind)
    @Test fun errorWithoutCode() = assertEquals(Kind.FAILED, CommandStatusLogic.status(StepStatus.ERROR, null, "boom").kind)
    @Test fun bodyStripsHeader() { assertEquals("hello", CommandStatusLogic.body("[exit code 1]\nhello")); assertEquals("hello", CommandStatusLogic.body("[timed out after 5s]\nhello")); assertEquals("a\nb", CommandStatusLogic.body("a\nb")) }
    @Test fun tailKeepsLastLines() {
        val t = (1..500).joinToString("\n") { "line $it" }
        val (txt, hidden) = CommandStatusLogic.tail(t, 200)
        assertEquals(300, hidden); assertTrue(txt.endsWith("line 500")); assertTrue(txt.startsWith("line 301"))
        assertEquals(0, CommandStatusLogic.tail("a\nb", 200).second)
    }
}
