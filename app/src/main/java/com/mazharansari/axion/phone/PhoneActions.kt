package com.mazharansari.axion.phone

import com.mazharansari.axion.agent.ToolOutput
import kotlinx.coroutines.delay

data class PhoneScreen(val pkg: String, val label: String, val nodes: List<PhoneSafety.Node>)
data class AppRef(val label: String, val pkg: String)

/** What the Accessibility service offers (a fake one in the unit tests). Node ids are only valid for the last [snapshot]. */
interface PhoneBackend {
    val connected: Boolean
    suspend fun snapshot(): PhoneScreen?
    suspend fun click(id: Int): Boolean
    suspend fun type(id: Int, text: String, submit: Boolean): Boolean
    suspend fun scroll(direction: String): Boolean
    suspend fun back(): Boolean
    suspend fun home(): Boolean
    fun findApps(query: String): List<AppRef>
    suspend fun launch(pkg: String): Boolean
}

/**
 * The phone tools' brain: every action goes through the same checks, in this order — run token (STOP / time / action limit), service connected,
 * foreground app not banking/payment/wallet, screen not sensitive, irreversible buttons confirmed — then it acts, logs the step for the
 * live overlay, and lets the screen settle. Refusals carry exit code [REFUSED] so the fault policy does not blindly retry them.
 */
class PhoneActions(private val backend: PhoneBackend, private val gate: PhoneGate, private val settleMs: Long = 700) {
    companion object { const val REFUSED = 126; const val MAX_NODES = 70 }
    @Volatile private var last: PhoneScreen? = null

    private fun refuse(msg: String) = ToolOutput(msg, isError = true, exitCode = REFUSED)
    private fun fail(msg: String) = ToolOutput(msg, isError = true)

    private fun precheck(): ToolOutput? {
        gate.check()?.let { return refuse(it) }
        if (!backend.connected) return refuse("Phone control is switched on in Axion, but the Android Accessibility service is not enabled. Tell the user: Settings → Phone control → Open Accessibility settings → Axion → On.")
        return null
    }

    /** Looks at the screen for the safety rules. Returns the screen, or a refusal (and goes Home if a forbidden app is in front). */
    private suspend fun look(): Pair<PhoneScreen?, ToolOutput?> {
        val s = backend.snapshot() ?: return null to fail("Could not read the screen (the app may block it). Try phone_wait, then phone_screen again.")
        last = s
        sensitive(s)?.let { why ->
            if (PhoneSafety.isDeniedPackage(s.pkg) || PhoneSafety.isDeniedLabel(s.label)) { backend.home(); gate.log("✋ Blocked app — went Home") }
            else gate.log("✋ Sensitive screen — not touching it")
            return s to refuse("Refused: $why. Axion never operates banking, payment or wallet apps and never reads or types passwords, PINs or OTPs. ${if (PhoneSafety.isDeniedPackage(s.pkg)) "I went back to the home screen. " else ""}Tell the user to do this step themselves.")
        }
        return s to null
    }
    private fun sensitive(s: PhoneScreen): String? = PhoneSafety.sensitiveScreen(s.pkg, s.nodes) ?: if (PhoneSafety.isDeniedLabel(s.label)) "this is a banking / payment / wallet app" else null

    private fun describe(n: PhoneSafety.Node): String {
        val role = when { n.password -> "password field"; n.editable -> "text field"; n.clickable && n.className.contains("Button") -> "button"; n.clickable -> "tappable"; n.scrollable -> "scrollable list"; n.className.contains("Image") -> "image"; else -> "text" }
        val flags = buildList { if (n.checked == true) add("on") else if (n.checked == false) add("off") }
        return "[${n.id}] $role" + (n.label.takeIf { it.isNotBlank() }?.let { " \"${it.replace('\n', ' ').take(80)}\"" } ?: "") + (if (flags.isNotEmpty()) " (${flags.joinToString()})" else "")
    }

    suspend fun screen(): ToolOutput {
        precheck()?.let { return it }
        val (s, bad) = look(); if (bad != null) return bad
        s!!; val shown = s.nodes.take(MAX_NODES)
        gate.log("👁 Reading ${s.label.ifBlank { s.pkg }}")
        return ToolOutput("App: ${s.label.ifBlank { s.pkg }} (${s.pkg})\n" + shown.joinToString("\n") { describe(it) } + if (s.nodes.size > shown.size) "\n… ${s.nodes.size - shown.size} more items (scroll)" else "")
    }

