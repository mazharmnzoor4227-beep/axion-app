package com.mazharansari.axion.ui

sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data object Appearance : Route
    data object Language : Route
    data object Advanced : Route
    data object Customize : Route
    data object Connectors : Route
    data object CustomConnector : Route
    data object Skills : Route
    data object VoiceCall : Route
    data object Data : Route
    data object About : Route
    data object Files : Route
    data object DevTools : Route
    data object Antigravity : Route
    data object Quota : Route
    data object KeyBackup : Route
    // v0.5
    data object LocalModels : Route
    data object MyServer : Route
    data object ServerGuide : Route
    // v0.5.3
    data object Storage : Route
    // v0.6
    data object ToolsLibrary : Route
    // v0.9
    data object Browser : Route
    data object Terminal : Route
    // v0.10 — the learning loop
    data object Memory : Route
    // v0.12 — remembered approvals + scheduled tasks
    data object Approvals : Route
    data object Schedules : Route
    // P1-3 — the user's own server for Turbo tasks (opt-in cloud sandbox)
    data object Turbo : Route
    // P1-1 — in-app GitHub coding agent
    data object GitHub : Route
}

/** v0.5.2: routes shown with FLAG_SECURE (a key, token or password can be typed or shown on them). */
object SecureRoutes {
    val all: Set<Route> = setOf(Route.Connectors, Route.CustomConnector, Route.Skills, Route.KeyBackup, Route.MyServer, Route.Antigravity, Route.Turbo, Route.GitHub)
}
