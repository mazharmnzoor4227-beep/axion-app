package com.mazharansari.axion.phone

/**
 * v0.6 (Phase 3.2) RUN TOKEN. Phone actions work only while a token is active, and a token exists only while the user's own chat request is being
 * worked on (the ViewModel begins it when a turn starts and ends it when the turn ends or Stop is pressed). So nothing can act in the background:
 * no token (after Stop, after the turn, after the time/action limit) → every phone tool refuses at once. Pure Kotlin, unit-tested.
 */
class PhoneGate(
    private val clock: () -> Long = System::currentTimeMillis,
    val maxActions: Int = 60,
    val maxMs: Long = 15 * 60_000L,
) {
    @Volatile private var active = false
    @Volatile private var stoppedReason: String? = null
    @Volatile private var startedAt = 0L
    @Volatile private var actions = 0
    private val lines = ArrayList<String>()
    @Volatile var onChange: (() -> Unit)? = null

    val isActive get() = active && stoppedReason == null

    @Synchronized fun begin() { active = true; stoppedReason = null; startedAt = clock(); actions = 0; lines.clear(); onChange?.invoke() }
    @Synchronized fun end() { active = false; onChange?.invoke() }
    /** The STOP button / Stop in chat / a safety trip. Takes effect on the very next check — no waiting for a model call. */
    @Synchronized fun stop(reason: String) { if (active && stoppedReason == null) { stoppedReason = reason; log("■ $reason") } }

    /** null = go ahead; otherwise why not (text for the model and the user). */
    @Synchronized fun check(): String? = when {
        !active -> "Phone control is not running: it only works while you are watching a task you asked for. Ask Axion again."
        stoppedReason != null -> "Stopped: $stoppedReason. Do not continue; tell the user where you got to."
        clock() - startedAt > maxMs -> { stop("time limit (${maxMs / 60000} min) reached"); "Stopped: time limit reached. Tell the user where you got to." }
        actions >= maxActions -> { stop("action limit ($maxActions) reached"); "Stopped: action limit reached. Tell the user where you got to." }
        else -> null
    }
    @Synchronized fun countAction() { actions++ }
    @Synchronized fun log(line: String) { lines += line.take(140); while (lines.size > 40) lines.removeAt(0); onChange?.invoke() }
    @Synchronized fun recent(n: Int = 3): List<String> = lines.takeLast(n)
    @Synchronized fun all(): List<String> = lines.toList()
    val actionCount get() = actions
}
