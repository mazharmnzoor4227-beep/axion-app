package com.mazharansari.axion.agent

import com.mazharansari.axion.data.Conversation
import com.mazharansari.axion.data.ConversationStore
import com.mazharansari.axion.data.Message
import com.mazharansari.axion.data.MsgStatus
import com.mazharansari.axion.data.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PHASE 1 SWARM — the Bots section's live ledger.
 *
 * Any bot can create another bot: the parent agent does its own work AND spawns workers,
 * and the user (or the creating bot) NAMES it — "CEO" is just one possible name, not a
 * built-in role. There is no special CEO class: the parent/child relationship plus the
 * given name is what makes a bot the parent for its run. This registry is the single
 * place the UI reads to show every live bot — each parent run (under its given name) and
 * each worker it spawned — with role, task, live status, steps used and result. It lives
 * in the app graph, so it outlives any single screen: leaving the chat for the Bots
 * section, a worker chat, or another app never loses the list. The runs themselves stay
 * alive through the existing [com.mazharansari.axion.work.RunRegistry] + WorkService
 * foreground service; this file only tracks and shows them.
 *
 * Spawning is VISIBLE by construction: the orchestrator emits a RUNNING [WorkerUpdate] the
 * moment a worker is created, and [onWorkerUpdate] turns it into a card with status
 * STARTING at once; the worker's first real step promotes it to WORKING (see
 * [ToolContext.onWorkerProgress]).
 *
 * A worker's transcript is an ordinary [Conversation] in the same [ConversationStore] as
 * normal chats (section "worker", hidden from the chat drawer) — no second storage system.
 * It is saved when the worker starts (so its card can be tapped while it is still working)
 * and finished when the worker settles, so the chat stays viewable after the run ends.
 * Original Axion code; no code copied from any other project.
 */

/** Live status of one bot. STARTING = created, first step not yet done. */
enum class BotStatus(val label: String) {
    STARTING("Starting"),
    WORKING("Working"),
    DONE("Done"),
    FAILED("Failed"),
}

enum class BotKind { PARENT, WORKER }

/** One bot the user can see: a parent run (under its given name) or a worker it spawned. */
data class BotRun(
    /** Parent: the run id ("run-<aid>"); worker: the worker card id (the update id). */
    val id: String,
    val kind: BotKind,
    /** The parent bot's given name ("Axion" by default, or an installed/created bot's name). Workers leave this blank and show their role. */
    val name: String = "",
    /** Worker: the parent run's conversation id; parent: null. */
    val parentId: String?,
    /** Worker: the task id of the worker that spawned it (null = the parent bot spawned it). */
    val spawnedBy: String?,
    /** Worker: the WorkerTask.id (transcript/progress lookup); parent: null. */
    val workerId: String?,
    val role: WorkerRole?,
    val task: String,
    val status: BotStatus,
    val stepsUsed: Int,
    val summary: String,
    /** Parent: its chat; worker: its own transcript conversation. */
    val conversationId: String,
    val depth: Int,
    val startedAt: Long,
    val updatedAt: Long,
)

/** A worker transcript's conversation id, from its card id. Unique per swarm run. */
fun workerConversationId(updateId: String) = "worker-$updateId"

/**
 * Builds a worker transcript conversation: the brief as the user message, the worker's
 * step notes + result as the assistant message. Section "worker" keeps it out of the
 * normal chat list; the Bots section and the worker chat read it back.
 */
fun buildWorkerConversation(
    updateId: String,
    role: WorkerRole,
    brief: String,
    notes: List<String>,
    status: MsgStatus,
    summary: String,
    startedAt: Long,
): Conversation {
    val text = buildString {
        if (notes.isEmpty() && status == MsgStatus.STREAMING) append("Working…")
        else append(notes.joinToString("\n"))
        if (summary.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(if (status == MsgStatus.ERROR) "Failed: " else "Result: ").append(summary)
        }
    }
    return Conversation(
        id = workerConversationId(updateId),
        title = "Worker · ${role.label} — ${brief.take(40)}",
        messages = listOf(
            Message(id = "w-$updateId-q", role = Role.USER, text = brief, createdAt = startedAt),
            Message(id = "w-$updateId-a", role = Role.ASSISTANT, text = text, status = status, createdAt = startedAt),
        ),
        createdAt = startedAt,
        updatedAt = System.currentTimeMillis(),
        section = "worker",
    )
}

/** The Bots section's live ledger. Thread-safe: updates arrive from agent threads. */
class BotRegistry(private val chats: ConversationStore? = null) {

    private val lock = Any()
    private val _bots = MutableStateFlow<List<BotRun>>(emptyList())
    val bots: StateFlow<List<BotRun>> = _bots.asStateFlow()
    /** Card id -> transcript lines so far (cap keeps a long worker's transcript bounded). */
    private val notes = HashMap<String, MutableList<String>>()

