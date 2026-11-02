package com.mazharansari.axion

import com.mazharansari.axion.antigravity.*
import com.mazharansari.axion.data.Connector
import com.mazharansari.axion.data.ConnectorRepo
import com.mazharansari.axion.data.KeyBackup
import com.mazharansari.axion.linux.DevTools
import com.mazharansari.axion.linux.LinuxRuntime
import com.mazharansari.axion.linux.ScriptProtocol
import com.mazharansari.axion.llm.Mode
import com.mazharansari.axion.quota.*
import org.junit.Assert.*
import org.junit.Test

/** v0.4 stage 1: quota parsing, rotation, account selection, key backup, PRoot argv — all pure, run on the JVM. */
class V04LogicTest {
    private val now = 1_793_520_000_000L   // 2026-11-01 ~08:40 UTC

    // ── agy /usage (shape unverified: three fixtures, spec §3.2 / §9) ──
    @Test fun agyUsage_likelyJsonShape() {
        val json = """{"tier":"free","groups":[{"label":"Gemini models","buckets":[{"label":"Weekly","models":["gemini-3.8-flash"],"remaining_percentage":72,"reset_time":"2026-11-05T09:00:00Z","disabled":false}]},
            {"label":"Claude models","buckets":[{"label":"Weekly","remaining_fraction":0.31,"reset_time":"2026-11-05T09:00:00Z"}]}]}"""
        val r = AgyUsageParser.parse(json, now)
        assertTrue(r.parsed); assertEquals("free", r.tier); assertEquals(2, r.buckets.size)
        assertEquals(0.72, r.buckets[0].remainingFraction!!, 1e-9)
        assertEquals("week", r.buckets[0].window)
        assertEquals(0.31, r.buckets[1].remainingFraction!!, 1e-9)
        assertTrue(r.buckets[0].label.contains("Gemini"))
    }

    @Test fun agyUsage_tsvText() {
        val tsv = "Gemini models\t5-hour\t82%\t2026-11-01T11:40:00Z\nClaude models\tWeekly\t40% remaining\t2026-11-05T09:00:00Z\n"
        val r = AgyUsageParser.parse(tsv, now)
        assertEquals(2, r.buckets.size)
        assertEquals(0.82, r.buckets[0].remainingFraction!!, 1e-9); assertEquals("5h", r.buckets[0].window)
        assertEquals(0.40, r.buckets[1].remainingFraction!!, 1e-9)
    }

    @Test fun agyUsage_missingFractionIsDashNotZero() {
        val json = """{"groups":[{"label":"Third-party","buckets":[{"label":"Weekly","used_percentage":null,"remaining_percentage":null,"reset_time":"2026-11-05T09:00:00Z"}]}]}"""
        val r = AgyUsageParser.parse(json, now)
        assertEquals(1, r.buckets.size)
        assertNull("missing must stay unknown, never 0 %", r.buckets[0].remainingFraction)
    }

    @Test fun agyUsage_garbageGivesNothing() { assertFalse(AgyUsageParser.parse("Authentication required. Please visit the URL…", now).parsed) }

    // ── Gemini 429 (the exact body captured live on 1 Nov 2026) ──
    @Test fun gemini429() {
        val body = """{"error":{"code":429,"message":"You exceeded your current quota","status":"RESOURCE_EXHAUSTED","details":[
          {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests","quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaDimensions":{"location":"global","model":"gemini-3.5-flash-lite"},"quotaValue":"500"}]},
          {"@type":"type.googleapis.com/google.rpc.Help","links":[]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"7739s"}]}}"""
        val g = Gemini429.parse(body)!!
        assertEquals(500.0, g.limit!!, 0.0); assertEquals("gemini-3.5-flash-lite", g.model); assertEquals("day", g.window); assertEquals(7_739_000L, g.retryMs)
    }

    // ── header sniffer ──
    @Test fun groqHeadersAreRpdAndTpm() {
        val h = mapOf("x-ratelimit-limit-requests" to "1000", "x-ratelimit-remaining-requests" to "820", "x-ratelimit-reset-requests" to "2m59.56s",
            "x-ratelimit-limit-tokens" to "8000", "x-ratelimit-remaining-tokens" to "800", "x-ratelimit-reset-tokens" to "7.66s", "x-ratelimit-foo" to "1")
        val r = RateHeaders.parse("groq", h, now)
        val day = r.first { it.window == "day" && it.unit == "req" }
        assertEquals(1000.0, day.limit!!, 0.0); assertEquals(820.0, day.remaining!!, 0.0); assertEquals(now + 179_560, day.resetAt)
        val tpm = r.first { it.window == "minute" && it.unit == "tok" }
        assertEquals(8000.0, tpm.limit!!, 0.0)
        assertTrue("unknown headers kept raw", day.raw.contains("x-ratelimit-foo"))
    }

