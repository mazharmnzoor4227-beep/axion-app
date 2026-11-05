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
    /** Live output for long commands (shown in the open Bash card). */
    val progress: (String) -> Unit = {},
)

fun String.clip(max: Int): String = if (length <= max) this else take(max) + "\n…[${length - max} more characters cut]"
