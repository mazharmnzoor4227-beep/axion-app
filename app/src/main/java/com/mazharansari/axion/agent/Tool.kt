package com.mazharansari.axion.agent

import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.serialization.json.JsonObject

data class ToolOutput(
    /** What the model reads. */
    val forModel: String,
    /** What the collapsible step card shows (defaults to forModel, trimmed). */
    val display: String = forModel,
    val artifact: Artifact? = null,
    val isError: Boolean = false,
    /** Optional replacement for the step title once finished, e.g. "Browsed 3 pages". */
    val doneTitle: String? = null,
    /** axion-p1.4: exit code for run_command (-1 = timed out). */
    val exitCode: Int? = null,
)

data class StepLabel(val kind: StepKind, val title: String, val target: String)

interface Tool {
    val spec: ToolSpec
    fun label(args: JsonObject): StepLabel
    suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput
}

class ToolContext(
    val workspace: Workspace,
    val skills: com.mazharansari.axion.skills.SkillRepo,
    /** v0.10 learning loop: the agent-curated persistent memory (null in surfaces without it, e.g. live calls). */
    val memory: com.mazharansari.axion.data.MemoryStore? = null,
    /** v0.10 learning loop: saved conversations, for the session_search tool. */
    val chats: com.mazharansari.axion.data.ConversationStore? = null,
    /**
     * v0.11 deep research: one plain prompt → one plain answer from the same models that serve the
     * chat (the agent wires its attempts in). Null in surfaces with no model behind them (live calls),
     * where tools must degrade to deterministic behaviour.
     */
    val ask: (suspend (prompt: String) -> String)? = null,
    /** Live output for long commands (shown in the open Bash card). Kept LAST: call sites pass it as a trailing lambda. */
    val progress: (String) -> Unit = {},
)

fun String.clip(max: Int): String = if (length <= max) this else take(max) + "\n…[${length - max} more characters cut]"
