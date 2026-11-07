package com.mazharansari.axion.phone

import com.mazharansari.axion.agent.*
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.serialization.json.JsonObject

/** v0.6 (Phase 3.2) the agent's phone tools. All of them refuse unless the run token is active (see [PhoneGate]); every refusal and step is logged to the overlay. */
object PhoneTools {
    const val NAMES = "phone_screen phone_open_app phone_tap phone_type phone_scroll phone_back phone_home phone_wait"
    const val PROMPT = "PHONE CONTROL (the user switched it on and is watching a STOP button): drive the phone one small step at a time. " +
        "Start with phone_open_app (or phone_screen), then ALWAYS call phone_screen to read the numbered elements before tapping/typing, and again after each action. " +
        "Act with phone_tap(id) / phone_type(id, text, submit) / phone_scroll / phone_back / phone_home. Element numbers change after every action. " +
        "To search in a browser: open the browser, phone_screen, tap the address/search bar, phone_type the query with submit=true, phone_screen to confirm, then summarise what you see. " +
        "NEVER operate banking, payment, wallet or password apps and never read or type passwords, PINs, OTPs or card numbers — if blocked, tell the user to do that step themselves. " +
        "Anything irreversible (sending a message, deleting, buying, posting) pauses on its own: the user personally presses CONFIRM on the control strip, or it does not happen — you cannot confirm it, and if the user does not confirm, do not look for another way; report that it was not done. If a tool says Stopped, stop and report where you got to. Keep going until the task is done, then summarise. " +
        "MESSAGING APPS (WhatsApp, Telegram): the message box is the text field at the BOTTOM of the chat (labelled \"Message\", id \"entry\"); type there, then the send icon appears beside it. BEFORE tapping send, tell the user a CONFIRM button is about to appear (notification / control strip) and that they must press it, or the message will not go. A padlock icon or an \"end-to-end encrypted\" notice inside WhatsApp is NORMAL and does NOT mean the chat is locked — never tell the user a chat or app is locked because of an icon; a truly locked app demands a fingerprint or PIN the moment it opens, and that you must never touch. " +
        "YOUTUBE ADS: if an ad is playing, tap the video area once to wake its controls, phone_wait for the skip countdown to run out, then tap the element labelled \"Skip Ad\" / \"Skip Ads\" / \"Skip\" (id \"skip_ad_button\"). If no skip element is listed yet, phone_wait 5 and phone_screen again. Some ads cannot be skipped at all — say so honestly instead of tapping random elements."

    fun all(actions: () -> PhoneActions = { PhoneController.actions }): List<Tool> = listOf(
        object : PhoneTool("phone_screen", "Read the current phone screen as numbered elements (app name, buttons, fields, text). Call before every tap or type.", schema { }) {
            override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Reading the screen", "")
            override suspend fun run(args: JsonObject, ctx: ToolContext) = actions().screen()
        },
        object : PhoneTool("phone_open_app", "Open an installed app by name (e.g. Chrome, Settings, YouTube; 'browser' = the default browser). Banking/payment apps are refused.", schema { str("name", "App name") }) {
            override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Opening app", args.s("name").orEmpty())
            override suspend fun run(args: JsonObject, ctx: ToolContext) = actions().openApp(args.s("name").orEmpty())
        },
        object : PhoneTool("phone_tap", "Tap element [id] from the latest phone_screen. Irreversible buttons (Send/Delete/Pay/Post…) pause and show the user a CONFIRM button on the control strip; the tap completes only if the user presses it. You cannot confirm it yourself.",
            schema { int("id", "Element number", required = true) }) {
            override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Tapping", "#" + (args.i("id") ?: 0))
            override suspend fun run(args: JsonObject, ctx: ToolContext) = actions().tap(args.i("id") ?: -1)
        },
        object : PhoneTool("phone_type", "Type text into text field [id] (replaces its content). submit=true presses Enter/Search afterwards. Never for passwords/PINs.",
            schema { int("id", "Element number of a text field", required = true); str("text", "Text to type"); bool("submit", "Press Enter / Search after typing") }) {
            override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Typing", args.s("text").orEmpty().take(40))
            override suspend fun run(args: JsonObject, ctx: ToolContext) = actions().type(args.i("id") ?: -1, args.s("text").orEmpty(), args.b("submit") == true)
        },
        object : PhoneTool("phone_scroll", "Scroll the screen: down (see more below), up, left, right.", schema { str("direction", "up | down | left | right") }) {
            override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Scrolling", args.s("direction").orEmpty())
            override suspend fun run(args: JsonObject, ctx: ToolContext) = actions().scroll(args.s("direction").orEmpty())
        },
        object : PhoneTool("phone_back", "Press the Android Back button.", schema { }) {
            override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Back", "")
            override suspend fun run(args: JsonObject, ctx: ToolContext) = actions().back()
        },
        object : PhoneTool("phone_home", "Go to the Android home screen.", schema { }) {
            override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Home", "")
            override suspend fun run(args: JsonObject, ctx: ToolContext) = actions().home()
        },
        object : PhoneTool("phone_wait", "Wait 1-10 seconds for a page or app to load.", schema { int("seconds", "1-10") }) {
            override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Waiting", "${args.i("seconds") ?: 2}s")
            override suspend fun run(args: JsonObject, ctx: ToolContext) = actions().waitSec(args.i("seconds") ?: 2)
        },
    )
}

abstract class PhoneTool(name: String, desc: String, params: JsonObject) : Tool {
    override val spec = ToolSpec(name, desc, params)
}