    /**
     * A parent run starts: its card appears in the Bots section at once, under the bot's
     * given name ([name] — "Axion" by default, or an installed/created bot's name).
     */
    fun registerParent(runId: String, conversationId: String, name: String, task: String) {
        val now = System.currentTimeMillis()
        upsert(BotRun(runId, BotKind.PARENT, name = name, parentId = null, spawnedBy = null, workerId = null,
            role = null, task = task.ifBlank { "Working" }, status = BotStatus.WORKING,
            stepsUsed = 0, summary = "", conversationId = conversationId, depth = 0,
            startedAt = now, updatedAt = now))
    }

    fun parentStep(runId: String) = synchronized(lock) {
        _bots.value = _bots.value.map {
            if (it.id == runId && it.kind == BotKind.PARENT) it.copy(stepsUsed = it.stepsUsed + 1, updatedAt = System.currentTimeMillis()) else it
        }
    }

    fun parentFinished(runId: String, status: BotStatus, summary: String) = synchronized(lock) {
        _bots.value = _bots.value.map {
            if (it.id == runId && it.kind == BotKind.PARENT) it.copy(status = status, summary = summary.take(200), updatedAt = System.currentTimeMillis()) else it
        }
    }

    /** One live update from a worker: creates its card on first sight (STARTING), then moves it. */
    fun onWorkerUpdate(u: WorkerUpdate) {
        val now = System.currentTimeMillis()
        val run = synchronized(lock) {
            val existing = _bots.value.firstOrNull { it.id == u.updateId }
            val next = when {
                existing == null -> BotRun(
                    id = u.updateId, kind = BotKind.WORKER, parentId = u.parentId, spawnedBy = u.parentWorkerId,
                    workerId = u.workerId, role = u.role, task = u.task,
                    status = if (u.status == WorkerStatus.RUNNING) BotStatus.STARTING
                    else if (u.status == WorkerStatus.DONE) BotStatus.DONE else BotStatus.FAILED,
                    stepsUsed = u.stepsUsed, summary = u.summary,
                    conversationId = workerConversationId(u.updateId), depth = u.depth,
                    startedAt = now, updatedAt = now,
                )
                u.status == WorkerStatus.DONE -> existing.copy(status = BotStatus.DONE, stepsUsed = u.stepsUsed, summary = u.summary, updatedAt = now)
                u.status == WorkerStatus.FAILED -> existing.copy(status = BotStatus.FAILED, stepsUsed = u.stepsUsed, summary = u.summary, updatedAt = now)
                u.stepsUsed > 0 -> existing.copy(status = BotStatus.WORKING, stepsUsed = u.stepsUsed, updatedAt = now)
                else -> existing.copy(updatedAt = now)
            }
            upsert(next)
            next
        }
        saveTranscript(run)
    }

    /**
     * A worker's step note (what it just did). Promotes STARTING → WORKING and appends the
     * line to the worker's transcript, so the card and the chat move together.
     */
    fun onWorkerProgress(workerId: String, stepsUsed: Int, note: String) {
        val run = synchronized(lock) {
            val live = _bots.value.filter { it.kind == BotKind.WORKER && it.workerId == workerId && it.status != BotStatus.DONE && it.status != BotStatus.FAILED }.lastOrNull() ?: return
            val lines = notes.getOrPut(live.id) { mutableListOf() }
            if (lines.size < 40) lines += note.take(300)
            val next = live.copy(status = BotStatus.WORKING, stepsUsed = stepsUsed, updatedAt = System.currentTimeMillis())
            upsert(next)
            next
        }
        saveTranscript(run)
    }

    /** Workers of one parent run that are still going (for the notification title + parent card). */
    fun liveWorkerCount(parentConversationId: String): Int = _bots.value.count {
        it.kind == BotKind.WORKER && it.parentId == parentConversationId && (it.status == BotStatus.STARTING || it.status == BotStatus.WORKING)
    }

    fun workersOf(parentConversationId: String): List<BotRun> =
        _bots.value.filter { it.kind == BotKind.WORKER && it.parentId == parentConversationId }

    private fun upsert(run: BotRun) = synchronized(lock) {
        _bots.value = listOf(run) + _bots.value.filterNot { it.id == run.id }
    }

    private fun saveTranscript(run: BotRun) {
        val store = chats ?: return
        if (run.kind != BotKind.WORKER || run.role == null) return
        val lines = synchronized(lock) { notes[run.id]?.toList().orEmpty() }
        val status = when (run.status) {
            BotStatus.DONE -> MsgStatus.DONE
            BotStatus.FAILED -> MsgStatus.ERROR
            else -> MsgStatus.STREAMING
        }
        val terminal = run.status == BotStatus.DONE || run.status == BotStatus.FAILED
        store.save(
            buildWorkerConversation(run.id, run.role, run.task, lines, status, run.summary, run.startedAt),
            immediate = terminal,
        )
    }
}
