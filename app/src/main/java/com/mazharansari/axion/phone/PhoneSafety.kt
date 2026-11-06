package com.mazharansari.axion.phone

/**
 * v0.6 (Phase 3.2) PHONE SAFETY — pure Kotlin, no Android classes, unit-tested (PhoneControlTest). Original code.
 * Rules that cannot be switched off:
 *  - banking / payment / wallet / crypto apps (and password managers and authenticators) are NEVER operated: the package name, the app label and, in a
 *    browser, the page address are all checked; a match refuses the action and the service sends the user to the home screen;
 *  - a screen that asks for a password, PIN, OTP, card number or CVV is never read out and never typed into;
 *  - buttons that look irreversible (send, delete, pay, buy, post …) need the user's own CONFIRM press on the on-screen control strip;
 *    the model cannot confirm them itself (v0.6.1).
 */
object PhoneSafety {
    /** Known ids (Pakistan first). Anything else is caught by the token rules below. */
    val knownDeniedPackages: Set<String> = setOf(
        "com.techlogix.mobilink", "pk.com.telenor.phoenix", "com.hbl.android.hblmobilebanking", "com.meezan.mobilebanking", "com.mcb.mcblive",
        "com.ubl.unified", "com.bankalfalah.myalfalah", "com.sadapay.app", "com.nayapay.app", "com.abl.mobile", "com.askari.mobile",
        "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user", "com.paypal.android.p2pmobile", "com.samsung.android.spay",
        "com.venmo", "com.squareup.cash", "com.revolut.revolut", "com.binance.dev", "com.coinbase.android",
    )
    private val exactTokens = setOf(
        "ubl", "hbl", "mcb", "abl", "nbp", "mobilink", "jazzcash", "easypaisa", "sadapay", "nayapay", "raast", "zindigi", "keenu", "upaisa", "omni", "finja",
        "paypal", "venmo", "cashapp", "revolut", "payoneer", "wise", "skrill", "neteller", "binance", "coinbase", "kraken", "metamask", "trustwallet",
        "gpay", "paytm", "phonepe", "bhim", "upi", "alipay", "wechatpay", "stripe", "zelle", "monzo", "n26", "chime", "klarna",
        "authenticator", "bitwarden", "lastpass", "1password", "dashlane", "keepass", "keepassdroid", "authy",
    )
    private val tokenContains = listOf("bank", "wallet", "paisa", "fintech", "finance", "crypto", "bitcoin", "remit", "payment", "mobilebank", "creditcard", "debitcard", "passwordmanager", "authenticator", "bitwarden", "lastpass", "1password", "dashlane", "keepass", "meezan", "alfalah", "askari", "faysal", "soneri", "habib")
    private fun tokens(s: String) = s.lowercase().split('.', '_', '-', ' ', '/', ':').filter { it.isNotEmpty() }
    private fun payLike(t: String) = t.startsWith("pay") || t.endsWith("pay")