    suspend fun openApp(name: String): ToolOutput {
        precheck()?.let { return it }
        if (name.isBlank()) return fail("Give the app name, e.g. Chrome.")
        if (PhoneSafety.isDeniedLabel(name) || PhoneSafety.isDeniedPackage(name)) { gate.log("✋ Refused to open $name"); return refuse("Refused: Axion never opens banking, payment or wallet apps. Tell the user to open it themselves.") }
        val hits = backend.findApps(name)
        if (hits.isEmpty()) return fail("No installed app matches \"$name\". Try another name (e.g. Chrome, Settings, YouTube).")
        val ok = hits.filter { !PhoneSafety.isDeniedPackage(it.pkg) && !PhoneSafety.isDeniedLabel(it.label) }
        if (ok.isEmpty()) { gate.log("✋ Refused to open ${hits.first().label}"); return refuse("Refused: ${hits.first().label} is a banking / payment / wallet app. Tell the user to open it themselves.") }
        val app = ok.first()
        gate.countAction(); gate.log("▶ Opening ${app.label}")
        if (!backend.launch(app.pkg)) return fail("Could not open ${app.label}.")
        delay(settleMs + 600)
        return ToolOutput("Opened ${app.label}. Call phone_screen to see it.")
    }

    suspend fun tap(id: Int, confirmed: Boolean): ToolOutput {
        precheck()?.let { return it }
        val (s, bad) = look(); if (bad != null) return bad
        val n = s!!.nodes.firstOrNull { it.id == id } ?: return fail("No element [$id] on this screen. Call phone_screen for fresh numbers.")
        if (!confirmed && PhoneSafety.looksIrreversible(n.label)) {
            gate.log("⏸ Waiting for your OK: \"${n.label.take(40)}\"")
            return refuse("\"${n.label}\" looks irreversible (send / delete / pay / post …). Ask the user in chat to confirm, and only after they say yes call phone_tap again with confirmed=true.")
        }
        if (n.password) return refuse("Refused: that is a password field.")
        gate.countAction(); gate.log("👆 Tap ${n.label.ifBlank { "[$id]" }.take(40)}")
        if (!backend.click(id)) return fail("Tapping [$id] did not work (element gone or not tappable). Call phone_screen again.")
        delay(settleMs); return ToolOutput("Tapped [$id] ${n.label.take(40)}. Call phone_screen to see the result.")
    }

    suspend fun type(id: Int, text: String, submit: Boolean, confirmed: Boolean = false): ToolOutput {
        precheck()?.let { return it }
        val (s, bad) = look(); if (bad != null) return bad
        val n = s!!.nodes.firstOrNull { it.id == id } ?: return fail("No element [$id] on this screen. Call phone_screen for fresh numbers.")
        if (n.password) { gate.log("✋ Refused to type into a password field"); return refuse("Refused: that is a password / PIN field. Axion never types those — ask the user to type it.") }
        if (!n.editable) return fail("[$id] is not a text field. Tap the search box / field first, then phone_screen again.")
        if (text.length > 2000) return fail("Text too long (max 2000 characters).")
        gate.countAction(); gate.log("⌨ Typing \"${text.take(30)}\"" + if (submit) " + Enter" else "")
        if (!backend.type(id, text, submit)) return fail("Typing into [$id] did not work. Tap the field first, then try again.")
        delay(settleMs); return ToolOutput("Typed into [$id]." + if (submit) " Pressed Enter/Search." else "")
    }

    suspend fun scroll(direction: String): ToolOutput {
        precheck()?.let { return it }
        val d = direction.trim().lowercase(); if (d !in setOf("up", "down", "left", "right")) return fail("direction must be up, down, left or right.")
        val (_, bad) = look(); if (bad != null) return bad
        gate.countAction(); gate.log("↕ Scroll $d")
        if (!backend.scroll(d)) return fail("Nothing to scroll here (or the end was reached).")
        delay(settleMs); return ToolOutput("Scrolled $d. Call phone_screen to read the new content.")
    }

    suspend fun back(): ToolOutput { precheck()?.let { return it }; gate.countAction(); gate.log("◀ Back"); backend.back(); delay(settleMs); return ToolOutput("Went back.") }
    suspend fun home(): ToolOutput { precheck()?.let { return it }; gate.countAction(); gate.log("⌂ Home"); backend.home(); delay(settleMs); return ToolOutput("Went to the home screen.") }
    suspend fun waitSec(sec: Int): ToolOutput { precheck()?.let { return it }; val s = sec.coerceIn(1, 10); gate.log("⏳ Waiting ${s}s"); delay(s * 1000L); return ToolOutput("Waited $s s.") }
}
