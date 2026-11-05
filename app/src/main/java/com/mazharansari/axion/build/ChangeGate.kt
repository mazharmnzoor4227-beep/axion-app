package com.mazharansari.axion.build

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v0.6 (Phase 1.2) DIFF REVIEW gate. Plain JVM, unit-tested (ChangeGateTest).
 *
 * The coding brain (agy) writes straight into the project folder while it works. To guarantee the project is NOT
 * changed until the user taps Approve, a turn is wrapped like this:
 *  1. [backup]   copy every tracked file into `<pending>/backup` (+ an `inturn` marker, so a crash mid-turn restores it)
 *  2. the brain works and Axion verifies on the real folder, exactly as before
 *  3. [stage]    compute the ChangeSet, copy the NEW content of added/modified files into `<pending>/after`, then put the
 *                project folder back exactly as it was (modified/deleted files restored, added files removed)
 *  4. the user reviews the diff: [apply] writes the approved changes into the project; [reject] throws them away.
 * Tracked = every file outside ProjectFiles.HIDDEN / GENERATED (node_modules, .git, dist, build ...).
 */
object ChangeGate {
    enum class Status { ADDED, MODIFIED, DELETED }

    @Serializable
    data class FileChange(val path: String, val status: Status, val binary: Boolean = false, val added: Int = 0, val removed: Int = 0, val size: Long = 0)

    @Serializable
    data class ChangeSet(val changes: List<FileChange>, val createdAt: Long = System.currentTimeMillis()) {
        val isEmpty get() = changes.isEmpty()
        val totalAdded get() = changes.sumOf { it.added }
        val totalRemoved get() = changes.sumOf { it.removed }
    }

    class TooLargeException(msg: String) : Exception(msg)

    const val MAX_FILE_BYTES = 25L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 400L * 1024 * 1024
    const val MAX_DIFF_TEXT = 400_000L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun pendingDir(projectsDir: File, slug: String) = File(projectsDir, ".pending/$slug")

    private fun tracked(root: File): Sequence<File> =
        root.walkTopDown().onEnter { it == root || (it.name !in ProjectFiles.HIDDEN && it.name !in ProjectFiles.GENERATED) }
            .filter { it.isFile && !it.name.endsWith(".axion-tmp") }

    /** Step 1. Throws [TooLargeException] when the project is too big to guard (the caller then runs ungated and says so). */
    fun backup(folder: File, pending: File) {
        pending.deleteRecursively()
        val back = File(pending, "backup").apply { mkdirs() }
        var total = 0L
        for (f in tracked(folder).toList()) {
            if (f.length() > MAX_FILE_BYTES) throw TooLargeException("${f.name} is larger than ${MAX_FILE_BYTES shr 20} MB")
            total += f.length()
            if (total > MAX_TOTAL_BYTES) { pending.deleteRecursively(); throw TooLargeException("project is larger than ${MAX_TOTAL_BYTES shr 20} MB") }
            val dest = File(back, f.relativeTo(folder).path); dest.parentFile?.mkdirs(); f.copyTo(dest, overwrite = true)
        }
        File(pending, "inturn").writeText("1")
    }

