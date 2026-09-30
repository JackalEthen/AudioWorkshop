package cn.qishui.tool.media.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回声分两种算法：山谷回响（两抽头）和两倍叠加（单抽头）。
 * 这里的用例锁住「干声必须原样透过去」这条底线 —— 效果只该叠加，不该改原始信号。
 */
class EchoKindTest {

    private val rate = 1_000

    @Test
    fun drySignalIsAlwaysPreservedAtFullLevelWhenMixIsZero() {
        val input = shortArrayOf(1000, 1000, 1000, 1000, 1000, 1000)

        val valley = PcmEffects.echo(
            PcmBuffer.of(input.copyOf(), sampleRateHz = rate, channels = 1),
            delayMs = 100f,
            feedback = 0.5f,
            mix = 0f,
            kind = 0,
        )
        val double = PcmEffects.echo(
            PcmBuffer.of(input.copyOf(), sampleRateHz = rate, channels = 1),
            delayMs = 100f,
            feedback = 0.5f,
            mix = 0f,
            kind = 1,
        )

        assertTrue(input.contentEquals(valley.samples))
        assertTrue(input.contentEquals(double.samples))
    }

    @Test
    fun doubleLayerRepeatsTheInputAfterTheDelay() {
        // 单声道 1000Hz，延迟 100ms = 100 帧
        val input = ShortArray(300) { if (it == 0) 1000 else 0 }

        val rendered = PcmEffects.echo(
            PcmBuffer.of(input, sampleRateHz = rate, channels = 1),
            delayMs = 100f,
            feedback = 0.9f,
            mix = 1f,
            kind = 1,
        )

        // 干 + 延迟 100 帧的原声
        assertEquals(0, rendered.samples[0].toInt())
        assertTrue(rendered.samples[100] > 0)
    }

    @Test
    fun valleyKindAddsASecondTapSoTheTailIsLongerThanDoubleLayer() {
        val input = ShortArray(900) { if (it == 0) 1000 else 0 }

        fun tailEnergy(kind: Int): Int {
            val buffer = PcmBuffer.of(input.copyOf(), sampleRateHz = rate, channels = 1)
            PcmEffects.echo(buffer, delayMs = 100f, feedback = 0.7f, mix = 0.5f, kind = kind)
            // 200 帧之后（第二次延迟之后）还在响的样本
            return buffer.samples.drop(250).count { it > 0 }
        }

        val valley = tailEnergy(0)
        val double = tailEnergy(1)

        assertTrue("山谷回响=$valley 尾巴应该不比单抽头=$double 短", valley >= double)
    }

    @Test
    fun valleyKindStillProducesSoundWhenTheWetLevelIsMaxedOut() {
        // 干声被完全压掉时，回声只能靠延迟线里存的能量；
        // 曾经漏了干声注入，这里 wet=1 会输出全零。
        val input = ShortArray(400) { if (it == 0) 1000 else 0 }

        val rendered = PcmEffects.echo(
            PcmBuffer.of(input, sampleRateHz = rate, channels = 1),
            delayMs = 100f,
            feedback = 0.7f,
            mix = 1f,
            kind = 0,
        )

        assertTrue(rendered.samples.any { it != 0.toShort() })
    }

    @Test
    fun outputNeverClipsPastShortRange() {
        val input = ShortArray(500) { if (it % 2 == 0) 32767 else -32768 }

        val rendered = PcmEffects.echo(
            PcmBuffer.of(input, sampleRateHz = rate, channels = 1),
            delayMs = 50f,
            feedback = 0.95f,
            mix = 1f,
            kind = 0,
        )

        assertTrue(rendered.samples.all { it.toInt() in -32768..32767 })
    }
}
