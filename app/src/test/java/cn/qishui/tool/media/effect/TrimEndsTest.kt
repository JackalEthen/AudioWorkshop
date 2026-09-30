package cn.qishui.tool.media.effect

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimEndsTest {

    private fun stereo(vararg frames: Int): PcmBuffer =
        PcmBuffer(48_000, 2, ShortArray(frames.size * 2) { frames[it / 2].toShort() })

    @Test
    fun `stereo frames are trimmed by frame not by sample`() {
        val buffer = stereo(*IntArray(12) { it })

        val out = PcmEffects.trimEnds(buffer, headMs = 1000f, tailMs = 1000f)

        // 1000ms @48k 远超 12 帧，整段被砍光
        assertEquals(0, out.frames)
    }

    @Test
    fun `drops requested head and tail frames`() {
        val buffer = PcmBuffer(1_000, 1, ShortArray(20) { (it + 1).toShort() })

        val out = PcmEffects.trimEnds(buffer, headMs = 3f, tailMs = 2f)

        // 砍掉 0,1,2 和 18,19，留下 3..17
        assertEquals(15, out.frames)
        assertEquals(4.toShort(), out.samples[0])
        assertEquals(18.toShort(), out.samples[14])
    }

    @Test
    fun `head only keeps the tail`() {
        val buffer = PcmBuffer(1_000, 1, ShortArray(10) { it.toShort() })

        val out = PcmEffects.trimEnds(buffer, headMs = 2000f, tailMs = 0f)

        assertEquals(0, out.frames)
    }

    @Test
    fun `milliseconds are converted with the buffer sample rate`() {
        // 1000Hz：1ms = 1 帧
        val buffer = PcmBuffer(1_000, 1, ShortArray(10) { (it + 1).toShort() })

        val out = PcmEffects.trimEnds(buffer, headMs = 3f, tailMs = 2f)

        assertEquals(5, out.frames)
        assertEquals(4.toShort(), out.samples[0])
        assertEquals(8.toShort(), out.samples[4])
    }

    @Test
    fun `zero on both sides returns the same buffer`() {
        val buffer = PcmBuffer(48_000, 2, shortArrayOf(1, 2, 3, 4))

        assertEquals(buffer, PcmEffects.trimEnds(buffer, 0f, 0f))
    }

    @Test
    fun `tail longer than the audio collapses to silence instead of crashing`() {
        val buffer = PcmBuffer(1_000, 1, ShortArray(10) { it.toShort() })

        val out = PcmEffects.trimEnds(buffer, headMs = 4f, tailMs = 9_000f)

        assertEquals(0, out.frames)
    }
}
