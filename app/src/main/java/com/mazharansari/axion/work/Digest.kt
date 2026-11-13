package com.mazharansari.axion.work

import com.mazharansari.axion.Graph
import com.mazharansari.axion.data.ScheduleEntry
import com.mazharansari.axion.data.ScheduleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2-11 (scheduled digests + proactive briefings): a daily briefing the user
 * asked for — overnight research, repo CI status, quota left — delivered as a
 * chat message at a set time, on the fixed scheduler (P0-7/P1-8). The briefing
 * is COMPOSED from real state, never invented: a section with nothing to report
 * says so in plain words, and a CI check that could not be read says that too.
 */
object DigestBuilder {
    const val DIGEST_ID = "daily-digest"
    const val DEFAULT_HOUR = 8
    const val DEFAULT_MINUTE = 0

    /** The schedule entry for the daily briefing (one entry, created from the Schedules screen). */
    fun digestEntry(hour: Int = DEFAULT_HOUR, minute: Int = DEFAULT_MINUTE) = ScheduleEntry(
        id = DIGEST_ID, title = "Daily briefing", prompt = "Daily briefing",
        type = ScheduleType.DAILY, hour = hour, minute = minute, digest = true,
    )

    /** Pure text composition — unit-tested without Android. */
    fun compose(dateLine: String, research: List<String>, ci: List<String>, quota: List<String>): String = buildString {
        appendLine("Daily briefing — $dateLine")
        appendLine()
        appendLine("Overnight research")
        if (research.isEmpty()) appendLine("No new research since yesterday.") else research.forEach { appendLine(it) }
        appendLine()
        appendLine("Repo CI status")
        if (ci.isEmpty()) appendLine("No repo to check yet.") else ci.forEach { appendLine(it) }
        appendLine()
        appendLine("Quota left")
        if (quota.isEmpty()) appendLine("Nothing measured yet.") else quota.forEach { appendLine(it) }
    }.trim()

    /** Newest research files first: "• <name> — 12 Nov, 09:41". */
    fun researchLines(files: List<Pair<String, Long>>): List<String> {
        val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH)
        return files.map { (name, at) -> "• ${name.removeSuffix(".md").replace('_', ' ')} — ${fmt.format(Date(at))}" }
    }

    /** Quota lines from the measured model: Antigravity accounts left + runs today, API keys left. */
    fun quotaLines(agyCombined: Double, agyCount: Int, agyRunsToday: Int, keysLeft: Double, keysLimit: Double, earliestResetAt: Long?): List<String> {
        val lines = mutableListOf<String>()
        if (agyCount > 0) {
            lines += "• Antigravity: ${"%.1f".format(Locale.US, agyCombined)} of $agyCount account${if (agyCount != 1) "s" else ""} quota left · $agyRunsToday run${if (agyRunsToday != 1) "s" else ""} today"
            earliestResetAt?.let { lines += "• Antigravity quota resets at ${SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH).format(Date(it))}" }
        }
        if (keysLimit > 0) lines += "• API keys: ${"%.0f".format(Locale.US, keysLeft)} of ${"%.0f".format(Locale.US, keysLimit)} left today"
        return lines
    }
}

/** Reads the three briefing inputs from real app state and composes the text. */
class DigestComposer(private val g: Graph) {

    suspend fun compose(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        DigestBuilder.compose(
            SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(now)),
            researchSection(now),
            ciSection(),
            quotaSection(),
        )
    }

    /** Research files written in the last 24 hours, newest first (max 5). */
    private fun researchSection(now: Long): List<String> {
        val dir = File(g.workspace.outputs, "research")
        val files = dir.listFiles()
            ?.filter { it.isFile && now - it.lastModified() < 24 * 60 * 60 * 1000L }
            ?.sortedByDescending { it.lastModified() }
            ?.take(5)
            ?.map { it.name to it.lastModified() }
            .orEmpty()
        return DigestBuilder.researchLines(files)
    }

    /** CI status for the repos in the recent GitHub audit log (max 3, most recent first). */
    private suspend fun ciSection(): List<String> {
        val gh = g.github
        if (!gh.isConnected()) return listOf("GitHub is not connected yet — connect it in Settings to see repo CI status.")
        val repos = gh.audit.list().map { it.repo }.distinct().take(3)
        if (repos.isEmpty()) return listOf("No repo activity yet — the next build or push will show up here.")
        return repos.mapNotNull { repo ->
            val parts = repo.split("/", limit = 2)
            val owner = parts.getOrNull(0).orEmpty()
            val name = parts.getOrNull(1).orEmpty()
            if (owner.isBlank() || name.isBlank()) return@mapNotNull null
            runCatching { gh.checks(owner, name, "main") }.fold(
                onSuccess = { "• $repo (main)\n$it" },
                onFailure = { "• $repo: could not read CI status (${it.message ?: "unknown error"})" },
            )
        }
    }

    /** Quota left, from the measured quota model. */
    private fun quotaSection(): List<String> {
        val m = runCatching { g.quota.model() }.getOrNull() ?: return emptyList()
        return DigestBuilder.quotaLines(m.agyCombined, m.agy.size, m.agyRunsToday, m.totals.left, m.totals.limit, m.agyEarliestReset)
    }
}
