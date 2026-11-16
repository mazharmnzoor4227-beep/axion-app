package com.mazharansari.axion.agent

/**
 * BRAIN (Phase 1) — verify-before-deliver: the CEO checks worker results BEFORE they
 * reach the user, instead of trusting them. Three checks, one per kind of worker output:
 *
 *  - RESEARCH: every fact must cite a source URL, and the merged facts are run through
 *    the real [CitationCheck] (the deep-research citation enforcer): an uncited key claim
 *    or a key number from a single source is a problem, not a pass.
 *  - CODE: every "Wrote <path>" fact is read back from the workspace — the file must
 *    exist with content. A worker that claims a file it never wrote fails verification.
 *  - TEST: every test fact must carry its exit code (the command really ran).
 *  - REVIEW: the file the review claims to cover must exist (read-back of its input).
 *
 * A failed check is never hidden: [Result] lists the problems, the swarm tool makes ONE
 * bounded fix attempt (at most 2 fix workers, one retry only), and whatever is still
 * unverified is reported as "Needs check" — the CEO never invents a fact or claims
 * success to cover a failure. Pure except for the workspace read-back; unit-tested.
 */
object SwarmVerify {

    /** One check on one worker fact. [fact] rides along so the caller can build a fix worker for it. */
    data class Check(
        val workerId: String,
        val kind: String, // "research" | "write" | "test" | "review"
        val ok: Boolean,
        val detail: String,
        val fact: WorkerFact,
        /** Soft caveat that does not fail the check — e.g. "(single source)" for a key number. */
        val note: String = "",
    )

    data class Result(val checks: List<Check>) {
        val total: Int get() = checks.size
        val verified: Int get() = checks.count { it.ok }
        val problems: List<Check> get() = checks.filterNot { it.ok }
        val allOk: Boolean get() = problems.isEmpty()
        /** The mark shown on the delivered result in chat. */
        val mark: String get() = if (allOk) "Verified" else "Needs check"
        val line: String get() = when {
            problems.isNotEmpty() -> "Needs check: ${problems.size} of $total results could not be verified — ${problems.take(3).joinToString("; ") { it.detail }}"
            checks.any { it.note.isNotBlank() } -> "Verified: $verified/$total worker results checked — ${checks.count { it.note.isNotBlank() }} number(s) from a single source"
            else -> "Verified: $verified/$total worker results checked"
        }
    }

    private val wroteLine = Regex("^Wrote\\s+(\\S+)")
    private val exitCode = Regex("exit\\s+(\\d+)")
    private val testCmd = Regex("Test\\s+`([^`]+)`")

    /** Verify every fact of a merged fan-out. No facts at all = nothing to check (not a pass). */
    fun verify(result: FanOutResult, workspace: Workspace, results: List<WorkerResult> = emptyList()): Result {
        if (result.facts.isEmpty()) return Result(emptyList())
        val roles = results.associate { it.workerId to it.role }
        val checks = result.facts.map { fact -> checkFact(fact, roles[fact.workerId] ?: WorkerRole.RESEARCHER, workspace) }.toMutableList()
        // CitationCheck over the merged research facts. A hard violation (uncited claim,
        // dangling citation) flips its fact's check to failed; a key number from a single
        // source is annotated "(single source)" — exactly how CitationCheck.annotate treats
        // it — without failing the check.
        for ((fact, violation) in citationProblems(result.facts)) {
            val i = checks.indexOfFirst { it.fact === fact || it.fact.text == fact.text }
            if (i < 0) continue
            when (violation.kind) {
                CitationCheck.ViolationKind.SINGLE_SOURCE_NUMBER ->
                    if (checks[i].ok) checks[i] = checks[i].copy(note = "(single source)")
                else -> if (checks[i].ok) checks[i] = checks[i].copy(ok = false, detail = "citation: ${violation.detail.take(160)}")
            }
        }
        return Result(checks)
    }

