# Token Efficiency

Every Hermes request costs tokens. Measured on this server (OmniRoute `usage_history`, real Hermes requests, November 2026):

- A fresh session starts at about **10,000–13,000 input tokens** (system prompt + tools + skill index + first message). The smallest fresh-session request measured was **12,345 tokens**.
- The session then GROWS with every turn. One real session grew from ~12k to an average of **~152,000 input tokens** within one hour, latest request **~204,000 tokens**. That session died with "conversation too long" — context overflow already killed one build (Dukaan Manager, first attempt).
- Prompt caching covers about **98% of the repeated context**, so the repeat is cheap in money — but the context window still fills up, and a full window kills the session. Cached tokens are not free context.
- A fresh (non-cached) turn in a long session still adds about **2,000–3,000 new tokens**.

The single biggest token saver is a short session. Everything below serves that.

## 1. New task = new session

- Start EVERY new task with `/new` first, then send the prompt. Never continue an old session with an unrelated task.
- Never let one session grow across several jobs. If the user sends a new job into an old session, do the job, then tell them the next job should start with `/new`.
- If a session passes roughly 30–50 exchanges, or the user reports "too long" / context errors, stop, give a 3–5 line summary of where the work stands, and tell the user to continue with `/new` plus that summary.
- Split big work into phases with a fresh `/new` between phases and a short handoff summary (what is done, what is next, file paths). Do not carry a whole long session across phases.

## 2. Smallest adequate model for the task

- Do not route a one-line question through the heavy default. Use the lightest model that can do the job well: quick questions and short answers go to the smallest fast model available; full builds and deep reasoning use the default.
- NEVER change the configured default model (`openrouter/auto`) or the fallback chain without the user saying so. Choosing a lighter model for one small reply is fine; changing the configuration is not.
- If the default model is rate-limited (429), let the fallback chain handle it. Do not retry the same heavy request in a loop.

## 3. Read once, read targeted

- Never re-read a file you already read in this session. Keep what you read in mind and work from it.
- Read targeted ranges (`grep`, `sed -n`, line ranges), never whole large files when only one function or section matters.
- Do not list whole directories to "have a look" — check the one path the task needs.
- Do not re-run a command that already answered the question. One live check, not three.

## 4. Batch independent calls, keep outputs concise

- Batch independent tool calls into one block instead of one call per turn. Each extra turn re-sends the whole conversation.
- Keep replies short and direct: no filler, no repeating the user's message back, no long explanation when a short answer plus the result (link, file, or diff) will do.
- Write minimal code (see `ponytail-minimal-code`): less code written means fewer output tokens and fewer fix-up turns.
- Verify once, properly, instead of verifying the same thing three different ways.

## 5. Keep the skill index lean

- Skills load on demand — only the skill index (name + one-line description per skill) rides in every request, not the full skill text. But every enabled skill still adds to that index, so do not teach redundant or overlapping skills. If two skills cover the same ground, use one.
- Do not create a new skill for a one-off job. Skills are for rules that repeat.
- The 71 enabled skills (November 2026) cost roughly 40 tokens each in the index — about 3,000 tokens of the ~12,000 baseline. Disabling skills to save tokens is a bad trade: it breaks the teaching the user ordered, and saves almost nothing next to session growth (~190,000 tokens). Session hygiene is the fix, not fewer skills.

## 6. How to check the numbers (for future audits)

- Per-request tokens live in OmniRoute storage: `~/.omniroute/storage.sqlite`, table `usage_history` (`tokens_input`, `tokens_cache_read`, `tokens_output`, `timestamp`, `provider`, `model`).
- Fresh-session baseline: rows with `tokens_cache_read = 0` and `tokens_input > 500`.
- Session growth: average `tokens_input` per hour for the current provider/model.
- If a new baseline measurement is ever needed, it can only come from a real fresh session's first request — do not trigger a live request just to measure. Report the log numbers instead.
