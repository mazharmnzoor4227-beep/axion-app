package com.mazharansari.axion.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Export / import of API keys as one encrypted file with a password the user chooses.
 *
 * v0.5.2 (format v2): PBKDF2-HMAC-SHA256 with 600,000 rounds (OWASP 2023; v1 used 210,000) → AES-256-GCM, a fresh random
 * 16-byte salt and 12-byte nonce per file, and the file's header (format, version, kdf, rounds, salt) bound into the
 * GCM tag as associated data, so changing any of it makes the file fail to open. A wrong password and a damaged file give
 * the same answer and reveal nothing about the contents. v1 files still import. The password itself is never stored.
 */
object KeyBackup {
    @Serializable data class Entry(val providerId: String, val key: String, val models: List<String>, val fastModels: List<String>, val pinnedModel: String? = null, val enabled: Boolean = true)
    @Serializable data class Envelope(val format: String = "axion-keys", val v: Int = 2, val kdf: String = "PBKDF2WithHmacSHA256", val iter: Int, val salt: String, val iv: String, val data: String, val count: Int)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    const val ITER = 600_000
    const val MIN_PASSWORD = 8
    /** Files from someone else can't make the phone grind forever, and can't be too weak to be ours. */
    private val ITER_RANGE = 100_000..5_000_000
    const val WRONG = "Wrong password, or the file is damaged."

    private fun aad(e: Envelope) = "${e.format}|${e.v}|${e.kdf}|${e.iter}|${e.salt}".toByteArray()

    fun export(entries: List<Entry>, password: String, iter: Int = ITER): String {
        require(password.length >= MIN_PASSWORD) { "Use a password of at least $MIN_PASSWORD characters" }
        val rnd = SecureRandom(); val salt = ByteArray(16).also(rnd::nextBytes); val iv = ByteArray(12).also(rnd::nextBytes)
        val head = Envelope(iter = iter, salt = b64(salt), iv = b64(iv), data = "", count = entries.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt, iter), GCMParameterSpec(128, iv)); updateAAD(aad(head)) }
        val ct = c.doFinal(json.encodeToString(entries).toByteArray())
        return json.encodeToString(head.copy(data = b64(ct)))
    }

    fun import(file: String, password: String): List<Entry> {
        val env = runCatching { json.decodeFromString<Envelope>(file) }.getOrElse { throw IllegalArgumentException("This isn't an Axion keys file") }
        require(env.format == "axion-keys") { "This isn't an Axion keys file" }
        require(env.v in 1..2 && env.kdf == "PBKDF2WithHmacSHA256" && env.iter in ITER_RANGE) { "This keys file is from an unknown version of Axion." }
        val plain = runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(password, unb64(env.salt), env.iter), GCMParameterSpec(128, unb64(env.iv)))
                if (env.v >= 2) updateAAD(aad(env))
            }
            c.doFinal(unb64(env.data))
        }.getOrElse { throw IllegalArgumentException(WRONG) }
        return runCatching { json.decodeFromString<List<Entry>>(plain.decodeToString()) }.getOrElse { throw IllegalArgumentException(WRONG) }
    }

    private fun key(pw: String, salt: ByteArray, iter: Int): SecretKeySpec {
        val spec = PBEKeySpec(pw.toCharArray(), salt, iter, 256)
        try { return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = java.util.Base64.getDecoder().decode(s)
}
