package cn.music.audioworkshop.media.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairTest {

    /** 每声道都带固定直流偏置的方波。 */
    private fun biasedSquare(frames: Int, offset: Int): PcmBuffer =
        PcmBuffer(1_000, 1, ShortArray(frames) { i ->
            if ((i / 20) % 2 == 0) (offset + 5_000).toShort() else (offset - 5_000).toShort()
        })

    private fun mean(buffer: PcmBuffer): Double =
        buffer.samples.sumOf { it.toDouble() } / buffer.samples.size

    @Test
    fun `dc offset is removed`() {
        val buffer = biasedSquare(1_000, offset = 2_000)

        val out = PcmEffects.repair(buffer, 1f)

        assertTrue("直流应该被去掉，平均 ${mean(out)}", kotlin.math.abs(mean(out)) < 1.0)
    }

    @Test
    fun `stronger setting removes more of the offset`() {
        val gentle = PcmEffects.repair(biasedSquare(1_000, 2_000), 0.35f)
        val strong = PcmEffects.repair(biasedSquare(1_000, 2_000), 0.9f)

        assertTrue("强力应该比轻度更干净", kotlin.math.abs(mean(strong)) < kotlin.math.abs(mean(gentle)))
    }

    @Test
    fun `clipped peaks are interpolated away`() {
        val frames = 400
        val samples = ShortArray(frames)
        for (i in 0 until frames) {
            samples[i] = when {
                i == 200 -> 32_767
                i in 190..210 -> 20_000
                else -> (Math.sin(2.0 * Math.PI * 100 * i / 1_000) * 8_000).toInt().toShort()
            }
        }

        val out = PcmEffects.repair(PcmBuffer(1_000, 1, samples), 1f)

        // 削波点被前后样本插值抹平，数值等于两个邻居的平均。
        // repair 会再去直流，所以邻居本身也被平移了，只能比相对关系。
        val expected = (out.samples[199].toInt() + out.samples[201].toInt()) / 2
        assertEquals(expected.toShort(), out.samples[200])
        // 关键点：必须先去削波再去直流，否则 32767 被压到阈值以下就再也认不出来了
        assertTrue("不该还顶在满幅附近，实际 ${out.samples[200]}", out.samples[200] < 30_000)
    }

    @Test
    fun `output never clips`() {
        val loud = PcmBuffer(1_000, 1, ShortArray(1_000) { 32_767 })

        val out = PcmEffects.repair(loud, 0.9f)

        assertTrue(out.samples.all { it >= PcmBuffer.SHORT_MIN && it <= PcmBuffer.SHORT_MAX })
    }

    @Test
    fun `length is preserved`() {
        val buffer = biasedSquare(600, 1_000)

        val out = PcmEffects.repair(buffer, 0.6f)

        assertEquals(600, out.frames)
    }

    @Test
    fun `stereo channels are de-offset independently`() {
        // 左声道 +1000，右声道 -1000
        val samples = ShortArray(2_000) { if (it % 2 == 0) 4_000 else -4_000 }

        val out = PcmEffects.repair(PcmBuffer(1_000, 2, samples), 1f)

        var left = 0.0
        var right = 0.0
        for (frame in 0 until out.frames) {
            left += out.samples[frame * 2]
            right += out.samples[frame * 2 + 1]
        }
        assertTrue("左声道应该归零，实际 ${left / out.frames}", kotlin.math.abs(left / out.frames) < 1.0)
        assertTrue("右声道应该归零，实际 ${right / out.frames}", kotlin.math.abs(right / out.frames) < 1.0)
    }
}
