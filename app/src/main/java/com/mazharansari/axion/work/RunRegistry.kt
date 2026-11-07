package com.mazharansari.axion.work

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v0.8.0 (Phase C — background work): the app's single ledger of work in flight.
 *
 * Today a run lives and dies inside ChatViewModel's single job slot; this registry is what lets a
 * chat turn and a project turn run at the same time, lets the projects list show "Working" under a
 * project, and tells WorkService when to keep the process alive. Pure bookkeeping — it never starts
 * work itself; ChatViewModel registers the job it launches and unregisters when the turn settles.
 */
class RunRegistry {
    enum class Kind { ASK, BUILD }

    class Handle internal constructor(
        val id: String,
        val kind: Kind,
        /** The chat id (ASK) or project id (BUILD) this run belongs to — never the "currently open" one. */
        val ownerId: String?,
        val title: String,
        val startedAt: Long,
        internal val job: Job,
    )

    private val lock = Any()
    private val handles = LinkedHashMap<String, Handle>()

    private val _active = MutableStateFlow<List<Handle>>(emptyList())

    /** Every run in flight right now, in start order. The UI (Working marks) and WorkService read this. */
    val active: StateFlow<List<Handle>> = _active

    /** Fired on the 0->1 and 1->0 edges only: WorkService starts/stops with real work, never idles. */
    @Volatile var onFirstRunStarted: (() -> Unit)? = null
    @Volatile var onLastRunFinished: (() -> Unit)? = null

    fun register(id: String, kind: Kind, ownerId: String?, title: String, job: Job, startedAt: Long = System.currentTimeMillis()): Handle {
        val handle = Handle(id, kind, ownerId, title, startedAt, job)
        val wasEmpty: Boolean
        synchronized(lock) {
            wasEmpty = handles.isEmpty()
            handles[id] = handle
            _active.value = handles.values.toList()
        }
        job.invokeOnCompletion { unregister(id) }
        if (wasEmpty) onFirstRunStarted?.invoke()
        return handle
    }

    fun unregister(id: String) {
        val nowEmpty: Boolean
        synchronized(lock) {
            if (handles.remove(id) == null) return
            _active.value = handles.values.toList()
            nowEmpty = handles.isEmpty()
        }
        if (nowEmpty) onLastRunFinished?.invoke()
    }

    fun isActive(id: String): Boolean = synchronized(lock) { handles.containsKey(id) }

    /** Is any run of [kind] in flight (for BUILD, optionally only [ownerId]'s)? */
    fun anyActive(kind: Kind, ownerId: String? = null): Boolean = synchronized(lock) {
        handles.values.any { it.kind == kind && (ownerId == null || it.ownerId == ownerId) }
    }

    /** The run holding [ownerId] (a project id or chat id), if one is in flight — the "Working" mark. */
    fun forOwner(ownerId: String): Handle? = synchronized(lock) { handles.values.firstOrNull { it.ownerId == ownerId } }

    /** Stop everything (the notification's Stop-all action and the app's own emergency stop). */
    fun cancelAll() {
        val jobs: List<Job>
        synchronized(lock) { jobs = handles.values.map { it.job } }
        jobs.forEach { it.cancel() }
    }
}