    @Test fun sambanovaDaySuffix() {
        val h = mapOf("x-ratelimit-limit-requests" to "20", "x-ratelimit-remaining-requests" to "19", "x-ratelimit-limit-requests-day" to "20", "x-ratelimit-remaining-requests-day" to "7", "x-ratelimit-reset-requests-day" to "3600")
        val r = RateHeaders.parse("sambanova", h, now)
        assertEquals(7.0, r.first { it.window == "day" }.remaining!!, 0.0)
        assertEquals(19.0, r.first { it.window == "minute" }.remaining!!, 0.0)
    }

    @Test fun openRouterKey() {
        val k = OpenRouterKey.parse("""{"data":{"label":"x","limit_remaining":null,"is_free_tier":true,"free_model_daily_requests":{"used":12,"limit":50,"remaining":38}}}""")!!
        assertEquals(12.0, k.used!!, 0.0); assertEquals(50.0, k.limit!!, 0.0); assertEquals(38.0, k.remaining!!, 0.0)
        val reset = ProviderDay.nextReset("openrouter", now)
        assertEquals(0L, reset % 86_400_000L)   // next 00:00 UTC
    }

    @Test fun geminiDayIsPacific() {
        val reset = ProviderDay.nextReset("gemini", now)
        val la = java.time.Instant.ofEpochMilli(reset).atZone(java.time.ZoneId.of("America/Los_Angeles"))
        assertEquals(0, la.hour); assertEquals(0, la.minute)
    }

    // ── quota math ──
    @Test fun dayMath() {
        val d = QuotaMath.day(null, 0, 0, 500.0, true, null, 412, 0, now, now + 3_600_000)
        assertEquals(Tag.FROM429, d.limit.tag); assertEquals(412.0, d.used.value!!, 0.0); assertEquals(88.0, d.left.value!!, 0.0); assertEquals(Tag.ESTIMATE, d.left.tag)
        val cooling = QuotaMath.day(null, 0, 0, 500.0, true, null, 3, now + 60_000, now, null)
        assertEquals(0.0, cooling.left.value!!, 0.0); assertEquals(now + 60_000, cooling.resetAt)
        val unknown = QuotaMath.day(null, 0, 0, null, false, null, 37, 0, now, null)
        assertNull(unknown.limit.value); assertEquals(Tag.UNKNOWN, unknown.left.tag)
    }

    @Test fun burnRateHiddenUnder3_andPastReset() {
        val dayStart = now - 8 * 3_600_000L
        assertNull(QuotaMath.ratePerHour(listOf(now - 1000, now - 2000), now, dayStart))
        val calls = (1..30).map { now - it * 60_000L }
        val rate = QuotaMath.ratePerHour(calls, now, dayStart)!!
        assertTrue(rate > 10)
        assertTrue(QuotaMath.runOut(1000.0, rate, now + 3_600_000, now) is QuotaMath.RunOut.LastsPastReset)
        assertTrue(QuotaMath.runOut(5.0, rate, now + 3_600_000, now) is QuotaMath.RunOut.Before)
        assertEquals(QuotaMath.RunOut.NotEnoughData, QuotaMath.runOut(5.0, null, null, now))
    }

    @Test fun totalsSkipUnknown() {
        fun d(l: Double?, u: Double) = QuotaMath.day(null, 0, 0, l, false, null, u.toInt(), 0, now, null)
        val t = QuotaMath.totals(listOf("a" to d(1000.0, 180.0), "b" to d(500.0, 412.0), "c" to d(null, 37.0)))
        assertEquals(1500.0, t.limit, 0.0); assertEquals(592.0, t.used, 0.0); assertEquals(908.0, t.left, 0.0); assertEquals(1, t.unknown)
    }

    @Test fun agyEstimates() {
        assertNull(QuotaMath.agyMoreRuns(2.3, listOf(0.1, 0.1)))
        assertEquals(23.0, QuotaMath.agyMoreRuns(2.3, listOf(0.1, 0.1, 0.1))!!, 1e-9)
        assertEquals(0.3, QuotaMath.agyUsedFromProbes(listOf(0.9, 0.7, 1.0, 0.9)), 1e-9)   // the refresh jump up isn't negative use
        val (sum, unknown) = QuotaMath.agyCombined(listOf(0.82, 0.0, null, 1.0))
        assertEquals(1.82, sum, 1e-9); assertEquals(1, unknown)
    }