    private fun checkFact(fact: WorkerFact, role: WorkerRole, workspace: Workspace): Check = when (role) {
        WorkerRole.RESEARCHER -> {
            val ok = fact.sourceUrl.startsWith("http://") || fact.sourceUrl.startsWith("https://")
            Check(fact.workerId, "research", ok, if (ok) "" else "research fact has no source URL: ${fact.text.take(120)}", fact)
        }
        WorkerRole.CODER -> {
            val path = wroteLine.find(fact.text)?.groupValues?.get(1)
            if (path == null) Check(fact.workerId, "write", true, "", fact) // a read note, not a write claim
            else {
                val f = runCatching { workspace.resolve(path) }.getOrNull()
                val ok = f != null && f.isFile && f.length() > 0
                Check(fact.workerId, "write", ok, if (ok) "" else "file missing or empty after write: $path", fact)
            }
        }
        WorkerRole.TESTER -> {
            val ok = exitCode.containsMatchIn(fact.text)
            Check(fact.workerId, "test", ok, if (ok) "" else "test result has no exit code (did it run?): ${fact.text.take(120)}", fact)
        }
        WorkerRole.REVIEWER -> {
            val path = fact.sourceUrl
            if (path.isBlank()) Check(fact.workerId, "review", true, "", fact)
            else {
                val f = runCatching { workspace.resolve(path) }.getOrNull()
                val ok = f != null && f.isFile
                Check(fact.workerId, "review", ok, if (ok) "" else "reviewed file not found: $path", fact)
            }
        }
    }

    /**
     * Runs the real citation checker over the research facts: facts become bullets under
     * "## Key findings" with [n] citations and a "## Sources" list, exactly the shape
     * DeepResearchTool feeds [CitationCheck.verify]. Returns each violation with the fact
     * it belongs to (matched by the claim text the checker quotes).
     */
    fun citationProblems(facts: List<WorkerFact>): List<Pair<WorkerFact, CitationCheck.Violation>> {
        val research = facts.filter { it.sourceUrl.startsWith("http") }
        if (research.isEmpty()) return emptyList()
        val report = buildString {
            appendLine("## Key findings")
            research.forEachIndexed { i, f -> appendLine("- ${f.text.take(400)} [${i + 1}]") }
            appendLine()
            appendLine("## Sources")
            research.forEachIndexed { i, f -> appendLine("${i + 1}. ${f.text.take(60)} — ${f.sourceUrl}") }
        }
        return CitationCheck.verify(report).violations.mapNotNull { v ->
            val fact = research.firstOrNull { f -> v.detail.contains(f.text.take(100)) } ?: research.firstOrNull() ?: return@mapNotNull null
            fact to v
        }
    }

    /**
     * ONE bounded fix attempt, from the problems: at most 2 fix workers, each owning one
     * failed part. The caller runs them through the normal fan-out (caps still apply) and
     * re-verifies; there is no second retry.
     */
    fun fixSpecs(problems: List<Check>, topic: String): List<WorkerTask> {
        return problems.take(2).mapIndexed { i, p ->
            when (p.kind) {
                "research" -> WorkerTask(
                    id = "fix-r${i + 1}", topic = topic, angle = "verify: ${p.fact.text.take(100)}",
                    role = WorkerRole.RESEARCHER,
                    brief = "Find a citable source URL for this claim, or say plainly it cannot be verified: ${p.fact.text.take(300)}",
                )
                "write" -> {
                    val path = wroteLine.find(p.fact.text)?.groupValues?.get(1).orEmpty()
                    WorkerTask(
                        id = "fix-w${i + 1}", topic = topic, angle = "rewrite: $path",
                        role = WorkerRole.CODER,
                        brief = "Write the file $path completely — the previous attempt left it missing or empty. Reply with READ: lines if you must see anything first, then WRITE: $path followed by the full file content in one fenced code block.",
                    )
                }
                "test" -> {
                    val cmd = testCmd.find(p.fact.text)?.groupValues?.get(1).orEmpty()
                    WorkerTask(
                        id = "fix-t${i + 1}", topic = topic, angle = "re-run test",
                        role = WorkerRole.TESTER,
                        brief = if (cmd.isNotBlank()) "Re-run this test command and report its exit code: $cmd" else "Re-run the failed test and report its exit code.",
                    )
                }
                else -> {
                    val path = p.fact.sourceUrl
                    WorkerTask(
                        id = "fix-v${i + 1}", topic = topic, angle = "re-review: $path",
                        role = WorkerRole.REVIEWER,
                        brief = "Re-read this file and report your findings. READ: $path",
                    )
                }
            }
        }
    }

    /** Merge two fan-outs' facts, deduped by text then URL (first worker wins) — for the retry. */
    fun mergeFacts(a: List<WorkerFact>, b: List<WorkerFact>): List<WorkerFact> {
        val seen = HashSet<String>()
        return (a + b).filter { f -> seen.add(f.text + "|" + f.sourceUrl) }
    }
}
