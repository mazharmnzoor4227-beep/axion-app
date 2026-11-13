package com.mazharansari.axion.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.mazharansari.axion.data.ScheduleEntry

/**
 * P1-8 — exact alarms (the proper fix for HI-5's timing half, which P0-7
 * deliberately deferred).
 *
 * Honest platform facts, in one place:
 * - Android 12 (API 31) added SCHEDULE_EXACT_ALARM: an app that targets API 31+
 *   needs the permission (and the user can revoke it in system settings).
 *   This app targets API 28, so the system still grants exact alarms by
 *   default — [canScheduleExactAlarms] normally returns true today. The
 *   permission is declared anyway, and this flow exists, so exact timing keeps
 *   working when the target SDK is raised later or the permission is revoked.
 * - Even with an exact alarm, nothing fires while the phone is switched off:
 *   the alarm fires when the phone is back on, and a Doze-delayed inexact run
 *   is still possible when exact alarms are not allowed — the app never
 *   pretends exact timing it does not have (see [timingLabel]).
 * - Exact alarms are per fire, not per schedule: [scheduleExact] arms the next
 *   fire only. The receiver re-arms the next occurrence for repeating entries.
 */
object ExactAlarms {
    const val ACTION_ALARM = "com.mazharansari.axion.SCHEDULE_ALARM"
    const val EXTRA_SCHEDULE_ID = "schedule_id"

    /** True when the app may schedule an exact alarm right now. Below API 31 there is no such permission. */
    fun canScheduleExactAlarms(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return runCatching {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        }.getOrDefault(false)
    }

    /**
     * The system screen that grants exact alarms, or null below API 31 (there
     * is no such screen there). The Schedules screen offers this when exact
     * alarms are not allowed; if the user comes back without granting it, the
     * schedule keeps running inexact and says so — never silently exact.
     */
    fun settingsIntent(ctx: Context): Intent? {
        if (Build.VERSION.SDK_INT < 31) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }
    }

    /** What the Schedules screen tells the user about one entry's timing — exact, or inexact with the reason. */
    fun timingLabel(ctx: Context, entry: ScheduleEntry): String = when {
        !entry.exactTiming -> "Inexact timing — this schedule does not ask for exact timing, so it may run a few minutes late."
        canScheduleExactAlarms(ctx) -> "Exact timing — fires at the set time."
        Build.VERSION.SDK_INT < 31 -> "Exact timing — fires at the set time."
        else -> "Inexact timing — exact alarms are not allowed on this phone, so it may run a few minutes late. Allow exact alarms in system settings for exact timing."
    }

    private fun pendingIntent(ctx: Context, scheduleId: String): PendingIntent {
        val intent = Intent(ctx, ScheduleAlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
        }
        return PendingIntent.getBroadcast(
            ctx, scheduleId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Arms one exact alarm for [targetMs] (RTC_WAKEUP, allowed while idle). Replaces any alarm for this schedule. */
    fun scheduleExact(ctx: Context, entry: ScheduleEntry, targetMs: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pendingIntent(ctx, entry.id)) }
    }

    /** Cancels this schedule's exact alarm, if one is armed. */
    fun cancelExact(ctx: Context, scheduleId: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(ctx, scheduleId)) }
    }
}
