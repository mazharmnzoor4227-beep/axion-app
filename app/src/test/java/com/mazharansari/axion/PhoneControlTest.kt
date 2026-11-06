package com.mazharansari.axion.phone

import com.mazharansari.axion.phone.PhoneSafety.Node
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneControlTest {
    // ---------- safety: banking / payment / wallet are permanently excluded ----------
    @Test fun bankingPaymentWalletPackagesAreDenied() {
        listOf("com.techlogix.mobilink", "pk.com.telenor.phoenix", "com.hbl.android.hblmobilebanking", "com.meezan.mobilebanking", "com.mcb.mcblive", "com.ubl.unified",
            "com.sadapay.app", "com.nayapay.app", "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user", "com.paypal.android.p2pmobile",
            "com.samsung.android.spay", "com.some.newbank.app", "com.foo.mobilebanking", "org.example.cryptowallet", "com.binance.dev", "com.bitwarden.mobile", "com.google.android.apps.authenticator2",
            "com.askari.digital", "com.hbl.konnect", "com.easypaisa.app", "com.jazzcash.new", "io.metamask").forEach { assertTrue(it, PhoneSafety.isDeniedPackage(it)) }
    }
    @Test fun ordinaryAppsAreNotDenied() {
        listOf("com.android.chrome", "com.android.settings", "com.mazharansari.axion", "com.google.android.youtube", "com.whatsapp", "com.google.android.apps.maps", "com.android.vending",
            "com.google.android.apps.photos", "com.instagram.android", "com.google.android.googlequicksearchbox", "org.mozilla.firefox", "com.android.publicdomain", "com.google.android.apps.publishing").forEach { assertFalse(it, PhoneSafety.isDeniedPackage(it)) }
        assertFalse(PhoneSafety.isDeniedPackage(null)); assertFalse(PhoneSafety.isDeniedPackage(""))
    }
    @Test fun appLabelsAreCheckedToo() {
        listOf("JazzCash", "Easypaisa", "HBL Mobile", "Meezan Mobile Banking", "Google Wallet", "Samsung Pay", "PayPal", "SadaPay", "NayaPay", "Bank Alfalah", "Binance", "Authenticator", "Bitwarden", "Google Pay").forEach { assertTrue(it, PhoneSafety.isDeniedLabel(it)) }
        listOf("Chrome", "Settings", "YouTube", "WhatsApp", "Play Store", "Maps", "Calculator", "Camera").forEach { assertFalse(it, PhoneSafety.isDeniedLabel(it)) }
    }
    @Test fun sensitiveScreensAreDetected() {
        assertNotNull(PhoneSafety.sensitiveScreen("com.some.app", listOf(Node(1, text = "Login"), Node(2, editable = true, password = true))))
        assertNotNull(PhoneSafety.sensitiveScreen("com.some.app", listOf(Node(1, text = "Please enter your PIN"))))
        assertNotNull(PhoneSafety.sensitiveScreen("com.some.app", listOf(Node(1, text = "Enter the one-time password we sent"))))
        assertNotNull(PhoneSafety.sensitiveScreen("com.some.app", listOf(Node(1, desc = "Card number"), Node(2, text = "CVV"))))
        assertNotNull(PhoneSafety.sensitiveScreen("com.hbl.android.hblmobilebanking", listOf(Node(1, text = "Welcome"))))
        assertNotNull(PhoneSafety.sensitiveScreen("com.android.chrome", listOf(Node(1, text = "https://secure.mybank.com/login", viewId = "com.android.chrome:id/url_bar"))))
        assertNull(PhoneSafety.sensitiveScreen("com.android.chrome", listOf(Node(1, text = "https://www.google.com/search?q=weather", viewId = "com.android.chrome:id/url_bar"), Node(2, text = "Search or type URL", editable = true))))
        assertNull(PhoneSafety.sensitiveScreen("com.android.settings", listOf(Node(1, text = "Wi-Fi", clickable = true), Node(2, text = "Bluetooth", clickable = true))))
    }
    @Test fun irreversibleButtonsNeedConfirmation() {
        listOf("Send", "Delete", "Pay now", "Buy now", "Place order", "Post", "Uninstall", "Factory reset", "Delete all messages").forEach { assertTrue(it, PhoneSafety.looksIrreversible(it)) }
        listOf("Search", "Wi-Fi", "Next", "Open", "Settings", "Reply", "Sender name").forEach { assertFalse(it, PhoneSafety.looksIrreversible(it)) }
    }

    // ---------- run token ----------
    @Test fun gateOnlyWorksDuringARun() {
        val g = PhoneGate()
        assertTrue(g.check()!!.contains("not running"))        // nothing runs in the background
        g.begin(); assertNull(g.check()); g.end(); assertNotNull(g.check())
    }
    @Test fun stopKillsTheTokenInstantly() {
        val g = PhoneGate(); g.begin(); assertNull(g.check())
        g.stop("You pressed STOP"); assertTrue(g.check()!!.startsWith("Stopped: You pressed STOP")); assertFalse(g.isActive)
        g.begin(); assertNull(g.check())                         // a new turn is a new token
    }
    @Test fun timeAndActionLimits() {
        var now = 0L; val g = PhoneGate({ now }, maxActions = 3, maxMs = 1000); g.begin()
        repeat(3) { g.countAction() }; assertTrue(g.check()!!.contains("action limit"))
        g.begin(); now = 2000; assertTrue(g.check()!!.contains("time limit"))
    }

    // ---------- actions over a fake phone ----------
    private class Fake(var screens: MutableList<PhoneScreen>, var apps: List<AppRef> = listOf(AppRef("Chrome", "com.android.chrome"), AppRef("Settings", "com.android.settings"), AppRef("JazzCash", "com.techlogix.mobilink"))) : PhoneBackend {
        override var connected = true
        val log = mutableListOf<String>(); var idx = 0
        override suspend fun snapshot() = screens[idx.coerceAtMost(screens.size - 1)]
        override suspend fun click(id: Int): Boolean { log += "click $id"; return true }
        override suspend fun type(id: Int, text: String, submit: Boolean): Boolean { log += "type $id '$text' submit=$submit"; if (submit) idx++; return true }
        override suspend fun scroll(direction: String): Boolean { log += "scroll $direction"; return true }
        override suspend fun back(): Boolean { log += "back"; return true }
        override suspend fun home(): Boolean { log += "home"; return true }
        override fun findApps(query: String) = apps.filter { it.label.contains(query, true) || (query.equals("browser", true) && it.label == "Chrome") }
        override suspend fun launch(pkg: String): Boolean { log += "launch $pkg"; return true }
    }
    private fun chromeHome() = PhoneScreen("com.android.chrome", "Chrome", listOf(Node(1, text = "Search or type URL", editable = true, viewId = "com.android.chrome:id/url_bar", className = "EditText"), Node(2, text = "Gmail", clickable = true)))
    private fun chromeResults() = PhoneScreen("com.android.chrome", "Chrome", listOf(Node(1, text = "https://www.google.com/search?q=load+shedding", viewId = "com.android.chrome:id/url_bar"), Node(2, text = "Load shedding schedule Karachi - K-Electric", clickable = true)))
    private fun act(f: Fake, g: PhoneGate) = PhoneActions(f, g, settleMs = 0)

    @Test fun acceptanceBrowserSearchEveryStepIsLoggedAndVisible() = runBlocking {
        val f = Fake(mutableListOf(chromeHome(), chromeResults())); val g = PhoneGate(); val a = act(f, g); g.begin()
        assertFalse(a.openApp("browser").isError)
        val s1 = a.screen(); assertFalse(s1.isError); assertTrue(s1.forModel.contains("[1] text field \"Search or type URL\""))
        assertFalse(a.tap(1).isError); assertFalse(a.type(1, "load shedding schedule", true).isError)
        val s2 = a.screen(); assertTrue(s2.forModel.contains("Load shedding schedule Karachi"))
        assertEquals(listOf("launch com.android.chrome", "click 1", "type 1 'load shedding schedule' submit=true"), f.log)
        val shown = g.all().joinToString("\n"); assertTrue(shown, shown.contains("Opening Chrome") && shown.contains("Typing") && shown.contains("Reading Chrome"))   // the live overlay log
    }
    @Test fun stopHaltsTheVeryNextActionAtOnce() = runBlocking {
        val f = Fake(mutableListOf(chromeHome())); val g = PhoneGate(); val a = act(f, g); g.begin()
        assertFalse(a.tap(2).isError)
        g.stop("You pressed STOP")
        for (r in listOf(a.screen(), a.tap(2), a.type(1, "x", false), a.scroll("down"), a.back(), a.home(), a.openApp("Chrome"), a.waitSec(1))) { assertTrue(r.isError); assertTrue(r.forModel, r.forModel.startsWith("Stopped")) }
        assertEquals(listOf("click 2"), f.log)                    // nothing touched the phone after STOP
    }
    @Test fun nothingRunsWithoutAnActiveRun() = runBlocking {
        val f = Fake(mutableListOf(chromeHome())); val a = act(f, PhoneGate())
        assertTrue(a.tap(1).isError); assertTrue(a.openApp("Chrome").isError); assertTrue(f.log.isEmpty())
    }
    @Test fun serviceOffExplainsHowToEnableIt() = runBlocking {
        val f = Fake(mutableListOf(chromeHome())).also { it.connected = false }; val g = PhoneGate(); g.begin()
        val r = act(f, g).screen(); assertTrue(r.isError); assertTrue(r.forModel.contains("Accessibility"))
    }
    @Test fun bankingAppsCannotBeOpenedByNameOrLabel() = runBlocking {
        val f = Fake(mutableListOf(chromeHome())); val g = PhoneGate(); g.begin(); val a = act(f, g)
        for (name in listOf("JazzCash", "com.techlogix.mobilink", "Easypaisa", "my bank app")) { val r = a.openApp(name); assertTrue(name, r.isError); assertEquals(126, r.exitCode) }
        assertTrue(f.log.none { it.startsWith("launch") })
    }
    @Test fun ifABankingAppIsInFrontItGoesHomeAndRefuses() = runBlocking {
        val f = Fake(mutableListOf(PhoneScreen("com.techlogix.mobilink", "JazzCash", listOf(Node(1, text = "Send money", clickable = true))))); val g = PhoneGate(); g.begin(); val a = act(f, g)
        val r = a.screen(); assertTrue(r.isError); assertTrue(r.forModel.contains("home screen")); assertFalse(r.forModel.contains("Send money"))
        assertEquals(listOf("home"), f.log); assertTrue(a.tap(1).isError); assertFalse(f.log.contains("click 1"))
    }
    @Test fun passwordFieldsAreNeverReadOrTyped() = runBlocking {
        val login = PhoneScreen("com.some.app", "Some App", listOf(Node(1, text = "Email", editable = true), Node(2, text = "Password", editable = true, password = true), Node(3, text = "Log in", clickable = true)))
        val f = Fake(mutableListOf(login)); val g = PhoneGate(); g.begin(); val a = act(f, g)
        val s = a.screen(); assertTrue(s.isError); assertFalse(s.forModel.contains("Email")); assertTrue(a.type(2, "hunter2", false).isError); assertTrue(a.type(1, "me@x.com", false).isError)
        assertTrue(f.log.isEmpty())
    }
    @Test fun irreversibleTapWaitsForTheUsersOwnConfirmPress() = runBlocking {
        val screen = PhoneScreen("com.whatsapp", "WhatsApp", listOf(Node(1, text = "Send", clickable = true), Node(2, text = "Attach", clickable = true)))
        // (a) v0.6.1: there is no confirmed=true any more — with nobody pressing CONFIRM the tap refuses and nothing is clicked
        val f1 = Fake(mutableListOf(screen)); val g1 = PhoneGate(confirmTimeoutMs = 300); g1.begin(); val a1 = act(f1, g1)
        val r = a1.tap(1); assertTrue(r.isError); assertEquals(126, r.exitCode); assertTrue(r.forModel.contains("CONFIRM")); assertFalse(r.forModel.contains("confirmed=true")); assertTrue(f1.log.isEmpty())
        // (b) the tap parks until the user personally presses CONFIRM — then exactly that one tap goes through
        val f2 = Fake(mutableListOf(screen)); val g2 = PhoneGate(confirmTimeoutMs = 5000); g2.begin(); val a2 = act(f2, g2)
        val waiting = async { a2.tap(1) }
        delay(200); assertTrue(g2.pendingConfirm != null); assertTrue(f2.log.isEmpty())          // parked: nothing tapped yet
        g2.confirmPending()
        assertFalse(waiting.await().isError); assertEquals(listOf("click 1"), f2.log)
        assertFalse(a2.tap(2).isError)                                                            // ordinary buttons never wait
        // (c) STOP while a confirmation is waiting answers "no" at once
        val f3 = Fake(mutableListOf(screen)); val g3 = PhoneGate(confirmTimeoutMs = 5000); g3.begin(); val a3 = act(f3, g3)
        val waiting3 = async { a3.tap(1) }; delay(200); g3.stop("You pressed STOP")
        assertTrue(waiting3.await().isError); assertTrue(f3.log.isEmpty())
    }
    @Test fun otpPhrasingVariantsAreCaught() {
        assertNotNull(PhoneSafety.sensitiveScreen("com.some.app", listOf(Node(1, text = "Enter the 6-digit code we sent you"))))
        assertNotNull(PhoneSafety.sensitiveScreen("com.some.app", listOf(Node(1, text = "OTP", editable = true))))
        assertNotNull(PhoneSafety.sensitiveScreen("com.some.app", listOf(Node(1, text = "Verification code", editable = true))))
        assertNotNull(PhoneSafety.sensitiveScreen("com.some.app", listOf(Node(1, text = "Verify your phone number"), Node(2, text = "Code", editable = true))))
        assertNotNull(PhoneSafety.sensitiveScreen("com.some.app", listOf(Node(1, text = "Apna 4-digit code daalo"))))
    }
    @Test fun plainBankDomainsAreBlockedInTheBrowser() {
        assertNotNull(PhoneSafety.sensitiveScreen("com.android.chrome", listOf(Node(1, text = "https://www.hbl.com/login", viewId = "com.android.chrome:id/url_bar"))))
        assertNotNull(PhoneSafety.sensitiveScreen("com.android.chrome", listOf(Node(1, text = "easypaisa.com.pk/account", viewId = "com.android.chrome:id/url_bar"))))
        assertNull(PhoneSafety.sensitiveScreen("com.android.chrome", listOf(Node(1, text = "https://www.google.com/search?q=hbl+timings", viewId = "com.android.chrome:id/url_bar"))))
    }
    @Test fun unlabelledIconBesideAFieldCountsAsSend() {
        val field = Node(1, editable = true, l = 100, t = 900, r = 800, b = 1000, className = "EditText")
        val icon = Node(2, clickable = true, l = 830, t = 910, r = 950, b = 990, className = "ImageButton")
        val farIcon = Node(3, clickable = true, l = 830, t = 100, r = 950, b = 200, className = "ImageButton")
        val nodes = listOf(field, icon, farIcon)
        assertTrue(PhoneSafety.looksLikeSend(icon, nodes)); assertFalse(PhoneSafety.looksLikeSend(farIcon, nodes))
        assertFalse(PhoneSafety.looksLikeSend(Node(4, clickable = true), nodes))   // unknown bounds never match
    }
    @Test fun backDoesNotActBlindOnASensitiveScreen() = runBlocking {
        val login = PhoneScreen("com.some.app", "Some App", listOf(Node(1, text = "Password", editable = true, password = true)))
        val f = Fake(mutableListOf(login)); val g = PhoneGate(); g.begin(); val a = act(f, g)
        assertTrue(a.back().isError); assertTrue(f.log.isEmpty())
        assertFalse(a.home().isError); assertEquals(listOf("home"), f.log)   // Home is the escape hatch — always allowed
    }
    @Test fun badIdsAndNonFieldsGiveHelpfulErrors() = runBlocking {
        val f = Fake(mutableListOf(chromeHome())); val g = PhoneGate(); g.begin(); val a = act(f, g)
        assertTrue(a.tap(99).forModel.contains("phone_screen")); assertTrue(a.type(2, "x", false).forModel.contains("not a text field")); assertTrue(a.scroll("sideways").isError)
        assertTrue(a.openApp("Nonexistent").forModel.contains("No installed app"))
    }
    @Test fun actionLimitStopsARunawayLoop() = runBlocking {
        val f = Fake(mutableListOf(chromeHome())); val g = PhoneGate(maxActions = 5); g.begin(); val a = act(f, g)
        repeat(5) { assertFalse(a.tap(2).isError) }
        val r = a.tap(2); assertTrue(r.forModel, r.forModel.contains("action limit")); assertEquals(5, f.log.size)
    }
    @Test fun theToolsRefuseWhenNoRunToken() = runBlocking {
        val tools = PhoneTools.all { PhoneActions(Fake(mutableListOf(chromeHome())), PhoneGate(), 0) }
        assertEquals(PhoneTools.NAMES.split(" ").toSet(), tools.map { it.spec.name }.toSet())
        assertTrue(tools.all { it.spec.description.isNotBlank() })
    }
}
