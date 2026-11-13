package com.mazharansari.axion

import com.mazharansari.axion.agent.ApprovalGate
import com.mazharansari.axion.github.GitAuditEntry
import com.mazharansari.axion.github.GitAuditStore
import com.mazharansari.axion.github.GitCommands
import com.mazharansari.axion.github.GitHubDeviceFlow
import com.mazharansari.axion.github.GitHubGate
import com.mazharansari.axion.github.GitHubRepo
import com.mazharansari.axion.github.GitHubToken
import com.mazharansari.axion.github.GitAction
import com.mazharansari.axion.security.SecretScanner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * P1-1 (§6.1–6.2): GitHub connect — pure-logic tests. No network, no Android,
 * no token ever leaves the test (the fake tokens below are random strings, not real keys).
 */
class P1GitHubTest {

    private val fakeClassic = "ghp_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8"
    private val fakeFineGrained = "github_pat_11ABCDEFG0a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4y5z6"
    private val fakeOauth = "gho_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8"

    // ── device flow ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun deviceCodeParses() {
        val el = Json.parseToJsonElement(
            """{"device_code":"dev-1","user_code":"ABCD-EFGH","verification_uri":"https://github.com/login/device","expires_in":900,"interval_sec":5}"""
        ).jsonObject
        val dc = GitHubDeviceFlow.parseDeviceCode(el)
        assertEquals("dev-1", dc.deviceCode)
        assertEquals("ABCD-EFGH", GitHubDeviceFlow.formatUserCode(dc.userCode))
        assertEquals("https://github.com/login/device", dc.verificationUri)
        assertEquals(900, dc.expiresInSec)
        assertEquals(5, dc.intervalSec)
    }

    @Test fun deviceCodeWithoutCodeThrows() {
        val el = Json.parseToJsonElement("""{"error":"incorrect_client_credentials"}""").jsonObject
        try {
            GitHubDeviceFlow.parseDeviceCode(el)
            fail("missing device_code must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("device code"))
        }
    }

    @Test fun tokenPollOutcomes() {
        val token = Json.parseToJsonElement("""{"access_token":"gho_x"}""").jsonObject
        assertEquals(GitHubDeviceFlow.PollResult.Token("gho_x"), GitHubDeviceFlow.parseTokenResponse(token, 5))
        val pending = Json.parseToJsonElement("""{"error":"authorization_pending"}""").jsonObject
        assertEquals(GitHubDeviceFlow.PollResult.Wait(5000), GitHubDeviceFlow.parseTokenResponse(pending, 5))
        val slow = Json.parseToJsonElement("""{"error":"slow_down"}""").jsonObject
        assertEquals(GitHubDeviceFlow.PollResult.Wait(10000), GitHubDeviceFlow.parseTokenResponse(slow, 5))
        val expired = Json.parseToJsonElement("""{"error":"expired_token"}""").jsonObject
        assertTrue(GitHubDeviceFlow.parseTokenResponse(expired, 5) is GitHubDeviceFlow.PollResult.Failed)
        val denied = Json.parseToJsonElement("""{"error":"access_denied"}""").jsonObject
        assertTrue((GitHubDeviceFlow.parseTokenResponse(denied, 5) as GitHubDeviceFlow.PollResult.Failed).message.contains("denied"))
    }

    // ── tokens ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun patShapesAccepted() {
        assertTrue(GitHubToken.isValidPat(fakeClassic))
        assertTrue(GitHubToken.isValidPat(fakeFineGrained))
        assertTrue(GitHubToken.isValidPat(fakeOauth))
        assertEquals("Classic personal access token", GitHubToken.kindOf(fakeClassic))
        assertEquals("Fine-grained personal access token", GitHubToken.kindOf(fakeFineGrained))
        assertEquals("OAuth token", GitHubToken.kindOf(fakeOauth))
    }

    @Test fun patShapesRejected() {
        for (bad in listOf("", "password123", "ghp_short", "github_pat_short", "sk-or-v1-abcdefghij1234567890")) {
            assertFalse("must reject: $bad", GitHubToken.isValidPat(bad))
        }
    }

    @Test fun secretScannerCoversGitHubTokens() {
        // §6.1: the token shapes must be covered by SecretScanner (ghp_ / github_pat_ / gho_ / ghu_).
        for (t in listOf(fakeClassic, fakeFineGrained, fakeOauth, "ghu_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8")) {
            val hits = SecretScanner.find("leaked $t here")
            assertTrue("SecretScanner must catch $t", hits.isNotEmpty())
            assertEquals("GitHub token", hits.first().kind)
            assertFalse("scrub must not leave the token", SecretScanner.scrub("leaked $t here").contains(t))
        }
        assertEquals("ghp_••••e7f8", GitHubToken.hint(fakeClassic))
        assertTrue("hint shows only the last 4", GitHubToken.hint(fakeClassic).endsWith("e7f8") && !GitHubToken.hint(fakeClassic).contains("a1b2"))
    }

    // ── repos ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun repoParsing() {
        assertEquals("owner" to "repo", GitHubRepo.parse("owner/repo"))
        assertEquals("owner" to "repo", GitHubRepo.parse("https://github.com/owner/repo"))
        assertEquals("owner" to "my-repo.js", GitHubRepo.parse("https://github.com/owner/my-repo.js.git"))
        assertEquals("o" to "r", GitHubRepo.parse("git@github.com:o/r.git"))
        assertNull(GitHubRepo.parse("owner"))
        assertNull(GitHubRepo.parse("a/b/c"))
        assertNull(GitHubRepo.parse("owner/re po"))
        assertEquals("/workspace/repos/owner-repo", GitHubRepo.guestDir("owner", "repo"))
        assertTrue(GitHubRepo.isDefaultBranch("main"))
        assertTrue(GitHubRepo.isDefaultBranch("master"))
        assertTrue(GitHubRepo.isDefaultBranch(null))
        assertFalse(GitHubRepo.isDefaultBranch("feature/x"))
    }

    // ── gate rules (§6.2 hard rules) ──────────────────────────────────────────────────────────────────────────

    @Test fun gateActions() {
        assertEquals(GitAction.PUSH_DEFAULT, GitHubGate.actionFor("github_push", emptyMap(), branch = "main"))
        assertEquals(GitAction.PUSH_DEFAULT, GitHubGate.actionFor("github_push", emptyMap(), branch = null))
        assertEquals(GitAction.PUSH, GitHubGate.actionFor("github_push", emptyMap(), branch = "feature/x"))
        assertEquals(GitAction.FORCE_PUSH, GitHubGate.actionFor("github_push", emptyMap(), force = true, branch = "feature/x"))
        assertEquals(GitAction.MERGE, GitHubGate.actionFor("github_pr", mapOf("action" to "merge")))
        assertEquals(GitAction.LIST_COMMENTS, GitHubGate.actionFor("github_pr", mapOf("action" to "comments")))
        assertEquals(GitAction.BRANCH_DELETE, GitHubGate.actionFor("github_branch", mapOf("action" to "delete")))
        assertEquals(GitAction.STATUS, GitHubGate.actionFor("github_status", emptyMap()))   // read-only: gated to null by the caller
        // read-only actions never need approval; always-ask actions need it even ungated.
        assertFalse(GitHubGate.requiresApproval(GitAction.STATUS, ungated = false))
        assertFalse(GitHubGate.requiresApproval(GitAction.READ_CI, ungated = false))
        assertTrue(GitHubGate.requiresApproval(GitAction.PUSH_DEFAULT, ungated = true))
        assertTrue(GitHubGate.requiresApproval(GitAction.FORCE_PUSH, ungated = true))
        assertTrue(GitHubGate.requiresApproval(GitAction.MERGE, ungated = true))
        assertFalse(GitHubGate.requiresApproval(GitAction.PUSH, ungated = true))
        assertTrue(GitHubGate.requiresApproval(GitAction.PUSH, ungated = false))
        assertTrue(GitHubGate.neverRemember(GitAction.PUSH_DEFAULT))
        assertTrue(GitHubGate.neverRemember(GitAction.FORCE_PUSH))
        assertFalse(GitHubGate.neverRemember(GitAction.PUSH))
        assertTrue(GitHubGate.hardReason(GitAction.PUSH_DEFAULT).isNotBlank())
    }

    @Test fun approvalGateRoutesGitHubTools() {
        val pushMain = ApprovalGate.requestFor("github_push", buildJsonObject { put("repo", "o/r"); put("branch", "main") })
        assertNotNull(pushMain); assertTrue(pushMain!!.hard); assertEquals("", pushMain.pattern)
        val pushFeat = ApprovalGate.requestFor("github_push", buildJsonObject { put("repo", "o/r"); put("branch", "feature/x") })
        assertNotNull(pushFeat); assertFalse(pushFeat!!.hard); assertEquals("github push", pushFeat.pattern)
        val force = ApprovalGate.requestFor("github_push", buildJsonObject { put("repo", "o/r"); put("branch", "feature/x"); put("force", true) })
        assertNotNull(force); assertTrue(force!!.hard)
        val merge = ApprovalGate.requestFor("github_pr", buildJsonObject { put("repo", "o/r"); put("action", "merge"); put("number", 3) })
        assertNotNull(merge); assertTrue(merge!!.hard)
        assertNull("status is read-only", ApprovalGate.requestFor("github_status", buildJsonObject { put("repo", "o/r") }))
        assertNull("CI is read-only", ApprovalGate.requestFor("github_ci", buildJsonObject { put("repo", "o/r") }))
        assertNull("PR comments are read-only", ApprovalGate.requestFor("github_pr", buildJsonObject { put("repo", "o/r"); put("action", "comments"); put("number", 3) }))
    }

    @Test fun shellGitHardRules() {
        assertNotNull(GitHubGate.hardReasonForShellCommand("git push origin main"))
        assertNotNull(GitHubGate.hardReasonForShellCommand("git push origin master"))
        assertNotNull(GitHubGate.hardReasonForShellCommand("git push --force-with-lease origin feature/x"))
        assertNotNull(GitHubGate.hardReasonForShellCommand("git push --force origin feature/x"))
        assertNotNull(GitHubGate.hardReasonForShellCommand("git branch -D old-branch"))
        assertNotNull(GitHubGate.hardReasonForShellCommand("git branch -d old-branch"))
        assertNotNull(GitHubGate.hardReasonForShellCommand("git push origin --delete old-branch"))
        assertNull(GitHubGate.hardReasonForShellCommand("git push origin feature/x"))
        assertNull(GitHubGate.hardReasonForShellCommand("git status"))
        assertNull(GitHubGate.hardReasonForShellCommand("git log --oneline"))
        // …and the shell gate itself turns those into hard requests.
        val g = ApprovalGate.requestFor("run_command", buildJsonObject { put("command", "git push origin main") })
        assertNotNull(g); assertTrue(g!!.hard); assertEquals("", g.pattern)
        assertNull(ApprovalGate.requestFor("run_command", buildJsonObject { put("command", "git status") }))
    }

    // ── commands ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun gitCommands() {
        assertEquals("git clone --depth 1 'https://github.com/o/r.git' '/workspace/repos/o-r'", GitCommands.clone("o", "r", "/workspace/repos/o-r"))
        assertEquals("git clone 'https://github.com/o/r.git' '/workspace/repos/o-r'", GitCommands.clone("o", "r", "/workspace/repos/o-r", shallow = false))
        assertTrue(GitCommands.push("/workspace/repos/o-r", "feature/x", force = true).contains("--force-with-lease"))
        assertFalse(GitCommands.push("/workspace/repos/o-r", "feature/x").contains("--force"))
        // A quote inside a commit message must not break out of the shell quoting.
        val commit = GitCommands.commit("/workspace/repos/o-r", "fix: it's done", "Mazhar", "a@b.c")
        assertTrue(commit.contains("'fix: it'\\''s done'"))
        assertTrue(commit.contains("-c user.name='Mazhar'"))
        assertEquals("'a'\\''b'", GitCommands.shellQuote("a'b"))
    }

    @Test fun diffSecretRefusal() {
        val dirty = "+const key = \"$fakeClassic\"\n+// normal line\n"
        assertTrue(GitCommands.diffHasSecret(dirty))
        assertEquals(listOf("GitHub token"), GitCommands.secretKindsIn(dirty))
        assertFalse(GitCommands.diffHasSecret("+const x = 1\n+// nothing secret here\n"))
        assertFalse(GitCommands.diffHasSecret(""))
    }

    // ── audit log ───────────────────────────────────────────────────────────────────────────────────────────

    @Test fun auditLogRoundTripAndCap() {
        val dir = File.createTempFile("p1-github-audit", "").apply { delete(); mkdirs() }
        try {
            val store = GitAuditStore(dir)
            assertTrue(store.list().isEmpty())
            store.append(GitAuditEntry("o/r", "main", "push", result = "ok"))
            store.append(GitAuditEntry("o/r", "feat", "commit", result = "refused", detail = "secret in diff"))
            val list = GitAuditStore(dir).list()   // re-read from disk
            assertEquals(2, list.size)
            assertEquals("commit", list[0].action)   // newest first
            assertEquals("refused", list[0].result)
            repeat(205) { store.append(GitAuditEntry("o/r", "", "status", result = "ok")) }
            assertEquals(200, store.list().size)
            store.clear()
            assertTrue(store.list().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
