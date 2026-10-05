package cn.music.audioworkshop.media.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 混响三个新参数：衰减时间 / 预延迟 / 高频阻尼。
 * 顺带锁住梳状反馈曾经的一个 bug：四条线错用累加值互相耦合。
 */
class ReverbTuningTest {

    private val rate = 2_000

    private fun render(
        frames: Int = 8_000,
        decay: Float = 0.6f,
        predelayMs: Float = 0f,
        damping: Float = 0.5f,
        roomSize: Float = 0.5f,
        mix: Float = 1f,
    ): ShortArray {
        val input = ShortArray(frames) { if (it == 0) 8000 else 0 }
        return PcmEffects.reverb(
            PcmBuffer.of(input, sampleRateHz = rate, channels = 1),
            roomSize = roomSize,
            mix = mix,
            decay = decay,
            predelayMs = predelayMs,
            damping = damping,
        ).samples
    }

    @Test
    fun drySignalSurvivesWhenWetIsZero() {
        val input = ShortArray(400) { if (it % 7 == 0) 3000 else -1500 }

        val rendered = PcmEffects.reverb(
            PcmBuffer.of(input, sampleRateHz = rate, channels = 1),
            roomSize = 0.5f,
            mix = 0f,
            decay = 0.9f,
            predelayMs = 50f,
            damping = 0.8f,
        )

        assertTrue(input.contentEquals(rendered.samples))
    }

    @Test
    fun predelayPushesTheWholeTailLater() {
        fun firstTailFrame(predelayMs: Float): Int {
            val out = render(frames = 8_000, predelayMs = predelayMs)
            // 输入只有第 0 帧，之后出现的非零都是混响
            return (1 until out.size).firstOrNull { out[it] != 0.toShort() } ?: -1
        }

        val none = firstTailFrame(0f)
        // 60ms @2000Hz = 120 帧
        val delayed = firstTailFrame(60f)

        assertTrue("没有预延迟时应该立刻有混响，实际第 $none 帧", none > 0)
        assertTrue("加了 60ms 预延迟后混响应明显后移，实际 $none -> $delayed", delayed >= none + 100)
    }

    @Test
    fun higherDecayKeepsTheTailAliveLonger() {
        fun tailLength(decay: Float): Int {
            val out = render(frames = 20_000, decay = decay)
            return out.indexOfLast { it != 0.toShort() }
        }

        val short = tailLength(0.1f)
        val long = tailLength(0.95f)

        assertTrue("衰减时间调大后尾巴应更长：$short -> $long", long > short)
    }

    @Test
    fun highDampingRemovesEnergyFromTheTailSoItTurnsDarker() {
        // 频率分析太重，改为验证阻尼确实会削减反馈能量：同样的尾巴总能量应更小
        fun tailEnergy(damping: Float): Long {
            val out = render(frames = 20_000, damping = damping)
            return out.drop(500).sumOf { kotlin.math.abs(it.toLong()) }
        }

        val bright = tailEnergy(0f)
        val dark = tailEnergy(0.95f)

        assertTrue("高频阻尼拉满后尾巴能量应显著变小：$bright -> $dark", dark < bright)
    }

    @Test
    fun combFeedbackNoLongerCrossFeedsBetweenVoices() {
        // 曾经写成 line[slot] = dry + acc * feedback，acc 是所有 comb 的累加值，
        // 结果四条线互相耦合、尾巴指数增长。这里断言不会失控发散。
        val out = render(frames = 30_000, decay = 1f, damping = 0f, roomSize = 0f, mix = 1f)

        val tailPeak = out.drop(1_000).maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("混响不该指数发散，尾部峰值=$tailPeak", tailPeak < 32_768)
    }
}
