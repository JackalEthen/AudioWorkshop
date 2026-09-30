package cn.qishui.tool.media.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioFxTest {

    private fun sine(rate: Int, hz: Float, seconds: Float): PcmBuffer {
        val frames = (rate * seconds).toInt()
        val out = ShortArray(frames)
        for (frame in 0 until frames) {
            out[frame] = (Math.sin(2.0 * Math.PI * hz * frame / rate) * 20_000).toInt().toShort()
        }
        return PcmBuffer(rate, 1, out)
    }

    @Test
    fun `narrower band drops more of a high frequency tone`() {
        val tone = sine(48_000, 1_000f, 0.5f)

        val wide = PcmEffects.radioFx(PcmBuffer(48_000, 1, tone.samples.copyOf()), 0.45f, 300f, 3_400f)
        val narrow = PcmEffects.radioFx(PcmBuffer(48_000, 1, tone.samples.copyOf()), 0.45f, 500f, 2_400f)

        fun energy(buffer: PcmBuffer): Long =
            buffer.samples.sumOf { (it.toLong() * it.toLong()) }

        assertTrue("更窄的频响应该留下更少能量", energy(narrow) < energy(wide))
    }

    @Test
    fun `band pass keeps the middle frequency and kills the extremes`() {
        fun energyAt(hz: Float): Long {
            val tone = sine(48_000, hz, 0.5f)
            val out = PcmEffects.radioFx(tone, 0.3f, 300f, 3_400f)
            return out.samples.sumOf { (it.toLong() * it.toLong()) }
        }

        val inside = energyAt(1_000f)
        val outside = energyAt(12_000f)

        assertTrue("带内应该比带外响", inside > outside)
    }

    @Test
    fun `band pass does not diverge and pin the output at full scale`() {
        // 两级滤波器共用一个 State 时会发散，tanh 会把它压成满幅方波而不是炸掉，
        // 所以「不削波」这种断言抓不住，必须直接查有没有长段钉在满幅的采样
        val frames = 48_000
        val loud = PcmBuffer(48_000, 1, ShortArray(frames) { if ((it / 100) % 2 == 0) 20_000 else -20_000 })

        val out = PcmEffects.radioFx(loud, 0.5f, 300f, 3_400f)

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
    fun `band pass changes the signal`() {
        val tone = sine(48_000, 440f, 0.2f)
        val out = PcmEffects.radioFx(PcmBuffer(48_000, 1, tone.samples.copyOf()), 0.45f, 300f, 3_400f)

        assertTrue("处理后应该和原信号不同", !out.samples.contentEquals(tone.samples))
    }

    @Test
    fun `output never clips`() {
        val loud = PcmBuffer(48_000, 1, ShortArray(4_800) { 30_000 })

        val out = PcmEffects.radioFx(loud, 1f, 300f, 3_400f)

        assertTrue(out.samples.all { it >= PcmBuffer.SHORT_MIN && it <= PcmBuffer.SHORT_MAX })
    }

    @Test
    fun `noise floor is deterministic across runs`() {
        val tone = sine(48_000, 1_000f, 0.2f)

        val first = PcmEffects.radioFx(PcmBuffer(48_000, 1, tone.samples.copyOf()), 0.6f, 420f, 2_800f, 0.012f)
        val second = PcmEffects.radioFx(PcmBuffer(48_000, 1, tone.samples.copyOf()), 0.6f, 420f, 2_800f, 0.012f)

        assertTrue("同一次导出两次结果必须一致", first.samples.contentEquals(second.samples))
    }

    @Test
    fun `noise floor raises the quiet parts`() {
        val quiet = PcmBuffer(48_000, 1, ShortArray(4_800))

        val withNoise = PcmEffects.radioFx(quiet, 0.6f, 420f, 2_800f, 0.03f)
        val withoutNoise = PcmEffects.radioFx(
            PcmBuffer(48_000, 1, ShortArray(4_800)), 0.6f, 420f, 2_800f, 0f,
        )

        assertTrue(withNoise.samples.any { it != 0.toShort() })
        assertTrue(withoutNoise.samples.all { it == 0.toShort() })
    }

    @Test
    fun `stereo channels get independent filter states`() {
        val frames = 4_800
        val left = sine(48_000, 1_000f, 0.1f)
        val samples = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            samples[frame * 2] = left.samples[frame]
        }
        val out = PcmEffects.radioFx(PcmBuffer(48_000, 2, samples), 0.5f)

        assertEquals(2, out.channels)
        // 右声道全程静音，滤波后仍是静音，说明没有串到左边的状态
        for (frame in 0 until out.frames) {
            assertEquals(0.toShort(), out.samples[frame * 2 + 1])
        }
    }
}
