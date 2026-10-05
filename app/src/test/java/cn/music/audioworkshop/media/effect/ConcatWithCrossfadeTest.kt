package cn.music.audioworkshop.media.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcatWithCrossfadeTest {

    @Test
    fun `crossfade zero simply concatenates`() {
        val a = PcmBuffer(1_000, 1, shortArrayOf(1, 2))
        val b = PcmBuffer(1_000, 1, shortArrayOf(3, 4, 5))

        val out = PcmEffects.concatWithCrossfade(listOf(a, b), crossfadeMs = 0f, normalizeRates = false)

        assertEquals(5, out.frames)
        assertEquals(1.toShort(), out.samples[0])
        assertEquals(2.toShort(), out.samples[1])
        assertEquals(3.toShort(), out.samples[2])
        assertEquals(5.toShort(), out.samples[4])
    }

    @Test
    fun `overlap shortens the result by the crossfade length`() {
        val a = PcmBuffer(1_000, 1, ShortArray(10) { 1_000 })
        val b = PcmBuffer(1_000, 1, ShortArray(10) { 2_000 })

        val out = PcmEffects.concatWithCrossfade(listOf(a, b), crossfadeMs = 4f, normalizeRates = false)

        // 10 + 10 - 4 = 16 帧
        assertEquals(16, out.frames)
    }

    @Test
    fun `crossfade midpoint averages the two tracks`() {
        val a = PcmBuffer(1_000, 1, ShortArray(10) { 1_000 })
        val b = PcmBuffer(1_000, 1, ShortArray(10) { 3_000 })

        val out = PcmEffects.concatWithCrossfade(listOf(a, b), crossfadeMs = 4f, normalizeRates = false)

        // 交叠区在第 6..9 帧；第 8 帧 t = 0.5，(1000 + 3000) / 2 = 2000
        assertEquals(2_000.toShort(), out.samples[8])
    }

    @Test
    fun `stereo keeps channel order through the join`() {
        val a = PcmBuffer(1_000, 2, shortArrayOf(10, -10, 10, -10))
        val b = PcmBuffer(1_000, 2, shortArrayOf(30, -30, 30, -30))

        val out = PcmEffects.concatWithCrossfade(listOf(a, b), crossfadeMs = 0f, normalizeRates = false)

        assertEquals(2, out.channels)
        assertEquals(4, out.frames)
        assertEquals(30.toShort(), out.samples[4])
        assertEquals((-30).toShort(), out.samples[5])
    }

    @Test
    fun `normalizeRates lifts every track to the highest rate`() {
        val slow = PcmBuffer(1_000, 1, ShortArray(10) { 1_000 })
        val fast = PcmBuffer(2_000, 1, ShortArray(20) { 2_000 })

        val unified = PcmEffects.concatWithCrossfade(listOf(slow, fast), 0f, normalizeRates = true)

        assertEquals(2_000, unified.sampleRateHz)
        // 1kHz 的 10 帧拉到 2kHz 变成 20 帧，再拼上原来的 20 帧
        assertEquals(40, unified.frames)
    }

    @Test
    fun `without normalize the first track rate wins`() {
        val slow = PcmBuffer(1_000, 1, ShortArray(10) { 1_000 })
        val fast = PcmBuffer(2_000, 1, ShortArray(20) { 2_000 })

        val out = PcmEffects.concatWithCrossfade(listOf(slow, fast), 0f, normalizeRates = false)

        assertEquals(1_000, out.sampleRateHz)
    }

    @Test
    fun `crossfade longer than the shortest track degenerates to the longest one`() {
        val tiny = PcmBuffer(1_000, 1, shortArrayOf(5, 5))
        val long = PcmBuffer(1_000, 1, ShortArray(10) { 7 })

        val out = PcmEffects.concatWithCrossfade(listOf(tiny, long), crossfadeMs = 9_000f, normalizeRates = false)

        assertTrue("不能比最长的轨道还长", out.frames <= 10)
        assertTrue(out.frames > 0)
    }

    @Test
    fun `three tracks chain two crossfades`() {
        val a = PcmBuffer(1_000, 1, ShortArray(10) { 100 })
        val b = PcmBuffer(1_000, 1, ShortArray(10) { 200 })
        val c = PcmBuffer(1_000, 1, ShortArray(10) { 300 })

        val out = PcmEffects.concatWithCrossfade(listOf(a, b, c), crossfadeMs = 0f, normalizeRates = false)

        assertEquals(30, out.frames)
        assertEquals(300.toShort(), out.samples[29])
    }
}
