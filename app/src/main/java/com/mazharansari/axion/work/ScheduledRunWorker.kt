package com.mazharansari.axion.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mazharansari.axion.AxionApp
import com.mazharansari.axion.Graph
import com.mazharansari.axion.MainActivity
import com.mazharansari.axion.agent.Agent
import com.mazharansari.axion.agent.AgentSink
import com.mazharansari.axion.agent.Attempt
import com.mazharansari.axion.agent.GenerateImageTool
import com.mazharansari.axion.agent.IntentGate
import com.mazharansari.axion.agent.NoModelException
import com.mazharansari.axion.agent.Profile
import com.mazharansari.axion.data.Conversation
import com.mazharansari.axion.data.Message
import com.mazharansari.axion.data.MsgStatus
import com.mazharansari.axion.data.Role
import com.mazharansari.axion.data.ScheduleEntry
import com.mazharansari.axion.data.ScheduleType
import com.mazharansari.axion.data.Step
import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.llm.LazyClient
import com.mazharansari.axion.llm.LlmClient
import com.mazharansari.axion.llm.Mode
import com.mazharansari.axion.llm.FreeFallback
import com.mazharansari.axion.llm.UserItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** What the worker tells WorkManager about a run. Mapped to [androidx.work.ListenableWorker.Result] in [ScheduledRunWorker.doWork]. */
enum class ScheduledRunResult { SUCCESS, RETRY, FAILURE }

/**
 * The outcome of one scheduled run, decided by [ScheduledRunWorker.decideOutcome].
 * The rules, in one place so they can be unit-tested without Android:
 * a run only counts as a success when it produced an answer (or steps); an
 * error retries while attempts remain and otherwise records a failure; a
 * ONCE schedule is disabled only together with a recorded outcome
 * ([saveChat] + [markRanStatus]) — never on a retry, never silently.
 */
data class ScheduledRunDecision(
    val result: ScheduledRunResult,
    /** Save the run as a chat (with the error recorded when it failed). */
    val saveChat: Boolean,
    /** Status line for markRan, or null when nothing was recorded yet (retry). */
    val markRanStatus: String?,
    /** Disable a ONCE schedule — only ever true together with a recorded outcome. */
    val disableOnce: Boolean,
)

/**
 * v0.12: one scheduled task firing. Runs the agent headlessly — nobody is there
 * to approve gated commands, so only remembered approval kinds run (see
 * ApprovalGate) — then saves the whole run as a NEW chat (the user reads it
 * like any conversation) and posts a notification. A one-off schedule disables
 * itself afterwards.
 *
 * P0-9 (HI-7): doWork used to return Result.success() on EVERY path — no
 * entry, disabled, no key, agent error, even an empty run. Now every path
 * reports what actually happened ([decideOutcome]): failure when nothing
 * could run, bounded retry (see [MAX_ATTEMPTS]) on a transient agent error,
 * and success only for a run that produced an answer or steps. A run that
 * produces nothing is never reported as a success.
 *
 * ~10-minute limit: WorkManager stops a worker after about 10 minutes. The
 * agent run is capped at [RUN_BUDGET_MS] (9 minutes) with withTimeout, so the
 * worker stops itself in time to save whatever partial text/steps exist and
 * record the outcome — instead of being killed mid-run with no chat saved,
 * markRan never called, and a ONCE schedule left armed forever. P0-9 was
 * the "report" half of the limit; P1-8 adds the other half — a run that hits
 * the budget checkpoints and resumes in the next invocation (see below).
 *
 * P0-9: no network constraint was set on purpose — on-device/local models
 * can run a scheduled task with no network at all, and a hard CONNECTED
 * constraint would stop those schedules from ever firing. A network-dependent
 * run with no network failed like any other agent error — recorded, then
 * retried with backoff while attempts remain. P1-8 keeps that default and
 * adds an opt-in per-schedule network requirement (see below).
 *
 * P1-8 (Scheduler v2 — the proper fix for HI-5/HI-7):
 * - Exact timing: [ScheduleEngine] arms an exact alarm where permitted; this
 *   worker is unchanged by which trigger fired it.
 * - Network: an entry with requireNetwork checks connectivity first
 *   ([isOnline]) and retries while offline instead of failing at once; the
 *   WorkManager job also carries a CONNECTED constraint.
 * - Retry: bounded by the entry's own maxRetries, exponential backoff
 *   ([RetryPolicy]), and RECORDED — a retry writes a "Retrying…" status on
 *   the schedule's card, so a retrying run is visible instead of silent.
 * - Long runs split across invocations: a run that hits the 9-minute budget
 *   checkpoints its partial text ([RunCheckpointPolicy]) and the next
 *   invocation resumes from it (up to [RunCheckpointPolicy.MAX_SEGMENTS]
 *   segments); after that it ends with a failure record carrying the partial
 *   result. The saved result chat merges all segments, so the user reads one
 *   whole result.
 * - Guaranteed outcome: every terminal run ends in a saved result chat +
 *   notification, or — if even saving fails — an explicit failure record in
 *   [SchedulesStore.recordFailure] that the Schedules screen shows. A run can
 *   no longer vanish.
 */
