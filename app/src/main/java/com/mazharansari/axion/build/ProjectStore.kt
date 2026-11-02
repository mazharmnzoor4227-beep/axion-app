package com.mazharansari.axion.build

import com.mazharansari.axion.data.DeployTarget
import com.mazharansari.axion.data.Project
import com.mazharansari.axion.data.ProjectSlug
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Projects list + their folders. Plain JVM (no Android) so it's unit-tested directly (ProjectStoreTest).
 * [root] is the app's files dir: projects.json sits there, folders under projects/<slug>.
 */
class ProjectStore(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(root, "projects.json")
    val dir = File(root, "projects").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<Project>> = _list.asStateFlow()

    private fun load(): List<Project> = runCatching { json.decodeFromString(ListSerializer(Project.serializer()), file.readText()) }.getOrDefault(emptyList())
    private fun save(l: List<Project>) {
        _list.value = l.sortedByDescending { it.updatedAt }
        synchronized(file) { val tmp = File(root, "projects.json.tmp"); tmp.writeText(json.encodeToString(ListSerializer(Project.serializer()), _list.value)); tmp.renameTo(file) }
    }

    fun get(id: String?): Project? = id?.let { i -> _list.value.firstOrNull { it.id == i } }
    fun folder(p: Project) = File(dir, p.slug)
    fun guestPath(p: Project) = "/root/projects/${p.slug}"

    /** Name is what the user typed (kept as-is, trimmed); the folder slug is made unique ("my-site", "my-site-2"…). */
    fun create(name: String, kind: String = "web"): Project {
        val clean = name.trim().replace(Regex("\\s+"), " ").take(60).ifBlank { "Untitled project" }
        val base = ProjectSlug.of(clean)
        var slug = base; var n = 2
        val taken = _list.value.map { it.slug }.toSet()
        while (slug in taken || File(dir, slug).exists()) { slug = "$base-$n"; n++ }
        val now = clock()
        val p = Project(UUID.randomUUID().toString(), clean, slug, now, now, kind)
        File(dir, slug).mkdirs()
        save(_list.value + p)
        return p
    }

    fun rename(id: String, name: String): Project? {
        val clean = name.trim().replace(Regex("\\s+"), " ").take(60)
        if (clean.isEmpty()) return get(id)
        return update(id) { it.copy(name = clean) }
    }

    fun delete(id: String): Boolean {
        val p = get(id) ?: return false
        File(dir, p.slug).deleteRecursively()
        save(_list.value.filterNot { it.id == id })
        return true
    }

    fun update(id: String, touch: Boolean = true, f: (Project) -> Project): Project? {
        var out: Project? = null
        save(_list.value.map { if (it.id == id) f(it).let { n -> if (touch) n.copy(updatedAt = clock()) else n }.also { n -> out = n } else it })
        return out
    }

    fun setConversation(id: String, conversationId: String) = update(id, touch = false) { it.copy(conversationId = conversationId) }
    fun setAgy(id: String, conversation: String?, account: String?) = update(id) { it.copy(agyConversation = conversation, agyAccount = account) }
    fun setDeploy(id: String, d: DeployTarget) = update(id) { it.copy(deploy = d) }
    fun projectFor(conversationId: String?): Project? = conversationId?.let { c -> _list.value.firstOrNull { it.conversationId == c } }
}
