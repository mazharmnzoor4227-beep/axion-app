package com.mazharansari.axion

import com.mazharansari.axion.build.ProjectVerifier
import com.mazharansari.axion.build.ReleaseSigning
import com.mazharansari.axion.build.ReleaseSigningRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P1-9 Builder release-signing path: pure-logic tests. No Gradle, no keystore tool — the
 * release build itself is proven on the phone (Build APK → Build release APK); these tests
 * pin the parts that must never regress: passwords never reach a log, the verifier really
 * runs a Gradle build for Android projects, and a claimed success without an APK is a failure.
 */
class P1ReleaseSigningTest {

    private fun keystoreFile(dir: File, name: String = "user.keystore"): File =
        File(dir, name).apply { writeText("fake-keystore-bytes") }

    private fun request(dir: File) = ReleaseSigningRequest(
        keystoreFile(dir), "mykey", "store-secret-1".toCharArray(), "key-secret-2".toCharArray())

    @Test fun validRequestPassesValidation() {
        val dir = Files.createTempDirectory("p1-9-valid-").toFile()
        try {
            assertTrue(request(dir).validate().toString(), request(dir).validate().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun validationCatchesMissingPieces() {
        val dir = Files.createTempDirectory("p1-9-invalid-").toFile()
        try {
            val missing = ReleaseSigningRequest(File(dir, "nope.keystore"), "", CharArray(0), CharArray(0)).validate()
            assertTrue(missing.any { it.contains("keystore file") })
            assertTrue(missing.any { it.contains("alias") })
            assertTrue(missing.any { it.contains("keystore password") })
            assertTrue(missing.any { it.contains("key password") })
            val wrongExt = ReleaseSigningRequest(File(dir, "key.txt").apply { writeText("x") }, "a", "s".toCharArray(), "k".toCharArray()).validate()
            assertTrue(wrongExt.any { it.contains(".keystore or .jks") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun clearZeroesBothPasswords() {
        val dir = Files.createTempDirectory("p1-9-clear-").toFile()
        try {
            val req = request(dir)
            req.clear()
            assertTrue(req.storePassword.all { it == '\u0000' })
            assertTrue(req.keyPassword.all { it == '\u0000' })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun gradleCommandUsesPipefailAndRelease() {
        val dir = Files.createTempDirectory("p1-9-cmd-").toFile()
        try {
            val req = request(dir)
            val injected = ReleaseSigning.injectedArgs("/workspace/.axion-signing/demo.keystore", req.alias, req.storePassword, req.keyPassword)
            val cmd = ReleaseSigning.gradleCommand("/root/projects/demo", useGradlew = true, injected)
            assertTrue(cmd.contains("set -o pipefail"))
            assertTrue(cmd.contains("./gradlew assembleRelease"))
            assertTrue(cmd.contains("android.injected.signing.store.file="))
            assertTrue(cmd.contains("android.injected.signing.key.alias="))
            assertTrue("the raw command carries the passwords (that is why it is never logged)", cmd.contains("store-secret-1") && cmd.contains("key-secret-2"))
            val scrubbed = ReleaseSigning.scrubbed(cmd, req.secrets())
            assertFalse("scrubbed output must not contain the store password", scrubbed.contains("store-secret-1"))
            assertFalse("scrubbed output must not contain the key password", scrubbed.contains("key-secret-2"))
            assertTrue("scrubbed output must show the mask instead", scrubbed.contains("****"))
            assertTrue("the alias is not a secret and stays visible", scrubbed.contains("mykey"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun shellQuoteKeepsTrickyPasswordsIntact() {
        // A password with a quote, a dollar and spaces must survive sh single-quoting.
        val pw = "p'a\$s sword"
        val quoted = ReleaseSigning.shellQuote(pw)
        val pb = ProcessBuilder("/bin/sh", "-c", "printf %s $quoted")
        pb.redirectErrorStream(true)
        val p = pb.start()
        assertEquals(pw, p.inputStream.bufferedReader().readText())
        assertEquals(0, p.waitFor())
    }

    @Test fun stageKeystoreStaysOutsideTheProject() {
        val dir = Files.createTempDirectory("p1-9-stage-").toFile()
        try {
            val project = File(dir, "projects/demo").apply { mkdirs() }
            val workspace = File(dir, "workspace").apply { mkdirs() }
            val src = keystoreFile(dir)
            val staged = ReleaseSigning.stageKeystore(src, workspace, "demo")
            assertEquals("fake-keystore-bytes", staged.readText())
            assertFalse("the staged keystore must never live inside the project", staged.canonicalPath.startsWith(project.canonicalPath))
            assertEquals("/workspace/.axion-signing/demo.keystore", ReleaseSigning.guestKeystorePath("demo"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun findReleaseApkNeedsARealArtifact() {
        val dir = Files.createTempDirectory("p1-9-apk-").toFile()
        try {
            assertNull(ReleaseSigning.findReleaseApk(dir))
            val apk = File(dir, "app/build/outputs/apk/release/app-release.apk").apply { parentFile.mkdirs(); writeText("apk") }
            assertEquals(apk.canonicalPath, ReleaseSigning.findReleaseApk(dir)?.canonicalPath)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── ProjectVerifier: Android coverage ────────────────────────────────────────────────

    @Test fun isAndroidProjectDetection() {
        val dir = Files.createTempDirectory("p1-9-detect-").toFile()
        try {
            assertFalse(ProjectVerifier.isAndroidProject(dir))
            File(dir, "package.json").writeText("{}")
            assertFalse("a web project is not an Android project", ProjectVerifier.isAndroidProject(dir))
            File(dir, "app").mkdirs()
            File(dir, "app/build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
            assertTrue(ProjectVerifier.isAndroidProject(dir))
        } finally {
            dir.deleteRecursively()
        }
        val dir2 = Files.createTempDirectory("p1-9-detect2-").toFile()
        try {
            File(dir2, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
            assertTrue("a root build file applying the Android plugin counts", ProjectVerifier.isAndroidProject(dir2))
        } finally {
            dir2.deleteRecursively()
        }
    }

    @Test fun androidCommandUsesPipefailAndAssembleDebug() {
        val cmd = ProjectVerifier.androidBuildCheckCommand("/root/projects/demo", useGradlew = true)
        assertTrue(cmd.contains("set -o pipefail"))
        assertTrue(cmd.contains("./gradlew :app:assembleDebug"))
        assertTrue(cmd.contains("tail -n 60"))
        assertTrue(ProjectVerifier.androidBuildCheckCommand("/root/projects/demo", useGradlew = false).contains("gradle :app:assembleDebug"))
    }

    @Test fun findDebugApkNeedsARealArtifact() {
        val dir = Files.createTempDirectory("p1-9-debugapk-").toFile()
        try {
            assertNull(ProjectVerifier.findDebugApk(dir))
            val apk = File(dir, "app/build/outputs/apk/debug/app-debug.apk").apply { parentFile.mkdirs(); writeText("apk") }
            assertEquals(apk.canonicalPath, ProjectVerifier.findDebugApk(dir)?.canonicalPath)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Same stub-gradlew pattern as ProjectVerifierTest: the exact verifier command must exit non-zero when Gradle fails. */
    private fun androidProjectWithStubGradlew(fail: Boolean): Pair<File, File> {
        val dir = Files.createTempDirectory("p1-9-gradle-").toFile()
        File(dir, "app").mkdirs()
        File(dir, "app/build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        File(dir, "gradlew").apply {
            writeText("#!/bin/sh\nif [ \"\$1\" = \":app:assembleDebug\" ]; then exit ${if (fail) 1 else 0}; fi\nexit 0\n")
            setExecutable(true)
        }
        return dir to dir
    }

    private fun runBash(cmd: String, dir: File): Pair<Int, String> {
        val pb = ProcessBuilder("/bin/bash", "-c", cmd).directory(dir)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun failingGradleBuildIsNotReportedDone() {
        val (dir, _) = androidProjectWithStubGradlew(fail = true)
        try {
            val (code, _) = runBash(ProjectVerifier.androidBuildCheckCommand(dir.absolutePath, useGradlew = true), dir)
            assertNotEquals("a failing :app:assembleDebug must surface as a non-zero exit code, never DONE", 0, code)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun passingGradleBuildStillExitsZero() {
        val (dir, _) = androidProjectWithStubGradlew(fail = false)
        try {
            val (code, _) = runBash(ProjectVerifier.androidBuildCheckCommand(dir.absolutePath, useGradlew = true), dir)
            assertEquals(0, code)
        } finally {
            dir.deleteRecursively()
        }
    }
}
