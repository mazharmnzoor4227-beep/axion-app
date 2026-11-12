package com.mazharansari.axion.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppSettings(
    val theme: String = "dark",          // system | dark | light
    val textScale: Float = 1f,
    val haptics: Boolean = true,
    val hapticsStreaming: Boolean = false,
    val appLanguage: String = "English",
    val speechLang: String = "ur-PK",    // dictation + read-aloud language
    val voice: String = "Kore",          // Gemini prebuilt voice
    val naturalVoice: Boolean = false,    // Gemini TTS when a Gemini key exists, else Android TTS
    val autoRead: Boolean = false,
    val userName: String = "",
    val assistantName: String = "Axion",
    val instructions: String = "",
    val replyLanguage: String = "",
    val mode: String = "AUTO",
    /** v0.6 Build: the AI's file changes wait for Approve (diff review) before they touch the project. */
    val reviewBeforeApply: Boolean = true,
    /** v0.6.1: a project too large to hold for review (over the ChangeGate limits) may run un-reviewed ONLY if the user switched this on — never silently. */
    val allowLargeUngated: Boolean = false,
    /** v0.6 Tools library: group id to on/off (missing = the group default; Phone control defaults to OFF). */
    val toolSwitches: Map<String, Boolean> = emptyMap(),
    /** v0.6 Phone control: the user read the permission screen and agreed. Without it no phone tool ever runs. */
    val phoneConsent: Boolean = false,
    /** Phase B: "Hey Axion" wake word. Default OFF — the microphone foreground service runs only while this is on. */
    val wakeWordEnabled: Boolean = false,
    /** Phase F (v0.8.5): the assistant reads new message notifications aloud. Default OFF; also needs the system's Notification access grant. */
    val assistantAnnounce: Boolean = false,
    /** Phase F (v0.8.7): Axion's floating character lives on the home screen (needs the Accessibility service, which hosts its window). Default ON — the owner's chosen main surface. */
    val assistantOrb: Boolean = true,
    val pasteAsFile: String = "Always ask",
    /** Developer tools › Free Mode: route only to free-tier providers (and Antigravity / Free (slow)). */
    val freeMode: Boolean = false,
    /** v0.11: the composer's Research toggle — while on, factual questions go through the deep_research pipeline. */
    val deepResearch: Boolean = false,
    /** v0.12: the composer's Long task toggle — while on, ACTION turns get a far bigger step/tool budget and plan-verify-finish guidance. */
    val longTask: Boolean = false,
    /** v0.5: the Build first-run "Install developer tools" popup was shown (addendum 2). */
    val buildIntroShown: Boolean = false,
    /** v0.5.3: the user chose "Continue as guest" on the sign-in screen (it then stays away; Settings → Account signs in). */
    val guestChosen: Boolean = false,
    /** v0.5.3 first run: the intro animation and the permissions screens were shown once. */
    val introShown: Boolean = false,
    val permsShown: Boolean = false,
)

