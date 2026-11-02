
package com.mazharansari.axion

import com.mazharansari.axion.agent.AgentSink
import com.mazharansari.axion.build.*
import com.mazharansari.axion.data.MyServer
import com.mazharansari.axion.data.Project
import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.data.Step
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.data.StepStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipInputStream

/** v0.5 stage 2: patch engine, project store, the Build agent loop (fake brain), agy step parsing, local server, site checks, deploy shapes. */
class V05LogicTest {
    private fun tmp() = Files.createTempDirectory("axv05").toFile()

    // ───────── PatchEngine ─────────
    @Test fun patchExactFuzzyAndFailure() {
        val src = "function a() {\n  return 1;\n}\n\nfunction b() {\n    return 2;\n}\n"
        val ok = PatchEngine.applyEdits(src, listOf(PatchEngine.Edit("  return 1;", "  return 10;")))
        assertTrue(ok is PatchEngine.Result.Ok); assertTrue((ok as PatchEngine.Result.Ok).text.contains("return 10;"))
        // indentation differs (model wrote 2 spaces, file has 4) → fuzzy match still lands
        val fuzzy = PatchEngine.applyEdits(src, listOf(PatchEngine.Edit("function b() {\n  return 2;\n}", "function b() {\n  return 20;\n}")))
        assertTrue("fuzzy: $fuzzy", fuzzy is PatchEngine.Result.Ok); assertTrue((fuzzy as PatchEngine.Result.Ok).text.contains("20"))
        val bad = PatchEngine.applyEdits(src, listOf(PatchEngine.Edit("nothing like this", "x")))
        assertTrue(bad is PatchEngine.Result.Failed)
    }

    @Test fun patchBlocksAndUnifiedDiff() {
        val blocks = PatchEngine.parseBlocks("index.html\n<<<<<<< SEARCH\n<h1>Old</h1>\n=======\n<h1>New</h1>\n>>>>>>> REPLACE\n")
        assertEquals(1, blocks.size); assertEquals("<h1>Old</h1>", blocks[0].search.trim()); assertEquals("<h1>New</h1>", blocks[0].replace.trim())
        val a = "one\ntwo\nthree\nfour\n"; val b = "one\n2\nthree\nfour\nfive\n"
        val d = PatchEngine.diff(a, b); val st = PatchEngine.stats(d)
        assertEquals(2, st.added); assertEquals(1, st.removed)
        val u = PatchEngine.unified(a, b, "x.txt")
        assertTrue(u.startsWith("--- a/x.txt")); assertTrue(u.contains("-two")); assertTrue(u.contains("+2")); assertTrue(u.contains("+five"))
        val ins = PatchEngine.stats(PatchEngine.diff("<header>\n<h1>x</h1>\n<a>b</a>\n</header>\n", "<header>\n<h1>x</h1>\n<p>new</p>\n<a>b</a>\n</header>\n"))
        assertEquals("a pure insertion is +1 −0", PatchEngine.Stats(1, 0), ins)
        val applied = PatchEngine.applyUnifiedDiff(a, u)
        assertTrue("$applied", applied is PatchEngine.Result.Ok); assertEquals(b, (applied as PatchEngine.Result.Ok).text)
    }

    // ───────── ProjectStore ─────────
    @Test fun projectStoreLifecycle() {
        val root = tmp(); var now = 1000L
        val s = ProjectStore(root) { now++ }
        val a = s.create("Clawora Store"); val b = s.create("Clawora Store", "android")
        assertNotEquals(a.slug, b.slug); assertEquals("clawora-store", a.slug)
        assertTrue(s.folder(a).isDirectory); assertEquals("/root/projects/clawora-store", s.guestPath(a))
        s.setConversation(a.id, "conv-1"); assertEquals(a.id, s.projectFor("conv-1")?.id)
        val r = s.rename(a.id, "Clawora Beauty")!!
        assertEquals("Clawora Beauty", r.name); assertEquals(a.slug, r.slug)   // folder never moves under a running agent
        // reload from disk
        val s2 = ProjectStore(root)
        assertEquals(setOf("Clawora Beauty", "Clawora Store"), s2.list.value.map { it.name }.toSet())
        assertEquals("android", s2.get(b.id)?.kind)
        File(s.folder(b), "x.txt").writeText("hi")
        assertTrue(s2.delete(b.id)); assertFalse(File(root, "projects/${b.slug}").exists()); assertNull(s2.get(b.id))
        assertEquals(1, ProjectStore(root).list.value.size)
    }

