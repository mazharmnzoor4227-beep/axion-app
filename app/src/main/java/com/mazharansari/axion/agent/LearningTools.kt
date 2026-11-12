package com.mazharansari.axion.agent

import com.mazharansari.axion.data.MemoryTarget
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.10 — the Hermes learning loop as Axion tools (pattern from Hermes Agent's memory + skills
 * design; all code is Axion's own):
 *  - memory_add / memory_replace / memory_remove / memory_read: the agent curates a small,
 *    bounded persistent memory (user preferences + facts/lessons) that is injected into every
 *    future system prompt and is fully visible/editable in Settings → Memory.
 *  - skill_create / skill_update / skill_delete / skill_view: the agent writes its own SKILL.md
 *    skills (Hermes section order: When to use → Procedure → Pitfalls → Verification). It may
 *    only edit or delete skills it wrote itself; built-in and user-installed skills are safe.
 *  - session_search: keyword search over the user's saved past conversations.
 */

private fun JsonObject.target(): MemoryTarget =
    if (s("target")?.trim()?.lowercase() == "user") MemoryTarget.USER else MemoryTarget.MEMORY

private fun noMemory() = ToolOutput("Memory is not available in this surface.", isError = true)

class MemoryAddTool : Tool {
    override val spec = ToolSpec("memory_add",
        "Save ONE durable thing to your persistent memory so you remember it in future chats. target 'user' = the user's preferences, habits, name, how they like things; target 'memory' = facts, decisions, lessons and project notes. Keep entries short and dense — memory is small on purpose. Do not save chit-chat, one-off requests, or anything with a password/key.",
        schema { str("target", "'user' or 'memory'"); str("content", "The fact to remember, one short sentence") })
    override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Remembering", args.s("content").orEmpty().take(48))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val mem = ctx.memory ?: return noMemory()
        val t = args.target()
        val content = args.s("content").orEmpty()
        val err = mem.add(t, content)
        return if (err == null) ToolOutput("Saved to ${t.name.lowercase()} memory: \"${mem.entries(t).last()}\" (${mem.entries(t).size} entries, ${mem.usedChars(t)}/${com.mazharansari.axion.data.MemoryStore.budgetOf(t)} characters)", doneTitle = "Remembered")
        else ToolOutput(err, isError = true)
    }
}

class MemoryReplaceTool : Tool {
    override val spec = ToolSpec("memory_replace",
        "Replace a saved memory entry that is outdated or wrong. 'old' must match (or be contained in) the existing entry; 'content' is the new text. Prefer this over adding a second, conflicting entry.",
        schema { str("target", "'user' or 'memory'"); str("old", "Text of the existing entry (or a distinctive part of it)"); str("content", "The new entry text") })
    override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Updating memory", args.s("old").orEmpty().take(48))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val mem = ctx.memory ?: return noMemory()
        val err = mem.replace(args.target(), args.s("old").orEmpty(), args.s("content").orEmpty())
        return if (err == null) ToolOutput("Memory updated.", doneTitle = "Memory updated") else ToolOutput(err, isError = true)
    }
}

class MemoryRemoveTool : Tool {
    override val spec = ToolSpec("memory_remove",
        "Delete a saved memory entry (it is wrong, outdated, or the user asked you to forget it). 'old' must match (or be contained in) the existing entry.",
        schema { str("target", "'user' or 'memory'"); str("old", "Text of the entry to delete (or a distinctive part of it)") })
    override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Forgetting", args.s("old").orEmpty().take(48))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val mem = ctx.memory ?: return noMemory()
        val err = mem.remove(args.target(), args.s("old").orEmpty())
        return if (err == null) ToolOutput("Memory entry deleted.", doneTitle = "Forgot that") else ToolOutput(err, isError = true)
    }
}

