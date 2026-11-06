package com.mazharansari.axion.ui

import android.os.Build

/**
 * True only inside the Robolectric screen-test runtime. Continuous frame loops (the orb, the
 * thinking ribbon, shimmer/pulse) keep recomposing forever there, so the Compose test rule never
 * reaches idle and screen tests die with AppNotIdle. The codebase already keys test-only behaviour
 * off this fingerprint (auth, updater); animations follow the same rule. On a real phone the
 * fingerprint is never "robolectric", so nothing changes for the user.
 */
object AnimationGate {
    val inTestRuntime: Boolean = Build.FINGERPRINT == "robolectric"
}
