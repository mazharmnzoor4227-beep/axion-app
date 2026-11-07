package com.mazharansari.axion

import com.mazharansari.axion.wakeword.WakePhraseMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase B: the wake phrase and its likely mishears wake; ordinary speech never does. */
class WakePhraseMatcherTest {
    @Test fun wakesOnThePhraseAndMishears() {
        for (t in listOf("Hey Axion", "hey axion", "HEY AXION!", "hey action", "hey exion", "hey aksion", "okay axion", "ok axion", "axion"))
            assertTrue("should wake: $t", WakePhraseMatcher.isWakePhrase(t))
    }

    @Test fun neverWakesOnOrdinarySpeech() {
        for (t in listOf("", "hello", "hey", "hey there", "an action movie", "take action now", "the axiom of choice", "okay let's go", "section five"))
            assertFalse("must not wake: $t", WakePhraseMatcher.isWakePhrase(t))
    }
}