    @Test fun projectFilesSafetyAndUndo() {
        val root = tmp(); File(root, "a.html").writeText("<p>1</p>"); File(root, "node_modules/x").mkdirs(); File(root, "node_modules/x/i.js").writeText("x")
        try { ProjectFiles.safeChild(root, "../../etc/passwd"); fail("escaped the project") } catch (_: Exception) {}
        val snap = ProjectFiles.snapshot(root)
        assertEquals(setOf("a.html"), snap.keys)
        File(root, "a.html").writeText("<p>2</p>"); File(root, "new.css").writeText("b{}")
        ProjectFiles.restore(root, snap)
        assertEquals("<p>1</p>", File(root, "a.html").readText()); assertFalse(File(root, "new.css").exists())
        assertEquals(listOf("a.html"), ProjectFiles.tree(root, emptySet()).map { it.rel })   // node_modules hidden
    }

    // ───────── BuildLoop with a fake model ─────────
    private class RecSink : AgentSink {
        val text = StringBuilder(); val steps = mutableListOf<Step>(); val arts = mutableListOf<Artifact>()
        override fun text(delta: String) { text.append(delta) }
        override fun reasoning(delta: String) {}
        override fun textToThoughts() {}
        override fun step(step: Step) { synchronized(steps) { steps += step } }
        override fun artifact(a: Artifact) { arts += a }
        override fun model(label: String) {}
    }

    /** Writes index.html in three chunks (so live code can be seen mid-write); on the fix turn, repairs the broken link. */
    private class FakeBrain(val folder: File) : BuildBrain {
        val prompts = mutableListOf<String>()
        override suspend fun turn(project: Project, prompt: String, fresh: Boolean, events: (BrainEvent) -> Unit): BrainResult {
            prompts += prompt
            val f = File(folder, "index.html")
            if (prompts.size == 1) {
                events(BrainEvent.Text("Plan: one page with a hero and products.\n"))
                events(BrainEvent.ToolStart("w1", "write_to_file", "index.html", "/root/projects/${project.slug}/index.html"))
                val parts = listOf("<!doctype html>\n<html><head><title>Shop</title>\n", "<meta name=\"viewport\" content=\"width=device-width\">\n</head><body>\n", "<h1>Shop</h1><a href=\"missing.html\">More</a>\n</body></html>\n")
                val sb = StringBuilder()
                for (p in parts) { sb.append(p); f.writeText(sb.toString()); delay(120) }
                events(BrainEvent.ToolDone("w1"))
                events(BrainEvent.ToolStart("r1", "run_command", "axion-check .")); events(BrainEvent.ToolDone("r1", true, "PROBLEM: missing.html"))
                events(BrainEvent.Text("Built the page."))
            } else {
                events(BrainEvent.ToolStart("w2", "replace_file_content", "index.html", "/root/projects/${project.slug}/index.html"))
                f.writeText(f.readText().replace("missing.html", "#products")); delay(50)
                events(BrainEvent.ToolDone("w2"))
                events(BrainEvent.Text("Fixed the link."))
            }
            return BrainResult(true)
        }
    }

    @Test fun buildLoopPlanWriteVerifyFix() = runBlocking {
        val root = tmp(); val store = ProjectStore(root); val p = store.create("Shop"); val folder = store.folder(p)
        val brain = FakeBrain(folder); val sink = RecSink()
        val verifier = object : BuildVerifier {
            var calls = 0
            override suspend fun verify(project: Project, folder: File, onStep: (Step) -> Unit): BuildVerifier.Report {
                calls++
                return BuildVerifier.Report(SiteChecker.check(folder).problems)
            }
        }
        val loop = BuildLoop(brain, verifier, folder, store.guestPath(p), sink, selfReview = false, pollMs = 30)
        val out = loop.run(p, "make a shop page", fresh = true)
        assertTrue("outcome $out", out.ok); assertEquals(1, out.rounds); assertEquals(2, verifier.calls)
        assertEquals(setOf("index.html"), out.changedFiles)
        assertTrue("fix prompt carries the problem: ${brain.prompts[1]}", brain.prompts[1].contains("missing.html"))
        assertFalse(File(folder, "index.html").readText().contains("missing.html"))
        // live code: a RUNNING write step carried partial file text before it finished
        val runningWithCode = sink.steps.filter { it.id == "w1" && it.status == StepStatus.RUNNING && it.detail.contains("<!doctype") }
        assertTrue("live code streamed", runningWithCode.isNotEmpty())
        val doneW1 = sink.steps.last { it.id == "w1" }
        assertEquals(StepKind.WRITE, doneW1.kind); assertEquals(StepStatus.DONE, doneW1.status)
        // a new file keeps its code in the card; the title carries the line count
        assertTrue("created: ${doneW1.title}", doneW1.title.startsWith("Created") && doneW1.title.contains("+6 ") && doneW1.detail.contains("<h1>Shop</h1>"))
        val doneW2 = sink.steps.last { it.id == "w2" }
        assertTrue("w2: ${doneW2.title}\n${doneW2.detail}", doneW2.detail.contains("-<h1>Shop</h1><a href=\"missing.html\">More</a>") && doneW2.detail.contains("+<h1>Shop</h1><a href=\"#products\">More</a>"))
        assertTrue(loop.before.isEmpty())   // Undo point = empty project
    }

