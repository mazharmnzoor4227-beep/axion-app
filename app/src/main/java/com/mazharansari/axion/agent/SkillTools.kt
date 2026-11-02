package com.mazharansari.axion.agent

import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.serialization.json.JsonObject

class LoadSkillTool : Tool {
    override val spec = ToolSpec("load_skill", "Load a skill's full instructions by name before doing that kind of task.",
        schema { str("name", "Skill name from the skills list") })
    override fun label(args: JsonObject) = StepLabel(StepKind.SKILL, "Loading skill", args.s("name").orEmpty())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val name = args.s("name").orEmpty()
        val body = ctx.skills.body(name) ?: return ToolOutput("No skill named '$name'. Available: " + ctx.skills.skills.value.joinToString { it.name }, isError = true)
        return ToolOutput(body, display = body.clip(1500), doneTitle = "Loaded skill")
    }
}

class InstallSkillsTool : Tool {
    override val spec = ToolSpec("install_skills_from_github",
        "When the user gives a GitHub repo URL with skills, list every SKILL.md in it and install them so they can be loaded.",
        schema { str("url", "GitHub repo or folder URL") })
    override fun label(args: JsonObject) = StepLabel(StepKind.SKILL, "Learning skills from", args.s("url").orEmpty().removePrefix("https://"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val url = args.s("url").orEmpty()
        val found = runCatching { ctx.skills.scanGitHub(url) }.getOrElse { return ToolOutput("Could not read repo: ${it.message}", isError = true) }
        if (found.isEmpty()) return ToolOutput("No SKILL.md files found in $url", isError = true)
        val added = ctx.skills.install(found, url)
        return ToolOutput("Installed ${added.size} skills:\n" + added.joinToString("\n") { "- ${it.name}: ${it.description}" }, doneTitle = "Learned ${added.size} skills")
    }
}
