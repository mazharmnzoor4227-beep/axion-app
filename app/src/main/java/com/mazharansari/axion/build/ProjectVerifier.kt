package com.mazharansari.axion.build

import com.mazharansari.axion.data.Project
import com.mazharansari.axion.data.Step
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.data.StepStatus
import com.mazharansari.axion.linux.LinuxRuntime
import kotlinx.coroutines.*
import java.io.File

/**
 * Axion's own verify step after each Build turn. Independent checks run in parallel:
 *  - site check on the phone (broken links/assets, viewport, empty page, cut-off JS) — always, instant;
 *  - `npm run build` when the project has a build script and Web is installed (before the screenshot);
 *  - Python files byte-compiled when Python is installed;
 *  - headless Chromium screenshot at phone size + console errors, when Browser automation is installed.
 *  - Android projects (P1-9): a real `:app:assembleDebug` Gradle build when the Android
 *    toolchains are installed, and the debug APK artifact must actually exist afterwards —
 *    a claimed success without an APK is a failure. This is slow on a phone (minutes, like
 *    the Build APK button); it is the price of verifying Android the same way web is verified.
 */
class ProjectVerifier(
    private val linux: LinuxRuntime?,
    private val installed: (String) -> Boolean,
    private val guestPath: (Project) -> String,
) : BuildVerifier {
    companion object {
        /**
         * The exact guest-shell command the build check runs (CR-1 / P0-1).
         * `set -o pipefail` is the whole fix: `npm run build` is piped through `tail`, and without
         * pipefail the pipeline's exit code is tail's — always 0 — so a failing build was reported
         * DONE and never added to the problems list. With pipefail the shell reports npm's own
         * non-zero code, `done(s, code == 0, …)` marks the step ERROR and the failure lands in
         * problems. `set` comes first so a failed `cd` still aborts the build via `&&`.
         */
        fun buildCheckCommand(guest: String, needInstall: Boolean): String {
            val cmd = (if (needInstall) "npm install --no-fund --no-audit && " else "") + "npm run build"
            return "set -o pipefail; cd '$guest' && $cmd 2>&1 | tail -n 60"
        }

        /** Pure: an Android (Gradle) project — an app module build file, or a root build file applying the Android application plugin. */
        fun isAndroidProject(folder: File): Boolean {
            if (File(folder, "app/build.gradle.kts").isFile || File(folder, "app/build.gradle").isFile) return true
            val root = File(folder, "build.gradle.kts").takeIf { it.isFile } ?: File(folder, "build.gradle").takeIf { it.isFile } ?: return false
            return runCatching { root.readText().contains("com.android.application") }.getOrDefault(false)
        }

        /**
         * The exact guest-shell command for the Android build check (P1-9): the same pipefail
         * rule as [buildCheckCommand] — a failing Gradle build must surface as a non-zero exit
         * code, never DONE. Uses the project's own gradlew when it has one.
         */
        fun androidBuildCheckCommand(guest: String, useGradlew: Boolean): String {
            val gradle = if (useGradlew) "./gradlew" else "gradle"
            return "set -o pipefail; cd '$guest' && $gradle :app:assembleDebug --console=plain 2>&1 | tail -n 60"
        }

        /** The debug APK a successful Android build must have produced (newest first). */
        fun findDebugApk(folder: File): File? =
            File(folder, "app/build/outputs/apk/debug").walkTopDown().filter { it.isFile && it.extension == "apk" }.maxByOrNull { it.lastModified() }
    }

    override suspend fun verify(project: Project, folder: File, onStep: (Step) -> Unit): BuildVerifier.Report = coroutineScope {
        val problems = java.util.Collections.synchronizedList(ArrayList<String>())
        val notes = java.util.Collections.synchronizedList(ArrayList<String>())
        fun step(id: String, kind: StepKind, title: String, target: String) = Step("verify-$id-${System.nanoTime()}", kind, title, target, startedAt = System.currentTimeMillis()).also(onStep)
        fun done(s: Step, ok: Boolean, out: String, title: String = s.title) = onStep(s.copy(status = if (ok) StepStatus.DONE else StepStatus.ERROR, output = out.take(6000), endedAt = System.currentTimeMillis(), title = title))
        val guest = guestPath(project)
        val canLinux = linux?.isInstalled() == true

        val pkg = File(folder, "package.json")
        val build = async(Dispatchers.IO) {
            if (!canLinux || !pkg.isFile || !installed("web")) return@async
            val txt = runCatching { pkg.readText() }.getOrDefault("")
            if (!Regex("\"build\"\\s*:").containsMatchIn(txt)) return@async
            val needInstall = !File(folder, "node_modules").isDirectory
            val cmd = (if (needInstall) "npm install --no-fund --no-audit && " else "") + "npm run build"
            val s = step("build", StepKind.COMMAND, "Bash", cmd)
            val (code, out) = linux!!.run(buildCheckCommand(guest, needInstall), timeoutMs = 15 * 60_000L, guestCwd = guest)
            done(s, code == 0, out)
            if (code != 0) problems += "`$cmd` failed:\n" + out.lines().filter { it.isNotBlank() }.takeLast(25).joinToString("\n")
        }
        val py = async(Dispatchers.IO) {
            if (!canLinux || !installed("python")) return@async
            val files = folder.walkTopDown().onEnter { it.name !in ProjectFiles.HIDDEN }.filter { it.isFile && it.extension == "py" }.take(60).toList()
            if (files.isEmpty()) return@async
            val s = step("py", StepKind.COMMAND, "Bash", "python3 -m py_compile (${files.size} files)")
            val (code, out) = linux!!.run("cd '$guest' && python3 -m py_compile " + files.joinToString(" ") { "'" + it.relativeTo(folder).path + "'" } + " 2>&1", 120_000, guestCwd = guest)
            done(s, code == 0, out.ifBlank { "OK" })
            if (code != 0) problems += "Python doesn't compile:\n" + out.takeLast(1500)
        }
        // P1-9: Android projects are verified with a real Gradle build, the same way web
        // projects get a real `npm run build` — plus the debug APK must actually exist.
        // Skipped (like the web check) when Linux or the Android toolchains are not installed.
        val android = async(Dispatchers.IO) {
            if (!canLinux || !isAndroidProject(folder) || !installed("android") || !installed("androidsdk")) return@async
            val s = step("android", StepKind.COMMAND, "Bash", "Gradle :app:assembleDebug (Android check)")
            val (code, out) = linux!!.run(androidBuildCheckCommand(guest, File(folder, "gradlew").isFile), timeoutMs = 45 * 60_000L, guestCwd = guest)
            val apk = findDebugApk(folder)
            val ok = code == 0 && apk != null && apk.length() > 0
            done(s, ok, out)
            if (code != 0) problems += "Android build failed (`:app:assembleDebug`):\n" + out.lines().filter { it.isNotBlank() }.takeLast(25).joinToString("\n")
            else if (apk == null) problems += "Android build reported success but no debug APK was produced."
            else notes += "Android build OK (${apk.name})."
        }
        build.await()
        val site = async(Dispatchers.IO) {
            val s = step("site", StepKind.SEARCH, "Checked", "site files")
            val r = SiteChecker.check(folder)
            problems += r.problems; notes += r.notes
            done(s, r.problems.isEmpty(), (r.problems + r.notes).joinToString("\n").ifBlank { "No broken links, viewport set." },
                if (r.problems.isEmpty()) "Checked" else "Found ${r.problems.size} problem${if (r.problems.size == 1) "" else "s"} in")
            r.siteDir
        }
        val siteDir = site.await()
        var shot: File? = null
        if (siteDir != null && canLinux && installed("browser")) {
            val s = step("shot", StepKind.BROWSE, "Screenshot", "phone size 412×915")
            val server = runCatching { StaticServer(siteDir).start() }.getOrNull()
            try {
                val out = File(folder, ".axion/shot.png").apply { parentFile?.mkdirs(); delete() }
                val (code, text) = linux!!.run("axion-shot '${server?.url ?: "$guest/index.html"}' '$guest/.axion/shot.png' 412 915", 120_000, guestCwd = guest, fakeRoot = false)
                val errs = text.lines().filter { it.startsWith("CONSOLE-ERROR:") }.map { it.removePrefix("CONSOLE-ERROR:").trim() }
                    .filterNot { it.contains("favicon.ico") }
                problems += errs.map { "Browser console: $it" }
                if (out.isFile) shot = out
                done(s, code == 0 && errs.isEmpty(), text.takeLast(3000), if (errs.isEmpty()) "Screenshot" else "Screenshot · ${errs.size} console error${if (errs.size == 1) "" else "s"}")
            } finally { server?.stop() }
        }
        py.await()
        android.await()
        BuildVerifier.Report(problems.distinct(), notes.toList(), shot, siteDir != null)
    }
}