    // ───────── agy stream-json ─────────
    @Test fun agyStepParser() {
        val init = AgyStepParser.parse("""{"event":"init","conversation_id":"c-42"}""")
        assertEquals("c-42", (init as AgyStepParser.Ev.Init).conversationId)
        val w = AgyStepParser.parse("""{"event":"step_update","step_update":{"step_id":"7","step_type":"TOOL_CALL","tool_name":"write_to_file","state":"RUNNING","tool_info":{"parameters":{"TargetFile":"/root/projects/shop/index.html","CodeContent":"<h1>Hi</h1>"}}}}""")
        w as AgyStepParser.Ev.Tool
        assertEquals("7", w.id); assertEquals("/root/projects/shop/index.html", w.path); assertEquals("<h1>Hi</h1>", w.content); assertEquals("RUNNING", w.state)
        val c = AgyStepParser.parse("""{"event":"step_update","step_update":{"step_id":"8","tool_name":"run_command","state":"DONE","output":"ok","tool_info":{"parameters":{"CommandLine":"npm run build"}}}}""") as AgyStepParser.Ev.Tool
        assertEquals("npm run build", c.command); assertEquals("ok", c.output); assertTrue(AgyStepParser.isDone(c.state))
        val r = AgyStepParser.parse("""{"event":"result","result":{"status":"SUCCESS","conversation_id":"c-42","response":"Done"},"usage":{"input_tokens":10,"output_tokens":5}}""") as AgyStepParser.Ev.Result
        assertTrue(r.ok); assertEquals("c-42", r.conversationId); assertEquals(10, r.inTok)
        val cmd = AgyStepParser.command("gemini-3-pro", "c-42")
        assertTrue(cmd.joinToString(" "), cmd.contains("--conversation") && cmd.contains("c-42"))
        assertTrue(AgyStepParser.command("gemini-3-pro", null).contains("--new-project"))
        assertNull(AgyStepParser.parse("not json"))
    }

