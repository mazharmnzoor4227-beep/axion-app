# Axion (Phase 1)

Personal AI agent for Android by Mazhar Ansari. Grok-style UI (Ask / Imagine / Build), a real tool-calling agent, paste-a-key connectors with automatic failover.

* Package `com.mazharansari.axion`, app name in `app/src/main/res/values/strings.xml` (one string).
* Build: `./gradlew :app:assembleRelease` (JDK 17, Android SDK 35). Signing reads `../keys/keystore.properties` (kept outside the source).
* Tests: `./gradlew :app:testDebugUnitTest` (Robolectric). `LiveAgentTest` makes real network calls; `ScreensTest` renders the screenshots into `app/build/screens`.

## Layout
* `llm/` provider catalog (15 providers, Get-key URLs), OpenAI-compatible + native Gemini streaming clients, model ranking, error classification.
* `data/` connectors (Keystore-encrypted keys, auto-detect, cool-downs/failover), conversations, settings.
* `agent/` agent loop + tools: web_search, browse_url, http_request, run_command (hidden sandbox shell), read_file (txt/code/docx/pptx/xlsx), write_file, list_files, extract_zip, create_pdf, generate_image, load_skill, install_skills_from_github, MCP tools.
* `mcp/` remote MCP client (Streamable HTTP) for custom connectors. `skills/` SKILL.md skills (built-in + from any GitHub URL).
* `voice/` dictation (Android speech recogniser) and read-aloud (Gemini TTS → phone TTS fallback).
* `ui/` Compose screens.
