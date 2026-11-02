package com.mazharansari.axion.antigravity

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class AgyAccount(
    val email: String,
    val folder: String,
    val label: String = "",
    val order: Int = 0,
    val enabled: Boolean = true,
    /** "free" | "pro" | "ultra" when /usage says so. Free resets weekly; AI Pro every 5 h plus a weekly cap. */
    val tier: String? = null,
    val authExpiredAt: Long = 0,
    val ineligibleAt: Long = 0,
    val quotaHitAt: Long = 0,
    val cooldownUntil: Long = 0,
    val lastUsedAt: Long = 0,
    val addedAt: Long = 0,
)

@Serializable
data class AgyStore(
    val accounts: List<AgyAccount> = emptyList(),
    val activeEmail: String? = null,
    val autoSwitch: Boolean = true,
    /** Antigravity as a model source in chat: "off" | "first" (use it before API keys) | "fallback" (after keys). */
    val useInChat: String = "off",
    val model: String = "",
    val models: List<String> = emptyList(),
)

/** Ported from Kodex AntigravityAuthController: account folder naming (sanitised email + sha256[:16]) and the 3-tier round robin. */
object AgySelector {
    const val COOLDOWN_MS = 60 * 60 * 1000L   // Kodex's HOURLY_LIMIT_COOLDOWN_MS

    fun folderName(email: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(email.lowercase().toByteArray())
        val suffix = d.joinToString("") { "%02x".format(it) }.take(16)
        return email.lowercase().replace(Regex("[^a-z0-9._-]"), "_") + "_" + suffix
    }

    fun usable(a: AgyAccount, now: Long, hasToken: (AgyAccount) -> Boolean) =
        a.enabled && a.ineligibleAt == 0L && a.authExpiredAt == 0L && hasToken(a) && a.cooldownUntil <= now

    /**
     * Next account for a run. (1) usable accounts, the one with the most quota left first (from the last /usage probe when known),
     * ties → least recently used (Kodex's round-robin feel); (2) an exhausted account whose cooldown has passed; (3) none — the caller
     * says when the earliest one comes back.
     */
    fun pick(accounts: List<AgyAccount>, now: Long, hasToken: (AgyAccount) -> Boolean, remaining: (AgyAccount) -> Double? = { null }, exclude: Set<String> = emptySet()): AgyAccount? {
        val pool = accounts.filter { it.email !in exclude }
        return pool.filter { usable(it, now, hasToken) }
            .sortedWith(compareByDescending<AgyAccount> { remaining(it) ?: 0.5 }.thenBy { it.lastUsedAt }.thenBy { it.order })
            .firstOrNull()
    }

    fun earliestBack(accounts: List<AgyAccount>, now: Long): AgyAccount? =
        accounts.filter { it.enabled && it.ineligibleAt == 0L && it.authExpiredAt == 0L && it.cooldownUntil > now }.minByOrNull { it.cooldownUntil }
}

