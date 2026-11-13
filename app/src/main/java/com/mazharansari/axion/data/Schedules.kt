package com.mazharansari.axion.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
enum class ScheduleType { ONCE, DAILY, WEEKLY }

/**
 * v0.12 (Hermes-style scheduler): one task Axion runs on its own at a set time.
 * The agent creates these with the schedule_* tools from the user's own words
 * ("kal subah 9 baje…", "har Monday…"); WorkManager does the waking, the entry
 * itself lives here so Settings → Schedules and the agent see the same list.
 */
@Serializable
data class ScheduleEntry(
    val id: String,
    val title: String,
    val prompt: String,
    val type: ScheduleType,
    val hour: Int,
    val minute: Int,
    /** WEEKLY only: 1 = Monday … 7 = Sunday (java.time.DayOfWeek value). */
    val dayOfWeek: Int = 1,
    /** ONCE only: the exact fire time. */
    val atEpochMs: Long = 0,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val lastRunAt: Long = 0,
    /** "" until it first runs; then "done" or a short error line. */
    val lastStatus: String = "",
    /**
     * P0-7 (HI-5) stable anchor: the epoch ms of the next intended fire, set when
     * the schedule is armed and rolled forward by ScheduleEngine.anchorFor.
     * It records INTENT (a wall-clock time), not WorkManager's actual first-run
     * time — WorkManager may still run minutes late in Doze, and the periodic
     * chain then repeats from that late run. What the anchor stops is
     * restart-driven drift: without it, every app start cancelled and
     * re-enqueued the job, dropping a pending run that had not fired yet.
     * 0 = never armed yet.
     */
    val anchorEpochMs: Long = 0,
    /**
     * P1-8 — run only when the phone is online. Off by default on purpose:
     * on-device/local models can run a scheduled task with no network at all,
     * and a hard network constraint would stop those schedules from ever firing.
     */
    val requireNetwork: Boolean = false,
    /**
     * P1-8 — ask for exact timing (an exact alarm where the system allows it).
     * When the system does not allow exact alarms, the schedule still runs —
     * inexact, and the Schedules screen says so instead of pretending exact.
     */
    val exactTiming: Boolean = true,
    /**
     * P1-8 — bounded retries for this schedule: the initial run plus this many
     * retries (0..5), with exponential backoff between them. Default 2.
     */
    val maxRetries: Int = 2,
    /**
     * P2-11 — a daily briefing entry: the worker composes the briefing (overnight
     * research, repo CI status, quota left) instead of running the agent on the
     * prompt. False for every ordinary schedule.
     */
    val digest: Boolean = false,
) {
    /** "Daily at 09:00" / "Every Monday at 09:00" / "Once, 12 Nov 2026 at 09:00". */
    fun describe(): String {
        val hm = "%02d:%02d".format(hour, minute)
        return when (type) {
            ScheduleType.DAILY -> "Daily at $hm"
            ScheduleType.WEEKLY -> "Every ${DAY_NAMES[(dayOfWeek - 1).coerceIn(0, 6)]} at $hm"
            ScheduleType.ONCE -> "Once, ${SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(atEpochMs))} at $hm"
        }
    }

    companion object {
        val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        fun dayOfWeek(name: String?): Int? = DAY_NAMES.indexOfFirst { it.equals(name?.trim(), ignoreCase = true) }.takeIf { it >= 0 }?.plus(1)
    }
}

/**
 * P1-8 — an explicit failure record for a scheduled run. Every terminal run
 * ends in either a saved result chat + notification or one of these, so a
 * run can never vanish silently (HI-7): the Schedules screen lists recent
 * failures, newest first.
 */
@Serializable
data class ScheduleFailure(
    val id: String,
    val scheduleId: String,
    val title: String,
    val error: String,
    val atMs: Long,
)

class SchedulesStore(ctx: Context) {
    private val file = File(ctx.filesDir, "schedules.json")
    private val failuresFile = File(ctx.filesDir, "schedule-failures.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _entries = MutableStateFlow(runCatching { json.decodeFromString<List<ScheduleEntry>>(file.readText()) }.getOrDefault(emptyList()))
    val entries: StateFlow<List<ScheduleEntry>> = _entries.asStateFlow()
    private val _failures = MutableStateFlow(runCatching { json.decodeFromString<List<ScheduleFailure>>(failuresFile.readText()) }.getOrDefault(emptyList()))
    /** P1-8: explicit failure records, newest first (see [ScheduleFailure]). */
    val failures: StateFlow<List<ScheduleFailure>> = _failures.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun mutate(f: (List<ScheduleEntry>) -> List<ScheduleEntry>) {
        _entries.value = f(_entries.value)
        val snap = _entries.value
        io.launch { synchronized(file) { runCatching { file.writeText(json.encodeToString(snap)) } } }
    }

    fun get(id: String): ScheduleEntry? = _entries.value.firstOrNull { it.id == id }

    fun add(e: ScheduleEntry) = mutate { it + e }

    fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    fun setEnabled(id: String, on: Boolean) = mutate { list -> list.map { if (it.id == id) it.copy(enabled = on) else it } }

    /** P0-7: persist the stable anchor for [id] (see [ScheduleEntry.anchorEpochMs]). */
    fun setAnchor(id: String, anchorMs: Long) = mutate { list -> list.map { if (it.id == id) it.copy(anchorEpochMs = anchorMs) else it } }

    /** P1-8: the schedule runs only when the phone is online. */
    fun setRequireNetwork(id: String, on: Boolean) = mutate { list -> list.map { if (it.id == id) it.copy(requireNetwork = on) else it } }

    /** P1-8: the schedule asks for exact timing where the system allows it. */
    fun setExactTiming(id: String, on: Boolean) = mutate { list -> list.map { if (it.id == id) it.copy(exactTiming = on) else it } }

    fun markRan(id: String, status: String) = mutate { list ->
        list.map { if (it.id == id) it.copy(lastRunAt = System.currentTimeMillis(), lastStatus = status.take(200)) else it }
    }

    /**
     * P1-8: records that a run is being retried (bounded, exponential backoff)
     * so the retry is visible on the schedule's card — the retry IS the same
     * run, so no result chat is saved yet.
     */
    fun recordRetry(id: String, attempt: Int, maxAttempts: Int, error: String, delayMs: Long) {
        val minutes = delayMs / 60_000L
        val whenText = if (minutes < 60) "about $minutes minutes" else "about ${minutes / 60} hours"
        markRan(id, "Retrying (attempt $attempt of $maxAttempts) — next try in $whenText: ${error.take(120)}")
    }

    /**
     * P1-8: appends an explicit failure record for a run that ended without a
     * saved result chat (for example the chat could not be saved). Kept to the
     * newest 50; the Schedules screen shows them.
     */
    fun recordFailure(scheduleId: String, title: String, error: String) {
        val record = ScheduleFailure(
            id = java.util.UUID.randomUUID().toString(),
            scheduleId = scheduleId, title = title,
            error = error.take(300), atMs = System.currentTimeMillis(),
        )
        _failures.value = (listOf(record) + _failures.value).take(50)
        val snap = _failures.value
        io.launch { synchronized(failuresFile) { runCatching { failuresFile.writeText(json.encodeToString(snap)) } } }
    }

    fun clearFailures() {
        _failures.value = emptyList()
        io.launch { synchronized(failuresFile) { runCatching { failuresFile.delete() } } }
    }
}
