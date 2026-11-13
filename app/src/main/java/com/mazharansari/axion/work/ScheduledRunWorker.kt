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
 * markRan never called, and a ONCE schedule left armed forever. This is the
 * "report" half of the limit: a long job is NOT split across worker
 * invocations (that is the later Scheduler v2 item), it is reported honestly.
 *
 * No network constraint is set on purpose: on-device/local models can run a
 * scheduled task with no network at all, and a hard CONNECTED constraint
 * would stop those schedules from ever firing. A network-dependent run with
 * no network fails like any other agent error — recorded, then retried with
 * backoff while attempts remain.
 */
class ScheduledRunWorker(appCtx: Context, params: WorkerParameters) : CoroutineWorker(appCtx, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(ScheduleEngine.KEY_ID) ?: return Result.failure()
        val app = applicationContext as? AxionApp ?: return Result.failure()
        val g = app.graph
        val store = g.schedules
        val entry = store.get(id) ?: return Result.failure()
        if (!entry.enabled) return Result.failure()

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
        try {
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
                        emptyList(), UserItem(entry.prompt),
                        attempts = { attempts }, sink = sink,
                        profile = Profile(settings.assistantName.ifBlank { "Axion" }, settings.userName, settings.replyLanguage, settings.instructions),
                        intent = IntentGate.classify(entry.prompt, false),
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Our own 9-minute budget (not a WorkManager kill): the partial
            // text/steps in the sink are still here and get saved below.
            error = TIMEOUT_ERROR
        } catch (e: CancellationException) {
            // WorkManager stopped the worker (app killed, constraints changed).
            // Save whatever partial text/steps exist so the run leaves a record
            // instead of vanishing, then let the cancellation stand. save/mark/
            // notify are all non-suspending, so they run even while cancelled.
            val nowMs = System.currentTimeMillis()
            val partialText = synchronized(text) { text.toString() }.ifBlank { answer }
            val partialSteps = synchronized(steps) { steps.values.toList() }
            saveRunChat(g, entry, partialText, partialSteps, STOPPED_ERROR, nowMs)
            store.markRan(id, STOPPED_ERROR)
            if (entry.type == ScheduleType.ONCE) {
                store.setEnabled(id, false)
                ScheduleEngine.disarm(applicationContext, id)
            }
            notify(applicationContext, entry.title, STOPPED_ERROR, ok = false, id = id.hashCode())
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
        val effectiveError = when {
            error.isNotBlank() -> error
            finalText.isBlank() && stepList.isEmpty() -> EMPTY_RESULT_ERROR
            else -> ""
        }
        val decision = decideOutcome(
            entryFound = true, enabled = true, hasAttempts = !noAttempts,
            error = effectiveError, hasAnswer = finalText.isNotBlank(), hasSteps = stepList.isNotEmpty(),
            isOnce = entry.type == ScheduleType.ONCE, runAttemptCount = runAttemptCount,
        )
        if (decision.saveChat) {
            saveRunChat(g, entry, finalText, stepList, effectiveError, now)
            store.markRan(id, decision.markRanStatus ?: if (effectiveError.isBlank()) "done" else effectiveError)
            if (decision.disableOnce) {
                store.setEnabled(id, false)
                ScheduleEngine.disarm(applicationContext, id)
            }
            notify(applicationContext, entry.title, if (effectiveError.isBlank()) finalText else effectiveError,
                ok = effectiveError.isBlank(), id = id.hashCode())
        }
        // On RETRY nothing is saved, recorded or disabled: the retry IS the
        // same run, and the ONCE schedule stays armed until an outcome exists.
        return when (decision.result) {
            ScheduledRunResult.SUCCESS -> Result.success()
            ScheduledRunResult.RETRY -> Result.retry()
            ScheduledRunResult.FAILURE -> Result.failure()
        }
    }

    /** Saves one run as a new chat. Does not touch the schedule itself. */
    private fun saveRunChat(g: Graph, entry: ScheduleEntry, finalText: String, stepList: List<Step>, error: String, now: Long) {
        val conv = Conversation(
            id = UUID.randomUUID().toString(),
            title = entry.title,
            messages = listOf(
                Message(UUID.randomUUID().toString(), Role.USER, entry.prompt, createdAt = now),
                Message(UUID.randomUUID().toString(), Role.ASSISTANT,
                    text = if (error.isBlank()) finalText else "",
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
            val failed = error.isNotBlank() || (!hasAnswer && !hasSteps)
            if (!failed)
                return ScheduledRunDecision(ScheduledRunResult.SUCCESS, saveChat = true, markRanStatus = "done", disableOnce = isOnce)
            // Transient failure (agent error, timeout, or an empty run): retry
            // while attempts remain, recording nothing yet — the retry is the
            // same run, so a ONCE schedule stays armed until an outcome exists.
            if (runAttemptCount < maxRetries)
                return ScheduledRunDecision(ScheduledRunResult.RETRY, saveChat = false, markRanStatus = null, disableOnce = false)
            // Out of retries: record the failure and end the run.
            return ScheduledRunDecision(ScheduledRunResult.FAILURE, saveChat = true,
                markRanStatus = error.ifBlank { EMPTY_RESULT_ERROR }, disableOnce = isOnce)
        }
    }
}
