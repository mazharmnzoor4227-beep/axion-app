package com.mazharansari.axion.work

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * P1-8 — one checkpoint file per schedule, under filesDir/schedule-checkpoints/.
 * Only the worker touches this: it saves a checkpoint when a run hits the
 * ~10-minute budget and loads (then clears) it when the run finally settles.
 * A checkpoint is small (the partial text is capped by [RunCheckpointPolicy]),
 * and a stale one can never resurrect an old run — it is keyed by schedule id
 * and cleared on every terminal outcome.
 */
class ScheduleCheckpointStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "schedule-checkpoints").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(scheduleId: String) = File(dir, scheduleId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun load(scheduleId: String): RunCheckpoint? =
        runCatching { json.decodeFromString<RunCheckpoint>(file(scheduleId).readText()) }.getOrNull()

    fun save(checkpoint: RunCheckpoint) {
        runCatching { synchronized(dir) { file(checkpoint.scheduleId).writeText(json.encodeToString(checkpoint)) } }
    }

    fun clear(scheduleId: String) {
        runCatching { file(scheduleId).delete() }
    }
}