class MemoryReadTool : Tool {
    override val spec = ToolSpec("memory_read",
        "Read everything currently saved in your persistent memory (both sections, or one target). Use it before replacing/removing entries, or when the user asks what you remember about them.",
        schema { str("target", "'user', 'memory', or 'all'", required = false) })
    override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Reading memory", args.s("target").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val mem = ctx.memory ?: return noMemory()
        val want = args.s("target")?.trim()?.lowercase()
        val out = buildString {
            if (want != "memory") { appendLine("[user] (${mem.usedChars(MemoryTarget.USER)}/${com.mazharansari.axion.data.MemoryStore.budgetOf(MemoryTarget.USER)} chars)"); mem.entries(MemoryTarget.USER).forEach { appendLine("- $it") } }
            if (want != "user") { appendLine("[memory] (${mem.usedChars(MemoryTarget.MEMORY)}/${com.mazharansari.axion.data.MemoryStore.budgetOf(MemoryTarget.MEMORY)} chars)"); mem.entries(MemoryTarget.MEMORY).forEach { appendLine("- $it") } }
        }.trim()
        val shownEmpty = (want == "user" && mem.entries(MemoryTarget.USER).isEmpty()) ||
            (want == "memory" && mem.entries(MemoryTarget.MEMORY).isEmpty()) ||
            (want != "user" && want != "memory" && mem.entries(MemoryTarget.USER).isEmpty() && mem.entries(MemoryTarget.MEMORY).isEmpty())
        return ToolOutput(if (shownEmpty) "Memory is empty — nothing saved yet." else out, doneTitle = "Read memory")
    }
}

// ---------------------------------------------------------------- skills the agent writes itself

private fun skillMarkdown(name: String, description: String, whenToUse: String, procedure: String, pitfalls: String, verification: String): String = buildString {
    appendLine("---")
    appendLine("name: $name")
    appendLine("description: ${description.replace("\n", " ").take(300)}")
    appendLine("---")
    appendLine()
    appendLine("# $name")
    if (whenToUse.isNotBlank()) { appendLine(); appendLine("## When to use"); appendLine(whenToUse.trim()) }
    appendLine()
    appendLine("## Procedure")
    appendLine(procedure.trim())
    if (pitfalls.isNotBlank()) { appendLine(); appendLine("## Pitfalls"); appendLine(pitfalls.trim()) }
    if (verification.isNotBlank()) { appendLine(); appendLine("## Verification"); appendLine(verification.trim()) }
}

class SkillCreateTool : Tool {
    override val spec = ToolSpec("skill_create",
        "Write a NEW skill of your own: a reusable procedure you worked out (a multi-step task, a tricky workaround, something the user may ask again) so future-you does it the same way. The skill appears in your skills list immediately; load it with load_skill before that kind of task. You can only create skills that do not exist yet — improve yours later with skill_update.",
        schema {
            str("name", "Short kebab-case name, e.g. 'urdu-reel-captions'")
            str("description", "One line: what it does and when it applies (shown in your skills list)")
            str("when_to_use", "The situations/requests this skill is for")
            str("procedure", "The steps, in order, concrete enough to follow exactly")
            str("pitfalls", "Mistakes and traps to avoid (optional)", required = false)
            str("verification", "How to check the result is right (optional)", required = false)
        })
    override fun label(args: JsonObject) = StepLabel(StepKind.SKILL, "Writing skill", args.s("name").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val name = args.s("name").orEmpty().trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (name.isBlank()) return ToolOutput("Give the skill a name.", isError = true)
        val md = skillMarkdown(name, args.s("description").orEmpty(), args.s("when_to_use").orEmpty(),
            args.s("procedure").orEmpty(), args.s("pitfalls").orEmpty(), args.s("verification").orEmpty())
        val saved = runCatching { ctx.skills.saveLearned(name, md, updating = false) }
            .getOrElse { return ToolOutput(it.message ?: "Could not save the skill", isError = true) }
        return ToolOutput("Skill '${saved.name}' saved. It is now in your skills list; load it with load_skill before that kind of task.", doneTitle = "Learned a skill")
    }
}

