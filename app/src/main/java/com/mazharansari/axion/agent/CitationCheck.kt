package com.mazharansari.axion.agent

/**
 * P1-6 DEEP RESEARCH v2 — citations enforced IN CODE, not by prompt alone.
 *
 * [CitationCheck.verify] is a pure verification pass over a synthesised report + its Sources
 * list. It returns every violation found:
 *
 *   UNCITED_KEY_CLAIM    a bullet under "## Key findings" with no [n] citation at all;
 *   DANGLING_CITATION    a [n] used in the body whose number has no entry in Sources;
 *   SINGLE_SOURCE_NUMBER a key-finding bullet that states a number (price, date, count…)
 *                        backed by fewer than 2 independent sources.
 *
 * [CitationCheck.annotate] then corrects the report before it is shown or saved: a
 * single-sourced key number gets a "(single source)" marker, an uncited key claim gets a
 * "(citation needed)" marker, and a short "Citation check:" note is added so the reader can
 * see what was and was not verified. Annotation is idempotent — running it twice changes
 * nothing the second time. DeepResearchTool runs verify + annotate before saving/returning
 * the report; the chat UI renders the parsed Sources list as its own card (see
 * SourcesList in ui/chat/MessageViews.kt), so the sources are visible in chat, not only
 * in the saved .md file.
 */
object CitationCheck {

    enum class ViolationKind { UNCITED_KEY_CLAIM, DANGLING_CITATION, SINGLE_SOURCE_NUMBER }

    data class Violation(val kind: ViolationKind, val detail: String)

    data class CitedSource(val n: Int, val title: String, val url: String)

    data class Result(val sources: List<CitedSource>, val violations: List<Violation>)

    private val citationRef = Regex("\\[(\\d+)\\]")
    private val sourceLine = Regex("^\\s*(\\d+)\\.\\s+(.*?)\\s*[—\\-]\\s+(\\S+)\\s*$")
    private val bulletLine = Regex("^\\s*([-*•]|\\d+[.)])\\s+(.+)$")
    private val hasDigit = Regex("\\d")

    /** Parse the numbered "## Sources" list at the end of a report. Empty when there is none. */
    fun extractSources(report: String): List<CitedSource> = parseSources(report)

    fun parseSources(report: String): List<CitedSource> {
        val section = sourcesSection(report) ?: return emptyList()
        return section.lines().mapNotNull { line ->
            val m = sourceLine.find(line) ?: return@mapNotNull null
            CitedSource(n = m.groupValues[1].toInt(), title = m.groupValues[2].trim(), url = m.groupValues[3].trim())
        }.distinctBy { it.n }.sortedBy { it.n }
    }

    /** The full verification pass: sources + every violation. */
    fun verify(report: String): Result {
        val sources = parseSources(report)
        val known = sources.map { it.n }.toSet()
        val body = report.substringBefore(sourcesHeading(report) ?: "\u0000")
        val violations = mutableListOf<Violation>()

        // Every [n] in the body must exist in Sources.
        citationRef.findAll(body).map { it.groupValues[1].toInt() }.distinct()
            .filter { it !in known }
            .forEach { violations += Violation(ViolationKind.DANGLING_CITATION, "Citation [$it] has no entry in the Sources list.") }

        // Every key-finding bullet must carry a citation; a bullet stating a number needs 2+ sources.
        for (claim in keyFindings(report)) {
            val cited = citationRef.findAll(claim).map { it.groupValues[1].toInt() }.distinct().toList()
            if (cited.isEmpty()) {
                violations += Violation(ViolationKind.UNCITED_KEY_CLAIM, "Key claim has no citation: ${claim.take(160)}")
            } else if (hasDigit.containsMatchIn(claim) && cited.size < 2 && "(single source)" !in claim) {
                violations += Violation(
                    ViolationKind.SINGLE_SOURCE_NUMBER,
                    "Key number backed by only 1 source ([${cited.first()}]): ${claim.take(160)}",
                )
            }
        }
        return Result(sources, violations)
    }

    /**
     * Correct the report in place: mark single-sourced key numbers "(single source)" and
     * uncited key claims "(citation needed)", and add a "Citation check:" note just before
     * the Sources list. Idempotent — a report that already carries the markers/notes is
     * returned unchanged.
     */
    fun annotate(report: String): String {
        if ("## Citation check" in report) return report
        val result = verify(report)
        val heading = sourcesHeading(report)
        val lines = report.lines().toMutableList()
        val inKeyFindings = keyFindingLineIndexes(report)
        for (idx in inKeyFindings) {
            val line = lines[idx]
            val cited = citationRef.findAll(line).map { it.groupValues[1].toInt() }.distinct().toList()
            when {
                cited.isEmpty() && "(citation needed)" !in line -> lines[idx] = "$line (citation needed)"
                cited.size == 1 && hasDigit.containsMatchIn(line) && "(single source)" !in line -> lines[idx] = "$line (single source)"
            }
        }
        val note = if (result.violations.isEmpty()) {
            "> Citation check: all key claims cited; key numbers cross-checked in at least 2 sources."
        } else {
            val counts = result.violations.groupBy { it.kind }
                .entries.joinToString(", ") { (kind, list) ->
                    "${list.size} " + when (kind) {
                        ViolationKind.UNCITED_KEY_CLAIM -> "key claim${if (list.size == 1) "" else "s"} without a citation"
                        ViolationKind.DANGLING_CITATION -> "citation${if (list.size == 1) "" else "s"} with no matching source"
                        ViolationKind.SINGLE_SOURCE_NUMBER -> "key number${if (list.size == 1) "" else "s"} from a single source"
                    }
                }
            "> Citation check: ${result.violations.size} issue${if (result.violations.size == 1) "" else "s"} — $counts."
        }
        val annotated = lines.joinToString("\n")
        if (heading == null) return annotated + "\n\n## Citation check\n$note\n"
        // Insert the note as its own section just before "## Sources".
        return annotated.replaceFirst(heading, "## Citation check\n$note\n\n$heading")
    }

    /** Bullet lines under "## Key findings" (the claims the report stands on). */
    fun keyFindings(report: String): List<String> {
        val lines = report.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("## Key findings") }
        if (start < 0) return emptyList()
        val out = mutableListOf<String>()
        for (i in start + 1 until lines.size) {
            val t = lines[i].trim()
            if (t.startsWith("## ")) break
            bulletLine.find(lines[i])?.let { out += it.groupValues[2].trim() }
        }
        return out
    }

    /** Line indexes (in report.lines()) of the "## Key findings" bullets — for annotation. */
    private fun keyFindingLineIndexes(report: String): List<Int> {
        val lines = report.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("## Key findings") }
        if (start < 0) return emptyList()
        val out = mutableListOf<Int>()
        for (i in start + 1 until lines.size) {
            if (lines[i].trim().startsWith("## ")) break
            if (bulletLine.containsMatchIn(lines[i])) out += i
        }
        return out
    }

    private fun sourcesHeading(report: String): String? =
        report.lines().firstOrNull { it.trim() == "## Sources" }

    private fun sourcesSection(report: String): String? {
        val heading = sourcesHeading(report) ?: return null
        return report.substringAfter(heading)
    }
}