    fun isDeniedPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val p = pkg.lowercase()
        if (p in knownDeniedPackages) return true
        return tokens(p).any { it in exactTokens || tokenContains.any { k -> it.contains(k) } || payLike(it) }
    }

    /** App name as shown on the home screen: "HBL Mobile", "JazzCash", "Google Wallet", "Samsung Pay". */
    fun isDeniedLabel(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        val l = label.lowercase()
        if (tokenContains.any { l.contains(it) }) return true
        val words = l.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return words.any { it in exactTokens || payLike(it) } || l.replace(" ", "").let { c -> listOf("jazzcash", "easypaisa", "sadapay", "nayapay", "googlepay", "samsungpay", "applepay", "mobilebanking").any { c.contains(it) } }
    }

    private val deniedHostWords = listOf("bank", "paypal", "jazzcash", "easypaisa", "sadapay", "nayapay", "payoneer", "wise.com", "binance", "coinbase", "revolut", "stripe.com/login", "banking", "wallet.")
    /** v0.6.1: real bank / wallet domains — a plain domain like hbl.com carries no keyword, so it is listed outright (host or any subdomain matches). */
    private val deniedHosts = setOf(
        "hbl.com", "habibbank.com", "meezanbank.com", "bankalfalah.com", "ubl.com.pk", "mcb.com.pk", "abl.com", "askaribank.com",
        "soneri.com", "faysalbank.com", "bankalhabib.com", "jsbank.com", "jazzcash.com.pk", "easypaisa.com.pk", "sadapay.pk", "nayapay.com",
    )
    private fun hostOf(urlText: String): String = urlText.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?').substringBefore(':')
    private fun isDeniedHost(urlText: String): Boolean {
        val host = hostOf(urlText)
        if (host.isBlank()) return false
        if (deniedHosts.any { host == it || host.endsWith(".$it") }) return true
        return deniedHostWords.any { urlText.lowercase().contains(it) }
    }
    private val urlBarIds = listOf("url_bar", "location_bar", "url_view", "mozac_browser_toolbar_url_view", "urlbar", "addressbar", "address_bar")

    data class Node(
        val id: Int, val text: String = "", val desc: String = "", val viewId: String = "",
        val clickable: Boolean = false, val editable: Boolean = false, val password: Boolean = false, val scrollable: Boolean = false,
        val checked: Boolean? = null, val className: String = "",
        /** v0.6.1: on-screen bounds (0 = unknown, e.g. in old test fixtures). */
        val l: Int = 0, val t: Int = 0, val r: Int = 0, val b: Int = 0,
    ) {
        val label: String get() = text.ifBlank { desc }
        val hasBounds get() = r > l && b > t
    }

    private val sensitiveText = Regex(
        "(enter|type|input|confirm|re-?enter|your)\\s+(your\\s+|the\\s+|a\\s+)?(pin|passcode|password|otp|cvv|cvc|one-?time (code|password)|security code|verification code|mpin|atm pin)\\b|" +
        "\\b(card number|cvv|cvc|iban|account number|expiry date|security code|one-?time password|mpin)\\b|" +
        // v0.6.1: the phrasings real apps actually use — "Enter the 6-digit code", "4 digit OTP", Roman-Urdu forms
        "\\b\\d{1,2}[- ]?digit\\s+(code|pin|otp|password)\\b|\\b(verification|one[- ]?time|security|tasdeeqi?)\\s+code\\b|" +
        "(code|otp|pin)\\s+(daalo|dalo|likho|bharo|darj\\s+karo)\\b|(daalo|likho|bharo)\\s+(code|otp|pin)\\b", RegexOption.IGNORE_CASE)

    /** v0.6.1: a field whose whole label is just the secret's name ("OTP", "Code", "Verification code") is a secret field, sentence or not. */
    private val bareSecretLabels = setOf("otp", "code", "pin", "passcode", "verification code", "one-time code", "one time code", "otp code", "enter otp", "enter code", "enter pin", "security code", "tasdeeqi code")
    private fun isBareSecretLabel(label: String) = label.trim().lowercase().trimEnd(':', '*', '.') in bareSecretLabels

    /** v0.6.1: a number-verification screen ("Verify your phone number", "tasdeeq ke liye code") is sensitive even before any field is focused. */
    private val verifyScreen = Regex("\\b(verify|verification|tasdeeq)\\b.{0,40}\\b(number|phone|code|otp)\\b", RegexOption.IGNORE_CASE)

    /** null = fine to read/act; otherwise a short reason. */
    fun sensitiveScreen(pkg: String?, nodes: List<Node>): String? {
        if (isDeniedPackage(pkg)) return "this is a banking / payment / wallet / security app"
        nodes.firstOrNull { it.password }?.let { return "a password or PIN field is on screen" }
        for (n in nodes) {
            val low = n.viewId.lowercase()
            if (urlBarIds.any { low.endsWith(it) || low.contains(it) }) {
                if (n.label.isNotBlank() && isDeniedHost(n.label)) return "the browser is on a banking / payment website"
            }
        }
        nodes.firstOrNull { sensitiveText.containsMatchIn(it.label) || isBareSecretLabel(it.label) }?.let { return "the screen asks for a PIN, password, OTP or card details" }
        nodes.firstOrNull { verifyScreen.containsMatchIn(it.label) && nodes.any { e -> e.editable } }?.let { return "the screen is verifying a number or account with a code" }
        return null
    }

    private val risky = Regex("^(send|delete|remove|pay|pay now|buy|buy now|purchase|place order|order|confirm|confirm order|submit|post|publish|tweet|share|transfer|donate|subscribe|uninstall|factory reset|erase|format|sign out|log out|logout|clear all|clear data|cancel subscription|unlock)\\b", RegexOption.IGNORE_CASE)
    private val riskyAnywhere = Regex("\\b(delete|erase|factory reset|uninstall|place order|buy now|pay now|transfer|purchase)\\b", RegexOption.IGNORE_CASE)
    fun looksIrreversible(label: String?): Boolean = !label.isNullOrBlank() && label.trim().let { risky.containsMatchIn(it) || riskyAnywhere.containsMatchIn(it) }

    /**
     * v0.6.1: an UNLABELLED tappable icon sitting in the same row as a text field, to its right, is treated as that field's send button
     * (the WhatsApp/Telegram compose pattern) — it has no label for [looksIrreversible] to catch, so without this it would slip through.
     * Needs real bounds; unknown bounds (0) never match.
     */
    fun looksLikeSend(node: Node, nodes: List<Node>): Boolean {
        if (!node.clickable || node.label.isNotBlank() || !node.hasBounds) return false
        val cy = (node.t + node.b) / 2
        return nodes.any { e ->
            e.editable && e.hasBounds && node.l >= e.l && cy >= e.t - (e.b - e.t) / 2 && cy <= e.b + (e.b - e.t) / 2
        }
    }
}
