package com.mazharansari.axion.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v0.6 (Phase 2.4) FAULT TOLERANCE. Idea from JetBrains Koog (Apache-2.0): fault-tolerant tool execution (retry, then recover instead of crashing).
 * Original Kotlin, pure and unit-tested (FaultToleranceTest). The rules, in order:
 *  1. a failing tool call is RETRIED ONCE after a short pause;
 *  2. if it fails again the model is told to try an ALTERNATIVE (other tool / other arguments) — at most [maxAlternatives] such alternatives per run;
 *  3. only then the failure is REPORTED with its reason (the model gets "stop calling tools, explain to the user");
 *  and it can never hang or loop: every call has a timeout, the same call+same error twice is not run a third time, and a run has a hard tool-call budget.
 */
class FaultPolicy(
    private val maxAlternatives: Int = 2,
    private val maxToolCalls: Int = 40,
    private val retryPauseMs: Long = 600,
) {
    enum class Kind { OK, RETRIED_OK, FAILED, SKIPPED_REPEAT, TIMED_OUT, BUDGET }
    data class Outcome(val output: ToolOutput, val kind: Kind, val attempts: Int, /** text for the model (output + guidance) */ val forModel: String, /** true = stop calling tools and report */ val mustReport: Boolean)

    private val failed = HashMap<String, Int>()      // "tool|args|error" -> times seen
    private var calls = 0
    var alternativesUsed = 0; private set
    var consecutiveFailures = 0; private set

    private fun sig(tool: String, args: String, err: String) = tool + "|" + args + "|" + err.take(120)
    private fun errText(o: ToolOutput) = o.forModel.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("[exit") } ?: o.forModel.take(120)

    suspend fun execute(tool: String, argsKey: String, timeoutMs: Long, onRetry: (String) -> Unit = {}, run: suspend () -> ToolOutput): Outcome {
        if (++calls > maxToolCalls)
            return Outcome(ToolOutput("Tool-call budget reached.", isError = true), Kind.BUDGET, 0, "Tool-call budget for this task is used up. Do not call more tools: tell the user what was done and what is left.", true)
        // never repeat a call that already failed identically (checked on the args alone: any recorded failure of this exact call)
        failed.entries.firstOrNull { it.key.startsWith("$tool|$argsKey|") && it.value >= 2 }?.let {
            return Outcome(ToolOutput("Skipped: this exact call already failed twice.", isError = true), Kind.SKIPPED_REPEAT, 0,
                "Skipped — this exact $tool call already failed twice (${it.key.substringAfterLast('|')}). Do NOT repeat it: use a different tool or different arguments, or explain the problem to the user.", ++consecutiveFailures >= 3)
        }
        var attempt = 0; var last: ToolOutput = ToolOutput("", isError = true); var timedOut = false
        while (attempt < 2) {
            attempt++; timedOut = false
            val r = try {
                withTimeoutOrNull(timeoutMs) { run() }.also { if (it == null) timedOut = true } ?: ToolOutput("Timed out after ${timeoutMs / 1000} s.", isError = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { ToolOutput("Tool error: ${e.message ?: e.javaClass.simpleName}", isError = true) }
            last = r
            if (!r.isError) { consecutiveFailures = 0; return Outcome(r, if (attempt > 1) Kind.RETRIED_OK else Kind.OK, attempt, r.forModel, false) }
            // a command that RAN and exited non-zero is a result the model should read, not a glitch to repeat blindly
            if (r.exitCode != null && r.exitCode > 0) break
            // v0.6.1: a phone action that timed out may already have happened on the phone — never blindly do it twice
            if (timedOut && tool.startsWith("phone_")) break
            if (attempt == 1) { onRetry("Retrying…"); delay(retryPauseMs) }
        }
        // v0.6.1: a safety refusal (exit 126 — banking block, password screen, the user did not CONFIRM) is not a fault and not an invitation
        // to find a way around it: no alternative-approach pressure, no failure streak, the refusal text itself tells the model what to do.
        if (last.exitCode == 126) return Outcome(last, Kind.FAILED, attempt, last.forModel, false)
        val err = errText(last); val key = sig(tool, argsKey, err); failed[key] = (failed[key] ?: 0) + attempt
        consecutiveFailures++
        val canAlt = alternativesUsed < maxAlternatives && consecutiveFailures < 3
        val guidance = if (canAlt) { alternativesUsed++
            "\n\n[System note: $tool failed ${if (attempt > 1) "twice" else "(exit ${last.exitCode})"} (${err.take(160)}). Do not repeat this call. Try a DIFFERENT approach: another tool or different arguments (alternative ${alternativesUsed} of $maxAlternatives). If nothing else can work, explain the problem to the user.]"
        } else "\n\n[System note: $tool failed ${if (attempt > 1) "twice" else "(exit ${last.exitCode})"} (${err.take(160)}) and no alternatives are left. STOP calling tools now and tell the user plainly what failed and why, and what they could try.]"
        return Outcome(last, if (timedOut) Kind.TIMED_OUT else Kind.FAILED, attempt, last.forModel + guidance, !canAlt)
    }

    /** Per-tool time limit: a command may ask for up to 600 s itself, video edits take minutes, everything else gets 90 s. */
    companion object {
        fun timeoutFor(tool: String, args: kotlinx.serialization.json.JsonObject): Long = when (tool) {
            "run_command" -> ((args.i("timeout_sec") ?: 60).coerceIn(1, 600) + 40) * 1000L
            "video_edit" -> 1_700_000L
            "video_probe" -> 120_000L
            "generate_image" -> 150_000L
            "install_skills_from_github" -> 150_000L
            "phone_tap" -> 180_000L   // v0.6.1: an irreversible tap may wait up to 90 s for the user's own CONFIRM press
            else -> 90_000L
        }
    }
}
