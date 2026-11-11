package com.mazharansari.axion.agent

import com.mazharansari.axion.data.ScheduleEntry
import com.mazharansari.axion.data.ScheduleType
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.ToolSpec
import com.mazharansari.axion.work.ScheduleEngine
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * v0.12 — the Hermes-style scheduler as agent tools. The model fills the
 * structured fields from the user's own words ("kal subah 9 baje mujhe …",
 * "har Monday market rates check karna"); the entry is stored, armed in
 * WorkManager, and at the set time a fresh agent run does the task, saves the
 * result as a new chat and notifies the user.
 */

private fun noSchedule() = ToolOutput("Scheduling is not available in this surface.", isError = true)

private fun parseTime(raw: String?): Pair<Int, Int>? {
    val m = Regex("""^(\d{1,2})[:.](\d{2})$""").matchEntire(raw?.trim().orEmpty()) ?: return null
    val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) h to min else null
}

class ScheduleCreateTool : Tool {
    override val spec = ToolSpec("schedule_create",
        "Schedule a task for Axion to run later ON ITS OWN (the user does not need to be in the app). Use when the user asks for something at a time or repeating: 'kal subah 9 baje…', 'roz sham ko…', 'every Monday…'. Fill the fields from what the user said — resolve relative words like kal/parso/agle Monday into a concrete date/time yourself using today's date from your prompt. type: 'once' (needs date YYYY-MM-DD + time), 'daily' (time only), 'weekly' (weekday name + time). The task runs as a fresh chat; results are saved as a new conversation and the user is notified. Times are the phone's local time; Android may run it a few minutes late.",
        schema {
            str("title", "Short name for the schedule, e.g. 'Morning headlines'")
            str("prompt", "The full task Axion should do when it fires, written as an instruction to yourself")
            str("type", "'once', 'daily' or 'weekly'")
            str("time", "24-hour 'HH:MM', e.g. '09:00'")
            str("date", "For type 'once': 'YYYY-MM-DD'", required = false)
            str("weekday", "For type 'weekly': 'Monday'…'Sunday'", required = false)
        })
    override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Scheduling", args.s("title").orEmpty().take(48))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val store = ctx.schedules ?: return noSchedule()
        val appCtx = ctx.appCtx ?: return noSchedule()
        val title = args.s("title")?.trim().orEmpty()
        val prompt = args.s("prompt")?.trim().orEmpty()
        if (title.isBlank() || prompt.isBlank()) return ToolOutput("A schedule needs both a title and the task (prompt).", isError = true)
        val (hour, minute) = parseTime(args.s("time")) ?: return ToolOutput("Give the time as 24-hour HH:MM (e.g. 09:00).", isError = true)
        val typeName = args.s("type")?.trim()?.lowercase()
        val zone = ZoneId.systemDefault()
        val entry = when (typeName) {
            "daily" -> ScheduleEntry(UUID.randomUUID().toString(), title, prompt, ScheduleType.DAILY, hour, minute,
                createdAt = System.currentTimeMillis())
            "weekly" -> {
                val dow = ScheduleEntry.dayOfWeek(args.s("weekday"))
                    ?: return ToolOutput("For a weekly schedule, give the weekday ('Monday'…'Sunday').", isError = true)
                ScheduleEntry(UUID.randomUUID().toString(), title, prompt, ScheduleType.WEEKLY, hour, minute,
                    dayOfWeek = dow, createdAt = System.currentTimeMillis())
            }
            "once" -> {
                val date = runCatching { LocalDate.parse(args.s("date")?.trim()) }.getOrNull()
                    ?: return ToolOutput("For a one-off schedule, give the date as YYYY-MM-DD.", isError = true)
                val at = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
                if (at <= System.currentTimeMillis()) return ToolOutput("That date/time is already past — pick a future time.", isError = true)
                ScheduleEntry(UUID.randomUUID().toString(), title, prompt, ScheduleType.ONCE, hour, minute,
                    atEpochMs = at, createdAt = System.currentTimeMillis())
            }
            else -> return ToolOutput("type must be 'once', 'daily' or 'weekly'.", isError = true)
        }
        store.add(entry)
        ScheduleEngine.arm(appCtx, entry)
        val next = ScheduleEngine.nextRunMs(entry)
        val whenText = java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.ENGLISH).format(java.util.Date(next))
        return ToolOutput("Scheduled \"${entry.title}\" — ${entry.describe()}. First run: $whenText. When it fires, Axion does the task on its own, saves the result as a new chat and notifies the user. It is listed in Settings → Schedules, where it can be switched off or deleted.",
            doneTitle = "Scheduled: ${entry.title}")
    }
}

class ScheduleListTool : Tool {
    override val spec = ToolSpec("schedule_list",
        "List the user's scheduled tasks (id, title, when they run, on/off, last run). Use schedule_delete with an id from here to remove one.",
        schema {})
    override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Listing schedules", "")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val store = ctx.schedules ?: return noSchedule()
        val list = store.entries.value
        if (list.isEmpty()) return ToolOutput("No scheduled tasks yet. Create one with schedule_create.")
        val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.ENGLISH)
        val out = list.joinToString("\n") { e ->
            "- [${e.id}] \"${e.title}\" — ${e.describe()} — ${if (e.enabled) "ON" else "OFF"}" +
                (if (e.enabled) " — next: ${fmt.format(java.util.Date(ScheduleEngine.nextRunMs(e)))}" else "") +
                (if (e.lastRunAt > 0) " — last run ${fmt.format(java.util.Date(e.lastRunAt))}: ${e.lastStatus.ifBlank { "done" }}" else " — not run yet")
        }
        return ToolOutput("Scheduled tasks (${list.size}):\n$out", doneTitle = "${list.size} schedules")
    }
}

class ScheduleDeleteTool : Tool {
    override val spec = ToolSpec("schedule_delete",
        "Delete a scheduled task so it never runs again. Give its id (from schedule_list) or its exact title.",
        schema { str("id", "The schedule id, or its exact title") })
    override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Deleting schedule", args.s("id").orEmpty().take(48))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val store = ctx.schedules ?: return noSchedule()
        val appCtx = ctx.appCtx ?: return noSchedule()
        val key = args.s("id")?.trim().orEmpty()
        val entry = store.entries.value.firstOrNull { it.id == key } ?: store.entries.value.firstOrNull { it.title.equals(key, ignoreCase = true) }
            ?: return ToolOutput("No schedule matches \"$key\". Use schedule_list to see the ids.", isError = true)
        store.remove(entry.id)
        ScheduleEngine.disarm(appCtx, entry.id)
        return ToolOutput("Deleted the schedule \"${entry.title}\" (${entry.describe()}). It will not run again.", doneTitle = "Schedule deleted")
    }
}