/** Several Google accounts for Antigravity, each with its own credential folder bound into Linux as ~/.gemini/antigravity-cli. */
class AgyAccounts(private val ctx: Context) {
    private val file = File(ctx.filesDir, "agy_accounts.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val credRoot = File(ctx.filesDir, "secure-credentials/antigravity")
    val accountsDir = File(credRoot, "accounts")
    private val _store = MutableStateFlow(load())
    val store: StateFlow<AgyStore> = _store.asStateFlow()

    private fun load(): AgyStore = runCatching { json.decodeFromString<AgyStore>(file.readText()) }.getOrDefault(AgyStore())
    private fun save(s: AgyStore) { _store.value = s; runCatching { file.writeText(json.encodeToString(s)) } }
    private fun edit(email: String, f: (AgyAccount) -> AgyAccount) = save(_store.value.copy(accounts = _store.value.accounts.map { if (it.email.equals(email, true)) f(it) else it }))

    fun folderOf(a: AgyAccount) = File(accountsDir, a.folder)
    /** v0.5.2: the account's sign-in files, encrypted with the Keystore key, while no Antigravity run is using them. */
    fun sealedOf(a: AgyAccount) = File(accountsDir, a.folder + ".sealed")
    /** v0.5.2: where the files are decrypted for the length of a run (no-backup storage), then sealed again and deleted. */
    val liveRoot: File get() = File(ctx.noBackupFilesDir ?: ctx.filesDir, "agy-live")
    fun liveOf(a: AgyAccount) = File(liveRoot, a.folder)
    private val leases = HashMap<String, Int>()

    private fun plainHas(dir: File) = dir.isDirectory && dir.walkTopDown().maxDepth(2).any { it.isFile && it.length() > 0 && !it.name.startsWith(".") }
    /** Kodex checks the token file; agy writes it as "antigravity-oauth-token" (any non-empty file counts, in case the name changes). */
    fun hasToken(a: AgyAccount): Boolean = sealedOf(a).length() > 0 || plainHas(folderOf(a)) || plainHas(liveOf(a))

    /**
     * v0.5.2: an Antigravity run borrows the account's sign-in files. The first borrower decrypts them into [liveOf];
     * the last [release] encrypts them again (agy may have refreshed the token) and deletes the plain copy. Between runs
     * nothing readable is on disk — not for the Build agent, not for project code, not for anything else in Linux.
     */
    @Synchronized fun bindFor(a: AgyAccount): Pair<String, String> {
        val n = leases[a.folder] ?: 0
        val live = liveOf(a)
        if (n == 0) {
            val sealed = sealedOf(a)
            if (sealed.length() > 0) CredSeal.open(sealed.readBytes(), live)
            else if (plainHas(folderOf(a))) { live.deleteRecursively(); folderOf(a).copyRecursively(live, true) }   // not sealed yet (no Keystore)
            else live.mkdirs()
        }
        leases[a.folder] = n + 1
        return live.absolutePath to "/root/.gemini/antigravity-cli"
    }

    @Synchronized fun release(a: AgyAccount) {
        val n = (leases[a.folder] ?: 1) - 1
        if (n > 0) { leases[a.folder] = n; return }
        leases.remove(a.folder)
        sealFrom(a, liveOf(a))
    }

    /** Encrypts [dir] into the account's sealed file and deletes [dir]; if the Keystore can't seal, keeps a plain folder. */
    @Synchronized private fun sealFrom(a: AgyAccount, dir: File) {
        if (!plainHas(dir)) { dir.deleteRecursively(); return }
        val blob = CredSeal.seal(dir)
        if (blob != null) {
            val tmp = File(accountsDir.apply { mkdirs() }, a.folder + ".sealed.tmp")
            tmp.writeBytes(blob); tmp.renameTo(sealedOf(a))
            dir.deleteRecursively()
            if (dir != folderOf(a)) folderOf(a).deleteRecursively()
        } else if (dir != folderOf(a)) {
            folderOf(a).deleteRecursively(); dir.copyRecursively(folderOf(a), true); dir.deleteRecursively()
        }
    }

    /** At start: seal plain folders (pre-0.5.2 sign-ins, a run cut off by a crash) of every account nobody is using. */
    @Synchronized fun sealAtRest() {
        for (a in _store.value.accounts) {
            if ((leases[a.folder] ?: 0) > 0) continue
            runCatching { if (plainHas(liveOf(a))) sealFrom(a, liveOf(a)) }
            runCatching { if (plainHas(folderOf(a))) sealFrom(a, folderOf(a)) }
        }
    }
    fun accounts() = _store.value.accounts.sortedBy { it.order }
    fun active(): AgyAccount? = _store.value.accounts.firstOrNull { it.email.equals(_store.value.activeEmail, true) }

    /** After a sign-in: move the staging folder in as this account's folder and remember it. */
    fun recordSignedIn(email: String, staging: File) {
        val clean = email.trim().lowercase()
        val existing = _store.value.accounts.firstOrNull { it.email.equals(clean, true) }
        val folder = existing?.folder ?: AgySelector.folderName(clean)
        val dest = File(accountsDir, folder)
        accountsDir.mkdirs()
        if (staging.exists() && staging != dest) { dest.deleteRecursively(); if (!staging.renameTo(dest)) { staging.copyRecursively(dest, true); staging.deleteRecursively() } }
        val now = System.currentTimeMillis()
        val acc = existing?.copy(authExpiredAt = 0, ineligibleAt = 0) ?: AgyAccount(clean, folder, label = "Account ${_store.value.accounts.size + 1}", order = _store.value.accounts.size, addedAt = now)
        val list = _store.value.accounts.filterNot { it.email.equals(clean, true) } + acc
        save(_store.value.copy(accounts = list.sortedBy { it.order }, activeEmail = _store.value.activeEmail ?: clean))
        runCatching { if ((leases[acc.folder] ?: 0) == 0) sealFrom(acc, dest) }   // v0.5.2: encrypted straight after sign-in
    }

    fun setActive(email: String) = save(_store.value.copy(activeEmail = email))
    fun setAutoSwitch(on: Boolean) = save(_store.value.copy(autoSwitch = on))
    fun setUseInChat(mode: String) = save(_store.value.copy(useInChat = mode))
    fun setModel(m: String) = save(_store.value.copy(model = m))
    fun setModels(list: List<String>) = save(_store.value.copy(models = list))
    fun rename(email: String, label: String) = edit(email) { it.copy(label = label.take(24)) }
    fun setEnabled(email: String, on: Boolean) = edit(email) { it.copy(enabled = on) }
    fun setTier(email: String, tier: String?) = edit(email) { it.copy(tier = tier) }
    fun markUsed(email: String) = edit(email) { it.copy(lastUsedAt = System.currentTimeMillis()) }
    fun markQuota(email: String, resetAt: Long?) { val now = System.currentTimeMillis(); edit(email) { it.copy(quotaHitAt = now, cooldownUntil = resetAt?.takeIf { r -> r > now } ?: (now + AgySelector.COOLDOWN_MS)) } }
    fun clearQuota(email: String) = edit(email) { it.copy(quotaHitAt = 0, cooldownUntil = 0) }
    fun markAuthExpired(email: String) = edit(email) { it.copy(authExpiredAt = System.currentTimeMillis()) }
    fun markIneligible(email: String) = edit(email) { it.copy(ineligibleAt = System.currentTimeMillis()) }

    fun remove(email: String) {
        _store.value.accounts.firstOrNull { it.email.equals(email, true) }?.let { folderOf(it).deleteRecursively(); sealedOf(it).delete(); liveOf(it).deleteRecursively() }
        val rest = _store.value.accounts.filterNot { it.email.equals(email, true) }
        save(_store.value.copy(accounts = rest, activeEmail = if (_store.value.activeEmail.equals(email, true)) rest.firstOrNull()?.email else _store.value.activeEmail))
    }

    /** Which account runs next. Manual mode (auto-switch off) always uses the active one. */
    fun pickForRun(remaining: (AgyAccount) -> Double? = { null }, exclude: Set<String> = emptySet()): AgyAccount? {
        val s = _store.value; val now = System.currentTimeMillis()
        if (!s.autoSwitch) return active()?.takeIf { it.email !in exclude && AgySelector.usable(it, now, ::hasToken) }
        val a = active()
        if (a != null && a.email !in exclude && AgySelector.usable(a, now, ::hasToken)) return a
        return AgySelector.pick(s.accounts, now, ::hasToken, remaining, exclude)?.also { setActive(it.email) }
    }
}

/**
 * v0.5.2: a folder of small files ⇄ one blob encrypted with the app's Keystore key (AES-256-GCM).
 * Layout before encryption: repeated [path length][path][data length][data]. Swappable cipher for unit tests.
 */
object CredSeal {
    @Volatile var enc: (ByteArray) -> ByteArray? = { com.mazharansari.axion.data.KeyVault.encryptBytes(it) }
    @Volatile var dec: (ByteArray) -> ByteArray? = { com.mazharansari.axion.data.KeyVault.decryptBytes(it) }