    // ── key rotation: every model on a key before the next key ──
    private fun conn(id: String, p: String, models: List<String>) = Connector(id, p, "x", "…", models, models, addedAt = 1)

    @Test fun rotationWalksEveryModelBeforeNextKey() {
        val a = conn("A", "groq", listOf("m1", "m2", "m3")); val b = conn("B", "groq", listOf("n1", "n2"))
        val cd = mapOf("A|m1" to now + 3_600_000)
        val order = ConnectorRepo.order(listOf(a, b), Mode.AUTO, now, cd, null).map { "${it.first.id}/${it.second}" }
        assertEquals(listOf("A/m2", "A/m3", "B/n1", "B/n2"), order)
    }

    @Test fun rotationPutsKeysAtZeroLast() {
        val a = conn("A", "gemini", listOf("g1")); val b = conn("B", "gemini", listOf("h1"))
        val order = ConnectorRepo.order(listOf(a, b), Mode.AUTO, now, emptyMap()) { c -> if (c.id == "A") 0.0 else 50.0 }.map { it.first.id }
        assertEquals(listOf("B", "A"), order)
    }

    @Test fun keyWideCooldownSkipsKey() {
        val a = conn("A", "openrouter", listOf("x:free")); val b = conn("B", "groq", listOf("y"))
        val order = ConnectorRepo.order(listOf(a, b), Mode.AUTO, now, mapOf("A" to now + 1000), null).map { it.first.id }
        assertEquals(listOf("B"), order)
    }

    // ── Antigravity account selection (Kodex round robin + reset-aware) ──
    private fun acc(e: String, cool: Long = 0, used: Long = 0, auth: Long = 0) = AgyAccount(e, AgySelector.folderName(e), cooldownUntil = cool, lastUsedAt = used, authExpiredAt = auth)

    @Test fun selectorSkipsLimitedAndExpired() {
        val list = listOf(acc("a@x.com", cool = now + 1000), acc("b@x.com", auth = 5), acc("c@x.com", used = 10), acc("d@x.com", used = 5))
        assertEquals("d@x.com", AgySelector.pick(list, now, { true })?.email)   // least recently used of the usable ones
        assertEquals("c@x.com", AgySelector.pick(list, now, { true }, { if (it.email == "c@x.com") 0.9 else 0.1 })?.email)   // most quota left wins
        assertEquals("a@x.com", AgySelector.earliestBack(list, now)?.email)
        assertNull(AgySelector.pick(list, now, { true }, exclude = setOf("c@x.com", "d@x.com")))
    }

    @Test fun folderNameMatchesKodex() {
        val f = AgySelector.folderName("Clawora.Beauty78@gmail.com")
        assertTrue(f.startsWith("clawora.beauty78_gmail.com_")); assertEquals("clawora.beauty78_gmail.com_".length + 16, f.length)
    }

    @Test fun resetHint() {
        assertEquals(now + 2 * 3_600_000L + 10 * 60_000L, AgyFailover.resetHint("Quota exceeded. Resets in 2h 10m.", now))
        assertEquals(now + 45_000L, AgyFailover.resetHint("rate limited, retry in 45s", now))
        assertEquals(java.time.Instant.parse("2026-11-01T11:40:00Z").toEpochMilli(), AgyFailover.resetHint("limit reached; available after 2026-11-01T11:40:00Z", now))
        assertNull(AgyFailover.resetHint("quota exceeded", now))
    }

    /** The exact 429 Google sent this box on 1 Nov 2026 (free tier, gemini-3.8-flash, 20 a day). */
    @Test fun gemini429RealBody() {
        val body = javaClass.classLoader!!.getResource("gemini429_real.json")!!.readText()
        val g = Gemini429.parse(body)!!
        assertEquals(20.0, g.limit!!, 0.0); assertEquals("day", g.window); assertEquals("gemini-3.8-flash", g.model)
        assertEquals(80_786_000L, g.retryMs)
    }

    @Test fun agyScreens() {
        // agy's real screen (Kodex's fixture): the URL wraps across rows and a ── rule follows it.
        val screen = "https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=client.apps.googleusercontent.com&code_chall\nenge=challenge&code_challenge_method=S256&prompt=consent&state=eqPPegReyKP37OxuRsM0EQ\n──────────\nCopy and paste the URL or click on the link below:\nAfter authenticating, copy the code displayed in the browser and paste it below:"
        val url = AgyScreens.extractGoogleOAuthUrl(screen)!!
        assertTrue(url, url.endsWith("state=eqPPegReyKP37OxuRsM0EQ")); assertTrue(url.contains("code_challenge=challenge"))
        assertEquals("me@gmail.com", AgyScreens.extractSignedInEmail("Signed in as me@gmail.com 1071006060591-x.apps.googleusercontent.com"))
        assertEquals("hello", AgyScreens.sanitize("\u001B[32mhello\u001B[0m"))
    }

