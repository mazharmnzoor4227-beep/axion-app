package com.mazharansari.axion.telegram

import com.mazharansari.axion.Graph
import com.mazharansari.axion.agent.Agent
import com.mazharansari.axion.agent.AgentSink
import com.mazharansari.axion.agent.Attempt
import com.mazharansari.axion.agent.IntentGate
import com.mazharansari.axion.agent.Profile
import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.data.Step
import com.mazharansari.axion.llm.FreeFallback
import com.mazharansari.axion.llm.LazyClient
import com.mazharansari.axion.llm.LlmClient
import com.mazharansari.axion.llm.Mode
import com.mazharansari.axion.llm.UserItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * P2-1: runs one Telegram message through the agent headlessly and returns the
 * reply text. Same rules as a scheduled run: no approver, so only remembered
 * approval kinds run; gated commands are refused, never run silently. No phone
 * control, no accessibility, no wake word — a message is just a prompt, and
 * the answer is just text back.
 */
class TelegramRunner(private val g: Graph) {

    companion object {
        /** A Telegram reply waits at most 5 minutes; the partial answer is sent instead of nothing. */
        const val RUN_BUDGET_MS = 300_000L
        /** Telegram caps one message at 4096 chars (the API chunks); a long answer is cut with a note. */
        const val MAX_REPLY_CHARS = 4000
    }

    suspend fun answer(text: String): String {
        val attempts = attempts()
        if (attempts.isEmpty()) return "Axion has no model to answer with right now — add a key in Connectors, or set up the local model."
        val collected = StringBuilder()
        val sink = object : AgentSink {
            override fun text(delta: String) {
                synchronized(collected) { collected.append(delta) }
            }

            override fun reasoning(delta: String) {}
            override fun textToThoughts() {}
            override fun step(step: Step) {}
            override fun artifact(a: Artifact) {}
            override fun model(label: String) {}
        }
        val settings = g.settings.state.value
        return try {
            val agent = Agent(
                g.workspace, g.skills,
                baseTools = Agent.defaultTools(),
                extraTools = { g.mcp.tools() },
                toolSwitches = { g.settings.state.value.toolSwitches },
                memory = g.memory, conversations = g.chats,
                approvals = g.approvals, approver = null,
                schedules = g.schedules, appCtx = g.app,
            )
            val answer = withTimeout(RUN_BUDGET_MS) {
                agent.run(
                    emptyList(), UserItem(text),
                    attempts = { attempts }, sink = sink,
                    profile = Profile(
                        settings.assistantName.ifBlank { "Axion" },
                        settings.userName, settings.replyLanguage, settings.instructions,
                    ),
                    intent = IntentGate.classify(text, false),
                )
            }
            val reply = answer.ifBlank { synchronized(collected) { collected.toString() } }.trim()
            if (reply.isBlank()) "Axion ran that, but there is nothing to show. Open the app to see the result."
            else trimReply(reply)
        } catch (e: TimeoutCancellationException) {
            val partial = synchronized(collected) { collected.toString() }.trim()
            if (partial.isBlank()) "That is taking a while — open Axion to see the result when it finishes."
            else trimReply(partial) + "\n\n(Still running — open Axion for the full result.)"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Sorry, that request failed (${e.message ?: "unknown error"})."
        }
    }

    private fun trimReply(reply: String): String =
        if (reply.length <= MAX_REPLY_CHARS) reply
        else reply.take(MAX_REPLY_CHARS) + "\n\n…(cut short — open Axion to read the full answer)"

    /** Same attempt order as a scheduled headless run: API keys, then own models, then Free (slow). */
    private fun attempts(): List<Attempt> {
        val clients = HashMap<String, LlmClient>()
        val keys = g.connectors.attempts(Mode.AUTO).map { (c, model) ->
            Attempt(
                c.provider.name, model,
                clients.getOrPut(c.id) { LazyClient { g.connectors.clientFor(c) } },
                onFail = { e -> g.connectors.coolDown(c, model, e) },
            )
        }
        val own = listOfNotNull(g.connectors.server()?.takeIf { it.enabled }, g.connectors.local()?.takeIf { it.enabled })
            .mapNotNull { c ->
                runCatching { g.connectors.clientFor(c) }.getOrNull()?.let { cl ->
                    Attempt(
                        c.provider.name, c.pinnedModel ?: c.localModel ?: c.models.firstOrNull().orEmpty(), cl,
                        patientMs = if (c.providerId == "local") 150_000 else 90_000,
                    )
                }
            }
        val free = listOf(Attempt(FreeFallback.NAME, FreeFallback.NAME, FreeFallback.Client(), patientMs = FreeFallback.PATIENT_MS))
        return keys + own + free
    }
}
