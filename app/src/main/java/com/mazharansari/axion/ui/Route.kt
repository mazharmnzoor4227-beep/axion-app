package com.mazharansari.axion.ui

sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data object Appearance : Route
    data object Haptics : Route
    data object Language : Route
    data object Advanced : Route
    data object Customize : Route
    data object Connectors : Route
    data object CustomConnector : Route
    data object Skills : Route
    data object Voice : Route
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
    // v0.5.2
    data object Privacy : Route
    data object Security : Route
    data object Terms : Route
    // v0.5.3
    data object Account : Route
    data object SignIn : Route
    data object Storage : Route
}

/** v0.5.2: routes shown with FLAG_SECURE (a key, token or password can be typed or shown on them). */
object SecureRoutes {
    val all: Set<Route> = setOf(Route.Connectors, Route.CustomConnector, Route.Skills, Route.KeyBackup, Route.MyServer, Route.Antigravity, Route.Voice, Route.SignIn)
}