    @Test fun agyEvents() {
        assertEquals(AgyEvent.Text("Hi"), AgyEvents.parse("""{"event":"step_update","step_update":{"text_delta":"Hi"}}"""))
        val tool = AgyEvents.parse("""{"event":"step_update","step_update":{"step_type":"TOOL_CALL","tool_name":"run_command","state":"RUNNING","tool_info":{"parameters":{"CommandLine":"npm test"}}}}""") as AgyEvent.Tool
        assertEquals("Bash", tool.name); assertEquals("npm test", tool.detail)
        val res = AgyEvents.parse("""{"event":"result","result":{"conversation_id":"c1","status":"ERROR","error":"RESOURCE_EXHAUSTED: quota"},"usage":{"input_tokens":10,"output_tokens":5}}""") as AgyEvent.Result
        assertEquals("ERROR", res.status); assertTrue(AgyFailover.isQuotaFailure(res.error!!))
        assertEquals(listOf("/root/.local/bin/agy", "--input-format", "stream-json", "--output-format", "stream-json", "--print-timeout", "60m", "--dangerously-skip-permissions", "--new-project"), AgyEvents.command(""))
    }

    // ── key export / import ──
    @Test fun keyBackupRoundTrip() {
        val e = listOf(KeyBackup.Entry("gemini", "AIzaSyTESTKEY000000000000000000000", listOf("gemini-3.8-flash"), listOf("gemini-3.5-flash-lite")), KeyBackup.Entry("groq", "gsk_test_123456789012345678901234", listOf("openai/gpt-oss-120b"), emptyList(), pinnedModel = "openai/gpt-oss-120b"))
        val file = KeyBackup.export(e, "secret-pass", iter = 100_000)   // v0.5.2: files below 100k rounds are refused on import
        assertFalse("the key must not appear in the file", file.contains("AIzaSyTESTKEY"))
        assertEquals(e, KeyBackup.import(file, "secret-pass"))
        val wrong = runCatching { KeyBackup.import(file, "nope-nope") }.exceptionOrNull()
        assertEquals(KeyBackup.WRONG, wrong?.message)   // v0.5.2: one answer for wrong password or damaged file
    }

    // ── Linux / developer tools ──
    @Test fun prootArgv() {
        val a = LinuxRuntime.prootArgs("/n/libproot.so", "/d/ubuntu", "/d/ws", listOf("/d/acc1" to "/root/.gemini/antigravity-cli"), "/workspace", listOf("/system", "/apex"), listOf("/bin/bash", "-lc", "python3 -V"))
        assertEquals(listOf("/n/libproot.so", "--link2symlink", "--kill-on-exit", "-0", "-r", "/d/ubuntu", "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/system", "-b", "/apex",
            "-b", "/d/ws:/workspace", "-b", "/d/acc1:/root/.gemini/antigravity-cli", "-w", "/workspace", "/bin/bash", "-lc", "python3 -V"), a)
        val env = LinuxRuntime.baseEnv("/n", "/c/tmp")
        assertEquals("/n/libprootloader.so", env["PROOT_LOADER"]); assertEquals("1", env["PROOT_NO_SECCOMP"]); assertEquals("glibc.pthread.rseq=0", env["GLIBC_TUNABLES"])
    }

    @Test fun scriptProtocol() {
        assertEquals(ScriptProtocol.Progress(60, "Unpacking Node.js v26.13.0"), ScriptProtocol.parse("@@P 60 Unpacking Node.js v26.13.0"))
        assertEquals(ScriptProtocol.Version("8.4.27 · Composer 2.11.1"), ScriptProtocol.parse("@@V 8.4.27 · Composer 2.11.1"))
        assertEquals(ScriptProtocol.Size(77500), ScriptProtocol.parse("@@S 77500"))
        assertEquals("Setting up python3.12", ScriptProtocol.friendly("Setting up python3.12 (3.12.3-1ubuntu0.17) ..."))
        assertTrue(DevTools.newer("26.12.0", "26.13.0")); assertFalse(DevTools.newer("26.13.0", "26.13.0"))
        assertTrue(DevTools.newer("Java 17.0.20.1 · Kotlin 2.2.21", "Kotlin 2.4.21"))
    }

    @Test fun durations() {
        assertEquals(179_560L, Durations.parse("2m59.56s")); assertEquals(450L, Durations.parse("450ms")); assertEquals(3_723_000L, Durations.parse("1h2m3s")); assertEquals(30_000L, Durations.parse("30"))
    }
}
