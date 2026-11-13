# Axion plugins — community tools

A plugin is a Kotlin class compiled into the app that adds extra tools to
Axion's agent. Plugins let the community extend what the agent can do
without touching the built-in tools.

## The interface

Every plugin implements `AxionPlugin`
(`app/src/main/java/com/mazharansari/axion/agent/PluginApi.kt`):

```kotlin
interface AxionPlugin {
    val id: String          // lowercase letters, digits and dashes only, e.g. "sample-tools"
    val name: String        // human-readable name, must not be blank
    val version: String     // e.g. "1.0.0", must not be blank
    val description: String // one line describing what the plugin adds
    val tools: List<Tool>   // the tools this plugin contributes
}
```

Each tool is the same `Tool` interface the built-ins use
(`agent/Tool.kt`): a `ToolSpec` (name, description, JSON-schema parameters),
a `label()` for the visible step card, and a suspending `run(args, ctx)`
that returns a `ToolOutput`.

## The manifest

A plugin ships a `plugin.json` manifest next to its source. It is
descriptive only — it never loads code, it just declares what a plugin
contains. `PluginManifestParser.parse(json)` returns `null` (never throws)
for malformed JSON, missing fields, or a bad id.

Full example (matches the sample plugin below):

```json
{
  "id": "sample-tools",
  "name": "Sample Tools",
  "version": "1.0.0",
  "description": "Example plugin: text statistics and unit conversion.",
  "toolNames": ["text_stats", "unit_convert"]
}
```

## How to register

Plugins are registered once at startup, before the agent is built. The
registry is then handed to the coordinator, which adds the plugin tools to
the agent alongside the built-ins. Registration is rejected (as a
`Result.failure` with a plain message) when:

- the plugin id is already registered, invalid, or the name/version is blank;
- a tool name is blank, repeated inside one plugin, or already exposed by
  another plugin;
- a tool name collides with a built-in tool name — a plugin can never shadow
  or replace a built-in such as `run_command`. Pass the built-in names in
  when you create the registry (see `ToolCatalog` for the full list).
- a tool name starting with `mcp_` — that prefix is reserved for connector tools.

```kotlin
val registry = PluginRegistry(builtInNames = ToolCatalog.groups.flatMap { it.tools }.toSet())
val result = registry.register(SamplePlugin())
check(result.isSuccess) { "Plugin rejected: ${result.exceptionOrNull()?.message}" }

// Later, the agent uses registry.allTools() alongside the built-ins.
val pluginTools: List<Tool> = registry.allTools()
val names: Set<String> = registry.toolNames()

// Unregistering removes the plugin and frees its tool names.
registry.unregister("sample-tools")
```

## The ToolSpec JSON-schema format

A tool's parameters are a `kotlinx.serialization` `JsonObject` built with
`buildJsonObject`, describing a JSON-schema object. The model only ever
sends arguments that match this schema, so declare every argument and mark
the required ones:

```kotlin
ToolSpec(
    name = "text_stats",
    description = "Count the words, characters and lines in a piece of text.",
    parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "The text to count.")
            }
        }
        putJsonArray("required") { add("text") }
    },
)
```

Use `"string"`, `"number"`, `"integer"` or `"boolean"` for the argument
types. There is also a small `schema { str(...) }` builder in
`agent/Schema.kt` if you prefer it — it produces the same object.

## What ToolContext offers

`run(args, ctx)` receives the same `ToolContext` as built-in tools:

- `ctx.workspace` — Axion's workspace (`File` root, `uploads/`, `outputs/`).
  Use `workspace.resolve(path)` so a path can never escape the workspace.
- `ctx.skills` — the installed skills (`SkillRepo`).
- `ctx.memory` — the agent-curated persistent memory (null on surfaces
  without it, e.g. live calls).
- `ctx.chats` — saved conversations, for session search (null where absent).
- `ctx.schedules` / `ctx.appCtx` — scheduled tasks and the application
  context for arming WorkManager (null where absent).
- `ctx.ask` — one plain prompt to one plain answer from the chat models
  (null where there is no model behind the surface; degrade to
  deterministic behaviour then).
- `ctx.turbo` — whether this task runs commands on the user's own server.
- `ctx.progress` — live output for long-running work.

Read arguments with the helpers in `agent/Schema.kt`: `args.s("text")`
(`String?`), `args.i("count")` (`Int?`), `args.b("flag")` (`Boolean?`).
For a JSON number that may arrive as a string, read the `JsonPrimitive`
and use `doubleOrNull ?: contentOrNull?.toDoubleOrNull()`.

## Returning results and errors

Return `ToolOutput(forModel = "...")` — that string is what the model reads,
and it is also what the step card shows unless you set `display`. Never
throw for bad input: validate arguments and return
`ToolOutput(forModel = "…what is wrong…", isError = true)` with a plain
message, so the model can correct itself and try again. Set `doneTitle`
when the finished step deserves a better title than the tool name
(e.g. `"Converted km to miles"`).

## The approval rule for shell work

Plugin tools run with exactly the same `ToolContext` as built-in tools.
There is no sandbox and no separate privilege level for plugins. A plugin
tool that runs shell commands **must** go through the same approval gate as
the built-in shell tools — the `ApprovalGate` that lives in the agent.
That gate is the one place that decides what needs the user's OK, and this
plugin API deliberately does not implement a second one. A plugin must
never run a command, touch a file outside the workspace, or reach the
network on its own authority. A plugin that needs shell access should ask
the coordinator to route its call through the gate, exactly like
`run_command` does.

## Out of scope: no external code loading

Axion never loads plugin APKs, DEX files, or any other code at runtime.
Plugins are Kotlin classes compiled into the app or supplied as source and
compiled with it. Anything that would need downloading and executing
foreign code must be reviewed as a full app release, not shipped through
this API.

## Sample plugin

`app/src/main/java/com/mazharansari/axion/agent/plugins/SamplePlugin.kt`
is the reference implementation to copy. It is developer documentation in
code form — it is **not** auto-registered anywhere, and having the class on
the classpath adds no tools to the agent until startup code registers it
explicitly. It exposes two real, deterministic, offline tools:

- `text_stats` — input text, returns word, character and line counts.
- `unit_convert` — a value plus `from` and `to` units among km/miles,
  kg/lb, m/ft, C/F; returns the converted number, or an error for an
  unknown unit.
