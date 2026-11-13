package com.mazharansari.axion.skills

/**
 * P1-5 SKILL HUB — browse + one-tap install a curated skill list, in-app
 * (Settings → Skills → Skill hub). Three sources, all real, no demo content:
 *
 *   BUILT-IN  the app's own skill set, read live from [SkillRepo] (its asset folders —
 *             pdf, website-audit, deep-research, roman-urdu, video);
 *   MIKA      the 5 translated Mika skills from P0-10, which ship as built-in assets
 *             (mika-content-transcribe, mika-documents, mika-info-research,
 *             mika-messaging, mika-skill-authoring). Licence Apache-2.0, recorded in
 *             app/src/main/assets/skills/MIKA-LICENSE-Apache-2.0.txt and
 *             third_party/NOTICE-mika.md — the front-matter names are content-transcribe,
 *             documents, info-research, messaging, skill-authoring;
 *   COMMUNITY public GitHub skill repos, MIT/Apache only. Every entry's URL was verified
 *             from the code/docs on disk before being listed:
 *             - affaan-m/ECC — MIT (LICENSE file in the clone), git remote
 *               https://github.com/affaan-m/ECC.git, skills/ holds SKILL.md folders.
 *             Deliberately NOT listed: steipete/agent-scripts. It is MIT and the clone
 *             sits at ~/workspace/steipete-agent-scripts with 69 skills, but its repo URL
 *             could not be verified from the code/docs on disk (no git remote, no explicit
 *             URL in its README/AGENTS.MD — only a `steipete/<repo>` placeholder), so no
 *             URL is invented for it. Add it only after its URL is verified.
 *
 * Licence rule (free route): only MIT or Apache-2.0 entries may be installed.
 * [install] refuses anything else instead of installing it.
 */
object SkillHub {

    enum class HubKind { BUILT_IN, MIKA, COMMUNITY }

    data class HubEntry(
        val name: String,
        val description: String,
        /** GitHub repo URL for community entries; "" for built-ins (already in the app). */
        val repoUrl: String,
        /** Licence recorded per entry, e.g. "MIT", "Apache-2.0", "Axion built-in". */
        val licence: String,
        val kind: HubKind,
    )

    /** The 5 translated Mika skills (P0-10), by front-matter name. Licence: Apache-2.0. */
    val MIKA_SKILL_NAMES: Set<String> = setOf(
        "content-transcribe", "documents", "info-research", "messaging", "skill-authoring",
    )

    /** Community entries — only repos whose URL + licence were verified on disk. */
    val COMMUNITY: List<HubEntry> = listOf(
        HubEntry(
            name = "ECC agent skills",
            description = "Agent skills collection (293 skill folders, including agent architecture, evaluation and agentic engineering).",
            repoUrl = "https://github.com/affaan-m/ECC",
            licence = "MIT",
            kind = HubKind.COMMUNITY,
        ),
    )

    /** Only MIT / Apache-2.0 may be installed from the hub (the free route). */
    fun licenceAllowed(licence: String): Boolean {
        val l = licence.trim().lowercase().replace(" ", "").replace("_", "-")
        return l == "mit" || l == "apache-2.0" || l == "apache2.0" || l == "apache-2" || l == "apache"
    }

    /** Entries the hub may install (built-ins are already installed; community must pass the licence rule). */
    fun installable(entries: List<HubEntry>): List<HubEntry> =
        entries.filter { it.kind != HubKind.COMMUNITY || licenceAllowed(it.licence) }

    /**
     * The hub list for the UI: built-ins (with the Mika ones marked Apache-2.0) first,
     * then the curated community entries. Built-ins come from the live [SkillRepo] so the
     * list can never drift from what the app actually ships.
     */
    fun entries(repo: SkillRepo): List<HubEntry> {
        val builtIn = repo.skills.value.filter { it.source == "built-in" }.map { s ->
            val isMika = s.name in MIKA_SKILL_NAMES
            HubEntry(
                name = s.name,
                description = s.description,
                repoUrl = "",
                licence = if (isMika) "Apache-2.0" else "Axion built-in",
                kind = if (isMika) HubKind.MIKA else HubKind.BUILT_IN,
            )
        }
        return builtIn + COMMUNITY
    }

    /** One-tap install of a community entry: refuses non-MIT/Apache licences, then reuses SkillRepo.scanGitHub/install. */
    suspend fun install(repo: SkillRepo, entry: HubEntry): List<Skill> {
        require(entry.kind == HubKind.COMMUNITY) { "Only community entries are installed from the hub — built-in skills are already in the app." }
        require(entry.repoUrl.isNotBlank()) { "No repo URL recorded for ${entry.name}." }
        if (!licenceAllowed(entry.licence)) error("Cannot install ${entry.name}: its licence (${entry.licence}) is not MIT or Apache-2.0.")
        val found = repo.scanGitHub(entry.repoUrl)
        if (found.isEmpty()) error("No SKILL.md files found in ${entry.repoUrl}")
        return repo.install(found, entry.repoUrl)
    }

    /** True when a community entry is already installed (its skills carry the repo URL as source). */
    fun isInstalled(repo: SkillRepo, entry: HubEntry): Boolean =
        entry.repoUrl.isNotBlank() && repo.skills.value.any { it.source == entry.repoUrl }
}
