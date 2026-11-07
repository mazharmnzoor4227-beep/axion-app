package com.mazharansari.axion.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v0.8.4: the buttons on the system phone-control notification (see [PhoneControlNotify]).
 * STOP kills the run token first, exactly like the old on-screen strip; CONFIRM is the user's
 * own one-shot press for one waiting irreversible action — the same [PhoneGate] rules as always.
 */
class PhoneActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP -> PhoneController.stop("You pressed STOP")
            ACTION_CONFIRM -> PhoneController.confirmPending()
        }
    }

    companion object {
        const val ACTION_STOP = "com.mazharansari.axion.phone.STOP"
        const val ACTION_CONFIRM = "com.mazharansari.axion.phone.CONFIRM"
    }
}
