package com.mazharansari.axion

import com.mazharansari.axion.build.ProjectVerifier
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P0-1 (CR-1): the Builder verifier piped `npm run build` through `tail`, so the pipeline's
 * exit code was tail's — always 0 — and a failing build was reported DONE.
 *
 * The verifier shells out to a guest bash (LinuxRuntime.run runs `bash -lc`), which the JVM
 * test host cannot provide, so these tests run the EXACT command string the verifier builds
 * ([ProjectVerifier.buildCheckCommand]) through the host's bash, with a stub `npm` standing in
 * for the real one: the project carries a real package.json whose build script fails
 * (`"build": "exit 1"`), and the stub exits 1 exactly when invoked as `npm run build` — i.e.
 * it behaves the way npm would after running that failing script.
 */
class ProjectVerifierTest {

    /** A temp project with a failing build script, plus a stub `npm` first on PATH. */
    private fun failingProject(): Pair<File, File> {
        val dir = Files.createTempDirectory("p0-1-project-").toFile()
        File(dir, "package.json").writeText("""{"name":"t","scripts":{"build":"exit 1"}}""")
        val bin = File(dir, "bin").apply { mkdirs() }
        File(bin, "npm").apply {
            writeText("#!/bin/sh\nif [ \"\$1\" = \"run\" ] && [ \"\$2\" = \"build\" ]; then exit 1; fi\nexit 0\n")
            setExecutable(true)
        }
        return dir to bin
    }

    private fun runBash(cmd: String, bin: File): Pair<Int, String> {
        val pb = ProcessBuilder("/bin/bash", "-c", cmd)
        pb.environment()["PATH"] = bin.absolutePath + ":" + System.getenv("PATH")
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun commandUsesPipefail() {
        val cmd = ProjectVerifier.buildCheckCommand("/root/projects/demo", needInstall = false)
        assertTrue("the fix must be in the command the verifier runs", cmd.contains("set -o pipefail"))
        assertTrue(cmd.contains("npm run build"))
        assertTrue(cmd.contains("tail -n 60"))
        assertTrue(ProjectVerifier.buildCheckCommand("/root/projects/demo", needInstall = true).contains("npm install --no-fund --no-audit && npm run build"))
    }

    @Test fun failingBuildScriptIsNotReportedDone() {
        val (dir, bin) = failingProject()
        try {
            val (code, _) = runBash(ProjectVerifier.buildCheckCommand(dir.absolutePath, needInstall = false), bin)
            assertNotEquals("a failing `npm run build` must surface as a non-zero exit code, never DONE", 0, code)
        } finally { dir.deleteRecursively() }
    }

    @Test fun passingBuildStillExitsZero() {
        val (dir, bin) = failingProject()
        try {
            // same project, but npm now succeeds (stub ignores the failing script): the happy path must stay 0
            File(bin, "npm").writeText("#!/bin/sh\nexit 0\n")
            val (code, _) = runBash(ProjectVerifier.buildCheckCommand(dir.absolutePath, needInstall = false), bin)
            assertEquals(0, code)
        } finally { dir.deleteRecursively() }
    }

    @Test fun oldCommandWithoutPipefailHidTheFailure() {
        // Regression guard: proves this test setup can tell the bug from the fix — the pre-fix
        // command (no pipefail) reports 0 for the very same failing build.
        val (dir, bin) = failingProject()
        try {
            val (code, _) = runBash("cd '${dir.absolutePath}' && npm run build 2>&1 | tail -n 60", bin)
            assertEquals("without pipefail the pipeline exits 0 (tail's code) even though the build failed", 0, code)
        } finally { dir.deleteRecursively() }
    }
}
