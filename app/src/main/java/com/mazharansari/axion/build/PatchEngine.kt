package com.mazharansari.axion.build

/**
 * v0.5 Build: diff/patch edits instead of full rewrites (addendum 5).
 *  - [applyEdits]: search/replace blocks (exact first, then whitespace-tolerant), each must match exactly once.
 *  - [applyUnifiedDiff]: standard `@@ -a,b +c,d @@` hunks, tolerant of shifted line numbers.
 *  - [diff] / [unified]: Myers line diff, used to show what an agent step changed (+N −M and the coloured diff in the step box)
 *    and to undo a turn.
 * Pure Kotlin, unit-tested (PatchEngineTest).
 */
object PatchEngine {
    data class Edit(val search: String, val replace: String)

    sealed interface Result {
        data class Ok(val text: String, val applied: Int) : Result
        data class Failed(val reason: String, val editIndex: Int) : Result
    }

    // ── search / replace ────────────────────────────────────────────────────────────────────────────────────────────

    fun applyEdits(original: String, edits: List<Edit>): Result {
        var text = original
        edits.forEachIndexed { i, e ->
            if (e.search.isEmpty()) {   // empty search = append (new file or end of file)
                text = if (text.isEmpty()) e.replace else text.trimEnd('\n') + "\n" + e.replace
                return@forEachIndexed
            }
            val exact = occurrences(text, e.search)
            text = when {
                exact.size == 1 -> text.replaceRange(exact[0], exact[0] + e.search.length, e.replace)
                exact.size > 1 -> return Result.Failed("Edit ${i + 1}: the search text appears ${exact.size} times — add more surrounding lines so it is unique.", i)
                else -> fuzzyReplace(text, e.search, e.replace) ?: return Result.Failed("Edit ${i + 1}: the search text was not found in the file.", i)
            }
        }
        return Result.Ok(text, edits.size)
    }

    private fun occurrences(hay: String, needle: String): List<Int> {
        val out = ArrayList<Int>(); var at = hay.indexOf(needle)
        while (at >= 0) { out += at; if (out.size > 5) break; at = hay.indexOf(needle, at + 1) }
        return out
    }

    /** Line-based match ignoring indentation and trailing spaces; the replacement is re-indented by the indent difference. */
    private fun fuzzyReplace(text: String, search: String, replace: String): String? {
        val lines = text.split('\n')
        val want = search.trimEnd('\n').split('\n')
        if (want.all { it.isBlank() }) return null
        fun norm(s: String) = s.trim()
        val hits = ArrayList<Int>()
        for (start in 0..(lines.size - want.size)) {
            var ok = true
            for (k in want.indices) if (norm(lines[start + k]) != norm(want[k])) { ok = false; break }
            if (ok) hits += start
            if (hits.size > 1) return null
        }
        val start = hits.singleOrNull() ?: return null
        val firstReal = want.indexOfFirst { it.isNotBlank() }
        val fileIndent = lines[start + firstReal].takeWhile { it == ' ' || it == '\t' }
        val searchIndent = want[firstReal].takeWhile { it == ' ' || it == '\t' }
        val repl = replace.trimEnd('\n').split('\n').map { l ->
            when {
                l.isBlank() -> l
                l.startsWith(searchIndent) -> fileIndent + l.removePrefix(searchIndent)
                else -> l
            }
        }
        val out = lines.subList(0, start) + (if (replace.isEmpty()) emptyList() else repl) + lines.subList(start + want.size, lines.size)
        return out.joinToString("\n")
    }

