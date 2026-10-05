package cn.music.audioworkshop.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GainScaleTest {

    @Test
    fun zeroDecibelsIsOneHundredPercent() {
        assertEquals(100f, GainScale.PERCENT.toDisplay(0f), 0.001f)
    }

    @Test
    fun sixDecibelsRoughlyDoublesThePercent() {
        // 10^(6/20) = 1.9953 -> 199.5%
        assertEquals(199.5f, GainScale.PERCENT.toDisplay(6f), 0.1f)
    }

    @Test
    fun displayValueRoundTripsBackToDecibels() {
        listOf(0f, 3f, -6f, 12f).forEach { db ->
            val display = GainScale.PERCENT.toDisplay(db)
            assertEquals(db, GainScale.PERCENT.toGainDb(display), 0.001f)
        }
    }

    @Test
    fun percentAtOrBelowZeroKeepsThePreviousGainInsteadOfBlowingUp() {
        // log10(0) 是 -Inf，必须挡住
        assertEquals(4.5f, GainScale.PERCENT.toGainDb(0f, fallbackDb = 4.5f), 0.001f)
        assertTrue(GainScale.PERCENT.toGainDb(0f, 4.5f).isFinite())
    }

    @Test
    fun decibelsPassThroughUnchanged() {
        assertEquals(2.5f, GainScale.DECIBEL.toDisplay(2.5f), 0.0001f)
        assertEquals(2.5f, GainScale.DECIBEL.toGainDb(2.5f), 0.0001f)
        assertEquals("dB", GainScale.DECIBEL.unitLabel())
        assertEquals("%", GainScale.PERCENT.unitLabel())
    }
}
