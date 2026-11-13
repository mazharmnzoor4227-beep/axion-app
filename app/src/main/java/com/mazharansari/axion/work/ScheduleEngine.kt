package com.mazharansari.axion.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mazharansari.axion.data.ScheduleEntry
import com.mazharansari.axion.data.ScheduleType
import com.mazharansari.axion.data.SchedulesStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * v0.12: arms/disarms [ScheduleEntry]s in WorkManager.
 *
 * Honest limits that remain: nothing fires while the phone is switched off
 * (a missed run starts when the phone is back on), and the inexact
 * WorkManager path can still run some minutes late in Doze. Exact alarms
 * defeat Doze for the next fire, but they are per-fire and need the system
 * to allow them — see [ExactAlarms].
 *
 * P0-7 (HI-5) — why restarts used to drift schedules, and what changed:
 * every app start called [armAll], and [arm] used CANCEL_AND_REENQUEUE with a
 * freshly computed initial delay. Cancelling a job that was pending but had
 * not fired yet (e.g. the user opened the app just after the set time, while
 * Doze was still holding the job) silently dropped that run — the next fire
 * became tomorrow / next week — and cancelling a job mid-run killed the run
 * with no record. [armAll] now uses KEEP: existing work is left completely
 * alone and only missing work is enqueued. A stable anchor
 * ([ScheduleEntry.anchorEpochMs]) records the next intended wall-clock fire;
 * [arm] (an explicit user action: create / re-enable) still re-arms with
 * REPLACE so an edited time takes effect at once, and [armAll] re-arms only
 * when the entry's own settings no longer match its stored anchor (i.e. the
 * entry was edited since it was armed).
 *
 * P1-8 (Scheduler v2 — the proper fix for HI-5/HI-7): the exact-alarm path is
 * now here ([ExactAlarms]): when an entry asks for exact timing and the system
 * allows it, [arm] arms one exact alarm for the next fire instead of a
 * WorkManager job (the alarm receiver hands the run to the worker at once and
 * re-arms the next occurrence). When exact alarms are NOT allowed, the entry
 * keeps the WorkManager path and the Schedules screen says the timing is
 * inexact — the app never pretends exact timing it does not have. Entries can
 * also require network ([ScheduleEntry.requireNetwork]): the WorkManager job
 * carries a CONNECTED constraint, and the worker checks again before running.
 * Retries stay bounded with exponential backoff ([RetryPolicy]); long runs
 * checkpoint and resume across worker invocations ([RunCheckpointPolicy]).
 *
 * P0-7's anchor still holds for both paths: the anchor records the next
 * intended wall-clock fire, and [armAll] never drops a pending run or kills
 * a run in flight — switching triggers replaces one trigger with another for
 * the SAME anchor.
 */
object ScheduleEngine {
    const val KEY_ID = "schedule_id"
    private const val MIN_DELAY_MS = 60_000L

    fun workName(id: String) = "axion-schedule-$id"

    /** Unique name for the one-time work an exact alarm fires (never the periodic job's name). */
    fun exactWorkName(id: String) = "axion-schedule-$id-exact"