class ScheduledRunWorker(appCtx: Context, params: WorkerParameters) : CoroutineWorker(appCtx, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(ScheduleEngine.KEY_ID) ?: return Result.failure()
        val app = applicationContext as? AxionApp ?: return Result.failure()
        val g = app.graph
        val store = g.schedules
        val entry = store.get(id) ?: run {
            // Deleted between enqueue and fire: still leave a record, never vanish silently.
            runCatching { store.recordFailure(id, "Scheduled task", "The schedule no longer exists, so the run could not start.") }
            return Result.failure()
        }
        if (!entry.enabled) return Result.failure()

        // P2-11: a digest entry composes the briefing instead of running the agent.
        if (entry.digest) return runDigestEntry(g, entry)

        // P1-8: a run split across invocations resumes from its checkpoint.
        val checkpointStore = ScheduleCheckpointStore(applicationContext)
        val checkpoint = checkpointStore.load(id)
        val runId = checkpoint?.runId ?: UUID.randomUUID().toString()
        val runStartedAt = checkpoint?.startedAtMs ?: System.currentTimeMillis()
        val effectivePrompt = checkpoint?.let { RunCheckpointPolicy.continuationPrompt(entry.prompt, it) } ?: entry.prompt
        val maxRetries = entry.maxRetries.coerceIn(0, 5)

        val settings = g.settings.state.value
        val text = StringBuilder()
        val steps = LinkedHashMap<String, Step>()
        val sink = object : AgentSink {
            override fun text(delta: String) { synchronized(text) { text.append(delta) } }
            override fun reasoning(delta: String) {}
            override fun textToThoughts() {}
            override fun step(step: Step) { synchronized(steps) { steps[step.id] = step } }
            override fun artifact(a: Artifact) {}
            override fun model(label: String) {}
        }

        var error = ""
        var answer = ""
        var noAttempts = false
        var timedOut = false
        var networkUnavailable = false
        // P1-8: a schedule that needs network never starts the agent offline —
        // it waits (bounded retry) instead of burning a run on a certain failure.
        if (entry.requireNetwork && !isOnline(applicationContext)) {
            networkUnavailable = true
            error = NETWORK_ERROR
        } else try {
            val attempts = headlessAttempts(g)
            if (attempts.isEmpty()) {
                noAttempts = true
                error = NO_KEY_ERROR
            } else {
                val agent = Agent(
                    g.workspace, g.skills,
                    baseTools = Agent.defaultTools() + GenerateImageTool(g.imageGen),
                    extraTools = { g.mcp.tools() },
                    toolSwitches = { g.settings.state.value.toolSwitches },
                    memory = g.memory, conversations = g.chats,
                    approvals = g.approvals, approver = null,
                    schedules = g.schedules, appCtx = applicationContext,
                )
                answer = withTimeout(RUN_BUDGET_MS) {
                    agent.run(
                        emptyList(), UserItem(effectivePrompt),
                        attempts = { attempts }, sink = sink,
                        profile = Profile(settings.assistantName.ifBlank { "Axion" }, settings.userName, settings.replyLanguage, settings.instructions),
                        intent = IntentGate.classify(entry.prompt, false),
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Our own 9-minute budget (not a WorkManager kill): the partial
            // text/steps in the sink are still here. P1-8: the run checkpoints
            // and resumes in the next invocation instead of ending here.
            timedOut = true
            error = TIMEOUT_ERROR
        } catch (e: CancellationException) {
            // WorkManager stopped the worker (app killed, constraints changed).
            // Save whatever partial text/steps exist so the run leaves a record
            // instead of vanishing, then let the cancellation stand. save/mark/
            // notify are all non-suspending, so they run even while cancelled.
            val nowMs = System.currentTimeMillis()
            val partialText = synchronized(text) { text.toString() }.ifBlank { answer }
            val partialSteps = synchronized(steps) { steps.values.toList() }
            checkpointStore.clear(id)
            runCatching {
                saveRunChat(g, entry, partialText, partialSteps, STOPPED_ERROR, nowMs)
                store.markRan(id, STOPPED_ERROR)
                if (entry.type == ScheduleType.ONCE) {
                    store.setEnabled(id, false)
                    ScheduleEngine.disarm(applicationContext, id)
                }
                notify(applicationContext, entry.title, STOPPED_ERROR, ok = false, id = id.hashCode())
            }.onFailure {
                runCatching { store.recordFailure(id, entry.title, "The scheduled run was stopped, and its partial result could not be saved: ${it.message ?: "unknown error"}") }
            }
            throw e
        } catch (e: NoModelException) { error = e.message.orEmpty() }
        catch (e: Throwable) { error = e.message ?: "The scheduled run failed." }

        val now = System.currentTimeMillis()
        val sinkText = synchronized(text) { text.toString() }
        val finalText = when {
            answer.isNotBlank() -> answer
            sinkText.isNotBlank() -> sinkText
            else -> ""
        }
        val stepList = synchronized(steps) { steps.values.toList() }
        // P1-8: the saved result merges every segment, so a split run still reads as one whole result.
        val savedText = when {
            checkpoint == null -> finalText
            finalText.isBlank() -> checkpoint.partialText
            checkpoint.partialText.isBlank() -> finalText
            else -> checkpoint.partialText + "\n\n[continued]\n" + finalText
        }
        val effectiveError = when {
            error.isNotBlank() -> error
            savedText.isBlank() && stepList.isEmpty() -> EMPTY_RESULT_ERROR
            else -> ""
        }
        val decision = decideOutcome(
            entryFound = true, enabled = true, hasAttempts = !noAttempts,
            error = effectiveError, hasAnswer = savedText.isNotBlank(), hasSteps = stepList.isNotEmpty(),
            isOnce = entry.type == ScheduleType.ONCE, runAttemptCount = runAttemptCount,
            maxRetries = maxRetries, networkUnavailable = networkUnavailable,
            noMoreRetries = timedOut && RunCheckpointPolicy.isExhausted(checkpoint),
        )
        if (decision.result == ScheduledRunResult.RETRY) {
            // P1-8: a retry is recorded on the schedule's card (visible, with
            // the backoff wait) — and a timed-out run checkpoints so the next
            // invocation resumes instead of starting over.
            if (timedOut) {
                checkpointStore.save(
                    RunCheckpointPolicy.nextSegment(
                        checkpoint, id, runId, runStartedAt, entry.prompt, finalText, stepList.size, now))
            }
            store.recordRetry(id, runAttemptCount + 1, maxRetries + 1, effectiveError, RetryPolicy.delayMs(runAttemptCount))
            return Result.retry()
        }
        // On RETRY no result chat is saved and the ONCE schedule stays armed
        // until an outcome exists — but the retry itself IS recorded (see
        // recordRetry above), so a retrying run is visible on its card.
        if (decision.saveChat) {
            checkpointStore.clear(id)
            // P1-8: a terminal run ends in a saved chat + notification, or —
            // if even that fails — an explicit failure record. Never silence.
            val saved = runCatching {
                saveRunChat(g, entry, savedText, stepList, effectiveError, now)
                store.markRan(id, decision.markRanStatus ?: if (effectiveError.isBlank()) "done" else effectiveError)
                if (decision.disableOnce) {
                    store.setEnabled(id, false)
                    ScheduleEngine.disarm(applicationContext, id)
                }
            }
            if (saved.isFailure) {
                runCatching {
                    store.recordFailure(id, entry.title,
                        "The scheduled result could not be saved: ${saved.exceptionOrNull()?.message ?: "unknown error"}. " +
                            if (effectiveError.isBlank()) "Partial result: ${savedText.take(200)}" else effectiveError)
                    store.markRan(id, "failed — result could not be saved")
                    notify(applicationContext, entry.title, "The scheduled task ran but its result could not be saved.", ok = false, id = id.hashCode())
                }
            } else {
                val notified = runCatching {
                    notify(applicationContext, entry.title, if (effectiveError.isBlank()) savedText else effectiveError,
                        ok = effectiveError.isBlank(), id = id.hashCode())
                }
                if (notified.isFailure) {
                    runCatching { store.recordFailure(id, entry.title, "The scheduled result was saved as a chat, but the notification could not be sent.") }
                }
            }
        }
        return when (decision.result) {
            ScheduledRunResult.SUCCESS -> Result.success()
            ScheduledRunResult.RETRY -> Result.retry()
            ScheduledRunResult.FAILURE -> Result.failure()
        }
    }

    /**
     * P2-11: a digest entry never runs the agent — it composes the daily briefing
     * (overnight research, repo CI status, quota left) from real state, saves it
     * as a new chat and notifies, exactly like any other terminal run. A briefing
     * that cannot be composed still saves a chat saying so; a save failure is a
     * recorded failure, never silence.
     */
    private suspend fun runDigestEntry(g: Graph, entry: ScheduleEntry): Result {
        val now = System.currentTimeMillis()
        val text = runCatching { DigestComposer(g).compose() }.getOrElse {
            "Daily briefing — ${java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date(now))}\n\nThe briefing could not be put together: ${it.message ?: "unknown error"}"
        }
        return try {
            saveRunChat(g, entry, text, emptyList(), "", now)
            g.schedules.markRan(entry.id, "done")
            notify(applicationContext, entry.title, "Your daily briefing is ready.", ok = true, id = entry.id.hashCode())
            Result.success()
        } catch (e: Throwable) {
            runCatching { g.schedules.recordFailure(entry.id, entry.title, "The briefing could not be saved: ${e.message ?: "unknown error"}") }
            Result.failure()
        }
    }

    /** Saves one run as a new chat. Does not touch the schedule itself. */
    private fun saveRunChat(g: Graph, entry: ScheduleEntry, finalText: String, stepList: List<Step>, error: String, now: Long) {
        // P1-8: the partial text is kept even on failure — a run that produced
        // something before failing (a timed-out split run, an agent error
        // mid-task) shows what it produced, with the error recorded alongside.
        // That is what TIMEOUT_ERROR promises ("the partial result below").
        val conv = Conversation(
            id = UUID.randomUUID().toString(),
            title = entry.title,
            messages = listOf(
                Message(UUID.randomUUID().toString(), Role.USER, entry.prompt, createdAt = now),
                Message(UUID.randomUUID().toString(), Role.ASSISTANT,
                    text = finalText,
                    steps = stepList,
                    status = if (error.isBlank()) MsgStatus.DONE else MsgStatus.ERROR,
                    error = error, model = "Scheduled task", createdAt = now),
            ),
            createdAt = now, updatedAt = now, section = "ask",
        )
        g.chats.save(conv, immediate = true)
    }

    /** The chat rotation without a screen: the user's keys, then their own models, then Free (slow). */
    private fun headlessAttempts(g: Graph): List<Attempt> {
        val clients = HashMap<String, LlmClient>()
        val keys = g.connectors.attempts(Mode.AUTO).map { (c, model) ->
            Attempt(c.provider.name, model, clients.getOrPut(c.id) { LazyClient { g.connectors.clientFor(c) } },
                onFail = { e -> g.connectors.coolDown(c, model, e) })
        }
        val own = listOfNotNull(g.connectors.server()?.takeIf { it.enabled }, g.connectors.local()?.takeIf { it.enabled })
            .mapNotNull { c ->
                runCatching { g.connectors.clientFor(c) }.getOrNull()?.let { cl ->
                    Attempt(c.provider.name, c.pinnedModel ?: c.localModel ?: c.models.firstOrNull().orEmpty(), cl,
                        patientMs = if (c.providerId == "local") 150_000 else 90_000)
                }
            }
        val free = listOf(Attempt(FreeFallback.NAME, FreeFallback.NAME, FreeFallback.Client(), patientMs = FreeFallback.PATIENT_MS))
        return keys + own + free
    }

    private fun notify(ctx: Context, title: String, body: String, ok: Boolean, id: Int) = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CH, "Scheduled tasks", NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.content.Intent(ctx, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id, NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (ok) "Scheduled task done: $title" else "Scheduled task failed: $title")
            .setContentText(body.replace('\n', ' ').take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(400)))
            .setContentIntent(pi).setAutoCancel(true).build())
    }

    companion object {
        const val CH = "scheduled_tasks"

        /**
         * P0-9: WorkManager stops a worker after ~10 minutes. The agent run is
         * capped at 9 minutes so the worker can still save its partial result
         * and record the outcome instead of being killed with nothing saved.
         */
        const val RUN_BUDGET_MS = 9L * 60L * 1000L

        /** Initial run + 2 retries, then the failure is recorded and the run ends. */
        const val MAX_ATTEMPTS = 3

        const val NO_KEY_ERROR = "No AI key is connected, so the scheduled task could not run. Add a free key in Connectors."
        const val EMPTY_RESULT_ERROR = "The scheduled run produced no answer."
        const val TIMEOUT_ERROR = "The scheduled run reached the 10-minute background limit before it finished, so only the partial result below was saved."
        const val STOPPED_ERROR = "The scheduled run was stopped before it finished."
        const val NETWORK_ERROR = "No network connection — this schedule only runs when the phone is online."

        /** True when the phone has a network with internet access (P1-8 network constraint). */
        fun isOnline(ctx: Context): Boolean {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
            return runCatching {
                if (Build.VERSION.SDK_INT >= 23) {
                    val network = cm.activeNetwork ?: return false
                    val caps = cm.getNetworkCapabilities(network) ?: return false
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } else {
                    @Suppress("DEPRECATION")
                    cm.activeNetworkInfo?.isConnected == true
                }
            }.getOrDefault(false)
        }

        /**
         * Pure outcome rule for one scheduled run (no Android calls, unit-tested
         * in ScheduledRunDecisionTest). [runAttemptCount] is WorkManager's
         * 0-based attempt count for this work request.
         */
        fun decideOutcome(
            entryFound: Boolean,
            enabled: Boolean,
            hasAttempts: Boolean,
            error: String,
            hasAnswer: Boolean,
            hasSteps: Boolean,
            isOnce: Boolean,
            runAttemptCount: Int,
            maxRetries: Int = MAX_ATTEMPTS - 1,
            /** P1-8: the run never started — the schedule needs network and the phone is offline. */
            networkUnavailable: Boolean = false,
            /** P1-8: retries are used up another way (a split run out of segments) — end the run. */
            noMoreRetries: Boolean = false,
        ): ScheduledRunDecision {
            // Nothing to run (deleted / switched off between enqueue and fire):
            // terminal failure, nothing to record, nothing to retry.
            if (!entryFound || !enabled)
                return ScheduledRunDecision(ScheduledRunResult.FAILURE, saveChat = false, markRanStatus = null, disableOnce = false)
            // No key/model at all: retrying cannot fix that. Record the failure
            // once (chat + markRan) and disable a ONCE schedule — its outcome exists.
            if (!hasAttempts)
                return ScheduledRunDecision(ScheduledRunResult.FAILURE, saveChat = true,
                    markRanStatus = error.ifBlank { NO_KEY_ERROR }, disableOnce = isOnce)
            // P1-8: offline with a network requirement — retry while attempts
            // remain (the constraint may clear), then record the failure.
            if (networkUnavailable) {
                if (runAttemptCount < maxRetries && !noMoreRetries)
                    return ScheduledRunDecision(ScheduledRunResult.RETRY, saveChat = false, markRanStatus = null, disableOnce = false)
                return ScheduledRunDecision(ScheduledRunResult.FAILURE, saveChat = true,
                    markRanStatus = NETWORK_ERROR, disableOnce = isOnce)
            }
            val failed = error.isNotBlank() || (!hasAnswer && !hasSteps)
            if (!failed)
                return ScheduledRunDecision(ScheduledRunResult.SUCCESS, saveChat = true, markRanStatus = "done", disableOnce = isOnce)
            // Transient failure (agent error, timeout, or an empty run): retry
            // while attempts remain, recording nothing yet — the retry is the
            // same run, so a ONCE schedule stays armed until an outcome exists.
            if (runAttemptCount < maxRetries && !noMoreRetries)
                return ScheduledRunDecision(ScheduledRunResult.RETRY, saveChat = false, markRanStatus = null, disableOnce = false)
            // Out of retries: record the failure and end the run.
            return ScheduledRunDecision(ScheduledRunResult.FAILURE, saveChat = true,
                markRanStatus = error.ifBlank { EMPTY_RESULT_ERROR }, disableOnce = isOnce)
        }
    }
}
