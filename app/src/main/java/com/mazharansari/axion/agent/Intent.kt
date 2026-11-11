package com.mazharansari.axion.agent

/**
 * v0.3 (mazhar, 1 Nov 2026: "whatever I say, it starts doing everything"): decide BEFORE calling the model whether a
 * message is plain conversation, a lookup, or an explicit action. Plain conversation is sent with NO tools at all,
 * so the model cannot write files or run commands for "hello Kya Hal he" — and the request is ~10x smaller, which
 * also keeps Groq's per-minute token limit from tripping on a greeting.
 *
 *  CHAT    greetings, small talk, opinions, explanations, questions about Axion itself → no tools
 *  LOOKUP  needs fresh facts from the web (news, prices, weather, "search…", a bare link) → web tools only
 *  ACTION  the user explicitly asks Axion to make / run / build / fix / download / read a file → all tools
 */
enum class Intent { CHAT, LOOKUP, ACTION }

object IntentGate {
    // Explicit "do something" verbs — English, Roman Urdu / Hinglish, Urdu script.
    private val action = Regex(
        "\\b(create|make|build|generate|write|draft|code|program|develop|design|run|execute|install|download|convert|compress|zip|unzip|extract|" +
        "deploy|export|save|edit|fix|debug|refactor|translate this file|summari[sz]e (this|the|my) (file|pdf|doc)|audit|analy[sz]e (this|the|my)|" +
        "bana|banao|banado|bana do|bana dein|bana den|bana dijiye|bnao|bna do|bnado|likh do|likho|likhdo|likh dein|chala do|chalao|chala ke|" +
        "run karo|run kar do|install karo|download karo|download kar do|generate karo|create karo|fix karo|theek karo|thik karo|edit karo|convert karo|" +
        "open|tap|click|turn on|turn off|swipe|scroll|kholo|khol do|khol kar|on karo|off karo|band karo|trim|kaato|kato|kaat do|kaat dein|lagao|laga do|lagado|reframe)\\b",   // axion-p1.6: video requests
        RegexOption.IGNORE_CASE,
    )
    private val actionUrdu = Regex("(بنا دو|بناؤ|بنائیں|لکھ دو|لکھو|چلاؤ|چلا دو|ڈاؤن لوڈ|انسٹال)")
    private val artifact = Regex("\\b(file|files|pdf|html|page|website|site|web ?app|app|script|code|report|zip|apk|docx|excel|csv|image|logo|poster|resume|cv|presentation|slides|video|videos|reel|clip|mp4|captions?|subtitles?)\\b", RegexOption.IGNORE_CASE)
    private val lookup = Regex(
        "\\b(search|google|look ?up|find|latest|news|today|tonight|current|price|prices|rate|rates|weather|forecast|score|live|stock|bitcoin|dollar rate|" +
        "khabar|khabrein|qeemat|keemat|mausam|aaj|abhi ka|taza|dhoond|dhoondo|dhundo|talash|search karo|check karo)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val url = Regex("https?://\\S+|\\b[a-z0-9-]+\\.(com|pk|org|net|io|store|shop|app|dev|ai)\\b", RegexOption.IGNORE_CASE)
    // "can you run PowerShell commands?", "tum kya kar sakte ho?" — questions ABOUT Axion, not requests.
    private val capability = Regex(
        "^(can|could|do|does|are|will) (you|u|axion)\\b|\\b(what|which) (can|could) you\\b|\\b(tum|aap|ap) kya kar (sakte|sakti)|" +
        "\\bkya (tum|aap|ap) .* (sakte|sakti|chala sakte) ho\\b|\\bkya (tum|aap|ap) ke paas\\b",
        RegexOption.IGNORE_CASE,
    )
    private val forMe = Regex("\\b(for me|me a|me an|mujhe|mere liye|meri|mera ek|ye|yeh|this|isko|is ko)\\b", RegexOption.IGNORE_CASE)

    fun classify(text: String, hasAttachments: Boolean = false): Intent {
        val t = text.trim()
        if (hasAttachments) return Intent.ACTION
        if (t.isEmpty()) return Intent.CHAT
        val isQuestion = t.endsWith("?") || t.endsWith("؟")
        val wantsAction = action.containsMatchIn(t) || actionUrdu.containsMatchIn(t)
        // A capability question with no concrete object ("can you run PowerShell commands?") is answered, not executed.
        if (capability.containsMatchIn(t) && isQuestion && !forMe.containsMatchIn(t)) return Intent.CHAT
        if (wantsAction && (artifact.containsMatchIn(t) || forMe.containsMatchIn(t) || !isQuestion)) return Intent.ACTION
        if (lookup.containsMatchIn(t) || url.containsMatchIn(t)) return Intent.LOOKUP
        return Intent.CHAT
    }

    /**
     * v0.4 (mazhar 2:17 PM: "hi bhai" sat in Thinking): greetings and short small talk get an instant answer — reasoning
     * off, no thought block, non-reasoning models first. Anything longer or with a real question stays normal.
     */
    fun isSmallTalk(text: String, intent: Intent = classify(text)): Boolean {
        if (intent != Intent.CHAT) return false
        val t = text.trim()
        if (t.isEmpty()) return false
        if (isFollowUp(t)) return false   // v0.5.1: "dusra do" wants a full answer like the last one, not a one-line reply
        if (greeting.containsMatchIn(t) && t.length <= 80) return true
        val words = t.split(Regex("\\s+")).size
        return t.length <= 40 && words <= 6 && !t.contains('\n')
    }
    private val greeting = Regex(
        "^(hi+|hello+|hey+|helo|hlo|salam|salaam|assalam[ou]* ?(o|u)? ?alaikum|aoa|asalam|slam|kaise ho|kaisy ho|kese ho|kaisa hai|kya haal|kya hal|" +
        "theek ho|thik ho|good (morning|night|evening)|thanks|thank you|shukriya|ok|okay|acha|achha|bye|gn|gm|yo|sup|bhai|yaar)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * v0.5.1 (mazhar 3:36 PM: "dusra do" after an Urdu story): a short message that only makes sense with the previous
     * turn — "another", "more", "again", "continue". The model gets the conversation and an explicit note about it.
     */
    fun isFollowUp(text: String): Boolean {
        val t = text.trim().trimEnd('.', '!', '?', '؟', '۔').lowercase()
        if (t.isEmpty() || t.length > 40 || t.split(Regex("\\s+")).size > 6) return false
        return followUp.containsMatchIn(t)
    }
    private val followUp = Regex(
        "^(aur|or|ek aur|ik aur|aik aur|aur ek|aur do|aur sunao|aur batao|dusra|doosra|dusri|doosri|dusre|doosre|agla|agli|next|another|one more|" +
        "more|again|continue|go on|phir se|fir se|dobara|dubara|aage|aagay|age|jari rakho|jaari rakho|same|waisa hi|waisi hi|aisa hi|aisi hi|" +
        "اور|ایک اور|دوسرا|دوسری|اگلا|اگلی|پھر سے|دوبارہ|آگے)(\\b|\\s|$)",
        RegexOption.IGNORE_CASE,
    )

    /** Models that reason before answering (their first token comes late). */
    fun isReasoningModel(model: String): Boolean {
        val m = model.lowercase()
        return listOf("gpt-oss", "qwen3", "qwq", "deepseek-r1", "-r1", "reasoning", "thinking", "magistral", "o1", "o3", "o4-mini", "gpt-5", "kimi-k2-thinking", "glm-4.5", "grok-3-mini", "pro").any { it in m } &&
            !m.contains("instruct") && !m.contains("flash-lite")
    }

    /** Tool names each intent may see. */
    val lookupTools = setOf(
        "web_search", "browse_url", "http_request",
        // v0.9: the agent's own browser belongs in chat/lookup too — "browser kholo, research karke batao" must work here
        "browser_open", "browser_read", "browser_click", "browser_type", "browser_scroll",
        "browser_back", "browser_forward", "browser_reload", "browser_search", "browser_screenshot",
        // v0.10: memory is conversational — "ye yaad rakhna", "mera naam … hai", "last time hum ne kya decide kiya tha"
        // must work in plain chat, not only in ACTION turns. Skill writing stays ACTION-only (creating things).
        "memory_add", "memory_replace", "memory_remove", "memory_read", "session_search",
        // v0.11: deep research is a chat request too ("is par research karke batao") — not just an ACTION verb.
        "deep_research",
    )


    /**
     * v0.7.0 (owner: "ask ho ya build, jo bolun wo ho jaye"): what a conversation or lookup turn may use —
     * the web. The v0.3 gate kept every tool out of CHAT, so a plainly-worded request in chat
     * ("browser kholo, research karke batao") could never act; now the label only changes the prompt and the
     * effort, not the capability. File/command work still needs an ACTION turn, and small talk still gets
     * no tools at all (greetings stay instant and cheap).
     */
    val chatTools = lookupTools
}
