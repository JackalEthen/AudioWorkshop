package cn.music.audioworkshop.media.pcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmFormatRulesTest {

    @Test
    fun acceptsFortyFourOneAndFortyEightKiloHertz() {
        listOf(44_100, 48_000).forEach { sampleRate ->
            listOf(1, 2).forEach { channels ->
                val format = PcmFormatRules.requireSupported(sampleRate, channels, "audio/mp4a-latm")

                assertEquals(sampleRate, format.sampleRateHz)
                assertEquals(channels, format.channels)
            }
        }
    }

    @Test
    fun rejectsUnsupportedSampleRates() {
        listOf(0, 22_050, 32_000, 96_000).forEach { sampleRate ->
            assertThrows(UnsupportedPcmFormatException::class.java) {
                PcmFormatRules.requireSupported(sampleRate, 2, "audio/mpeg")
            }
        }
    }

    @Test
    fun rejectsUnsupportedChannelCounts() {
        listOf(0, 3, 6).forEach { channels ->
            assertThrows(UnsupportedPcmFormatException::class.java) {
                PcmFormatRules.requireSupported(44_100, channels, "audio/flac")
            }
        }
    }

    @Test
    fun sampleRateFailureIsReportedBeforeChannels() {
        val error = assertThrows(UnsupportedPcmFormatException::class.java) {
            PcmFormatRules.requireSupported(22_050, 6, "audio/ogg")
        }

        assertTrue(error.message!!.contains("22050"))
        assertTrue(error.message!!.contains("audio/ogg"))
    }

    @Test
    fun isSupportedMirrorsRequireSupported() {
        assertTrue(PcmFormatRules.isSupported(48_000, 1))
        assertTrue(PcmFormatRules.isSupported(44_100, 2))
        assertFalse(PcmFormatRules.isSupported(44_100, 3))
        assertFalse(PcmFormatRules.isSupported(8_000, 1))
    }
}