    /** Parses aider/Claude-style blocks:  <<<<<<< SEARCH … ======= … >>>>>>> REPLACE  */
    fun parseBlocks(text: String): List<Edit> {
        val out = ArrayList<Edit>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim().startsWith("<<<<<<<")) {
                val s = StringBuilder(); val r = StringBuilder(); i++
                while (i < lines.size && !lines[i].trim().startsWith("=======")) { s.append(lines[i]).append('\n'); i++ }
                i++
                while (i < lines.size && !lines[i].trim().startsWith(">>>>>>>")) { r.append(lines[i]).append('\n'); i++ }
                out += Edit(s.toString().removeSuffix("\n"), r.toString().removeSuffix("\n"))
            }
            i++
        }
        return out
    }

    // ── unified diff ────────────────────────────────────────────────────────────────────────────────────────────────

    private data class Hunk(val oldStart: Int, val lines: List<String>)

    fun applyUnifiedDiff(original: String, patch: String): Result {
        val hunks = ArrayList<Hunk>()
        var cur: MutableList<String>? = null; var start = 0
        for (l in patch.removeSuffix("\n").split('\n')) {
            val m = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@").find(l)
            when {
                m != null -> { cur?.let { hunks += Hunk(start, it) }; cur = ArrayList(); start = m.groupValues[1].toInt() }
                l.startsWith("---") || l.startsWith("+++") || l.startsWith("diff ") || l.startsWith("index ") -> {}
                cur != null && (l.startsWith(" ") || l.startsWith("+") || l.startsWith("-") || l.isEmpty()) -> cur.add(if (l.isEmpty()) " " else l)
                l.startsWith("\\") -> {}
            }
        }
        cur?.let { hunks += Hunk(start, it) }
        if (hunks.isEmpty()) return Result.Failed("No @@ hunks found in the patch.", 0)
        val lines = original.split('\n').toMutableList()
        var shift = 0
        hunks.forEachIndexed { hi, h ->
            val before = h.lines.filter { !it.startsWith("+") }.map { it.substring(1) }
            val after = h.lines.filter { !it.startsWith("-") }.map { it.substring(1) }
            val guess = (h.oldStart - 1 + shift).coerceAtLeast(0)
            val at = findBlock(lines, before, guess) ?: return Result.Failed("Hunk ${hi + 1} does not match the file (context changed).", hi)
            repeat(before.size) { lines.removeAt(at) }
            lines.addAll(at, after)
            shift += after.size - before.size + (at - guess)
        }
        return Result.Ok(lines.joinToString("\n"), hunks.size)
    }

    /** Nearest position of [block] to [guess] (exact, then trimmed). */
    private fun findBlock(lines: List<String>, block: List<String>, guess: Int): Int? {
        if (block.isEmpty()) return guess.coerceAtMost(lines.size)
        for (strict in listOf(true, false)) {
            fun eq(a: String, b: String) = if (strict) a == b else a.trim() == b.trim()
            var best: Int? = null
            for (s in 0..(lines.size - block.size)) {
                var ok = true
                for (k in block.indices) if (!eq(lines[s + k], block[k])) { ok = false; break }
                if (ok && (best == null || kotlin.math.abs(s - guess) < kotlin.math.abs(best - guess))) best = s
            }
            if (best != null) return best
        }
        return null
    }

    // ── line diff (Myers) ───────────────────────────────────────────────────────────────────────────────────────────

    enum class Op { SAME, ADD, DEL }
    data class Line(val op: Op, val text: String, val oldNo: Int, val newNo: Int)
    data class Stats(val added: Int, val removed: Int) { override fun toString() = "+$added −$removed" }

    fun diff(a: String, b: String): List<Line> {
        val x = if (a.isEmpty()) emptyList() else a.removeSuffix("\n").split('\n'); val y = if (b.isEmpty()) emptyList() else b.removeSuffix("\n").split('\n')
        // trim common prefix/suffix first (most agent edits touch a few lines of a big file)
        var pre = 0; while (pre < x.size && pre < y.size && x[pre] == y[pre]) pre++
        var suf = 0; while (suf < x.size - pre && suf < y.size - pre && x[x.size - 1 - suf] == y[y.size - 1 - suf]) suf++
        val xm = x.subList(pre, x.size - suf); val ym = y.subList(pre, y.size - suf)
        val mid = myers(xm, ym)
        val out = ArrayList<Line>(x.size + y.size)
        for (i in 0 until pre) out += Line(Op.SAME, x[i], i + 1, i + 1)
        var oi = pre; var ni = pre
        for (op in mid) when (op) {
            Op.SAME -> { out += Line(Op.SAME, x[oi], oi + 1, ni + 1); oi++; ni++ }
            Op.DEL -> { out += Line(Op.DEL, x[oi], oi + 1, 0); oi++ }
            Op.ADD -> { out += Line(Op.ADD, y[ni], 0, ni + 1); ni++ }
        }
        for (k in 0 until suf) { out += Line(Op.SAME, x[oi], oi + 1, ni + 1); oi++; ni++ }
        return out
    }

    /** Edit script for x → y. Falls back to delete-all/add-all past a size budget (keeps a phone responsive). */
    private fun myers(x: List<String>, y: List<String>): List<Op> {
        val n = x.size; val m = y.size
        if (n == 0) return List(m) { Op.ADD }
        if (m == 0) return List(n) { Op.DEL }
        val max = n + m
        if (max > 20_000) return List(n) { Op.DEL } + List(m) { Op.ADD }
        val v = IntArray(2 * max + 2); val trace = ArrayList<IntArray>()
        var found = false
        loop@ for (d in 0..max) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var xi = if (k == -d || (k != d && v[k - 1 + max] < v[k + 1 + max])) v[k + 1 + max] else v[k - 1 + max] + 1
                var yi = xi - k
                while (xi < n && yi < m && x[xi] == y[yi]) { xi++; yi++ }
                v[k + max] = xi
                if (xi >= n && yi >= m) { found = true; break@loop }
                k += 2
            }
            if (d > 4000) break
        }
        if (!found) return List(n) { Op.DEL } + List(m) { Op.ADD }
        // backtrack
        val ops = ArrayList<Op>()
        var xi = n; var yi = m
        for (d in trace.size - 1 downTo 0) {
            val vv = trace[d]; val k = xi - yi
            val prevK = if (k == -d || (k != d && vv[k - 1 + max] < vv[k + 1 + max])) k + 1 else k - 1
            val prevX = vv[prevK + max]; val prevY = prevX - prevK
            while (xi > prevX && yi > prevY) { ops += Op.SAME; xi--; yi-- }
            if (d > 0) { if (xi == prevX) { ops += Op.ADD; yi-- } else { ops += Op.DEL; xi-- } }
        }
        return ops.asReversed()
    }

    fun stats(lines: List<Line>) = Stats(lines.count { it.op == Op.ADD }, lines.count { it.op == Op.DEL })

    /** Unified diff text with [context] lines around each change (what the step box shows). */
    fun unified(a: String, b: String, path: String = "file", context: Int = 3): String {
        val d = diff(a, b)
        if (d.none { it.op != Op.SAME }) return ""
        val sb = StringBuilder("--- a/$path\n+++ b/$path\n")
        val changed = d.indices.filter { d[it].op != Op.SAME }
        var i = 0
        while (i < changed.size) {
            var from = (changed[i] - context).coerceAtLeast(0); var to = (changed[i] + context).coerceAtMost(d.lastIndex)
            var j = i
            while (j + 1 < changed.size && changed[j + 1] - context <= to + 1) { j++; to = (changed[j] + context).coerceAtMost(d.lastIndex) }
            val slice = d.subList(from, to + 1)
            val oldStart = slice.firstOrNull { it.oldNo > 0 }?.oldNo ?: 0
            val newStart = slice.firstOrNull { it.newNo > 0 }?.newNo ?: 0
            sb.append("@@ -$oldStart,${slice.count { it.op != Op.ADD }} +$newStart,${slice.count { it.op != Op.DEL }} @@\n")
            slice.forEach { sb.append(when (it.op) { Op.SAME -> " "; Op.ADD -> "+"; Op.DEL -> "-" }).append(it.text).append('\n') }
            i = j + 1
        }
        return sb.toString()
    }
}
