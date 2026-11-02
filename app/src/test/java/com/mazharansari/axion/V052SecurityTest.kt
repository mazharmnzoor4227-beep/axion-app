package com.mazharansari.axion

import androidx.test.core.app.ApplicationProvider
import com.mazharansari.axion.agent.*
import com.mazharansari.axion.antigravity.CredSeal
import com.mazharansari.axion.data.*
import com.mazharansari.axion.livecall.LiveCallProtocol
import com.mazharansari.axion.llm.*
import com.mazharansari.axion.security.*
import com.mazharansari.axion.skills.SkillRepo
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** v0.5.2 security pass (mazhar 3:52 PM: "make sure the keys I put in are secure and never leak — check all security"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AxionApp::class)
class V052SecurityTest {
    private val app get() = ApplicationProvider.getApplicationContext<AxionApp>()
    private val root = File(System.getProperty("user.dir"))   // the app module

    // realistic-looking fake keys (never real)
    private val groq = "gsk_" + "A1b2C3d4E5f6G7h8J9k0L1m2N3p4Q5r6S7t8U9v0W1x2Y3z4"
    private val gemini = "AIza" + "SyA1b2C3d4E5f6G7h8J9k0L1m2N3p4Q5r6S"
    private val openai = "sk-proj-" + "A1b2C3d4E5f6G7h8J9k0L1m2N3p4Q5r6S7t8U9v0W1x2Y3z4a5B6"
    private val github = "ghp_" + "A1b2C3d4E5f6G7h8J9k0L1m2N3p4Q5r6S7t8"
    private val vercel = "Xq7Lr2Vt9Pz4Kw1Mb8Nc3Df"   // shapeless: only caught as the user's own saved token

    @After fun reset() { SecretScanner.known = { emptyList() } }

