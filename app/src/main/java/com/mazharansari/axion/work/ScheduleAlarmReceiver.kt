package com.mazharansari.axion.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mazharansari.axion.AxionApp
import com.mazharansari.axion.data.ScheduleType

/**
 * P1-8 — the exact alarm's other end. An exact alarm cannot run the agent
 * itself (a receiver lives only seconds), so it hands the run to WorkManager
 * at once and, for a repeating schedule, arms the next exact alarm.
 *
 * The fired work uses its own unique name ([ScheduleEngine.exactWorkName]):
 * it must never replace the schedule's periodic WorkManager job, which is
 * the inexact fallback for the same schedule.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != ExactAlarms.ACTION_ALARM) return
        val id = intent.getStringExtra(ExactAlarms.EXTRA_SCHEDULE_ID) ?: return
        runCatching {
            val app = ctx.applicationContext as? AxionApp ?: return
            val store = app.graph.schedules
            val entry = store.get(id) ?: return
            if (!entry.enabled) return
            // Hand the run to the worker right now — no delay, no constraints beyond the entry's own.
            val data = Data.Builder().putString(ScheduleEngine.KEY_ID, id).build()
            val req = OneTimeWorkRequestBuilder<ScheduledRunWorker>()
                .setInputData(data).addTag(id).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                ScheduleEngine.exactWorkName(id), ExistingWorkPolicy.KEEP, req)
            // Repeating schedules: arm the next fire. The worker also rolls the
            // stored anchor forward when it records the run; the alarm is the trigger.
            if (entry.type != ScheduleType.ONCE) {
                // Next fire strictly after the one that just fired (never the same anchor twice).
                val fromMs = maxOf(System.currentTimeMillis(), entry.anchorEpochMs)
                val next = ScheduleEngine.anchorFor(entry, fromMs)
                store.setAnchor(id, next)
                if (entry.exactTiming && ExactAlarms.canScheduleExactAlarms(ctx)) {
                    ExactAlarms.scheduleExact(ctx, entry, next)
                } else {
                    // Arm with the FRESH anchor: the entry object above still
                    // carries the old one, and arming with it would re-fire
                    // the time that just passed.
                    ScheduleEngine.arm(ctx, entry.copy(anchorEpochMs = next), store)
                }
            }
        }
    }
}
