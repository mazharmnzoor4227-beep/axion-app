package com.mazharansari.axion.phone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v0.6 (Phase 3.2) glue between the chat turn, the run token, the overlay and the Accessibility service.
 * ChatViewModel: [beginRun] when a turn starts (only if the user gave consent and switched Phone control on), [endRun] when it ends, [stop] on Stop.
 */
object PhoneController {
    val gate = PhoneGate()
    val actions: PhoneActions by lazy { PhoneActions(ServiceBackend, gate) }
    @Volatile private var overlay: PhoneOverlay? = null
    @Volatile var onStopRequested: (() -> Unit)? = null
    private val _serviceUp = MutableStateFlow(false)
    val serviceUp: StateFlow<Boolean> get() = _serviceUp
    val active get() = gate.isActive

    init { gate.onChange = { overlay?.update(gate.recent(3), gate.pendingConfirm?.label) } }

    /** The CONFIRM button on the control strip — the user's own press for one waiting irreversible tap. */
    fun confirmPending() = gate.confirmPending()

    fun attach(svc: AxionAccessibilityService, o: PhoneOverlay) { overlay = o; _serviceUp.value = true }
    fun detach() { overlay?.hide(); overlay = null; _serviceUp.value = false }
    val available get() = AxionAccessibilityService.connected

    /** false = could not start (service off) — the agent then has no working phone tools and says so. */
    fun beginRun(): Boolean {
        if (!available) return false
        gate.begin(); overlay?.show(); gate.log("Starting — tap STOP any time"); return true
    }
    fun endRun() { gate.end(); overlay?.hide() }

    /** STOP button / Stop in chat / safety trip: the token dies first (next tool call refuses), then the overlay goes, then the turn is cancelled. */
    fun stop(reason: String) {
        val was = gate.isActive
        gate.stop(reason); overlay?.hide()
        if (was) onStopRequested?.invoke()
    }
}
