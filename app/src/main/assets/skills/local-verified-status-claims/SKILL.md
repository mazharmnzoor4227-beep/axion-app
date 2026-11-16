

# Verified Status Claims

## Purpose
Ensure every status statement about ongoing or completed work is backed by a handle a second check can follow, so the user never has to probe to discover that claimed work never ran.

## Workflow
1. Before saying work is running in the background, name the actual mechanism (background exec, subagent, CI run, cron) and its id. Work done inside the current chat turn is chat-time work — say so plainly; never imply it continues between chats unless a real background handle exists.
2. Before saying code was pushed, verify the push landed: check the remote commit (e.g. `git ls-remote` or the CI run triggered by that commit). A local script existing, or having existed before a /tmp cleanup, is not a push.
3. Before saying a build is running or passed, cite the CI/local run id and its current state; before saying a deploy is live, fetch the served content and grep for the new markers — an HTTP 200 or a deploy tool's success message is not live verification.
4. Keep working scripts and tooling under a durable workspace path (e.g. `~/workspace/gh-tools/`), not /tmp, when later steps depend on re-running them.
5. When a status turns out to have been wrong, correct it in the same conversation, unprompted, naming what actually happened and what is true now.

## Output Contract
Every progress or completion report carries its handles inline: background run id or an explicit 'done in this chat, nothing running', commit hash for a push, run id for a build, and the live-check result (marker found / not found) for a deploy. A claim without its handle is not sent.

## Operating Rules
- 'Continuing', 'in progress', and 're-running' are claims, not filler — they require the same handle as 'done'.
- If verification cannot be performed yet, report the last verified state and what remains unverified.
- Never let a summary of earlier sections or rounds upgrade a step's real state (planned, written, pushed, built, delivered are distinct states — use the exact one).
## Available connectors

Use `muse.connector_search` to find the current connector docs before following
recovered instructions.
