package com.mazharansari.axion.livecall

import com.mazharansari.axion.agent.BrowseTool
import com.mazharansari.axion.agent.HttpRequestTool
import com.mazharansari.axion.agent.Tool
import com.mazharansari.axion.agent.ToolCatalog
import com.mazharansari.axion.agent.ToolContext
import com.mazharansari.axion.agent.WebSearchTool
import com.mazharansari.axion.agent.Workspace
import com.mazharansari.axion.agent.clip
import com.mazharansari.axion.skills.SkillRepo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

/**
 * v0.7.0: the Live call's hands. The SAME Tool objects the chat agent uses (web research),
 * exposed to the Gemini Live model as function declarations and executed through the same code paths —
 * so the Tools-library switches apply in a call exactly as they do in chat. Plus one synthetic tool,
 * end_call, which the session turns into a hang-up after the model's spoken goodbye.
 *
 * Built by the call screen from the app graph; a null bridge (never built) leaves the call voice-only.
 */
class LiveToolBridge private constructor(
    val declarations: List<LiveCallProtocol.LiveToolDecl>,
    private val toolsByName: Map<String, Tool>,
    private val context: ToolContext,
) {
    /** Executes one call. Never throws: a failure becomes a result the model can explain to the user. */
    suspend fun execute(name: String, args: JSONObject): JSONObject {
        if (name == LiveCallProtocol.END_CALL) return JSONObject().put("result", "The call is ending now.")
        val tool = toolsByName[name] ?: return JSONObject().put("result", "That tool is not available in this call.")
        val parsed = runCatching { Json.parseToJsonElement(args.toString()).jsonObject }.getOrElse {
            return JSONObject().put("result", "The arguments for $name were not valid JSON.")
        }
        val out = runCatching { tool.run(parsed, context) }
            .getOrElse { e -> return JSONObject().put("result", "That tool failed: ${e.message ?: "unknown error"}") }
        // A voice model needs the gist, not a whole page: keep results speakable.
        return JSONObject().put("result", out.forModel.clip(4000))
    }

    companion object {
        /**
         * The call's tool set: web group (when switched on) + end_call, always.
         * ToolCatalog switches filter every group exactly as in chat.
         */
        fun build(workspace: Workspace, skills: SkillRepo, switches: Map<String, Boolean>): LiveToolBridge {
            val candidates: List<Tool> = listOf(WebSearchTool(), BrowseTool(), HttpRequestTool())
            val enabled = candidates.filter { ToolCatalog.isEnabled(it.spec.name, switches) }
            val declarations = enabled.map { tool ->
                LiveCallProtocol.LiveToolDecl(
                    name = tool.spec.name,
                    description = tool.spec.description,
                    parameters = JSONObject(tool.spec.parameters.toString()),
                )
            } + LiveCallProtocol.LiveToolDecl(
                name = LiveCallProtocol.END_CALL,
                description = "End this call. Call it only after a short spoken goodbye, when the user says goodbye (for example \"Axion ab tum jao\" or \"bye\").",
                parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
            )
            return LiveToolBridge(declarations, enabled.associateBy { it.spec.name }, ToolContext(workspace, skills))
        }
    }
}
