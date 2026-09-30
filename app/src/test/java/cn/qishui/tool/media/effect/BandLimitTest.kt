package cn.qishui.tool.media.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandLimitTest {

    /** 只统计后半段，避开滤波器起振的瞬态。 */
    private fun sine(rate: Int, hz: Float, seconds: Float): PcmBuffer {
        val frames = (rate * seconds).toInt()
        val out = ShortArray(frames)
        for (frame in 0 until frames) {
            out[frame] = (Math.sin(2.0 * Math.PI * hz * frame / rate) * 20_000).toInt().toShort()
        }
        return PcmBuffer(rate, 1, out)
    }

    private fun rms(buffer: PcmBuffer): Double {
        var sum = 0.0
        var count = 0
        for (index in buffer.samples.size / 2 until buffer.samples.size) {
            val v = buffer.samples[index].toDouble()
            sum += v * v
            count++
        }
        return Math.sqrt(sum / count)
    }

    @Test
    fun `in band tone stays far louder than out of band tones`() {
        fun pass(hz: Float): Double = rms(
            PcmEffects.bandLimit(sine(48_000, hz, 1.0f), 200f, 3_000f, 1f),
        )

        val inside = pass(1_000f)
        val below = pass(50f)
        val above = pass(12_000f)

        assertTrue("50Hz 应该被高频门砍掉：inside=$inside below=$below", inside > below * 8)
        assertTrue("12kHz 应该被低频门砍掉：inside=$inside above=$above", inside > above * 8)
    }

    @Test
    fun `a wider band keeps more than a narrow one`() {
        val tone = sine(48_000, 6_000f, 1.0f)

        val narrow = PcmEffects.bandLimit(PcmBuffer(48_000, 1, tone.samples.copyOf()), 200f, 3_000f, 1f)
        val wide = PcmEffects.bandLimit(PcmBuffer(48_000, 1, tone.samples.copyOf()), 80f, 8_000f, 1f)

        assertTrue("宽频应该留下更多：narrow=${rms(narrow)} wide=${rms(wide)}", rms(wide) > rms(narrow))
    }

    @Test
    fun `stronger setting removes more than the gentle one`() {
        val tone = sine(48_000, 12_000f, 1.0f)

        val gentle = PcmEffects.bandLimit(PcmBuffer(48_000, 1, tone.samples.copyOf()), 200f, 3_000f, 0f)
        val strong = PcmEffects.bandLimit(PcmBuffer(48_000, 1, tone.samples.copyOf()), 200f, 3_000f, 1f)

        assertTrue("正常档应该比稳定档滤得更干净", rms(strong) < rms(gentle))
    }

    @Test
    fun `corner overshoot is bounded instead of diverging`() {
        // 共用 State 会让级联发散，输出被钉死在满幅。这里用满幅方波最容易暴露
        val frames = 48_000
        val square = ShortArray(frames) { if ((it / 200) % 2 == 0) 30_000 else -30_000 }

        val out = PcmEffects.bandLimit(PcmBuffer(48_000, 1, square), 200f, 3_000f, 1f)

        // 发散时会出现成千上万个连续钉在同一条轨上的采样
        var run = 0
        var longest = 0
        for (index in 1 until out.samples.size) {
            val atRail = Math.abs(out.samples[index].toInt()) >= 32_000
            run = if (atRail && out.samples[index] == out.samples[index - 1]) run + 1 else 0
            longest = maxOf(longest, run)
        }
        assertTrue("不该出现长段钉在满幅的输出：最长连续 $longest", longest < 1_000)
    }

    @Test
    fun `crossed corners are clamped instead of producing an empty band`() {
        // 结束频率给得比起始还低，Biquad 会算出负的 omega 直接崩，所以必须夹住
        val tone = sine(48_000, 1_000f, 0.5f)

        val out = PcmEffects.bandLimit(tone, 1_000f, 1_000f, 1f)

        assertTrue(out.samples.any { it != 0.toShort() })
    }

    @Test
    fun `corners above nyquist are clamped`() {
        val tone = sine(48_000, 1_000f, 0.5f)

        val out = PcmEffects.bandLimit(tone, 19_000f, 19_500f, 1f)

        assertTrue(out.samples.all { it >= PcmBuffer.SHORT_MIN && it <= PcmBuffer.SHORT_MAX })
    }

    @Test
    fun `length is never changed`() {
        val tone = sine(48_000, 1_000f, 0.3f)

        val out = PcmEffects.bandLimit(tone, 200f, 3_000f, 1f)

        assertEquals(tone.frames, out.frames)
    }
}
