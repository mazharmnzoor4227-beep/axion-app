---
name: agent-building
description: Build autonomous AI agents the Hermes way — tools, skills, memory and learning, planning, deep research, safety gates, provider fallbacks, and long-task execution. Use when the user asks to build, improve, or teach an agent (Hermes, Axion, or a new one).
version: 1.0.0
author: User custom skill (written for Hermes)
license: MIT
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [agent, autonomous-agent, tools, skills, memory, planning, research, safety, hermes, axion]
    related_skills: [hermes-agent, claude-code, codex, computer-use, local-pre-delivery-verification-gate, local_verify_before_recommending]
---

# Building Autonomous AI Agents

## The loop every agent runs

Plan → act → verify → remember. Never skip the verify step: a task is done
only when the result was checked against live evidence (a command's output, a
served page, a real device), never from code inspection alone.

## Tools first, chat second

- An agent is only as good as its tools. Give it a small set of real tools
  (shell, file read/write, web fetch/search, provider calls) with clear
  boundaries, not a long list of vague abilities.
- Every tool call must be traceable: log what was called, with what, and what
  came back. A tool that silently fails is worse than no tool.
- Destructive actions (delete, publish, spend, send) go behind a CONFIRM
  gate: the agent asks once, remembers the approval for that task, and never
  asks twice for the same approval. Bank/payment actions always ask, every time.

## Memory and learning

- Keep a short per-run memory snapshot (what was decided, what failed, what
  worked) and a durable long-term memory for facts that must survive sessions.
- Let the agent write its own "Learned" skills from repeated work: when the
  same procedure succeeds twice, distill it into a SKILL.md so the next run
  starts from the procedure, not from scratch.
- Never store keys, passwords, or tokens in memory, skills, or logs. Keys live
  only in the provider config / secure vault.

## Deep research (when the user wants real answers)

- PLAN first: write the questions, then search, then read real pages.
- Run a GAP LOOP: after the first pass, list what is still unanswered and
  search again specifically for those gaps (2–3 rounds, ~20+ sources for hard
  topics).
- Cite every claim. Cross-check key facts against 2 sources; if only one
  source supports a fact, say so ("single source").
- Distinguish what was verified firsthand from what a search snippet claims.

## Providers and models

- Route the agent through a router (OmniRoute or equivalent) so providers can
  be swapped without touching the agent. Set a default model and a fallback
  provider (`hermes fallback add`) for when the default fails.
- Mind the token budget: an agent's full request (system prompt + tools +
  memory + conversation) can be ~12K tokens. A provider whose per-minute
  token cap is smaller than one request can NEVER serve that agent — test with
  a real ~13K-token prompt before declaring a model "working".
- Test a new model with a tiny prompt first (fast fail), then a big prompt
  (real load), then the real task through the real channel (e.g. Telegram).

## Skills

- Skills are SKILL.md files with YAML frontmatter (`name`, `description`,
  `version`, `author`, `license`, `platforms`, `metadata.hermes.tags`).
  Install from registries with `hermes skills search` / `hermes skills install`,
  or write custom ones for the user's own workflows and place them under
  `~/.hermes/skills/<category>/<name>/SKILL.md`, then run
  `hermes skills audit` and confirm with `hermes skills list`.
- A skill must be loadable from its SKILL.md alone: no hidden dependencies,
  no credentials inside, no paths that only exist on one machine.

## Long tasks

- Long work runs in the background with stage updates (the user watches for a
  working indicator). Send progress as the work moves; never leave the user
  guessing whether anything is happening.
- Finish a handed task end to end in one flow (build → sign → publish →
  verify → report). Never stop midway waiting for the next message.
- If a step cannot run in this environment, say so and leave it unverified —
  never fold it into a pass.

## Safety and licenses

- Never copy code from a repo whose license forbids it (e.g. AGPL code, or a
  license that bars distributing a component inside another product). Ideas
  and behaviour may be rebuilt in the agent's own code; the code itself must
  be original or permissively licensed.
- Never ask the user to paste a key, password, or token in chat. Keys are
  entered by the user on a secure page or in the provider dashboard.
- Small test tasks only for powerful capabilities (screen control,
  accessibility, payments). Never bank or payment work without explicit
  per-action approval.

## Delivery

- Deliver through the channel the user actually uses (Telegram, CLI, app).
  Text is a short skeleton; full detail goes in a voice note or a linked
  document, never in a wall of text.
- Every delivery states plainly what was verified, what is still pending, and
  what the user needs to do next — in that order.