class SkillUpdateTool : Tool {
    override val spec = ToolSpec("skill_update",
        "Rewrite one of YOUR OWN learned skills (skill_create ones) with a better version — e.g. you found a shorter procedure or a new pitfall while using it. Give the complete new content (same fields as skill_create); it replaces the old version. Built-in and user-installed skills cannot be edited.",
        schema {
            str("name", "Name of your learned skill")
            str("description", "One line: what it does and when it applies")
            str("when_to_use", "The situations/requests this skill is for")
            str("procedure", "The full new steps, in order")
            str("pitfalls", "Mistakes and traps to avoid (optional)", required = false)
            str("verification", "How to check the result is right (optional)", required = false)
        })
    override fun label(args: JsonObject) = StepLabel(StepKind.SKILL, "Improving skill", args.s("name").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val name = args.s("name").orEmpty().trim()
        if (!ctx.skills.isLearned(name)) return ToolOutput("'$name' is not one of your learned skills, so you cannot edit it. Your learned skills: " +
            ctx.skills.skills.value.filter { it.source == com.mazharansari.axion.skills.SkillRepo.LEARNED }.joinToString { it.name }.ifBlank { "(none yet)" }, isError = true)
        val md = skillMarkdown(name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'), args.s("description").orEmpty(), args.s("when_to_use").orEmpty(),
            args.s("procedure").orEmpty(), args.s("pitfalls").orEmpty(), args.s("verification").orEmpty())
        val saved = runCatching { ctx.skills.saveLearned(name, md, updating = true) }
            .getOrElse { return ToolOutput(it.message ?: "Could not update the skill", isError = true) }
        return ToolOutput("Skill '${saved.name}' updated.", doneTitle = "Improved a skill")
    }
}

class SkillDeleteTool : Tool {
    override val spec = ToolSpec("skill_delete",
        "Delete one of YOUR OWN learned skills (it is wrong, obsolete, or the user asked you to forget it). Built-in and user-installed skills can never be deleted by you.",
        schema { str("name", "Name of your learned skill") })
    override fun label(args: JsonObject) = StepLabel(StepKind.SKILL, "Deleting skill", args.s("name").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val name = args.s("name").orEmpty()
        return if (ctx.skills.deleteLearned(name)) ToolOutput("Skill '$name' deleted.", doneTitle = "Deleted a skill")
        else ToolOutput("'$name' is not one of your learned skills, so you cannot delete it.", isError = true)
    }
}

class SkillViewTool : Tool {
    override val spec = ToolSpec("skill_view",
        "Load the full instructions of any skill by name (same content as load_skill) — read it before doing that kind of task.",
        schema { str("name", "Skill name from the skills list") })
    override fun label(args: JsonObject) = StepLabel(StepKind.SKILL, "Viewing skill", args.s("name").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val name = args.s("name").orEmpty()
        val body = ctx.skills.body(name) ?: return ToolOutput("No skill named '$name'. Available: " + ctx.skills.skills.value.joinToString { it.name }, isError = true)
        return ToolOutput(body, display = body.clip(1500), doneTitle = "Viewed skill")
    }
}

// ---------------------------------------------------------------- past conversations

class SessionSearchTool : Tool {
    override val spec = ToolSpec("session_search",
        "Search the user's PAST conversations (all saved chats on this phone) by keywords — use it when the user refers to something from before ('last time', 'wo wala', 'jo pehle decide hua tha') or you need an old decision/fact from an earlier chat. Returns short excerpts, newest first.",
        schema { str("query", "2–4 keywords that would appear in the old messages") })
    override fun label(args: JsonObject) = StepLabel(StepKind.SEARCH, "Searching past chats", args.s("query").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val store = ctx.chats ?: return ToolOutput("Past conversations are not available in this surface.", isError = true)
        val q = args.s("query").orEmpty()
        val hits = runCatching { store.search(q) }.getOrElse { return ToolOutput("Search failed: ${it.message}", isError = true) }
        if (hits.isEmpty()) return ToolOutput("No past conversation mentions \"$q\".")
        val fmt = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
        val out = hits.joinToString("\n\n") { h ->
            "[${fmt.format(Date(h.updatedAt))}] ${h.title.ifBlank { "Chat" }} — ${if (h.role.name == "USER") "user" else "Axion"}: ${h.excerpt}"
        }
        return ToolOutput("Found ${hits.size} matches in past chats:\n\n$out", doneTitle = "Searched past chats")
    }
}