    // ───────── local preview server + site checks ─────────
    private fun get(url: String): Pair<Int, String> = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 3000; readTimeout = 3000
        val c = responseCode; c to (if (c < 400) inputStream else errorStream)?.bufferedReader()?.readText().orEmpty()
    }

    @Test fun staticServerServesAndFallsBack() {
        val site = tmp(); File(site, "index.html").writeText("<h1>Home</h1>"); File(site, "css").mkdirs(); File(site, "css/a.css").writeText("h1{}")
        val s = StaticServer(site).start()
        try {
            assertEquals(200 to "<h1>Home</h1>", get(s.url))
            assertEquals("h1{}", get(s.url + "css/a.css").second)
            assertEquals(200 to "<h1>Home</h1>", get(s.url + "products/42"))    // SPA route
            assertEquals(404, get(s.url + "img/missing.png").first)            // real missing asset stays a 404
            assertNotEquals("root:", get(s.url + "../../../../etc/passwd").second.take(5))
        } finally { s.stop() }
    }

    @Test fun siteCheckerFindsProblems() {
        val site = tmp()
        File(site, "index.html").writeText("<html><head><title>x</title></head><body><img src=\"logo.png\"><script src=\"app.js\"></script></body></html>")
        File(site, "app.js").writeText("function a() { return 1; ")
        val probs = SiteChecker.check(site).problems.joinToString("\n")
        assertTrue(probs, probs.contains("logo.png")); assertTrue(probs, probs.contains("viewport")); assertTrue(probs, probs.contains("app.js"))
        File(site, "index.html").writeText("<html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body><h1>Hi</h1><script src=\"app.js\"></script></body></html>")
        File(site, "app.js").writeText("function a() { return '}'; }")
        assertEquals(emptyList<String>(), SiteChecker.check(site).problems)
        // a Vite project: the built dist/ is the site
        val vite = tmp(); File(vite, "package.json").writeText("{}"); File(vite, "dist").mkdirs(); File(vite, "dist/index.html").writeText("<meta name=viewport content=x><body>x</body>")
        File(vite, "index.html").writeText("<script type=module src=/src/main.js></script>")
        assertEquals(File(vite, "dist").canonicalPath, SiteChecker.siteDir(vite)?.canonicalPath)
    }

    // ───────── deploy request shapes (fake Netlify on localhost) ─────────
    @Test fun netlifyDeployShape() = runBlocking {
        val site = tmp(); File(site, "index.html").writeText("<h1>x</h1>"); File(site, "css").mkdirs(); File(site, "css/s.css").writeText("h1{}")
        val seen = mutableListOf<String>(); var zipEntries = emptyList<String>(); var ctype = ""
        val srv = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val th = Thread {
            while (!srv.isClosed) {
                val sock = runCatching { srv.accept() }.getOrNull() ?: break
                Thread {
                    sock.use { so ->
                        val inp = java.io.BufferedInputStream(so.getInputStream()); val os = so.getOutputStream()
                        fun line(): String? { val b = java.io.ByteArrayOutputStream(); while (true) { val c = inp.read(); if (c < 0) return if (b.size() == 0) null else b.toString(); if (c == '\n'.code) return b.toString().trimEnd('\r'); b.write(c) } }
                        while (true) {
                            val req = line() ?: break; if (req.isBlank()) continue
                            val (method, path) = req.split(" ").let { it[0] to it[1] }
                            val headers = mutableMapOf<String, String>()
                            while (true) { val h = line() ?: break; if (h.isEmpty()) break; headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim() }
                            val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { var o = 0; while (o < it.size) { val n = inp.read(it, o, it.size - o); if (n < 0) break; o += n } }
                            synchronized(seen) { seen += "$method $path ${headers["authorization"]}" }
                            val out = when {
                                method == "POST" && path == "/sites" -> { assertTrue(String(body).contains("\"name\":\"my-shop\"")); """{"id":"S1","ssl_url":"https://my-shop.netlify.app"}""" }
                                method == "POST" && path == "/sites/S1/deploys" -> {
                                    ctype = headers["content-type"].orEmpty()
                                    zipEntries = ZipInputStream(body.inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
                                    """{"id":"D1","state":"uploading"}"""
                                }
                                path == "/deploys/D1" -> """{"id":"D1","state":"ready","ssl_url":"https://my-shop.netlify.app"}"""
                                else -> "{}"
                            }.toByteArray()
                            os.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\n\r\n".toByteArray()); os.write(out); os.flush()
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }
        try {
            val d = Deployer(api = mapOf("netlify" to "http://127.0.0.1:${srv.localPort}"))
            val r = d.netlify("tok123", site, "my-shop", null) {}
            assertEquals("https://my-shop.netlify.app", r.url); assertEquals("S1", r.siteId)
            assertTrue("ctype=$ctype", ctype.startsWith("application/zip"))
            assertEquals(setOf("index.html", "css/s.css"), zipEntries.toSet())
            assertTrue(seen.toString(), seen.size >= 3 && seen.all { it.endsWith("Bearer tok123") })
        } finally { srv.close() }
        val vb = Deployer().vercelBody(site, "my-shop").toString()
        assertTrue(vb.contains("\"file\":\"css/s.css\"") && vb.contains("\"encoding\":\"base64\"") && vb.contains("\"target\":\"production\""))
        assertEquals("my-shop", Deployer().siteName("My Shop!"))
        assertTrue(Deployer().wranglerCommand("/root/projects/shop", "shop").contains("pages deploy '/root/projects/shop' --project-name 'shop'"))
    }

    // ───────── My server ─────────
    @Test fun myServerNormalize() {
        assertEquals("http://1.2.3.4:11434/v1", MyServer.normalize("1.2.3.4:11434"))
        assertEquals("https://1-2-3-4.sslip.io/v1", MyServer.normalize("https://1-2-3-4.sslip.io/"))
        assertEquals("https://x.io/v1", MyServer.normalize("https://x.io/v1/chat/completions"))
        assertEquals("https://x.io/api/v2", MyServer.normalize("https://x.io/api/v2/models"))
        assertEquals("1-2-3-4.sslip.io", MyServer.host("https://1-2-3-4.sslip.io"))
    }

    /** Writes the Android starter into app/build/android-template (the box then builds it with Gradle — see the report). */
    @Test fun androidTemplate() {
        val out = File(System.getProperty("user.dir"), "build/android-template").apply { deleteRecursively(); mkdirs() }
        AndroidTemplate.write(out, "Pocket Budget", "pocket-budget")
        listOf("settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts", "app/src/main/AndroidManifest.xml").forEach { assertTrue(it, File(out, it).isFile) }
        assertTrue(File(out, "app/build.gradle.kts").readText().contains("com.axion.pocketbudget"))
    }
}
