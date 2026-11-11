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
import com.mazharansari.axion.data.ScheduleType
import com.mazharansari.axion.data.Step
import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.llm.LazyClient
import com.mazharansari.axion.llm.LlmClient
import com.mazharansari.axion.llm.Mode
import com.mazharansari.axion.llm.FreeFallback
import com.mazharansari.axion.llm.UserItem
import java.util.UUID

/**
 * v0.12: one scheduled task firing. Runs the agent headlessly — nobody is there
 * to approve gated commands, so only remembered approval kinds run (see
 * ApprovalGate) — then saves the whole run as a NEW chat (the user reads it
 * like any conversation) and posts a notification. A one-off schedule disables
 * itself afterwards.
 */
class ScheduledRunWorker(appCtx: Context, params: WorkerParameters) : CoroutineWorker(appCtx, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(ScheduleEngine.KEY_ID) ?: return Result.success()
        val app = applicationContext as? AxionApp ?: return Result.success()
        val g = app.graph
        val store = g.schedules
        val entry = store.get(id) ?: return Result.success()
        if (!entry.enabled) return Result.success()

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
        try {
            val attempts = headlessAttempts(g)
            if (attempts.isEmpty()) {
                error = "No AI key is connected, so the scheduled task could not run. Add a free key in Connectors."
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
                answer = agent.run(
                    emptyList(), UserItem(entry.prompt),
                    attempts = { attempts }, sink = sink,
                    profile = Profile(settings.assistantName.ifBlank { "Axion" }, settings.userName, settings.replyLanguage, settings.instructions),
                    intent = IntentGate.classify(entry.prompt, false),
                )
            }
        } catch (e: NoModelException) { error = e.message.orEmpty() }
        catch (e: Throwable) { error = e.message ?: "The scheduled run failed." }

        val now = System.currentTimeMillis()
        val finalText = when {
            answer.isNotBlank() -> answer
            synchronized(text) { text.toString() }.isNotBlank() -> synchronized(text) { text.toString() }
            else -> ""
        }
        val conv = Conversation(
            id = UUID.randomUUID().toString(),
            title = entry.title,
            messages = listOf(
                Message(UUID.randomUUID().toString(), Role.USER, entry.prompt, createdAt = now),
                Message(UUID.randomUUID().toString(), Role.ASSISTANT,
                    text = if (error.isBlank()) finalText else "",
                    steps = synchronized(steps) { steps.values.toList() },
                    status = if (error.isBlank()) MsgStatus.DONE else MsgStatus.ERROR,
                    error = error, model = "Scheduled task", createdAt = now),
            ),
            createdAt = now, updatedAt = now, section = "ask",
        )
        g.chats.save(conv, immediate = true)
        store.markRan(id, if (error.isBlank()) "done" else error)
        if (entry.type == ScheduleType.ONCE) {
            store.setEnabled(id, false)
            ScheduleEngine.disarm(applicationContext, id)
        }
        notify(applicationContext, entry.title, if (error.isBlank()) finalText else error, ok = error.isBlank(), id = id.hashCode())
        return Result.success()
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

    companion object { const val CH = "scheduled_tasks" }
}
