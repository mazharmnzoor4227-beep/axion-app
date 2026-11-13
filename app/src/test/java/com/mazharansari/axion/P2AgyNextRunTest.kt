package com.mazharansari.axion

import com.mazharansari.axion.antigravity.AgyAccount
import com.mazharansari.axion.antigravity.AgySelector
import com.mazharansari.axion.antigravity.AgyStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P2-7 (multi-account Antigravity UI + usage dashboard): the one-shot per-task
 * pick is pure and tested here. The dashboard itself (Quota screen + the
 * accounts list with per-account /usage buckets) already existed — this item
 * adds the per-task pick on top of it, and the run paths (AgyEngine, AgyBrain)
 * both go through pickForRun, which consumes the pick.
 */
class P2AgyNextRunTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val now = System.currentTimeMillis()
    private val accounts = listOf(
        AgyAccount("a@gmail.com", "a_folder", label = "Account 1", order = 0),
        AgyAccount("b@gmail.com", "b_folder", label = "Account 2", order = 1),
    )

    @Test
    fun `a usable override account is returned`() {
        val acc = AgySelector.resolveOneShot(accounts, "a@gmail.com", now, { true }, emptySet())
        assertEquals("a@gmail.com", acc?.email)
    }

    @Test
    fun `override is case-insensitive on email`() {
        val acc = AgySelector.resolveOneShot(accounts, "B@GMAIL.COM", now, { true }, emptySet())
        assertEquals("b@gmail.com", acc?.email)
    }

    @Test
    fun `a disabled override falls through to null`() {
        val disabled = accounts.map { if (it.email == "a@gmail.com") it.copy(enabled = false) else it }
        assertNull(AgySelector.resolveOneShot(disabled, "a@gmail.com", now, { true }, emptySet()))
    }

    @Test
    fun `an expired or cooling-down override falls through to null`() {
        val expired = accounts.map { if (it.email == "a@gmail.com") it.copy(authExpiredAt = now) else it }
        assertNull(AgySelector.resolveOneShot(expired, "a@gmail.com", now, { true }, emptySet()))
        val cooling = accounts.map { if (it.email == "a@gmail.com") it.copy(cooldownUntil = now + 60_000) else it }
        assertNull(AgySelector.resolveOneShot(cooling, "a@gmail.com", now, { true }, emptySet()))
    }

    @Test
    fun `an override without a token falls through to null`() {
        assertNull(AgySelector.resolveOneShot(accounts, "a@gmail.com", now, { false }, emptySet()))
    }

    @Test
    fun `an excluded override falls through to null`() {
        assertNull(AgySelector.resolveOneShot(accounts, "a@gmail.com", now, { true }, setOf("a@gmail.com")))
    }

    @Test
    fun `a missing or blank override falls through to null`() {
        assertNull(AgySelector.resolveOneShot(accounts, "nobody@gmail.com", now, { true }, emptySet()))
        assertNull(AgySelector.resolveOneShot(accounts, null, now, { true }, emptySet()))
        assertNull(AgySelector.resolveOneShot(accounts, "  ", now, { true }, emptySet()))
    }

    @Test
    fun `the one-shot pick survives a store round-trip`() {
        val store = AgyStore(accounts = accounts, activeEmail = "a@gmail.com", nextRunEmail = "b@gmail.com")
        val back = json.decodeFromString<AgyStore>(json.encodeToString(store))
        assertEquals("b@gmail.com", back.nextRunEmail)
        assertEquals("a@gmail.com", back.activeEmail)
    }

    @Test
    fun `an old store without the field decodes with no pick`() {
        val back = json.decodeFromString<AgyStore>("""{"accounts":[],"activeEmail":null,"autoSwitch":true,"useInChat":"off","model":"","models":[]}""")
        assertNull(back.nextRunEmail)
    }
}
