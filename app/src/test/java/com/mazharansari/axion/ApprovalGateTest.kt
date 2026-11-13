package com.mazharansari.axion

import com.mazharansari.axion.agent.ApprovalGate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

// P0-11 (ME-17): `rm -rf /*` and variants hard-deny every time, and remembered approvals key on the full command shape, not the bare program name. Pure Kotlin — no Android needed.
class ApprovalGateTest {

    private fun gate(cmd: String) = ApprovalGate.requestFor("run_command", buildJsonObject { put("command", cmd) })

    @Test fun rmRfRootVariantsHardDeny() {
        for (cmd in listOf(
            "rm -rf /*",
            "rm -rf / *",
            "rm --recursive --force /",
            "rm -rf / --no-preserve-root",
            "rm -fr /",
            "rm -r -f /",
            "rm -rf ~",
            "rm -rf \$HOME",
            "rm -rf /data",
            "rm -rf /system",
            "sudo rm -rf /*",
        )) {
            assertNotNull(cmd, ApprovalGate.hardReason(cmd))
            val g = gate(cmd)
            assertNotNull(cmd, g)
            assertTrue("$cmd must be a hard gate", g!!.hard)
            assertEquals("$cmd must never be rememberable", "", g.pattern)
            assertTrue(g.hardReason.isNotBlank())
        }
    }

    @Test fun chainedRmRfRootIsStillHardDenied() {
        assertNotNull(ApprovalGate.hardReason("echo hi; rm -rf /*"))
        assertTrue(gate("echo hi; rm -rf /*")!!.hard)
    }

    @Test fun benignRmIsANormalGateNotAHardDeny() {
        val plain = gate("rm old.txt")
        assertNotNull(plain); assertFalse(plain!!.hard); assertEquals("rm old.txt", plain.pattern)
        val tmp = gate("rm -rf /tmp/x")
        assertNotNull(tmp); assertFalse("rm -rf /tmp/x is not a root target", tmp!!.hard)
        assertEquals("rm -rf /tmp/x", tmp.pattern)
        assertNull("read-only commands need no gate", gate("ls"))
        assertFalse("recursive without force is not a hard deny", gate("rm -r /tmp/x")!!.hard)
    }

    @Test fun rememberedBenignRmDoesNotAllowRmRfRoot() {
        // The remembered rule for a benign rm keys on "rm old.txt"; the root-wipe keys on
        // "rm -rf /*" — different patterns — and the root-wipe is hard anyway, so the agent
        // never consults remembered rules for it (Agent.kt: the auto-allow branch needs !hard).
        assertEquals("rm old.txt", ApprovalGate.patternOf("rm old.txt"))
        assertEquals("rm -rf /*", ApprovalGate.patternOf("rm -rf /*"))
        assertNotEquals(ApprovalGate.patternOf("rm old.txt"), ApprovalGate.patternOf("rm -rf /*"))
        val g = gate("rm -rf /*")
        assertNotNull(g); assertTrue(g!!.hard); assertEquals("", g.pattern)
    }

    @Test fun rememberedPython3ApprovalIsPerScript() {
        assertEquals("python3 build.py", ApprovalGate.patternOf("python3 build.py"))
        assertNotEquals(ApprovalGate.patternOf("python3 build.py"), ApprovalGate.patternOf("python3 other.py"))
        assertNotEquals(ApprovalGate.patternOf("python3 build.py"), ApprovalGate.patternOf("python3 -u build.py"))
        assertEquals("python3 -m pytest", ApprovalGate.patternOf("python3 -m pytest"))
        // Bare subcommands still group as before: "git status" is one kind.
        assertEquals("git status", ApprovalGate.patternOf("git status"))
        assertEquals("npm run", ApprovalGate.patternOf("npm run build"))
        assertEquals("rm -rf /tmp/x", ApprovalGate.patternOf("rm -rf /tmp/x"))
    }
}
