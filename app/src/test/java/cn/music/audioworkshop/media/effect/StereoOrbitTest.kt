package cn.music.audioworkshop.media.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 立体声环绕 = 绕听者持续旋转的立体声像。
 * 关键性质：整圈后必须回到原点、单声道原样返回、能量不被放大。
 */
class StereoOrbitTest {

    private val rate = 1_000

    private fun stereo(frames: Int, left: Short, right: Short) =
        ShortArray(frames * 2) { if (it % 2 == 0) left else right }

    @Test
    fun monoSourceIsLeftUntouched() {
        val mono = PcmBuffer.of(shortArrayOf(100, -100, 200), sampleRateHz = rate, channels = 1)

        val rendered = PcmEffects.stereoOrbit(mono, halfCircleSec = 1f, degrees = 9f)

        assertTrue(shortArrayOf(100, -100, 200).contentEquals(rendered.samples))
    }

    @Test
    fun zeroAmplitudeLeavesTheStereoImageWhereItIs() {
        val input = stereo(200, 1000, -1000)
        val buffer = PcmBuffer.of(input.copyOf(), sampleRateHz = rate, channels = 2)

        PcmEffects.stereoOrbit(buffer, halfCircleSec = 1f, degrees = 0f)

        assertTrue(input.contentEquals(buffer.samples))
    }

    @Test
    fun aFullCircleBringsTheImageBackToTheStart() {
        val input = stereo(2_001, 1000, 0)
        val buffer = PcmBuffer.of(input.copyOf(), sampleRateHz = rate, channels = 2)
        val start = input.copyOf()

        // 半圈 0.5 秒 => 整圈 1 秒 = 1000 帧
        PcmEffects.stereoOrbit(buffer, halfCircleSec = 0.5f, degrees = 9f)

        val afterOneCircle = buffer.samples.copyOfRange(1_000 * 2, 1_000 * 2 + 2)
        // 一圈后角度回到 0，图像应回到起点（浮点误差内）
        assertTrue(
            "一圈后应回到起点：${afterOneCircle.toList()} vs ${start.copyOfRange(0, 2).toList()}",
            kotlin.math.abs(afterOneCircle[0] - start[0]) <= 1,
        )
    }

    @Test
    fun theImageActuallySwingsSoItIsNotAnIdentityTransform() {
        val buffer = PcmBuffer.of(stereo(1_000, 1000, 0), sampleRateHz = rate, channels = 2)

        PcmEffects.stereoOrbit(buffer, halfCircleSec = 0.5f, degrees = 9f)

        val midwayLeft = buffer.samples[500 * 2].toInt()
        assertTrue("半圈处左右应该串过去，实际 left=$midwayLeft", midwayLeft > 0)
    }

    @Test
    fun rotationPreservesTotalEnergyEvenThoughASingleChannelCanGrow() {
        // [[cos, sin], [-sin, cos]] 的行列式是 cos²+sin²=1，总能量守恒，
        // 但单个声道分量会变大 —— 硬声像信号在大角度下必然削顶，这是算法的固有性质。
        val buffer = PcmBuffer.of(stereo(4_000, 30_000, -30_000), sampleRateHz = rate, channels = 2)
        val before = energy(buffer.samples)

        PcmEffects.stereoOrbit(buffer, halfCircleSec = 1f, degrees = 9f)

        val after = energy(buffer.samples)
        val drift = kotlin.math.abs(after - before) / before
        // 削顶会少一点能量，但不能放大
        assertTrue("旋转不该放大能量：$before -> $after", after <= before * 1.02f)
        assertTrue("能量损失应很小（只可能来自削顶），实际漂移 $drift", drift < 0.25f)
    }

    @Test
    fun outputStaysInsideTheShortRange() {
        val buffer = PcmBuffer.of(stereo(4_000, 32_000, -32_000), sampleRateHz = rate, channels = 2)

        PcmEffects.stereoOrbit(buffer, halfCircleSec = 1f, degrees = 9f)

        assertTrue(buffer.samples.all { it.toInt() in -32768..32767 })
    }

    private fun energy(samples: ShortArray): Long =
        samples.sumOf { (it.toLong() * it.toLong()) }
}
