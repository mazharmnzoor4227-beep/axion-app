package com.mazharansari.axion.data

import kotlinx.serialization.Serializable

@Serializable
enum class Role { USER, ASSISTANT }

@Serializable
enum class StepKind { THINK, SEARCH, BROWSE, COMMAND, READ, WRITE, SKILL, PDF, ZIP, MCP, OTHER }

@Serializable
enum class StepStatus { RUNNING, DONE, ERROR }

/** One visible agent step ("Browsed claworabeauty.store", a Bash command, "Loaded skill pdf"). */
@Serializable
data class Step(
    val id: String,
    val kind: StepKind,
    val title: String,
    /** The bold target shown after the title (URL, file name, query) or the command for Bash cards. */
    val target: String = "",
    val output: String = "",
    val status: StepStatus = StepStatus.RUNNING,
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    /** v0.3: the full content behind the step (the code a write_file wrote, the PDF's markdown) — shown when the row is tapped. */
    val detail: String = "",
    /** v0.6 (axion-p1.4): exit code of a finished command (null = unknown / not a command). -1 = timed out. */
    val exitCode: Int? = null,
)

/** v0.6 (axion-p2) the agent's visible plan: numbered steps with a live status. */
@Serializable
enum class PlanStatus { PENDING, RUNNING, DONE, FAILED }

@Serializable
data class PlanStep(val text: String, val status: PlanStatus = PlanStatus.PENDING, val note: String = "")

@Serializable
data class Attachment(
    val name: String,
    val mime: String,
    /** Path inside the private workspace (the agent sees the same path). */
    val path: String,
    val size: Long,
)

/** A file the agent produced (PDF report, zip, etc.), shown as a card with Download / Open. */
@Serializable
data class Artifact(
    val name: String,
    val mime: String,
    val path: String,
    val size: Long,
    val pages: Int = 0,
)

@Serializable
enum class MsgStatus { DONE, STREAMING, INTERRUPTED, ERROR }

@Serializable
data class Message(
    val id: String,
    val role: Role,
    val text: String = "",
    val attachments: List<Attachment> = emptyList(),
    val steps: List<Step> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
    val reasoning: String = "",
    val status: MsgStatus = MsgStatus.DONE,
    val thinkingStartedAt: Long = 0,
    val thinkingMs: Long = 0,
    val model: String = "",
    val error: String = "",
    val rating: Int = 0,
    val createdAt: Long = 0,
    /** v0.5.1: transient line under "Thinking" ("Switching to Groq · llama-3.3-70b…"). */
    val notice: String = "",
    /** v0.5.2: ms from tapping Send to the first answer word on screen, and how much of that was Axion itself (before the request left). */
    val firstWordMs: Long = 0,
    val prepMs: Long = 0,
    /** v0.6 (axion-p2): the plan the agent showed for a multi-step task (empty for simple replies). */
    val plan: List<PlanStep> = emptyList(),
    /** v0.6 Tools library: which tools the agent used for this answer, shown as a Used tool line. */
    val usedTools: List<String> = emptyList(),
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val pinned: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** v0.4: which engine owns this thread — "ask" (API keys) or "build" (Antigravity). See [com.mazharansari.axion.ui.Section]. */
    val section: String = "ask",
)

@Serializable
data class ConversationMeta(
    val id: String,
    val title: String,
    val pinned: Boolean,
    val updatedAt: Long,
    val preview: String = "",
    val section: String = "ask",
)
