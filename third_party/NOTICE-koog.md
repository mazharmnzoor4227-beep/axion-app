# Koog (JetBrains) — credit (Apache-2.0)

Source: https://github.com/JetBrains/koog — Apache License 2.0 (as stated in the Axion upgrade brief; the licence file path was not re-checked by the build agent).

Axion v0.6 Phase 2 (agent/Planning.kt, agent/HistoryCompressor.kt, agent/FaultPolicy.kt) takes **design ideas** from Koog's public README: an agent run as a
strategy of nodes (plan → execute → finish), "history compression" to keep long conversations inside a token budget, and fault-tolerant
tool execution (retry, recover). Only the README was read; **no Koog source code was copied, translated or depended on** — the Koog library is not
a dependency. The implementations are Axion's own Kotlin (deterministic, extractive history compression; retry-once-then-alternative-then-report policy).
