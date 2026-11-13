package com.mazharansari.axion.work

import kotlinx.serialization.Serializable

/**
 * P1-8 — long runs split across worker invocations.
 *
 * WorkManager stops a worker after about 10 minutes. P0-9 handled that by
 * reporting the partial result honestly; this is the other half: when a run
 * hits the budget, its progress is checkpointed and the NEXT invocation
 * resumes from it instead of starting over. A checkpoint is the run's own
 * memory: which schedule, which run, how many segments have already run,
 * and the partial answer so far.
 *
 * Bounds: at most [MAX_SEGMENTS] invocations per run. After that the run ends
 * with a failure record carrying the partial result — it never loops forever.
 * The partial text is capped ([MAX_PARTIAL_CHARS]) so the checkpoint file
 * stays small and the resumed prompt stays inside the context budget.
 */
@Serializable
data class RunCheckpoint(
    val scheduleId: String,
    val runId: String,
    /** Segments already run (1 after the first invocation hit the budget). */
    val invocation: Int,
    val startedAtMs: Long,
    val prompt: String,
    val partialText: String,
    val stepCount: Int,
    val savedAtMs: Long,
)

object RunCheckpointPolicy {
    /** A run gets this many worker invocations at most, then it ends with a failure record. */
    const val MAX_SEGMENTS = 3

    /** Partial text kept in a checkpoint (and fed back into the resumed prompt). */
    const val MAX_PARTIAL_CHARS = 4000

    /** True when [checkpoint] has used up its segments and the run must end, not resume. */
    fun isExhausted(checkpoint: RunCheckpoint?): Boolean =
        checkpoint != null && checkpoint.invocation >= MAX_SEGMENTS

    /** True when [elapsedMs] has used the worker's budget and the run should checkpoint, not die. */
    fun shouldSplit(elapsedMs: Long, budgetMs: Long): Boolean = elapsedMs >= budgetMs

    /** Merges the previous checkpoint's text with this invocation's new text, capped. */
    fun mergePartial(prior: String, new: String): String {
        val combined = when {
            prior.isBlank() -> new
            new.isBlank() -> prior
            else -> "$prior\n\n[continued]\n$new"
        }
        return combined.takeLast(MAX_PARTIAL_CHARS)
    }

    /**
     * The prompt the next invocation runs: the original task, plus what the
     * run already produced, so the agent continues instead of repeating work.
     */
    fun continuationPrompt(originalPrompt: String, checkpoint: RunCheckpoint): String = buildString {
        append("Continue this scheduled task from where the previous run stopped. ")
        append("Do NOT repeat work already done — pick up after the partial result below and finish the task.\n\n")
        append("Original task: ").append(originalPrompt).append("\n\n")
        append("Result so far (part ${checkpoint.invocation} of $MAX_SEGMENTS):\n")
        append(checkpoint.partialText.ifBlank { "(no text was produced yet)" })
    }

    /** The checkpoint to save when an invocation ends at the budget, carrying [partialText] forward. */
    fun nextSegment(previous: RunCheckpoint?, scheduleId: String, runId: String, startedAtMs: Long, prompt: String, partialText: String, stepCount: Int, nowMs: Long): RunCheckpoint {
        val prior = previous?.partialText.orEmpty()
        return RunCheckpoint(
            scheduleId = scheduleId,
            runId = runId,
            invocation = (previous?.invocation ?: 0) + 1,
            startedAtMs = startedAtMs,
            prompt = prompt,
            partialText = mergePartial(prior, partialText),
            stepCount = stepCount,
            savedAtMs = nowMs,
        )
    }
}