    /** The next fire time (epoch ms) for an entry, strictly after [fromMs] (except ONCE, which is fixed). */
    fun nextRunMs(e: ScheduleEntry, fromMs: Long = System.currentTimeMillis()): Long {
        if (e.type == ScheduleType.ONCE) return e.atEpochMs
        val zone = ZoneId.systemDefault()
        val from = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(fromMs), zone)
        var next = from.toLocalDate().atTime(e.hour, e.minute)
        if (e.type == ScheduleType.WEEKLY) {
            val want = java.time.DayOfWeek.of(e.dayOfWeek.coerceIn(1, 7))
            next = next.with(TemporalAdjusters.nextOrSame(want))
            if (!next.isAfter(from)) next = next.with(TemporalAdjusters.next(want))
        } else {
            if (!next.isAfter(from)) next = next.plusDays(1)
        }
        return next.atZone(zone).toInstant().toEpochMilli()
    }

    /** Whole-period length for a repeating entry, or 0 for ONCE. */
    fun periodMs(e: ScheduleEntry): Long = when (e.type) {
        ScheduleType.DAILY -> 24L * 3_600_000L
        ScheduleType.WEEKLY -> 7L * 24L * 3_600_000L
        ScheduleType.ONCE -> 0L
    }

    /**
     * True when [anchorMs] is the wall-clock fire time this entry asks for:
     * same hour/minute, and for WEEKLY the same weekday; for ONCE, exactly
     * [ScheduleEntry.atEpochMs]. A stored anchor that fails this check means
     * the entry was edited (time/day changed) since it was armed.
     */
    fun anchorMatches(e: ScheduleEntry, anchorMs: Long): Boolean {
        if (anchorMs <= 0L) return false
        if (e.type == ScheduleType.ONCE) return anchorMs == e.atEpochMs
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(anchorMs), ZoneId.systemDefault())
        if (at.hour != e.hour || at.minute != e.minute) return false
        if (e.type == ScheduleType.WEEKLY && at.dayOfWeek.value != e.dayOfWeek.coerceIn(1, 7)) return false
        return true
    }

    /**
     * The stable anchor: the next intended fire time (epoch ms) for [e].
     * The stored anchor wins while it is still in the future and still matches
     * the entry's wall-clock settings; otherwise the next wall-clock occurrence
     * is computed ([nextRunMs]) — the anchor is derived from the entry itself,
     * so it never drifts with app restarts. Callers persist the returned value
     * with [SchedulesStore.setAnchor] when it differs from the stored one.
     */
    fun anchorFor(e: ScheduleEntry, fromMs: Long = System.currentTimeMillis()): Long {
        if (e.type == ScheduleType.ONCE) return e.atEpochMs
        val stored = e.anchorEpochMs
        if (stored > fromMs && anchorMatches(e, stored)) return stored
        return nextRunMs(e, fromMs)
    }

    /**
     * Explicit arm: the user (or agent) just created, edited or re-enabled this
     * entry, so the new time takes effect at once — REPLACE / CANCEL_AND_REENQUEUE
     * is correct here, and the stable anchor is stored first.
     *
     * P1-8: the trigger depends on the entry and the system. When the entry
     * asks for exact timing and the system allows exact alarms, one exact alarm
     * is armed for the next fire (and any WorkManager job for this schedule is
     * cancelled, so the run cannot fire twice). Otherwise the WorkManager path
     * below is used and any exact alarm is cancelled.
     */
    fun arm(ctx: Context, e: ScheduleEntry, store: SchedulesStore) {
        if (!e.enabled) { disarm(ctx, e.id); return }
        val target = anchorFor(e)
        if (target != e.anchorEpochMs) store.setAnchor(e.id, target)
        if (e.exactTiming && ExactAlarms.canScheduleExactAlarms(ctx)) {
            runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(workName(e.id)) }
            ExactAlarms.scheduleExact(ctx, e, target)
        } else {
            ExactAlarms.cancelExact(ctx, e.id)
            enqueue(ctx, e, target, replace = true)
        }
    }

    /**
     * P0-9: a worker that returns Result.retry() (transient agent error, see
     * [ScheduledRunWorker.decideOutcome]) backs off exponentially from 10
     * minutes, so a failing schedule does not hammer the network or the model
     * every few seconds.
     */
    private fun enqueue(ctx: Context, e: ScheduleEntry, targetMs: Long, replace: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        val data = Data.Builder().putString(KEY_ID, e.id).build()
        val delay = (targetMs - System.currentTimeMillis()).coerceAtLeast(MIN_DELAY_MS)
        // P1-8: a schedule that needs network waits for connectivity instead of
        // firing offline and failing. Off by default — local-model schedules
        // must still fire with no network at all.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (e.requireNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        when (e.type) {
            ScheduleType.ONCE -> {
                val req = OneTimeWorkRequestBuilder<ScheduledRunWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(data).addTag(e.id)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build()
                wm.enqueueUniqueWork(workName(e.id), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
            }
            ScheduleType.DAILY, ScheduleType.WEEKLY -> {
                val period = if (e.type == ScheduleType.DAILY) 24L else 7L
                val unit = if (e.type == ScheduleType.DAILY) TimeUnit.HOURS else TimeUnit.DAYS
                val req = PeriodicWorkRequestBuilder<ScheduledRunWorker>(period, unit)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(data).addTag(e.id)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build()
                wm.enqueueUniquePeriodicWork(workName(e.id),
                    if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP, req)
            }
        }
    }

    /** Cancels both triggers for a schedule: its WorkManager job, its exact alarm, and any exact-fired work. */
    fun disarm(ctx: Context, id: String) {
        runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(workName(id)) }
        runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(exactWorkName(id)) }
        ExactAlarms.cancelExact(ctx, id)
    }

    /**
     * Re-arms everything enabled (app start — WorkManager already persists jobs).
     * P0-7: this NEVER cancels existing work. A pending or running job is left
     * alone (KEEP), so an app restart can no longer drop today's run or kill a
     * run in flight; only a schedule with no WorkManager job at all is enqueued.
     * The stored anchor is rolled forward when it is stale, and a schedule whose
     * stored anchor no longer matches its own wall-clock settings (edited since
     * it was armed) is re-armed with REPLACE so the new time takes effect.
     *
     * P1-8: entries that ask for exact timing (and are allowed it) are re-armed
     * as exact alarms instead — setting the same exact alarm again is
     * idempotent, so this stays safe to run on every app start.
     */
    fun armAll(ctx: Context, store: SchedulesStore) {
        val now = System.currentTimeMillis()
        store.entries.value.forEach { e ->
            if (!e.enabled) return@forEach
            val target = anchorFor(e, now)
            if (target != e.anchorEpochMs) store.setAnchor(e.id, target)
            if (e.exactTiming && ExactAlarms.canScheduleExactAlarms(ctx)) {
                // The exact alarm REPLACES the WorkManager job for the same anchor:
                // cancelling the job here cannot drop the run (the alarm carries
                // the same fire time), it only stops the run from firing twice.
                // P0-7 still holds: a job that is RUNNING right now is never
                // cancelled — its run finishes, and a later armAll cancels the
                // then-idle job (at most one more cycle can double-trigger,
                // only when the trigger switched mid-run).
                val running = runCatching {
                    WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(workName(e.id)).get()
                        .any { it.state == WorkInfo.State.RUNNING }
                }.getOrDefault(false)
                if (!running) runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(workName(e.id)) }
                ExactAlarms.scheduleExact(ctx, e, target)
                return@forEach
            }
            // Exact timing is off or not allowed: the WorkManager job is the
            // trigger, so a leftover exact alarm must go (same reasoning).
            ExactAlarms.cancelExact(ctx, e.id)
            val edited = e.anchorEpochMs != 0L && !anchorMatches(e, e.anchorEpochMs)
            enqueue(ctx, e, target, replace = edited)
        }
    }
}
