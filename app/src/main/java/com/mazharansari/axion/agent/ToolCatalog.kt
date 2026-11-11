package com.mazharansari.axion.agent

/**
 * v0.6 (Phase 3.4) TOOLS LIBRARY — the user-facing list of everything Axion's agent can do, each with a plain description and an on/off switch.
 * Original Axion design (a library of ready tools the user can switch on and off is the general idea; nothing from any other project's code).
 * One group = one switch = one or more agent tools. Planning tools are internal and always on. MCP connector tools (names `mcp_…`) form the "Connectors" group.
 */
data class ToolGroup(
    val id: String, val title: String, val description: String, val tools: Set<String>,
    val defaultOn: Boolean = true,
    /** Shown under the switch, e.g. what the user has to do first. */
    val note: String = "",
    /** The sensitive ones get a warning colour in the UI. */
    val sensitive: Boolean = false,
    /** What the request is called when this group is off ("controlling the phone"). */
    val refusal: String = "",
)

object ToolCatalog {
    val groups: List<ToolGroup> = listOf(
        ToolGroup("web", "Web search & pages", "Searches the web, opens and reads web pages, and calls web APIs for fresh facts, prices, news and weather.",
            setOf("web_search", "browse_url", "http_request"), refusal = "searching the web or opening pages"),
        ToolGroup("browser", "Browser", "Axion's own web browser: opens real pages (JavaScript, forms, logins), reads them, clicks, types, scrolls and takes screenshots. Watch or take over in the Browser screen.",
            setOf("browser_open", "browser_read", "browser_click", "browser_type", "browser_scroll", "browser_back", "browser_forward", "browser_reload", "browser_search", "browser_screenshot"),
            refusal = "using the built-in browser"),
        ToolGroup("files", "Files & workspace", "Reads your uploaded files (PDF, documents, ZIP), lists them and writes code or text files into Axion's workspace.",
            setOf("read_file", "write_file", "list_files", "extract_zip"), refusal = "reading or writing files"),
        ToolGroup("documents", "PDF reports", "Turns text into a formatted PDF report you can download.", setOf("create_pdf"), refusal = "making PDF documents"),
        ToolGroup("images", "Image generation", "Creates pictures from a description (posters, logos, product shots) with your connected image model.", setOf("generate_image"), refusal = "generating images"),
        ToolGroup("video", "Video editing", "Edits videos on your phone: trim, captions, reel size (9:16), loudness, compress, extract audio, platform presets. Needs Developer tools (Linux).",
            setOf("video_probe", "video_edit"), refusal = "editing videos"),
        ToolGroup("commands", "Run commands", "Runs shell commands in Axion's Linux (Python, Node, ffmpeg …) to build, convert or analyse things: quick commands, a persistent shell session (directory and variables carry over) and background jobs for long work.",
            setOf("run_command", "shell_session", "shell_reset", "run_background", "job_output", "job_kill"),
            sensitive = true, refusal = "running commands"),
        ToolGroup("skills", "Skills", "Loads your installed skills and installs new ones from GitHub when you share a link.", setOf("load_skill", "install_skills_from_github"), refusal = "using or installing skills"),
        ToolGroup("connectors", "Connectors (MCP)", "Uses the tools of connectors you added (Gmail, Notion, GitHub …). Manage connectors in Settings → Connectors.", emptySet(), refusal = "using your connectors"),
    )
    private val byTool: Map<String, ToolGroup> = groups.flatMap { g -> g.tools.map { it to g } }.toMap()
    private val internal = setOf(Planner.PLAN_SET, Planner.PLAN_UPDATE)

    fun groupOf(toolName: String): ToolGroup? = byTool[toolName] ?: if (toolName.startsWith("mcp_")) groups.first { it.id == "connectors" } else null
    fun isEnabled(group: ToolGroup, switches: Map<String, Boolean>) = switches[group.id] ?: group.defaultOn
    /** Tools outside the catalog (plan tools) are always on. */
    fun isEnabled(toolName: String, switches: Map<String, Boolean>): Boolean = if (toolName in internal) true else groupOf(toolName)?.let { isEnabled(it, switches) } ?: true
    fun titleOf(toolName: String): String = groupOf(toolName)?.title ?: toolName

    fun disabledGroups(switches: Map<String, Boolean>): List<ToolGroup> = groups.filter { !isEnabled(it, switches) }

    /** The result of a call to a switched-off tool (the model should relay it politely). */
    fun refusalFor(toolName: String): String {
        val g = groupOf(toolName)
        return "The user switched OFF the tool \"${g?.title ?: toolName}\" in Settings → Tools library, so Axion cannot do that. Politely tell them it is off and that they can turn it on in Settings → Tools library; do not try another way around it."
    }

    fun promptNote(switches: Map<String, Boolean>): String {
        val off = disabledGroups(switches); if (off.isEmpty()) return ""
        return "TOOLS SWITCHED OFF by the user (Settings → Tools library): " + off.joinToString("; ") { "${it.title} (${it.refusal})" } +
            ". If a request needs one of these, do NOT attempt it another way: reply politely, say that tool is off and that they can switch it on in Settings → Tools library."
    }
}
