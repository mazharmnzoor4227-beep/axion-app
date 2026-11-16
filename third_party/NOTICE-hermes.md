# Hermes Agent (Nous Research) — credit (MIT)

Source: https://github.com/NousResearch/hermes-agent — MIT Licence (LICENSE file in that repo; verified 2026-11-16).

Axion v0.9.7 ("Swarm") takes **patterns** from Hermes Agent — the learning loop
(the agent creates/improves skills from experience), subagent delegation with
per-subagent provider/model/limits, the built-in cron scheduler, and memory search
across sessions — and implements them in Axion's own Kotlin
(`agent/Swarm.kt`, `agent/SwarmVerify.kt`, `agent/SwarmRuns.kt`, `data/BotStore.kt`,
`data/BotCatalog.kt`). The learning loop, scheduler and memory search already
existed in Axion (SkillRepo.saveLearned + skill_create/skill_update,
SchedulesStore, session_search + MemoryStore); v0.9.7 adds the swarm-specific
nudge and the Bot Store's "Learner"/"Researcher"/"Scheduler" bot definitions,
which follow Hermes's patterns in Axion's own words.

Hermes Agent is Python/TypeScript/Node; **no Hermes source code was copied,
translated or depended on**. The SKILL.md skill texts bundled in
`app/src/main/assets/skills/` from the Hermes-port set are the agentskills.io
skill format the project uses; their authorship is noted in
`app/src/main/assets/skills/HERMES-NOTICE.txt`. The "local-*" and ponytail
texts in that set are the user's own custom skills, repackaged in that format
and bundled as-is, not edited.
