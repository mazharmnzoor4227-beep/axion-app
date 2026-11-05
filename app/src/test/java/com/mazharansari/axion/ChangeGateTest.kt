package com.mazharansari.axion.build

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChangeGateTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tree(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readText() }

    private fun project(): Pair<File, File> {
        val folder = tmp.newFolder("proj"); val pending = File(tmp.root, ".pending/proj")
        File(folder, "index.html").writeText("<h1>old</h1>\n")
        File(folder, "css").mkdirs(); File(folder, "css/site.css").writeText("body{color:red}\n")
        File(folder, "gone.txt").writeText("delete me\n")
        File(folder, "node_modules/x").mkdirs(); File(folder, "node_modules/x/a.js").writeText("untracked")
        return folder to pending
    }
    private fun brainWorks(folder: File) {
        File(folder, "index.html").writeText("<h1>new</h1>\n<p>hi</p>\n")
        File(folder, "about.html").writeText("about\n")
        File(folder, "gone.txt").delete()
        File(folder, "logo.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 1, 2))
    }

    @Test fun projectIsByteIdenticalUntilApprove() {
        val (folder, pending) = project(); val before = tree(folder)
        ChangeGate.backup(folder, pending); brainWorks(folder)
        val set = ChangeGate.stage(folder, pending)
        assertEquals("folder must be exactly as before the turn", before, tree(folder))
        assertEquals(setOf("index.html", "about.html", "gone.txt", "logo.png"), set.changes.map { it.path }.toSet())
        assertEquals(ChangeGate.Status.MODIFIED, set.changes.first { it.path == "index.html" }.status)
        assertEquals(ChangeGate.Status.ADDED, set.changes.first { it.path == "about.html" }.status)
        assertEquals(ChangeGate.Status.DELETED, set.changes.first { it.path == "gone.txt" }.status)
        assertTrue(set.changes.first { it.path == "logo.png" }.binary)
        assertEquals(2, set.changes.first { it.path == "index.html" }.added)
        assertTrue(File(folder, "node_modules/x/a.js").isFile)
        assertNotNull(ChangeGate.load(pending))
    }

    @Test fun approveAppliesAddsEditsAndDeletes() {
        val (folder, pending) = project()
        ChangeGate.backup(folder, pending); brainWorks(folder)
        val set = ChangeGate.stage(folder, pending)
        val applied = ChangeGate.apply(folder, pending, set)
        assertEquals(4, applied.size)
        assertEquals("<h1>new</h1>\n<p>hi</p>\n", File(folder, "index.html").readText())
        assertEquals("about\n", File(folder, "about.html").readText())
        assertFalse(File(folder, "gone.txt").exists())
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 1, 2), File(folder, "logo.png").readBytes())
        assertEquals("body{color:red}\n", File(folder, "css/site.css").readText())
    }

    @Test fun approveOnlySelectedFiles() {
        val (folder, pending) = project()
        ChangeGate.backup(folder, pending); brainWorks(folder)
        val set = ChangeGate.stage(folder, pending)
        ChangeGate.apply(folder, pending, set, setOf("about.html"))
        assertTrue(File(folder, "about.html").isFile)
        assertEquals("<h1>old</h1>\n", File(folder, "index.html").readText())
        assertTrue(File(folder, "gone.txt").isFile)
    }

    @Test fun rejectLeavesProjectUntouched() {
        val (folder, pending) = project(); val before = tree(folder)
        ChangeGate.backup(folder, pending); brainWorks(folder)
        ChangeGate.stage(folder, pending)
        ChangeGate.discard(pending)
        assertEquals(before, tree(folder)); assertFalse(pending.exists())
    }

    @Test fun pathTraversalIsRefused() {
        val (folder, pending) = project()
        ChangeGate.backup(folder, pending); brainWorks(folder)
        val set = ChangeGate.stage(folder, pending)
        val evil = ChangeGate.ChangeSet(listOf(ChangeGate.FileChange("../escape.txt", ChangeGate.Status.ADDED)))
        File(pending, "after/../escape.txt").writeText("x")
        assertThrows(IllegalArgumentException::class.java) { ChangeGate.apply(folder, pending, evil) }
        assertFalse(File(folder.parentFile, "escape.txt").exists())
        val evil2 = ChangeGate.ChangeSet(listOf(ChangeGate.FileChange("/etc/passwd-x", ChangeGate.Status.DELETED)))
        ChangeGate.apply(folder, pending, evil2)   // absolute path is anchored inside the project, never outside
        assertTrue(set.changes.isNotEmpty())
    }

    @Test fun crashMidTurnRestoresProject() {
        val (folder, pending) = project(); val before = tree(folder)
        ChangeGate.backup(folder, pending); brainWorks(folder)   // process dies here: no stage()
        assertTrue(ChangeGate.recoverIfInterrupted(folder, pending))
        assertEquals(before, tree(folder))
        assertFalse(ChangeGate.recoverIfInterrupted(folder, pending))
    }

    @Test fun nothingChangedGivesEmptySet() {
        val (folder, pending) = project()
        ChangeGate.backup(folder, pending)
        val set = ChangeGate.stage(folder, pending)
        assertTrue(set.isEmpty)
    }

    @Test fun diffTextsForReview() {
        val (folder, pending) = project()
        ChangeGate.backup(folder, pending); brainWorks(folder)
        val set = ChangeGate.stage(folder, pending)
        val (o, n) = ChangeGate.texts(pending, set.changes.first { it.path == "index.html" })
        assertEquals("<h1>old</h1>\n", o); assertEquals("<h1>new</h1>\n<p>hi</p>\n", n)
        val (o2, n2) = ChangeGate.texts(pending, set.changes.first { it.path == "gone.txt" })
        assertEquals("delete me\n", o2); assertNull(n2)
    }
}
