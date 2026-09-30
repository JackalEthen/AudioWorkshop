package cn.qishui.tool.media.effect

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * 反转相位现在能只翻一个声道。
 * 整轨翻转是老行为，这两个用例是回归护栏。
 */
class InvertPhaseChannelTest {

    @Test
    fun channelZeroFlipsEveryChannel() {
        val buffer = PcmBuffer.of(shortArrayOf(100, 200, 300, 400), sampleRateHz = 44_100, channels = 2)

        PcmEffects.invertPhase(buffer, channel = 0)

        assertArrayEquals(shortArrayOf(-100, -200, -300, -400), buffer.samples)
    }

    @Test
    fun leftChannelOnlyLeavesTheRightChannelAlone() {
        val buffer = PcmBuffer.of(shortArrayOf(100, 200, 300, 400), sampleRateHz = 44_100, channels = 2)

        PcmEffects.invertPhase(buffer, channel = 1)

        assertArrayEquals(shortArrayOf(-100, 200, -300, 400), buffer.samples)
    }

    @Test
    fun rightChannelOnlyLeavesTheLeftChannelAlone() {
        val buffer = PcmBuffer.of(shortArrayOf(100, 200, 300, 400), sampleRateHz = 44_100, channels = 2)

        PcmEffects.invertPhase(buffer, channel = 2)

        assertArrayEquals(shortArrayOf(100, -200, 300, -400), buffer.samples)
    }

    @Test
    fun monoFallsBackToFlippingTheOnlyChannelWhateverWasPicked() {
        val buffer = PcmBuffer.of(shortArrayOf(100, 200), sampleRateHz = 44_100, channels = 1)

        PcmEffects.invertPhase(buffer, channel = 2)

        // 单声道没有左右之分，选了右声道也得翻，不然会静默什么都不做
        assertArrayEquals(shortArrayOf(-100, -200), buffer.samples)
    }
}