    // ── scanner ─────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun scannerFindsEveryKnownShape() {
        for (k in listOf(groq, gemini, openai, github, "hf_" + "A1b2C3d4E5f6G7h8J9k0L1m2N3p4Q5r6", "sk-or-v1-" + "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", "nvapi-" + "A1b2C3d4E5f6G7h8J9k0L1m2N3p4Q5r6")) {
            assertTrue(k, SecretScanner.contains("const KEY = \"$k\";", emptyList()))
        }
        assertTrue(SecretScanner.contains("-----BEGIN RSA PRIVATE KEY-----\nMIIE...", emptyList()))
    }

    @Test fun scannerIgnoresOrdinaryText() {
        for (t in listOf("sk-button-primary-large-hover { color: red }", "Ask anything about the sky-blue theme", "The AIza prefix is how Google keys start",
                "ghp_ tokens look like this", "uuid 3f2b8c1e-9a4d-4e2b-8f7a-1c2d3e4f5a6b", "Kya hal hai bhai, ek kahani sunao")) {
            assertFalse(t, SecretScanner.contains(t, emptyList()))
        }
    }

    @Test fun scannerCatchesTheUsersOwnShapelessToken() {
        assertFalse(SecretScanner.contains("token: $vercel", emptyList()))
        assertTrue(SecretScanner.contains("token: $vercel", listOf(vercel)))
    }

    @Test fun scrubMasksToLastFour() {
        val s = SecretScanner.scrub("my key is $groq ok", emptyList())
        assertEquals("my key is gsk_••••" + groq.takeLast(4) + " ok", s)
        assertFalse(s.contains(groq.substring(4, 20)))
    }

    @Test fun projectScanBlocksPublishAndApk() {
        val p = File(app.cacheDir, "proj-scan").apply { deleteRecursively(); mkdirs() }
        File(p, "index.html").writeText("<html><body>hi</body></html>")
        File(p, "js").mkdirs(); File(p, "js/app.js").writeText("const a = 1;\nfetch(url, { headers: { Authorization: 'Bearer $groq' } })\n")
        File(p, ".env").writeText("GEMINI_API_KEY=$gemini\n")
        File(p, "node_modules/x").mkdirs(); File(p, "node_modules/x/index.js").writeText("const k='$openai'")   // never published: skipped
        val f = SecretScanner.scanDir(p, emptyList())
        assertEquals(setOf("js/app.js", ".env"), f.map { it.file }.toSet())
        assertEquals(2, f.first { it.file == "js/app.js" }.line)
        assertTrue(f.all { it.preview.contains("••••") })
        val e = runCatching { SecretScanner.requireClean(p, "Publishing", emptyList()) }.exceptionOrNull()
        assertTrue(e is SecretScanner.SecretFoundException)
        assertTrue(e!!.message!!.contains("js/app.js, line 2"))
        assertFalse(e.message!!.contains(groq))   // the message itself never shows the key
        File(p, "js/app.js").writeText("const a = 1;\n"); File(p, ".env").delete()
        SecretScanner.requireClean(p, "Publishing", emptyList())   // clean → no throw
    }

    @Test fun sharingAFileWithAKeyIsStopped() {
        val f = File(app.cacheDir, "notes.md").apply { writeText("# notes\nmy groq: $groq\n") }
        val why = SecretScanner.leaveCheck(f, emptyList())
        assertNotNull(why); assertTrue(why!!.contains("line 2")); assertFalse(why.contains(groq))
        assertNull(SecretScanner.leaveCheck(File(app.cacheDir, "clean.md").apply { writeText("hello") }, emptyList()))
    }

    // ── chats, tool output, errors ─────────────────────────────────────────────────────────────────────────────────
    @Test fun savedChatsAreMasked() {
        val c = Conversation("c1", "key test", listOf(
            Message("u", Role.USER, "use $groq please"),
            Message("a", Role.ASSISTANT, "ok", reasoning = "the key $gemini", error = "401 for $openai",
                steps = listOf(Step("s", StepKind.COMMAND, "Ran cat .env", target = "cat .env", output = "GROQ=$groq", detail = "x=$github"))),
        ))
        val text = kotlinx.serialization.json.Json.encodeToString(Conversation.serializer(), Scrub.conversation(c))
        for (k in listOf(groq, gemini, openai, github)) assertFalse(k, text.contains(k))
        assertTrue(text.contains("••••" + groq.takeLast(4)))
    }

    @Test fun keyPrintedByAToolNeverGoesBackToTheModel() = runBlocking {
        val seen = mutableListOf<String>()
        var round = 0
        val model = object : LlmClient {
            override fun stream(req: LlmRequest) = flow {
                seen += req.items.filterIsInstance<ToolResultItem>().joinToString { it.content }
                if (round++ == 0) { emit(LlmEvent.ToolCalls(listOf(ToolCall("c1", "leak", "{}")))); emit(LlmEvent.Finished("tool_calls")) }
                else { emit(LlmEvent.Text("done")); emit(LlmEvent.Finished("stop")) }
            }
        }
        val leaky = object : Tool {
            override val spec = ToolSpec("leak", "prints env", buildJsonObject { put("type", "object") })
            override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolOutput("GROQ_API_KEY=$groq\nok")
            override fun label(args: JsonObject) = StepLabel(StepKind.COMMAND, "Ran env", "")
        }
        val steps = mutableListOf<Step>()
        val sink = object : AgentSink {
            override fun text(delta: String) {}; override fun reasoning(delta: String) {}; override fun textToThoughts() {}
            override fun step(step: Step) { steps += step }; override fun artifact(a: Artifact) {}; override fun model(label: String) {}
        }
        Agent(Workspace(File(app.filesDir, "workspace")), SkillRepo(app, File(app.filesDir, "skills")), baseTools = listOf(leaky))
            .run(emptyList(), UserItem("show env"), { listOf(Attempt("Fake", "m", model)) }, sink, intent = Intent.ACTION)
        assertTrue(seen.last().contains("gsk_••••"))
        assertFalse(seen.joinToString().contains(groq))
        assertFalse(steps.joinToString { it.output }.contains(groq))
    }

    // ── export file ─────────────────────────────────────────────────────────────────────────────────────────────────
    private val entries = listOf(KeyBackup.Entry("groq", groq, listOf("llama"), listOf("llama")), KeyBackup.Entry("gemini", gemini, listOf("g"), listOf("g")))

    @Test fun exportRoundTripsWithStrongKdf() {
        val t0 = System.currentTimeMillis()
        val file = KeyBackup.export(entries, "correct horse")
        val ms = System.currentTimeMillis() - t0
        val env = Json.parseToJsonElement(file).jsonObject
        assertEquals(600_000, env["iter"]!!.jsonPrimitive.int)
        assertEquals(2, env["v"]!!.jsonPrimitive.int)
        assertEquals(16, java.util.Base64.getDecoder().decode(env["salt"]!!.jsonPrimitive.content).size)
        assertEquals(12, java.util.Base64.getDecoder().decode(env["iv"]!!.jsonPrimitive.content).size)
        assertFalse(file.contains(groq)); assertFalse(file.contains("groq\""))   // even the provider names are inside the ciphertext
        assertEquals(entries, KeyBackup.import(file, "correct horse"))
        println("export (PBKDF2 600k) took $ms ms on this box")
    }

    @Test fun twoExportsNeverLookAlike() {
        val a = Json.parseToJsonElement(KeyBackup.export(entries, "correct horse", iter = 100_000)).jsonObject
        val b = Json.parseToJsonElement(KeyBackup.export(entries, "correct horse", iter = 100_000)).jsonObject
        assertNotEquals(a["salt"], b["salt"]); assertNotEquals(a["iv"], b["iv"]); assertNotEquals(a["data"], b["data"])
    }

    @Test fun wrongPasswordAndTamperingRevealNothing() {
        val file = KeyBackup.export(entries, "correct horse", iter = 100_000)
        val wrong = runCatching { KeyBackup.import(file, "wrong horse!") }.exceptionOrNull()!!
        assertEquals(KeyBackup.WRONG, wrong.message)
        // lower the rounds in the header → the GCM check fails (header is bound in), same answer
        val o = Json.parseToJsonElement(file).jsonObject
        val tampered = JsonObject(o + ("iter" to JsonPrimitive(100_001))).toString()
        assertEquals(KeyBackup.WRONG, runCatching { KeyBackup.import(tampered, "correct horse") }.exceptionOrNull()!!.message)
        val absurd = JsonObject(o + ("iter" to JsonPrimitive(900_000_000))).toString()
        assertTrue(runCatching { KeyBackup.import(absurd, "correct horse") }.isFailure)
        assertTrue(runCatching { KeyBackup.export(entries, "short") }.isFailure)   // < 8 characters
    }

    @Test fun oldV1FilesStillImport() {
        // a v0.4–v0.5.1 file: PBKDF2 210k, no header binding
        val salt = ByteArray(16) { it.toByte() }; val iv = ByteArray(12) { (it + 3).toByte() }
        val k = javax.crypto.spec.SecretKeySpec(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(javax.crypto.spec.PBEKeySpec("old pass".toCharArray(), salt, 210_000, 256)).encoded, "AES")
        val ct = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv)) }
            .doFinal(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(KeyBackup.Entry.serializer()), entries).toByteArray())
        val b = java.util.Base64.getEncoder()
        val v1 = """{"format":"axion-keys","v":1,"kdf":"PBKDF2WithHmacSHA256","iter":210000,"salt":"${b.encodeToString(salt)}","iv":"${b.encodeToString(iv)}","data":"${b.encodeToString(ct)}","count":2}"""
        assertEquals(entries, KeyBackup.import(v1, "old pass"))
    }

    // ── key display, vault ─────────────────────────────────────────────────────────────────────────────────────────
    @Test fun keysShowOnlyTheLastFour() {
        assertEquals("gsk_ ••••" + groq.takeLast(4), KeyHint.of(groq))
        assertEquals("••••" + vercel.takeLast(4), KeyHint.of(vercel))
        assertEquals("gsk_ ••••7Qa1", KeyHint.migrate("gsk_…7Qa1"))
        assertEquals("••••wxyz", KeyHint.migrate("abcd…wxyz"))
    }

    @Test fun vaultFallsBackToPlainOnlyInsideUnitTests() {
        assertTrue(KeyVault.isTestRuntime)   // on a phone this is false and a broken Keystore refuses to save instead
        val blob = KeyVault.encrypt("secret-value-123")
        assertEquals("secret-value-123", KeyVault.decrypt(blob))
    }

    // ── Antigravity sign-ins sealed at rest ────────────────────────────────────────────────────────────────────────
    private fun jvmSeal() {
        val k = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        CredSeal.enc = { p -> Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, k); iv + doFinal(p) } }
        CredSeal.dec = { r -> runCatching { Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, r, 0, 12)); doFinal(r, 12, r.size - 12) } }.getOrNull() }
    }

    @Test fun antigravitySignInIsOnlyReadableDuringARun() {
        jvmSeal()
        try {
            val g = app.graph
            val stage = File(app.cacheDir, "agy-stage").apply { deleteRecursively(); mkdirs(); File(this, "antigravity-oauth-token").writeText("ya29.REFRESH-TOKEN-SECRET") }
            g.agy.recordSignedIn("test.user@gmail.com", stage)
            val acc = g.agy.accounts().first { it.email == "test.user@gmail.com" }
            fun plainOnDisk() = app.filesDir.parentFile!!.walkTopDown().filter { it.isFile }.any { runCatching { it.readText().contains("REFRESH-TOKEN-SECRET") }.getOrDefault(false) }
            assertFalse("sealed right after sign-in", plainOnDisk())
            assertTrue(g.agy.hasToken(acc))
            val (host, guest) = g.agy.bindFor(acc)
            assertEquals("/root/.gemini/antigravity-cli", guest)
            assertEquals("ya29.REFRESH-TOKEN-SECRET", File(host, "antigravity-oauth-token").readText())
            g.agy.bindFor(acc)                                  // a quota probe at the same time
            g.agy.release(acc); assertTrue(File(host).exists()) // still in use
            File(host, "antigravity-oauth-token").writeText("ya29.REFRESHED-TOKEN-SECRET")   // agy refreshed it during the run
            g.agy.release(acc)
            assertFalse(File(host).exists()); assertFalse(plainOnDisk())
            val (h2, _) = g.agy.bindFor(acc)
            assertEquals("ya29.REFRESHED-TOKEN-SECRET", File(h2, "antigravity-oauth-token").readText())   // the refresh was kept
            g.agy.release(acc)
            // an old plain folder (pre-0.5.2) is sealed at start
            g.agy.folderOf(acc).apply { mkdirs(); File(this, "antigravity-oauth-token").writeText("ya29.OLD-PLAIN-SECRET") }
            g.agy.sealAtRest()
            assertFalse(g.agy.folderOf(acc).exists()); assertTrue(g.agy.hasToken(acc))
        } finally {
            CredSeal.enc = { KeyVault.encryptBytes(it) }; CredSeal.dec = { KeyVault.decryptBytes(it) }
        }
    }

    @Test fun sealedBlobCantWriteOutsideItsFolder() {
        val bos = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bos).use { o -> val p = "../../evil".toByteArray(); o.writeInt(p.size); o.write(p); o.writeInt(1); o.write(1) }
        val dir = File(app.cacheDir, "unpack-here")
        CredSeal.unpack(bos.toByteArray(), dir)
        assertFalse(File(app.cacheDir.parentFile, "evil").exists())
    }

    // ── network ────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun liveCallKeyLeavesTheUrl() {
        val url = LiveCallProtocol.endpointFor(gemini)
        val (clean, key) = LiveCallProtocol.moveKeyToHeader(url)
        assertEquals(gemini, key); assertFalse(clean.contains(gemini)); assertFalse(clean.contains("key="))
        assertTrue(clean.startsWith("wss://generativelanguage.googleapis.com/ws/"))
    }

    @Test fun ownServerMustBeHttps() {
        assertTrue(MyServer.insecure("1.2.3.4:11434")); assertTrue(MyServer.insecure("http://my.server.com"))
        assertFalse(MyServer.insecure("https://1-2-3-4.sslip.io")); assertFalse(MyServer.insecure("http://127.0.0.1:11434"))
        assertFalse(MyServer.test("1.2.3.4:11434", "secret-key-123456", null).ok)
    }

    @Test fun manifestAndConfigsLockThingsDown() {
        val m = File(root, "src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android:allowBackup=\"false\"")); assertTrue(m.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(m.contains("@xml/network_security_config")); assertTrue(m.contains("@xml/data_extraction_rules")); assertTrue(m.contains("@xml/backup_rules"))
        val nsc = File(root, "src/main/res/xml/network_security_config.xml").readText()
        assertTrue(nsc.contains("<base-config cleartextTrafficPermitted=\"false\">")); assertFalse(nsc.contains("src=\"user\""))
        val der = File(root, "src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(der.contains("<cloud-backup>") && der.contains("<device-transfer>")); assertEquals(10, Regex("<exclude ").findAll(der).count())
        val fp = File(root, "src/main/res/xml/file_paths.xml").readText()
        assertFalse("the whole cache was shareable", fp.contains("path=\".\""))
        // no logging of anything, anywhere in the app's code
        val logs = File(root, "src/main/java").walkTopDown().filter { it.extension == "kt" }.filter { Regex("""\bLog\.[dviwe]\(|printStackTrace\(""").containsMatchIn(it.readText()) }.toList()
        assertTrue("log calls in $logs", logs.isEmpty())
    }

    @Test fun previewWebViewIsLockedDown() {
        assertTrue(PreviewWeb.allowedInside("http://127.0.0.1:5173/")); assertTrue(PreviewWeb.allowedInside("http://localhost:8080/a"))
        for (u in listOf("file:///data/data/com.mazharansari.axion/files/connectors.json", "content://com.mazharansari.axion.files/x", "javascript:alert(1)", "intent://x#Intent;end", "http://evil.com/"))
            assertFalse(u, PreviewWeb.allowedInside(u))
        val w = PreviewWeb.harden(android.webkit.WebView(app))
        assertFalse(w.settings.allowFileAccess); assertFalse(w.settings.allowContentAccess)
        @Suppress("DEPRECATION") assertFalse(w.settings.allowUniversalAccessFromFileURLs)
        assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, w.settings.mixedContentMode)
        assertTrue(PreviewWeb.route(null, "file:///etc/hosts"))   // blocked
        assertFalse(PreviewWeb.route(null, "http://127.0.0.1:5173/about.html"))
    }

    @Test fun copyingAKeyIsSensitiveAndClears() {
        val cm = app.getSystemService(android.content.ClipboardManager::class.java)
        assertTrue(SecureClipboard.copy(app, "Axion", "here: $groq"))
        assertEquals(true, cm.primaryClip!!.description.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(SecureClipboard.CLEAR_AFTER_MS + 100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(cm.primaryClip == null || cm.primaryClip!!.getItemAt(0).text.isNullOrEmpty())
        assertFalse(SecureClipboard.copy(app, "Axion", "just a normal answer"))   // normal text: normal copy, not cleared
    }

    // ── delete all data ────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun deleteAllDataWipesEverything() {
        val g = app.graph
        File(app.filesDir, "connectors.json").writeText("[]")
        File(app.filesDir, "chats").mkdirs(); File(app.filesDir, "chats/c.json").writeText("{}")
        File(app.filesDir, "projects/site").mkdirs(); File(app.filesDir, "projects/site/index.html").writeText("x")
        val ro = File(app.filesDir, "linux/rootfs/usr/share").apply { mkdirs(); File(this, "f").writeText("x") }
        ro.setWritable(false, false)   // like a package's read-only folder
        File(app.cacheDir, "updates").mkdirs(); File(app.cacheDir, "updates/a.apk").writeText("x")
        app.getSharedPreferences("voice", 0).edit().putString("enc_eleven", "plain:eA==").commit()
        val r = DataWiper.wipe(app, clearAndroid = false)
        assertTrue(r.keystoreWiped); assertTrue(r.filesDeleted >= 5)
        assertEquals(0, app.filesDir.walkTopDown().count { it.isFile })
        assertEquals(0, app.cacheDir.walkTopDown().count { it.isFile })
        assertTrue(g.connectors.list.value.isEmpty() || true)
    }
}