    fun pack(dir: File): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bos).use { out ->
            dir.walkTopDown().filter { it.isFile && it.length() < 4 * 1024 * 1024 }.forEach { f ->
                val rel = f.relativeTo(dir).path.toByteArray(); val data = f.readBytes()
                out.writeInt(rel.size); out.write(rel); out.writeInt(data.size); out.write(data)
            }
        }
        return bos.toByteArray()
    }

    fun unpack(bytes: ByteArray, dir: File) {
        dir.deleteRecursively(); dir.mkdirs()
        val root = dir.canonicalFile
        java.io.DataInputStream(bytes.inputStream()).use { inp ->
            while (inp.available() > 0) {
                val rel = ByteArray(inp.readInt()).also { inp.readFully(it) }.decodeToString()
                val data = ByteArray(inp.readInt()).also { inp.readFully(it) }
                val f = File(dir, rel).canonicalFile
                if (!f.path.startsWith(root.path + File.separator)) continue   // never outside the folder
                f.parentFile?.mkdirs(); f.writeBytes(data)
                runCatching { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true) }
            }
        }
    }

    fun seal(dir: File): ByteArray? = enc(pack(dir))
    fun open(blob: ByteArray, dir: File) { dec(blob)?.let { unpack(it, dir) } ?: dir.mkdirs() }
}
