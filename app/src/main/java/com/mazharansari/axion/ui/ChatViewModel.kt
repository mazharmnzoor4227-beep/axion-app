package com.mazharansari.axion.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mazharansari.axion.AxionApp
import com.mazharansari.axion.agent.*
import com.mazharansari.axion.data.*
import com.mazharansari.axion.llm.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val g = (app as AxionApp).graph

    var conversationId by mutableStateOf<String?>(null); private set
    var title by mutableStateOf("")
    var messages by mutableStateOf<List<Message>>(emptyList()); private set
    var running by mutableStateOf(false); private set
    val pending = mutableStateListOf<Attachment>()
    var draft by mutableStateOf("")
    var toast by mutableStateOf<String?>(null)
    /** v0.5.3 tests/screenshots: Storage screen with fixed numbers (items, free bytes). */
    var debugStorage by mutableStateOf<Pair<List<com.mazharansari.axion.linux.StorageItem>, Long>?>(null)
    /** Screenshot-test hooks: 1 = + popover open, 2 = model popover open; non-null opens the Thoughts sheet with that text. */
    var debugPopover by mutableStateOf(0)
    var debugThoughts by mutableStateOf<String?>(null)
    /** Screenshot-test hook: non-null shows the call screen in this state without opening the mic. */
    var debugCall by mutableStateOf<com.mazharansari.axion.voice.SameVoiceCall.State?>(null)
    // v0.3.1 render hooks (tests only): speed list open, pull-up progress (0..1+), PDF viewer open on an artifact name
    var debugSpeedMenu by mutableStateOf(false)
    var debugPull by mutableStateOf(0f)
    var debugViewer by mutableStateOf<String?>(null)
    // v0.4 render hooks (tests only): open a screen at start, a toolchain sheet, the Antigravity sign-in sheet in a given state
    var debugStart by mutableStateOf<com.mazharansari.axion.ui.Route?>(null)
    var debugToolSheet by mutableStateOf<com.mazharansari.axion.linux.Toolchain?>(null)
    var debugAgyLogin by mutableStateOf<com.mazharansari.axion.antigravity.AgyLoginState?>(null)
    var debugHomeTab by mutableStateOf(-1)
    // v0.5 render hooks (tests only): project tab, an open file, editor in edit mode, the publish sheet, the Build first-run popup
    var debugProjectTab by mutableStateOf(-1)

    // v0.5: quiet update check on app start (at most once a day; a notification only when something newer is out)
    init { viewModelScope.launch { runCatching { g.updater.quietCheck() } } }
    var debugOpenFile by mutableStateOf<String?>(null)
    var debugEditing by mutableStateOf(false)
    var debugDeploySheet by mutableStateOf(false)
    var debugBuildIntro by mutableStateOf(false)
    var debugNewProject by mutableStateOf(false)
    var debugProjectMenu by mutableStateOf(false)
    // v0.5.2 render hooks (tests only): the "before you save" warning on key export, the Delete-all-data dialog
    var debugExportWarning by mutableStateOf(false)
    var debugDeleteAll by mutableStateOf(false)
    /** Tests: wipe files but don't ask Android to clear the app (that would end the test process). */
    var debugNoClear = false
    var debugNoField = false
    /** Tests: open the install-log dialog for this tool at start. */
    var debugToolLog: String? = null
    var debugTyped = ""
    /** Speed report: true = the pre-0.5.2 way (a client and a key decrypt for every key × model, up front). */
    var debugEagerClients = false
    /** The PDF open in Axion's full-screen viewer (drawn over the whole Home, top bar included). */
    var viewingPdf by mutableStateOf<com.mazharansari.axion.data.Artifact?>(null)
    /** axion-p1.3: an .html the agent wrote, previewed inside Axion. */
    var viewingHtml by mutableStateOf<com.mazharansari.axion.data.Artifact?>(null)

    fun shareConversation(id: String) = viewModelScope.launch {
        val c = g.chats.load(id) ?: return@launch
        val body = buildString {
            append(c.title).append("\n\n")
            c.messages.forEach { m -> append(if (m.role == Role.USER) "Me: " else "Axion: ").append(m.text.trim()).append("\n\n") }
        }.trim()
        val ctx = getApplication<Application>()
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_SUBJECT, c.title).putExtra(android.content.Intent.EXTRA_TEXT, body)
        runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Share conversation").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // Imagine tab
    val images = mutableStateListOf<File>()
    var imagining by mutableStateOf(false); private set
    var imagineNote by mutableStateOf<String?>(null)

    fun loadImages() = viewModelScope.launch(Dispatchers.IO) {
        val l = File(g.workspace.outputs, "images").listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        withContext(Dispatchers.Main) { images.clear(); images.addAll(l) }
    }

    fun imagine(prompt: String, aspect: String) {
        if (prompt.isBlank() || imagining) return
        imagining = true; imagineNote = null
        viewModelScope.launch {
            runCatching { g.imageGen.generate(prompt.trim(), aspect) }
                .onSuccess { r -> images.add(0, r.file); imagineNote = "Made with ${r.provider}" }
                .onFailure { imagineNote = it.message }
            imagining = false
        }
    }

    private var job: Job? = null
    private var createdAt = 0L

    fun newChat(into: Section = section) {
        stop(); conversationId = null; title = ""; messages = emptyList(); pending.clear(); draft = ""; section = into
        if (into != Section.BUILD) projectId = null
    }

    // ── v0.5 Build projects ────────────────────────────────────────────────────────────────────────────────────────
    /** The Build project whose chat is open (null = no project). Build turns run inside its folder. */
    var projectId by mutableStateOf<String?>(null); private set
    /** Per project: every file's text before the last Build turn (Undo). */
    private val undoPoints = HashMap<String, Map<String, String>>()
    var undoAvailable by mutableStateOf(false); private set

    // ── axion-p1.2 diff review gate: the AI's changes wait here until the user approves them ─────────────────────────
    data class Review(val projectId: String, val set: com.mazharansari.axion.build.ChangeGate.ChangeSet)
    var pendingReview by mutableStateOf<Review?>(null); private set
    /** Bumped when a change is approved so the Preview tab reloads. */
    var previewEpoch by mutableIntStateOf(0); private set
    /** Bumped to ask the Build screen to open the review sheet. */
    var reviewRequested by mutableIntStateOf(0); private set
    private var reviewNote: String? = null
    private fun pendingDir(p: com.mazharansari.axion.data.Project) = com.mazharansari.axion.build.ChangeGate.pendingDir(g.build.store.dir, p.slug)
    fun reviewTexts(c: com.mazharansari.axion.build.ChangeGate.FileChange): Pair<String?, String?> {
        val p = g.build.store.get(pendingReview?.projectId) ?: return null to null
        return com.mazharansari.axion.build.ChangeGate.texts(pendingDir(p), c)
    }
    /** Opening a project: restore it if a turn was cut off mid-way, and bring back a review that was still waiting. */
    private fun loadPending(p: com.mazharansari.axion.data.Project) = viewModelScope.launch(Dispatchers.IO) {
        val dir = pendingDir(p)
        val restored = com.mazharansari.axion.build.ChangeGate.recoverIfInterrupted(g.build.folder(p), dir)
        val set = com.mazharansari.axion.build.ChangeGate.load(dir)
        withContext(Dispatchers.Main) {
            if (restored) toast = "A Build turn was interrupted — your project is back to how it was."
            if (set != null && !set.isEmpty && projectId == p.id) { pendingReview = Review(p.id, set); reviewRequested++ } else if (projectId == p.id) pendingReview = null
        }
    }
    fun approveReview(selected: Set<String>) {
        val r = pendingReview ?: return; val p = g.build.store.get(r.projectId) ?: return
        val dir = pendingDir(p); val folder = g.build.folder(p)
        viewModelScope.launch(Dispatchers.IO) {
            val before = com.mazharansari.axion.build.ProjectFiles.snapshot(folder)
            val rejected = r.set.changes.map { it.path }.filter { it !in selected }
            val applied = runCatching { com.mazharansari.axion.build.ChangeGate.apply(folder, dir, r.set, selected) }
            if (applied.isFailure) { withContext(Dispatchers.Main) { toast = "Could not apply the changes: ${applied.exceptionOrNull()?.message}" }; return@launch }
            com.mazharansari.axion.build.ChangeGate.discard(dir)
            withContext(Dispatchers.Main) {
                pendingReview = null
                if (applied.getOrThrow().isNotEmpty()) { undoPoints[p.id] = before; undoAvailable = projectId == p.id; previewEpoch++ }
                reviewNote = if (rejected.isEmpty()) null else "(The user did NOT approve your changes to: ${rejected.take(12).joinToString()}. Those files are exactly as they were before your last turn; re-read them before editing.)"
                toast = "Applied ${applied.getOrThrow().size} file${if (applied.getOrThrow().size == 1) "" else "s"}"
            }
        }
    }
    fun rejectReview() {
        val r = pendingReview ?: return; val p = g.build.store.get(r.projectId) ?: return
        val dir = pendingDir(p)
        viewModelScope.launch(Dispatchers.IO) {
            com.mazharansari.axion.build.ChangeGate.discard(dir)
            withContext(Dispatchers.Main) {
                pendingReview = null
                reviewNote = "(The user REJECTED your last changes. The project is exactly as it was before that turn; nothing you wrote was kept. Re-read files before editing.)"
                toast = "Changes rejected — project unchanged"
            }
        }
    }

    fun openProject(id: String) {
        val p = g.build.store.get(id) ?: return
        stop()
        projectId = id; section = Section.BUILD; undoAvailable = undoPoints.containsKey(id); pendingReview = null; loadPending(p)   // axion-p1.2
        conversationId = null; title = p.name; messages = emptyList(); pending.clear(); draft = ""
        p.conversationId?.let { cid ->
            viewModelScope.launch {
                val c = g.chats.load(cid) ?: return@launch
                if (projectId != id) return@launch
                conversationId = c.id; title = p.name; messages = c.messages; createdAt = c.createdAt
            }
        }
    }

    fun closeProject() { stop(); projectId = null; conversationId = null; messages = emptyList(); title = "" }

    /** Creates a project and opens its chat. [firstPrompt] (from a template) is sent right away. */
    fun createProject(name: String, kind: String, firstPrompt: String? = null): String {
        val p = g.build.create(name, kind)
        openProject(p.id)
        if (!firstPrompt.isNullOrBlank()) send(firstPrompt)
        return p.id
    }

    /** Puts every file back the way it was before the last Build turn. */
    fun undoLastBuild() {
        val id = projectId ?: return
        val snap = undoPoints.remove(id) ?: return
        val p = g.build.store.get(id) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val n = com.mazharansari.axion.build.ProjectFiles.restore(g.build.folder(p), snap)
            withContext(Dispatchers.Main) { undoAvailable = false; toast = "Undid the last turn ($n file${if (n == 1) "" else "s"})" }
        }
    }

    /** Build can run: Linux + the agy CLI installed and at least one signed-in Antigravity account. */
    fun buildReady(): Boolean = runCatching { g.agyReady() }.getOrDefault(false)

    private var opening: String? = null

    fun open(id: String) {
        if (id == conversationId) return
        stop()
        opening = id
        viewModelScope.launch {
            val c = g.chats.load(id) ?: return@launch
            if (opening != id) return@launch   // v0.5.2: two quick taps in the drawer — the last one wins
            opening = null
            conversationId = c.id; title = c.title; messages = c.messages; createdAt = c.createdAt
            section = Section.of(c.section)
            projectId = if (section == Section.BUILD) g.build.store.projectFor(c.id)?.id else null
            undoAvailable = projectId?.let { undoPoints.containsKey(it) } == true
            pendingReview = null; projectId?.let { id -> g.build.store.get(id)?.let { loadPending(it) } }   // axion-p1.2
        }
    }

    fun addAttachment(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val cr = getApplication<Application>().contentResolver
        var name = "file"; var size = 0L
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        val mime = cr.getType(uri) ?: Mime.of(name)
        val dest = uniqueFile(g.workspace.uploads, name.replace('/', '_'))
        runCatching { cr.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } } }
            .onFailure { withContext(Dispatchers.Main) { toast = "Couldn't read that file" }; return@launch }
        val a = Attachment(dest.name, mime, dest.absolutePath, dest.length().takeIf { it > 0 } ?: size)
        withContext(Dispatchers.Main) { pending += a }
    }

    /** + popover → Upload skill: ZIP / SKILL.md / .md / .txt / PDF. */
    fun importSkills(uris: List<Uri>) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        var n = 0; var err: String? = null
        for (uri in uris) runCatching {
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null } ?: "skill"
            val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            n += g.skills.importFile(name, bytes).size
        }.onFailure { err = it.message }
        toast = (if (n > 0) "Added $n skill${if (n == 1) "" else "s"}" else "") + (err?.let { (if (n > 0) " · " else "") + it } ?: "")
    }

    fun addFile(f: File) { pending += Attachment(f.name, Mime.of(f.name), f.absolutePath, f.length()) }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name); var n = 1
        while (f.exists()) { f = File(dir, name.substringBeforeLast('.') + "_$n" + name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }); n++ }
        return f
    }

    /** v0.5.2: a pasted message longer than this goes in as a .txt attachment instead (keeps the request and the list fast). */
    internal val maxInlineChars = 60_000

    fun send(textIn: String = draft) {
        var text = textIn.trim()
        if ((text.isEmpty() && pending.isEmpty()) || running) return
        if (section == Section.BUILD && pendingReview != null) { toast = "Review the waiting changes first — Approve or Reject."; reviewRequested++; return }   // axion-p1.2
        sendTappedAt = System.currentTimeMillis()
        // v0.5.2 security: an API key pasted into the chat is never sent to the AI or saved — it's masked (gsk_••••abcd).
        if (com.mazharansari.axion.security.SecretScanner.contains(text)) {
            text = com.mazharansari.axion.security.SecretScanner.scrub(text)
            toast = "Axion hid an API key in your message. Keys are never sent in chat — add them in Settings → Connectors."
        }
        // v0.5.2 bug hunt: a giant paste froze the list and failed at the provider; send it as a file instead.
        if (text.length > maxInlineChars) {
            val f = uniqueFile(g.workspace.uploads.apply { mkdirs() }, "pasted_${System.currentTimeMillis()}.txt")
            runCatching { f.writeText(text) }.onSuccess { pending += Attachment(f.name, "text/plain", f.absolutePath, f.length()); text = "Read the attached text (${text.length / 1000}k characters)." }
        }
        // v0.5.2 bug hunt: offline → say so at once instead of walking every key (each one would be cooled down for nothing).
        if (section == Section.ASK && !isOnline() && g.settings.state.value.mode != "LOCAL") {
            val u = Message(UUID.randomUUID().toString(), Role.USER, text, attachments = pending.toList(), createdAt = System.currentTimeMillis())
            pending.clear(); draft = ""
            if (conversationId == null) { conversationId = UUID.randomUUID().toString(); createdAt = System.currentTimeMillis(); title = text.ifBlank { "New chat" }.lineSequence().first().take(48) }
            messages = messages + u + Message(UUID.randomUUID().toString(), Role.ASSISTANT, status = MsgStatus.ERROR, error = "No internet connection. Check Wi-Fi or mobile data, then tap Regenerate.", createdAt = System.currentTimeMillis())
            persist(true); return
        }
        val user = Message(UUID.randomUUID().toString(), Role.USER, text, attachments = pending.toList(), createdAt = System.currentTimeMillis())
        pending.clear(); draft = ""
        if (section == Section.BUILD && projectId == null) {
            // v0.5: every Build chat lives in a project — name it from the request, the user can rename it.
            val name = text.lineSequence().first().removePrefix("Build ").removePrefix("build ").take(36).trim().ifBlank { "New project" }.replaceFirstChar { it.uppercase() }
            val p = g.build.create(name, if (Regex("(?i)\\b(android app|apk|mobile app)\\b").containsMatchIn(text)) "android" else "web")
            projectId = p.id
        }
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString(); createdAt = System.currentTimeMillis()
            title = text.ifBlank { user.attachments.firstOrNull()?.name ?: "New chat" }.lineSequence().first().take(48)
            projectId?.let { pid -> g.build.store.get(pid)?.let { title = it.name }; g.build.store.setConversation(pid, conversationId!!) }
        }
        messages = messages + user
        runAgent()
    }

    fun regenerate() {
        if (running) return
        val lastUser = messages.indexOfLast { it.role == Role.USER }
        if (lastUser < 0) return
        messages = messages.subList(0, lastUser + 1)
        runAgent()
    }

    fun rate(id: String, r: Int) { messages = messages.map { if (it.id == id) it.copy(rating = if (it.rating == r) 0 else r) else it }; persist(true) }

    fun editAndResend(id: String) {
        val i = messages.indexOfFirst { it.id == id }; if (i < 0 || running) return
        draft = messages[i].text; messages = messages.subList(0, i); persist(true)
    }

    /**
     * v0.4 Stop (Grok behaviour): the reply freezes where it is the instant ■ is tapped — partial text kept, marked
     * stopped, read-aloud silenced — while the cancelled job unwinds behind it (its HTTP socket is closed at once, see
     * [com.mazharansari.axion.llm.cancelHook]). Nothing that arrives after the tap is added.
     */
    fun stop() {
        val j = job ?: return
        job = null
        live?.let { (aid, buf) ->
            synchronized(buf) { buf.closed = true; buf.dirty = true }
            flush(aid, buf, force = true)
            messages = messages.map { if (it.id == aid) it.copy(status = MsgStatus.INTERRUPTED, notice = "", thinkingMs = System.currentTimeMillis() - it.createdAt,
                steps = it.steps.map { st -> if (st.status == StepStatus.RUNNING) st.copy(status = StepStatus.ERROR, endedAt = System.currentTimeMillis()) else st }) else it }
        }
        live = null
        running = false
        j.cancel()
        g.speaker.stop()
        persist(true)
    }

    /**
     * v0.4 (mazhar 2:28 PM): Ask and Build are two separate engines. Ask = API-key providers + Free (slow), never
     * Antigravity. Build = Antigravity only (agy in Linux, multi-account auto-switch), never API keys.
     */
    var section by mutableStateOf(Section.ASK)

    /** The reply being generated right now (stop() freezes it). */
    private var live: Pair<String, StreamBuffer>? = null

    /** Test hook: null = ask Android. */
    var debugOnline: Boolean? = null
    internal fun isOnline(): Boolean = debugOnline ?: if (KeyVault.isTestRuntime) true else runCatching {
        val cm = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return@runCatching false
        cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    // ── v0.5.2 speed ────────────────────────────────────────────────────────────────────────────────────────────────
    private var sendTappedAt = 0L
    /** Last measured turn (tests + report): Send tap → request leaving the phone, and → first word on screen. */
    var lastTiming: Pair<Long, Long>? = null; private set
    private var lastWarm = 0L

    /** Composer focused / first character typed: open the connection to the model that will answer first. */
    fun prewarm() {
        if (section != Section.ASK) return
        val now = System.currentTimeMillis()
        if (now - lastWarm < 20_000) return
        lastWarm = now
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val s = g.settings.state.value
                val mode = runCatching { Mode.valueOf(s.mode) }.getOrDefault(Mode.AUTO)
                val c = if (s.mode == "SERVER") g.connectors.server() else g.connectors.attempts(if (mode == Mode.AUTO) Mode.FAST else mode).firstOrNull()?.first
                val url = when {
                    c == null -> null
                    c.providerId == "myserver" -> com.mazharansari.axion.data.MyServer.normalize(c.baseUrl.orEmpty())
                    c.providerId == "local" -> null
                    else -> c.provider.baseUrl
                }
                url?.let { Http.prewarm(it) }
            }
        }
    }

    private fun persist(immediate: Boolean = false) {
        val id = conversationId ?: return
        g.chats.save(Conversation(id, title, messages, createdAt = createdAt, updatedAt = System.currentTimeMillis(), section = section.id), immediate)
    }

    private fun runAgent() {
        val s = g.settings.state.value
        val mode = runCatching { Mode.valueOf(s.mode) }.getOrDefault(Mode.AUTO)
        val history = messages.dropLast(1).mapNotNull { m ->
            when (m.role) {
                Role.USER -> UserItem(m.text + attachmentNote(m.attachments))
                Role.ASSISTANT -> m.text.takeIf { it.isNotBlank() }?.let { AssistantItem(it) }
            }
        }
        val u = messages.last()
        val intent = com.mazharansari.axion.agent.IntentGate.classify(u.text, u.attachments.isNotEmpty())
        // Auto: short chat goes to the fast models first (a greeting should never wait on a big reasoning model).
        val runMode = if (mode == Mode.AUTO && intent != com.mazharansari.axion.agent.Intent.ACTION) Mode.FAST else mode
        val sec = section
        val quick = sec == Section.ASK && com.mazharansari.axion.agent.IntentGate.isSmallTalk(u.text, intent)
        val userItem = UserItem(u.text.ifBlank { "Look at the attached file(s)." } + attachmentNote(u.attachments), media(u.attachments))
        val aid = UUID.randomUUID().toString()
        val started = System.currentTimeMillis()
        messages = messages + Message(aid, Role.ASSISTANT, status = MsgStatus.STREAMING, thinkingStartedAt = started, createdAt = started)
        running = true
        persist(true)

        val buf = StreamBuffer()
        live = aid to buf
        val tapped = sendTappedAt.takeIf { it > 0 && started - it < 5_000 } ?: started
        sendTappedAt = 0
        var requestAt = 0L; var firstAt = 0L
        job = viewModelScope.launch {
            // UI ticker: at most ~16 updates/s however fast tokens arrive, so long answers never jank the list.
            val ticker = launch { while (isActive) { delay(45); flush(aid, buf) } }   // v0.4: ~22 UI updates/s while streaming
            val sink = object : AgentSink {
                override fun reasoning(delta: String) { synchronized(buf) { if (buf.closed) return; buf.reasoning.append(delta); buf.dirty = true } }
                override fun textToThoughts() = synchronized(buf) {
                    val t = buf.text.substring(buf.roundStart); buf.text.setLength(buf.roundStart)
                    if (t.isNotBlank()) buf.reasoning.append(if (buf.reasoning.isEmpty()) "" else "\n\n").append(t.trim())
                    buf.dirty = true
                }
                override fun step(step: Step) = synchronized(buf) {
                    val i = buf.steps.indexOfFirst { it.id == step.id }
                    if (i >= 0) buf.steps[i] = step else buf.steps += step
                    buf.roundStart = buf.text.length; buf.dirty = true
                }
                override fun artifact(a: Artifact) = synchronized(buf) { buf.artifacts += a; buf.dirty = true }
                override fun plan(steps: List<PlanStep>) = synchronized(buf) { buf.plan = steps; buf.dirty = true }   // axion-p2
                override fun model(label: String) = synchronized(buf) { buf.model = label; buf.dirty = true }
                override fun switching(label: String) = synchronized(buf) { buf.notice = "Switching to $label…"; buf.dirty = true }
                override fun discardRound() = synchronized(buf) { buf.text.setLength(buf.roundStart); buf.dirty = true }
                override fun text(delta: String) { synchronized(buf) { if (buf.closed) return; buf.text.append(delta); buf.notice = ""; buf.dirty = true } }
                override fun requestStart() { if (requestAt == 0L) requestAt = System.currentTimeMillis() }
                // v0.5.2 speed: the first word goes on screen now, not at the next 45 ms tick.
                override fun firstToken() {
                    if (firstAt != 0L) return
                    firstAt = System.currentTimeMillis()
                    viewModelScope.launch(Dispatchers.Main.immediate) { flush(aid, buf) }
                }
            }
            var status = MsgStatus.DONE; var error = ""
            try {
                val pid = projectId
                if (sec == Section.BUILD && pid != null) withContext(Dispatchers.Default) { runBuildTurn(pid, u, sink) }
                else withContext(Dispatchers.Default) {
                    Agent(g.workspace, g.skills, baseTools = Agent.defaultTools() + GenerateImageTool(g.imageGen), extraTools = { g.mcp.tools() }).run(
                        history, userItem, attempts = { if (sec == Section.BUILD) buildAttempts() else attempts(runMode, quick) }, sink = sink,
                        profile = Profile(s.assistantName.ifBlank { "Axion" }, s.userName, s.replyLanguage, s.instructions),
                        intent = if (sec == Section.BUILD) com.mazharansari.axion.agent.Intent.CHAT else intent,   // agy brings its own tools
                    )
                }
            } catch (e: CancellationException) { status = MsgStatus.INTERRUPTED }
            catch (e: NoModelException) { status = MsgStatus.ERROR; error = e.message.orEmpty() }
            catch (e: LlmException) { status = MsgStatus.ERROR; error = friendly(e) }
            catch (e: Throwable) { status = MsgStatus.ERROR; error = e.message ?: e.toString() }
            ticker.cancel()
            if (buf.closed) return@launch   // Stop already froze this reply
            withContext(NonCancellable) {
                live = null
                buf.dirty = true; flush(aid, buf)
                messages = messages.map {
                    if (it.id == aid) it.copy(status = status, error = com.mazharansari.axion.security.SecretScanner.scrub(error), notice = "", thinkingMs = System.currentTimeMillis() - started,
                        firstWordMs = if (firstAt > 0) firstAt - tapped else 0, prepMs = if (requestAt > 0) requestAt - tapped else 0,
                        steps = it.steps.map { st -> if (st.status == StepStatus.RUNNING) st.copy(status = StepStatus.ERROR, endedAt = System.currentTimeMillis()) else st },
                        plan = it.plan.map { ps -> if (ps.status == PlanStatus.RUNNING) ps.copy(status = PlanStatus.FAILED, note = ps.note.ifBlank { "stopped" }) else ps })   // axion-p2
                    else it
                }
                running = false
                if (requestAt > 0) lastTiming = (requestAt - tapped) to (if (firstAt > 0) firstAt - tapped else -1L)
                persist(true)
                if (status == MsgStatus.DONE && s.autoRead) messages.lastOrNull()?.let { speak(it) }
            }
        }
    }

    /**
     * v0.5 Build turn: Antigravity works inside the project folder (plan → files → run), Axion verifies (site check, build,
     * screenshot + console errors) and sends any errors back for a fix, up to 2 rounds. Antigravity only — never API keys.
     */
    private suspend fun runBuildTurn(pid: String, u: Message, sink: AgentSink) {
        val p = g.build.store.get(pid) ?: throw NoModelException("This project was deleted.")
        if (!buildReady()) throw NoModelException("Build runs on Antigravity. Install Developer tools and add an Antigravity account first.")
        val folder = g.build.folder(p)
        // files the user attached go into the project's uploads/ folder
        val note = if (u.attachments.isEmpty()) "" else {
            val up = File(folder, "uploads").apply { mkdirs() }
            u.attachments.forEach { a -> runCatching { File(a.path).copyTo(File(up, a.name), overwrite = true) } }
            "\n\n[Files the user attached, now in the project: " + u.attachments.joinToString { "uploads/${it.name}" } + "]"
        }
        sink.model("Antigravity" + g.agy.store.value.model.takeIf { it.isNotBlank() && it != "auto" }?.let { " · $it" }.orEmpty())
        val loop = com.mazharansari.axion.build.BuildLoop(
            com.mazharansari.axion.build.AgyBrain(g.linux, g.agy, g.build.store, g.quota), g.build.verifier(), folder, g.build.guest(p), sink,
        )
        // axion-p1.2: with review on, the project folder is backed up first and put back after the turn; the change waits for Approve.
        val dir = pendingDir(p)
        var gated = false
        if (g.settings.state.value.reviewBeforeApply) {
            gated = try { withContext(Dispatchers.IO) { com.mazharansari.axion.build.ChangeGate.backup(folder, dir) }; true }
            catch (e: com.mazharansari.axion.build.ChangeGate.TooLargeException) { sink.reasoning("This project is too big to hold changes for review (${e.message}); changes apply directly.\n"); false }
        }
        val rn = reviewNote.also { reviewNote = null }
        val out = try { loop.run(p, (rn?.let { it + "\n\n" } ?: "") + u.text + note, fresh = p.agyConversation == null) }
        finally {
            if (gated) withContext(NonCancellable + Dispatchers.IO) {
                val set = com.mazharansari.axion.build.ChangeGate.stage(folder, dir)
                withContext(Dispatchers.Main) {
                    if (set.isEmpty) { com.mazharansari.axion.build.ChangeGate.discard(dir) }
                    else { pendingReview = Review(pid, set); reviewRequested++; sink.text("\n\n**${set.changes.size} file change${if (set.changes.size == 1) "" else "s"} waiting for your review** (+${set.totalAdded} −${set.totalRemoved}). Nothing is applied until you tap Approve.") }
                }
            }
        }
        if (!gated && out.changedFiles.isNotEmpty()) { undoPoints[pid] = loop.before; withContext(Dispatchers.Main) { undoAvailable = projectId == pid } }
        if (out.error != null && out.changedFiles.isEmpty()) throw NoModelException(out.error)
        if (out.problems.isNotEmpty()) sink.text("\n\n**Still not fixed after ${out.rounds} rounds:**\n" + out.problems.take(6).joinToString("\n") { "- " + it.lines().first() })
    }

    private class StreamBuffer {
        val text = StringBuilder(); val reasoning = StringBuilder(); val steps = ArrayList<Step>(); val artifacts = ArrayList<Artifact>()
        @Volatile var plan: List<PlanStep> = emptyList()   // axion-p2
        var model = ""; var notice = ""; var dirty = false; var roundStart = 0
        @Volatile var closed = false
    }

    private fun flush(id: String, b: StreamBuffer, force: Boolean = false) {
        val snap = synchronized(b) {
            if (!b.dirty || (b.closed && !force)) return
            b.dirty = false
            Triple(b.text.toString(), b.reasoning.toString(), Triple(b.steps.toList(), b.artifacts.toList(), b.model)) to b.notice
        }
        val (s0, notice) = snap
        messages = messages.map {
            if (it.id == id) it.copy(text = s0.first, reasoning = s0.second, steps = s0.third.first, artifacts = s0.third.second, model = s0.third.third, notice = notice, plan = b.plan) else it
        }
        persist()
    }

    /**
     * Ask (v0.4, mazhar 2:28 PM): API keys only — every model on a key before the next key — then "Free (slow)"
     * (Pollinations, no key) only when nothing else is left. Antigravity is never used here.
     */
    /** "Free model is busy — retrying in 12 s…" under Thinking while Free (slow) waits out its limit. */
    private fun freeWaitNotice(secs: Int) {
        val b = live?.second ?: return
        synchronized(b) { if (b.closed) return; b.notice = if (secs > 0) "Free model is busy — retrying in $secs s…" else ""; b.dirty = true }
    }

    /** Tests: replaces the model list (a fake model) while everything else in the send path stays real. */
    internal var debugAttempts: ((Mode, Boolean) -> List<Attempt>)? = null

    internal fun attempts(mode: Mode, quick: Boolean = false): List<Attempt> {
        debugAttempts?.let { return it(mode, quick) }
        val pairs = g.connectors.attempts(mode).let { if (quick) quickOrder(it) { p -> p.second } else it }
        // v0.5.2 speed: one lazily-made client per key (was: a key decrypt for every model on every key, before the first request).
        val clients = HashMap<String, LlmClient>()
        val keys = pairs.map { (c, model) ->
            val cl = if (debugEagerClients) g.connectors.clientFor(c) else clients.getOrPut(c.id) { LazyClient { g.connectors.clientFor(c) } }
            Attempt(c.provider.name, model, cl, onFail = { e -> g.connectors.coolDown(c, model, e) })
        }
        // v0.5.2: labelled "Free (slow)" (was "openai"); its 45 s rate limit is waited out with a visible countdown.
        val free = listOf(Attempt(FreeFallback.NAME, FreeFallback.NAME, FreeFallback.Client(onWait = ::freeWaitNotice), patientMs = FreeFallback.PATIENT_MS))
        // v0.5 (addendum 6): the user's own models. Picked in the model menu → they go first; otherwise they're the backup
        // after the API keys and before Free (slow).
        val pick = g.settings.state.value.mode
        if (pick == "FREE") return free   // picked in the model menu
        val own = listOfNotNull(g.connectors.server()?.takeIf { it.enabled }, g.connectors.local()?.takeIf { it.enabled })
            .sortedBy { c -> if ((pick == "LOCAL" && c.providerId == "local") || (pick == "SERVER" && c.providerId == "myserver")) 0 else 1 }
            .mapNotNull { c -> runCatching { g.connectors.clientFor(c) }.getOrNull()?.let { cl -> Attempt(c.provider.name, c.pinnedModel ?: c.localModel ?: c.models.firstOrNull().orEmpty(), cl, patientMs = if (c.providerId == "local") 150_000 else 90_000) } }
        return if (pick == "LOCAL" || pick == "SERVER") own + keys + free else keys + own + free
    }

    /** Build: Antigravity only (the client itself switches accounts on a limit). No API key, no Free (slow). */
    internal fun buildAttempts(): List<Attempt> {
        if (!buildReady()) throw NoModelException("Build runs on Antigravity — add an Antigravity account (and Developer tools) first.")
        // v0.5.1: the stall watchdog is for API models; agy starts a CLI inside Linux and may take a while to speak.
        return listOf(Attempt("Antigravity", g.agy.store.value.model.ifBlank { "auto" }, g.agyClient, patientMs = 180_000))
    }

    private fun attachmentNote(a: List<Attachment>) = if (a.isEmpty()) "" else
        "\n\n[Attached files, saved in the workspace: " + a.joinToString { "uploads/${it.name} (${it.mime}, ${it.size / 1024} KB)" } +
            ". Use read_file / extract_zip / run_command on them.]"

    private fun media(a: List<Attachment>): List<ImagePart> = a.filter {
        (it.mime.startsWith("image/") && it.size < 6_000_000) || (it.mime == "application/pdf" && it.size < 15_000_000)
    }.mapNotNull { runCatching { ImagePart(it.mime, Base64.encodeToString(File(it.path).readBytes(), Base64.NO_WRAP)) }.getOrNull() }

    /** One friendly line + the provider's words (scrubbed of org ids, keys and links) behind "Details". */
    private fun friendly(e: LlmException): String {
        val line = when {
            e.message == FreeFallback.BUSY -> FreeFallback.BUSY
            (e.message ?: "").startsWith(FreeFallback.NAME) -> "The free model didn't answer in time (it's slow and limited). Try again, or add a free Gemini or Groq key in Connectors for fast replies."
            else -> friendlyKind(e)
        }
        val details = (e.message ?: "").replace(Regex("org_[A-Za-z0-9]+"), "org_…").replace(Regex("https?://\\S+"), "").replace(Regex("\\S*\\*{3,}\\S*"), "").take(240)
        return line + "\u0001" + details
    }

    private fun friendlyKind(e: LlmException): String {
        return when (e.kind) {
            FailKind.RATE -> "The free models are busy for a moment (per-minute limit)" + (if (e.retryAfterMs > 0) " — try again in ${(e.retryAfterMs / 1000).coerceAtLeast(1)} s." else " — try again in a few seconds.")
            FailKind.QUOTA -> "Your connected keys have hit their limit for now. Add another free key in Connectors, or try later."
            FailKind.AUTH -> "A key was rejected — check it in Connectors."
            FailKind.NETWORK -> "No connection to the AI right now. Check your internet and try again."
            else -> "The AI didn't answer this time. Try again."
        }
    }

    fun speak(m: Message) {
        if (g.speaker.speakingId.value == m.id) { g.speaker.stop(); return }
        g.speaker.speak(m.id, m.text)
    }

    /** One spoken turn of the Live call ("same voice" mode): a short spoken answer, web search allowed, fast models first. */
    suspend fun callReply(history: List<Pair<String, String>>, text: String, onDelta: ((String) -> Unit)? = null): String = withContext(Dispatchers.Default) {
        val s = g.settings.state.value
        val hist = history.takeLast(12).flatMap { listOf(UserItem(it.first), AssistantItem(it.second)) }
        val out = StringBuilder()
        val sink = object : AgentSink {
            override fun text(delta: String) { synchronized(out) { out.append(delta) }; onDelta?.invoke(delta) }
            override fun reasoning(delta: String) {}
            override fun textToThoughts() { synchronized(out) { out.setLength(0) }; onDelta?.invoke(RESET) }
            override fun step(step: Step) {}
            override fun artifact(a: Artifact) {}
            override fun model(label: String) {}
            override fun discardRound() { synchronized(out) { out.setLength(0) }; onDelta?.invoke(RESET) }
        }
        val voiceRules = "\n\nThis is a LIVE VOICE CALL: your words are spoken aloud. Reply in one to three short, natural spoken sentences " +
            "in the language the user speaks (Urdu, Hindi, English or a mix). No markdown, lists, emojis, links or code."
        val answer = Agent(g.workspace, g.skills, baseTools = listOf(WebSearchTool()), maxRounds = 4).run(
            hist, UserItem(text), attempts = { attempts(Mode.FAST) }, sink = sink,
            profile = Profile(s.assistantName.ifBlank { "Axion" }, s.userName, s.replyLanguage, s.instructions + voiceRules),
        )
        answer.ifBlank { synchronized(out) { out.toString() } }
    }

    companion object {
        /** callReply's onDelta gets this when text already streamed turned out to be pre-tool "thinking". */ const val RESET = "\u0000reset"
        /**
         * v0.4 small talk: models that answer without reasoning move ahead of the reasoning ones (stable sort — the
         * order is otherwise unchanged), so "hi bhai" starts streaming at once. Normal messages keep the full rotation.
         */
        fun <T> quickOrder(list: List<T>, model: (T) -> String): List<T> =
            list.sortedBy { if (com.mazharansari.axion.agent.IntentGate.isReasoningModel(model(it))) 1 else 0 }
    }

    /** Saves a spoken call turn into the open chat, like a typed exchange (Kodex does the same). */
    fun appendCallTurn(userText: String, assistantText: String) {
        viewModelScope.launch(Dispatchers.Main) {
            if (userText.isBlank() && assistantText.isBlank()) return@launch
            val now = System.currentTimeMillis()
            if (conversationId == null) {
                conversationId = UUID.randomUUID().toString(); createdAt = now
                title = ("📞 " + userText.ifBlank { "Voice call" }).lineSequence().first().take(48)
            }
            val add = buildList {
                if (userText.isNotBlank()) add(Message(UUID.randomUUID().toString(), Role.USER, userText, createdAt = now))
                if (assistantText.isNotBlank()) add(Message(UUID.randomUUID().toString(), Role.ASSISTANT, assistantText, status = MsgStatus.DONE, createdAt = now + 1, model = "Voice call"))
            }
            messages = messages + add
            persist(true)
        }
    }

    override fun onCleared() { job?.cancel(); g.speaker.stop() }
}
