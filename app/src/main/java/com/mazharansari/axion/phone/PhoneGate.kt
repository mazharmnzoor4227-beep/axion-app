package com.mazharansari.axion.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v0.6 (Phase 3.2) RUN TOKEN. Phone actions work only while a token is active, and a token exists only while the user's own chat request is being
 * worked on (the ViewModel begins it when a turn starts and ends it when the turn ends or Stop is pressed). So nothing can act in the background:
 * no token (after Stop, after the turn, after the time/action limit) → every phone tool refuses at once. Unit-tested (PhoneControlTest).
 *
 * v0.6.1: the gate also owns USER CONFIRMATION for irreversible taps. The model can never confirm anything itself: an irreversible tap parks here
 * until the user personally presses the CONFIRM button on the control strip (see [awaitUserConfirm]) — one pending request at a time, and it arms
 * exactly the one tap that is waiting. STOP / end of run / timeout all answer "no".
 */
class PhoneGate(
    private val clock: () -> Long = System::currentTimeMillis,
    val maxActions: Int = 60,
    val maxMs: Long = 15 * 60_000L,
    val confirmTimeoutMs: Long = 90_000L,
) {
    @Volatile private var active = false
    @Volatile private var stoppedReason: String? = null
    @Volatile private var startedAt = 0L
    @Volatile private var actions = 0
    private val lines = ArrayList<String>()
    @Volatile var onChange: (() -> Unit)? = null

    val isActive get() = active && stoppedReason == null

    @Synchronized fun begin() { cancelPending(); active = true; stoppedReason = null; startedAt = clock(); actions = 0; lines.clear(); onChange?.invoke() }
    @Synchronized fun end() { cancelPending(); active = false; onChange?.invoke() }
    /** The STOP button / Stop in chat / a safety trip. Takes effect on the very next check — no waiting for a model call. */
    @Synchronized fun stop(reason: String) { cancelPending(); if (active && stoppedReason == null) { stoppedReason = reason; log("■ $reason") } }

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

    // ---- user confirmation (v0.6.1): the ONLY way an irreversible tap proceeds ----
    class PendingConfirm(val key: String, val label: String) { val answer = CompletableDeferred<Boolean>() }
    @Volatile var pendingConfirm: PendingConfirm? = null; private set

    /**
     * Parks the caller until the user presses CONFIRM on the control strip for exactly this [label], or the wait ends
     * (timeout, STOP, run end). Returns true only on the user's own press. There is no model-side way to answer this.
     */
    suspend fun awaitUserConfirm(key: String, label: String): Boolean {
        if (!isActive) return false
        val pc = PendingConfirm(key, label)
        synchronized(this) { pendingConfirm = pc }
        log("⏸ Tap CONFIRM on the strip to allow: \"${label.take(40)}\"")
        onChange?.invoke()
        val ok = try { withTimeoutOrNull(confirmTimeoutMs) { pc.answer.await() } ?: false }
            catch (e: CancellationException) { synchronized(this) { if (pendingConfirm === pc) pendingConfirm = null }; onChange?.invoke(); throw e }
        synchronized(this) { if (pendingConfirm === pc) pendingConfirm = null }
        onChange?.invoke()
        if (ok) log("✔ You confirmed: \"${label.take(40)}\"")
        return ok
    }

    /** The CONFIRM button on the control strip — a real user press, nothing else calls this. */
    fun confirmPending() { pendingConfirm?.let { if (!it.answer.isCompleted) it.answer.complete(true) } }
    fun cancelPending() { pendingConfirm?.let { if (!it.answer.isCompleted) it.answer.complete(false) } }
}
