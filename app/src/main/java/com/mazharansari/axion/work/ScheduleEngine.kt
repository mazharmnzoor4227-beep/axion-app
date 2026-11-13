package com.mazharansari.axion.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
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
 * Honest limits of this design (no exact-alarm permission, on purpose): Android
 * may run a job some minutes late (battery optimisation, Doze), periodic jobs
 * repeat every 24 h / 7 d from their first run, and nothing fires while the
 * phone is off — a missed one-off simply runs when the phone is back. That is
 * the trade for not asking for intrusive alarm permissions.
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
 * What this does NOT do: there is no SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM
 * permission in the manifest and no AlarmManager path in the app, so exact
 * wall-clock firing cannot be guaranteed — the anchor stops restart-driven
 * drift and dropped runs, it does not defeat Doze. Exact alarms are a later
 * roadmap item (Scheduler v2); adding that permission needs the user's
 * approval first.
 */
object ScheduleEngine {
    const val KEY_ID = "schedule_id"
    private const val MIN_DELAY_MS = 60_000L

    fun workName(id: String) = "axion-schedule-$id"

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
     */
    fun arm(ctx: Context, e: ScheduleEntry, store: SchedulesStore) {
        if (!e.enabled) { disarm(ctx, e.id); return }
        val target = anchorFor(e)
        if (target != e.anchorEpochMs) store.setAnchor(e.id, target)
        enqueue(ctx, e, target, replace = true)
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
        when (e.type) {
            ScheduleType.ONCE -> {
                val req = OneTimeWorkRequestBuilder<ScheduledRunWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(data).addTag(e.id)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build()
                wm.enqueueUniqueWork(workName(e.id), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
            }
            ScheduleType.DAILY, ScheduleType.WEEKLY -> {
                val period = if (e.type == ScheduleType.DAILY) 24L else 7L
                val unit = if (e.type == ScheduleType.DAILY) TimeUnit.HOURS else TimeUnit.DAYS
                val req = PeriodicWorkRequestBuilder<ScheduledRunWorker>(period, unit)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(data).addTag(e.id)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build()
                wm.enqueueUniquePeriodicWork(workName(e.id),
                    if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP, req)
            }
        }
    }

    fun disarm(ctx: Context, id: String) {
        runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(workName(id)) }
    }

    /**
     * Re-arms everything enabled (app start — WorkManager already persists jobs).
     * P0-7: this NEVER cancels existing work. A pending or running job is left
     * alone (KEEP), so an app restart can no longer drop today's run or kill a
     * run in flight; only a schedule with no WorkManager job at all is enqueued.
     * The stored anchor is rolled forward when it is stale, and a schedule whose
     * stored anchor no longer matches its own wall-clock settings (edited since
     * it was armed) is re-armed with REPLACE so the new time takes effect.
     */
    fun armAll(ctx: Context, store: SchedulesStore) {
        val now = System.currentTimeMillis()
        store.entries.value.forEach { e ->
            if (!e.enabled) return@forEach
            val target = anchorFor(e, now)
            if (target != e.anchorEpochMs) store.setAnchor(e.id, target)
            val edited = e.anchorEpochMs != 0L && !anchorMatches(e, e.anchorEpochMs)
            enqueue(ctx, e, target, replace = edited)
        }
    }
}
