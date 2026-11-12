package com.mazharansari.axion.work

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mazharansari.axion.data.ScheduleEntry
import com.mazharansari.axion.data.ScheduleType
import com.mazharansari.axion.data.SchedulesStore
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

    fun arm(ctx: Context, e: ScheduleEntry) {
        if (!e.enabled) { disarm(ctx, e.id); return }
        val wm = WorkManager.getInstance(ctx)
        val data = Data.Builder().putString(KEY_ID, e.id).build()
        val delay = (nextRunMs(e) - System.currentTimeMillis()).coerceAtLeast(MIN_DELAY_MS)
        when (e.type) {
            ScheduleType.ONCE -> {
                val req = OneTimeWorkRequestBuilder<ScheduledRunWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(data).addTag(e.id).build()
                wm.enqueueUniqueWork(workName(e.id), ExistingWorkPolicy.REPLACE, req)
            }
            ScheduleType.DAILY, ScheduleType.WEEKLY -> {
                val period = if (e.type == ScheduleType.DAILY) 24L else 7L
                val unit = if (e.type == ScheduleType.DAILY) TimeUnit.HOURS else TimeUnit.DAYS
                val req = PeriodicWorkRequestBuilder<ScheduledRunWorker>(period, unit)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(data).addTag(e.id).build()
                wm.enqueueUniquePeriodicWork(workName(e.id), ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, req)
            }
        }
    }

    fun disarm(ctx: Context, id: String) {
        runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(workName(id)) }
    }

    /** Re-arms everything enabled (app start — WorkManager already persists jobs; this also heals edits made while the process was dead). */
    fun armAll(ctx: Context, store: SchedulesStore) {
        store.entries.value.forEach { if (it.enabled) arm(ctx, it) }
    }
}
