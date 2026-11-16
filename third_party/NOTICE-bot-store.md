# Bot Store seeds — credits

The Bot Store (v0.9.7, `data/BotCatalog.kt`) ships installable bot DEFINITIONS —
persona + skills + tool allow-list — written here in Axion's own words from the
PATTERNS of the sources below. No source code was copied from any of them, and
no installed bot runs foreign code: every bot runs on Axion's own agent loop
with its manifest's tool allow-list and the swarm worker caps.

- **Coworker** — OpenDots (CopilotKit), https://github.com/CopilotKit/OpenDots — MIT.
  A persistent teammate that keeps context across chats (named agents with a role
  and a tool allow-list, approval-gated actions, background work).
- **Learner, Researcher, Scheduler** — Hermes Agent (Nous Research),
  https://github.com/NousResearch/hermes-agent — MIT. Self-improving skill loop,
  research with citations, scheduled jobs. See also third_party/NOTICE-hermes.md.
- **Coder** — Ponytail (Dietrich Gebert), https://github.com/DietrichGebert/ponytail — MIT
  (minimal-code / YAGNI rules for coding agents) + Mika (wangzan101),
  https://github.com/wangzan101/Mika — Apache-2.0 (phone-agent skills; 5 already
  ported in Axion, see third_party/NOTICE-mika.md).
- **Reviewer** — Koog (JetBrains), https://github.com/JetBrains/koog — Apache-2.0
  (planning strategy, history compression, fault tolerance — ideas/patterns;
  see third_party/NOTICE-koog.md).

Never in the store: Andee code (licence A.1(d) forbids it as a component),
Locus (AGPL) code, Aether (GPL) code, Operit (LGPL) code. Behaviour/ideas from
those are fine; their code is not.
