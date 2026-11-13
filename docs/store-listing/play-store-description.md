# Axion — Play Store description (draft)

> Status: DRAFT listing text for the P2-10 store-listing prep (Axion roadmap).
> Prepared from the code in this branch (`axion-roadmap-p2`), not from the
> shipped v0.9.4 APK. Before this text is published, re-check every claim
> against the actual release build on a real phone — in particular the GitHub
> agent and browser downloads, which exist in this branch's code but were
> not part of the v0.9.4 release. No ratings, download numbers, or awards
> are claimed anywhere in this file, because none have been verified.

## Short description (75 characters — Play Store limit is 80)

Your phone is the computer: real Linux, a GitHub agent, MCP and offline AI.

## Full description

Hermes needs a server. Axion IS the computer.

Axion is an AI agent that runs on your phone — on your own keys, with no
server and no subscription from us. Your phone runs a real Linux, browses
for real, codes on GitHub, talks to MCP servers, and runs a local model
offline.

**Real Linux on your phone**

Axion installs a real PRoot Ubuntu 24.04 system on your phone and runs
real shell commands in it — Python, Node, git, ffmpeg and more. One
persistent shell session keeps your directory and variables, and long
work runs as background jobs. No server, no remote desktop: the computer
is the phone in your hand.

**A GitHub agent, not just a chatbot**

Connect GitHub in Settings and Axion clones a repo, lists branches,
edits files, commits with your name and email, pushes, opens pull
requests, and reads CI status — from your phone. Changes go through a
review gate first: you approve the diff before anything is committed,
and a push is refused if the diff contains a secret. Pushing to main and
force-pushing always ask you first.

**Your keys, your providers**

Paste a key once and Axion detects which provider it belongs to. Keys
are encrypted with AES-256-GCM in the Android Keystore and are never
logged. If one provider fails or hits a limit, Axion tries the next one
automatically. There is no Axion account and no Axion billing — you pay
your providers only what their own free tiers don't cover, and the local
model costs nothing at all.

**A local model that works offline**

Download LFM3 1.7B (Liquid AI, about 1.1 GB, 4-bit) and chat with it
with no internet at all — summaries, translation, and quick answers on
device. It is chat-only: it does not call tools. Qwen2.5-Coder and Gemma
3 models are available too if your phone has the RAM.

**Real MCP connectors**

Axion is an MCP client: add a remote MCP server (Streamable HTTP) in
Settings and its tools become Axion's tools, with per-server
enable/disable. Gmail, Notion, GitHub and other MCP servers work with
your own accounts.

**A real browser, deep research, and a builder**

Axion has its own in-app browser with JavaScript, logins, and downloads
— the agent and you share the same browser, so it can read a page you
are logged into. Deep Research plans sub-questions, reads sources in
full, and writes a cited report with a Sources list. The Builder turns a
prompt into a working website or app, shows you a live preview on your
phone, and can deploy it to Netlify, Vercel, GitHub Pages, or
Cloudflare Pages with your own token.

**Voice, memory, skills, and schedules**

Talk to Axion in a live voice call, dictate messages, or have replies
read aloud. Axion remembers facts you tell it, learns skills you install
from GitHub, and runs scheduled tasks on its own — once, daily, or
weekly — saving the result as a new chat and notifying you. Shell
commands ask for your approval first, and dangerous commands can never
be auto-approved.

**Free, on your own keys**

Axion itself is free. The Linux, the local model, the browser, the
builder, and the scheduler cost nothing. Cloud answers use your own
provider keys and their free tiers. That is the whole deal: a phone that
runs a real Linux, browses for real, holds its own keys, codes on GitHub,
talks to any MCP server, and runs a local model offline.

## What this description deliberately does NOT claim

- No star rating, download count, award, or "best" / "#1" claim.
- No Telegram or remote control — Axion is phone-only.
- No release-signed APK building — the on-device builder produces a
  debug APK; there is no release-signing path in this build.
- No tool use by the local model — LFM3 is chat-only in this build.
- No exact-to-the-minute scheduling promise — schedules may run a few
  minutes late when exact alarms are not allowed on the phone, and
  nothing fires while the phone is switched off.
- No file upload from the in-app browser — that was not verified in
  this branch's code.

## Sources (code this text was checked against)

All paths are relative to the repo root, branch `axion-roadmap-p2`.

- Real Linux: `app/src/main/java/com/mazharansari/axion/linux/LinuxRuntime.kt`
  (Ubuntu 24.04 rootfs, PRoot) and `agent/ShellTools.kt`.
- GitHub agent: `agent/GitHubTools.kt` — `github_clone`, `github_status`,
  `github_branch`, `github_commit`, `github_push`, `github_pr`,
  `github_ci`; review gate and secret refusal in the tool specs.
- Keys: `data/KeyVault.kt` (AES-256-GCM, Android Keystore),
  `data/Connectors.kt` (key detection), `llm/` fallback chain.
- Local model: `local/LocalModels.kt` — LFM3 1.7B, 1,099,627,808 bytes,
  Q4_K_M, min 4 GB RAM, 8k context, chat-only by design.
- MCP: `mcp/Mcp.kt` (Streamable HTTP client), `agent/ToolCatalog.kt`
  ("connectors" group).
- Browser: `browser/BrowserController.kt` (WebView, downloads via
  `startDownload`), `agent/BrowserTools.kt`, `agent/WebTools.kt`.
- Deep Research: `agent/ResearchTools.kt` (multi-round, Sources list).
- Builder: `build/` (`BuildLoop`, `ProjectVerifier`, deploy targets),
  `ui/home/BuildPane.kt` (preview + deploy).
- Voice: `voice/GeminiVoice.kt`, `voice/LiveVoiceCall.kt`,
  `voice/SameVoiceCall.kt`, `voice/ReadAloudPlayer.kt`,
  `voice/VoiceRecognizer.kt`.
- Memory/skills/scheduler: `agent/LearningTools.kt`, `skills/SkillRepo.kt`,
  `agent/ScheduleTools.kt`, `work/ScheduleEngine.kt`,
  `ui/settings/SchedulesScreen.kt`.
- Approvals gate: `agent/ApprovalGate.kt`, `agent/Agent.kt`.
