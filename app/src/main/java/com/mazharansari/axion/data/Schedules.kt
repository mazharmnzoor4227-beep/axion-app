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

class SchedulesStore(ctx: Context) {
    private val file = File(ctx.filesDir, "schedules.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _entries = MutableStateFlow(runCatching { json.decodeFromString<List<ScheduleEntry>>(file.readText()) }.getOrDefault(emptyList()))
    val entries: StateFlow<List<ScheduleEntry>> = _entries.asStateFlow()
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

    fun markRan(id: String, status: String) = mutate { list ->
        list.map { if (it.id == id) it.copy(lastRunAt = System.currentTimeMillis(), lastStatus = status.take(200)) else it }
    }
}
