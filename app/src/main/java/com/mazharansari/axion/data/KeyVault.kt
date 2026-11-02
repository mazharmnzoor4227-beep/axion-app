package com.mazharansari.axion.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys, deploy tokens, MCP tokens and the Antigravity sign-in files are encrypted with an AES-256-GCM key that is
 * created inside the Android Keystore and never leaves it (hardware-backed on phones that have a TEE / StrongBox).
 *
 * v0.5.2 security pass:
 *  - no silent plain-text fallback on a phone any more: if the Keystore fails, saving a key fails with a clear message
 *    (the old "plain:" fallback stays only for Robolectric unit tests, and old "plain:" blobs are re-encrypted on load);
 *  - the Keystore key handle is cached, and each blob is decrypted once per app run (speed: the old send path decrypted
 *    every key for every model on every message — a Keystore round trip each);
 *  - [wipe] removes the key from the Keystore ("Delete all data"), which makes every stored blob unreadable for good.
 */
object KeyVault {
    private const val ALIAS = "axion_api_keys_v1"
    @Volatile private var cachedKey: SecretKey? = null
    private val plainCache = ConcurrentHashMap<String, String>()
    /** Keystore decrypt calls since start (tests + the speed report count these). */
    @Volatile var decryptCalls = 0; private set

    /** Unit tests (Robolectric) have no AndroidKeyStore provider. */
    val isTestRuntime: Boolean by lazy { Build.FINGERPRINT == "robolectric" || runCatching { Class.forName("org.robolectric.Robolectric") }.isSuccess }

    class VaultUnavailable(msg: String) : SecurityException(msg)

    @Synchronized private fun key(): SecretKey? {
        cachedKey?.let { return it }
        val k = runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256).build())
                generateKey()
            }
        }.getOrNull()
        cachedKey = k
        return k
    }

    fun encrypt(plain: String): String {
        val raw = encryptBytes(plain.toByteArray()) ?: return testPlain(plain)
        return "gcm:" + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun testPlain(plain: String): String {
        if (!isTestRuntime) throw VaultUnavailable("This phone's secure key storage (Android Keystore) isn't working, so Axion won't save the key in plain text. Restart the phone and try again.")
        return "plain:" + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)
    }

    /** iv(12) ‖ ciphertext+tag, or null when no Keystore (tests). */
    fun encryptBytes(plain: ByteArray): ByteArray? {
        val k = key() ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, k)
        return c.iv + c.doFinal(plain)
    }

    fun decryptBytes(raw: ByteArray): ByteArray? = runCatching {
        val k = key() ?: return null
        decryptCalls++
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, raw, 0, 12))
        c.doFinal(raw, 12, raw.size - 12)
    }.getOrNull()

    /** Test/report hooks: [debugNoCache] reproduces the pre-0.5.2 behaviour (every call decrypts); [decodes] counts real decrypts. */
    @Volatile var debugNoCache = false
    @Volatile var decodes = 0

    fun decrypt(blob: String): String? {
        if (blob.isEmpty()) return null
        if (!debugNoCache) plainCache[blob]?.let { return it }
        decodes++
        val v = runCatching {
            when {
                blob.startsWith("plain:") -> String(Base64.decode(blob.removePrefix("plain:"), Base64.NO_WRAP))
                blob.startsWith("gcm:") -> decryptBytes(Base64.decode(blob.removePrefix("gcm:"), Base64.NO_WRAP))?.let { String(it) }
                else -> null
            }
        }.getOrNull()
        if (v != null) plainCache[blob] = v
        return v
    }

    /** An old "plain:" blob (a pre-0.5.2 fallback) → re-encrypted with the Keystore when it works now; else unchanged. */
    fun upgrade(blob: String): String {
        if (!blob.startsWith("plain:") || key() == null) return blob
        val v = decrypt(blob) ?: return blob
        return runCatching { encrypt(v) }.getOrDefault(blob)
    }

    fun forget(blob: String) { plainCache.remove(blob) }

    /** "Delete all data": drop the cached plain text and delete the Keystore key itself. */
    @Synchronized fun wipe() {
        plainCache.clear(); cachedKey = null
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }
}