    private fun rel(root: File): Map<String, File> = if (!root.isDirectory) emptyMap() else root.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(root).path }
    private fun same(a: File, b: File) = a.length() == b.length() && a.readBytes().contentEquals(b.readBytes())

    /** Step 3. Returns the ChangeSet (and leaves the folder byte-identical to its state at [backup]). */
    fun stage(folder: File, pending: File): ChangeSet {
        val back = File(pending, "backup"); val after = File(pending, "after")
        after.deleteRecursively(); after.mkdirs()
        val before = rel(back)
        val nowFiles = tracked(folder).associateBy { it.relativeTo(folder).path }
        val changes = ArrayList<FileChange>()
        for ((p, f) in nowFiles) {
            val old = before[p]
            if (old != null && same(old, f)) continue
            val binary = !ProjectFiles.isProbablyText(f) || f.length() > MAX_DIFF_TEXT
            var add = 0; var rem = 0
            if (!binary) { val d = PatchEngine.diff(old?.readText().orEmpty(), f.readText()); add = d.count { it.op == PatchEngine.Op.ADD }; rem = d.count { it.op == PatchEngine.Op.DEL } }
            val dest = File(after, p); dest.parentFile?.mkdirs(); f.copyTo(dest, overwrite = true)
            changes += FileChange(p, if (old == null) Status.ADDED else Status.MODIFIED, binary, add, rem, f.length())
        }
        for ((p, old) in before) if (p !in nowFiles) {
            val binary = !ProjectFiles.isProbablyText(old) || old.length() > MAX_DIFF_TEXT
            val lines = if (binary) 0 else old.readLines().size
            changes += FileChange(p, Status.DELETED, binary, 0, lines, old.length())
        }
        val set = ChangeSet(changes.sortedBy { it.path })
        File(pending, "manifest.json").writeText(json.encodeToString(ChangeSet.serializer(), set))
        restoreFromBackup(folder, pending)
        File(pending, "inturn").delete()
        return set
    }

    /** Puts the folder back exactly as [backup] saw it (tracked files only). Used by [stage] and by crash recovery. */
    fun restoreFromBackup(folder: File, pending: File) {
        val back = File(pending, "backup"); val before = rel(back)
        for (f in tracked(folder).toList()) if (f.relativeTo(folder).path !in before) f.delete()
        for ((p, src) in before) {
            val dest = File(folder, p)
            if (!dest.isFile || !same(src, dest)) { dest.parentFile?.mkdirs(); src.copyTo(dest, overwrite = true) }
        }
        folder.walkBottomUp().filter { it.isDirectory && it != folder && it.listFiles().isNullOrEmpty() && it.name !in ProjectFiles.HIDDEN && it.name !in ProjectFiles.GENERATED }.forEach { it.delete() }
    }

    /** Crash recovery: a turn started (inturn marker) and never reached [stage]. Restores the project, clears the marker. */
    fun recoverIfInterrupted(folder: File, pending: File): Boolean {
        if (!File(pending, "inturn").isFile) return false
        restoreFromBackup(folder, pending)
        pending.deleteRecursively()
        return true
    }

    fun load(pending: File): ChangeSet? = runCatching { json.decodeFromString(ChangeSet.serializer(), File(pending, "manifest.json").readText()) }.getOrNull()?.takeIf { File(pending, "after").isDirectory || it.changes.all { c -> c.status == Status.DELETED } }

    /** Old and new text of one change, for the diff view (null = no such side; binary files have no text). */
    fun texts(pending: File, c: FileChange): Pair<String?, String?> {
        if (c.binary) return null to null
        val old = if (c.status == Status.ADDED) null else runCatching { File(pending, "backup/${c.path}").readText() }.getOrNull()
        val new = if (c.status == Status.DELETED) null else runCatching { File(pending, "after/${c.path}").readText() }.getOrNull()
        return old to new
    }

    /** Step 4a. Writes the approved changes into [folder]. [selected] = paths to apply (null = all). Returns the paths applied. Path traversal is refused. */
    fun apply(folder: File, pending: File, set: ChangeSet, selected: Set<String>? = null): List<String> {
        val applied = ArrayList<String>()
        for (c in set.changes) {
            if (selected != null && c.path !in selected) continue
            val target = ProjectFiles.safeChild(folder, c.path)   // throws on '..' / absolute escapes
            val inTracked = c.path.split('/', '\\').none { it in ProjectFiles.HIDDEN || it in ProjectFiles.GENERATED }
            if (!inTracked) continue
            when (c.status) {
                Status.DELETED -> target.delete()
                else -> {
                    val src = File(pending, "after/${c.path}")
                    require(src.isFile) { "Staged file missing: ${c.path}" }
                    target.parentFile?.mkdirs(); src.copyTo(target, overwrite = true)
                }
            }
            applied += c.path
        }
        return applied
    }

    /** Step 4b / after apply: forget the pending change. */
    fun discard(pending: File) { pending.deleteRecursively() }
}