class SettingsStore(ctx: Context) {
    private val file = File(ctx.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _s = MutableStateFlow(runCatching { json.decodeFromString<AppSettings>(file.readText()) }.getOrDefault(AppSettings()))
    val state: StateFlow<AppSettings> = _s.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun update(f: (AppSettings) -> AppSettings) {
        _s.value = f(_s.value)
        val snap = _s.value
        io.launch { synchronized(file) { file.writeText(json.encodeToString(snap)) } }
    }
}

/** Conversations as one JSON file each; the drawer reads a small in-memory index. Writes are coalesced off the main thread. */
class ConversationStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "chats").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val _index = MutableStateFlow<List<ConversationMeta>>(emptyList())
    val index: StateFlow<List<ConversationMeta>> = _index.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val pending = HashMap<String, Job>()

    init {
        io.launch {
            _index.value = dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
                runCatching { json.decodeFromString<Conversation>(f.readText()) }.getOrNull()?.let(::meta)
            }.sortedByDescending { it.updatedAt }
        }
    }

    private fun meta(c: Conversation) = ConversationMeta(c.id, c.title, c.pinned, c.updatedAt,
        c.messages.lastOrNull { it.role == Role.ASSISTANT }?.text?.take(80).orEmpty(), c.section)

    suspend fun load(id: String): Conversation? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<Conversation>(File(dir, "$id.json").readText()) }.getOrNull()?.let(::settle)
    }

    /** v0.5.2 bug hunt: a reply that was streaming when Android killed the app stayed "Thinking…" forever on reopen. */
    internal fun settle(c: Conversation): Conversation =
        if (c.messages.none { it.status == MsgStatus.STREAMING }) c
        else c.copy(messages = c.messages.map { if (it.status == MsgStatus.STREAMING) it.copy(status = MsgStatus.INTERRUPTED, notice = "",
            steps = it.steps.map { st -> if (st.status == StepStatus.RUNNING) st.copy(status = StepStatus.ERROR) else st }) else it })

    /** Debounced save: during streaming this runs at most every 600 ms. */
    fun save(c: Conversation, immediate: Boolean = false) {
        val m = meta(c)
        _index.value = (listOf(m) + _index.value.filterNot { it.id == c.id }).sortedWith(compareByDescending<ConversationMeta> { it.updatedAt })
        synchronized(pending) {
            pending[c.id]?.cancel()
            pending[c.id] = io.launch {
                if (!immediate) delay(600)
                File(dir, "${c.id}.json.tmp").apply { writeText(json.encodeToString(Scrub.conversation(c))); renameTo(File(dir, "${c.id}.json")) }
            }
        }
    }

    /**
     * v0.10 (Hermes learning loop): full-text search over every saved conversation — the agent's
     * `session_search` tool. Every keyword must appear in a message; each hit carries an excerpt
     * around the first matching keyword so the model can answer "what did we decide about …?"
     * without loading whole chats. Newest conversations first.
     */
    suspend fun search(query: String, limit: Int = 8): List<ChatSearchHit> = withContext(Dispatchers.IO) {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        if (words.isEmpty()) return@withContext emptyList()
        val hits = mutableListOf<ChatSearchHit>()
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }
        for (f in files) {
            if (hits.size >= limit) break
            val c = runCatching { json.decodeFromString<Conversation>(f.readText()) }.getOrNull() ?: continue
            for (m in c.messages) {
                if (hits.size >= limit) break
                val text = m.text
                if (text.isBlank()) continue
                val lower = text.lowercase()
                if (!words.all { it in lower }) continue
                val at = words.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
                val from = (at - 90).coerceAtLeast(0)
                val to = (at + 190).coerceAtMost(text.length)
                hits += ChatSearchHit(c.id, c.title, c.updatedAt, m.role,
                    (if (from > 0) "…" else "") + text.substring(from, to).replace('\n', ' ').trim() + if (to < text.length) "…" else "")
            }
        }
        hits
    }

    fun delete(id: String) { _index.value = _index.value.filterNot { it.id == id }; io.launch { File(dir, "$id.json").delete() } }

    fun rename(id: String, title: String) = io.launch { load(id)?.let { save(it.copy(title = title), true) } }
    fun pin(id: String, pinned: Boolean) = io.launch { load(id)?.let { save(it.copy(pinned = pinned), true) } }
    fun deleteAll() { _index.value = emptyList(); io.launch { dir.listFiles()?.forEach { it.delete() } } }
}

/** v0.5.2: chats are saved with every API key / token masked (gsk_••••abcd) — text, thinking, steps, errors. */
object Scrub {
    private fun s(t: String) = com.mazharansari.axion.security.SecretScanner.scrub(t)
    fun message(m: Message): Message = m.copy(
        text = s(m.text), reasoning = s(m.reasoning), error = s(m.error),
        steps = m.steps.map { st -> st.copy(detail = s(st.detail), output = s(st.output), target = s(st.target), title = s(st.title)) },
    )
    fun conversation(c: Conversation): Conversation = c.copy(title = s(c.title), messages = c.messages.map(::message))
}
