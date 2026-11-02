package com.mazharansari.axion

import com.mazharansari.axion.voice.EdgeTts
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tokens computed by the Python edge-tts package (drm.generate_sec_ms_gec_v2) for the same timestamps. */
class EdgeTokenTest {
    @Test fun matchesPythonReference() {
        assertEquals("E24FC7300983DF16-F024DC59F0D22A361B69B861A1BD1BEB35D2DC93BDC4A8314FE5FB4EBBB3F8CB", EdgeTts.secMsGec(1793500000.0))
        assertEquals("99E54519B490BE5C-E162DB0977FBDCB9B86DD8148BF1BCDC3C0303176CE39852A42B415935C20654", EdgeTts.secMsGec(1800000123.5))
        assertEquals("2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2", EdgeTts.secMsGec(1700000000.0))
    }
}
