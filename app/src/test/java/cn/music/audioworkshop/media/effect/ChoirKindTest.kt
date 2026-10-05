package cn.music.audioworkshop.media.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合唱人数对应声部数量：两人=2、三人=3、多人=6。
 * 人多时如果每声部都按 1/N 衰减，整体会越叠越小，必须验一下不被压没。
 */
class ChoirKindTest {

    private val rate = 1_000

    private fun render(kind: Int, frames: Int = 600): ShortArray {
        val input = ShortArray(frames) { if (it < 20) 4000 else 0 }
        // choir 返回新 buffer，不像 echo 那样原地改，得接返回值
        return PcmEffects.choir(
            PcmBuffer.of(input, sampleRateHz = rate, channels = 1),
            spreadMs = 30f,
            mix = 0.6f,
            kind = kind,
        ).samples
    }

    @Test
    fun twoVoiceKindDelaysTheCopyByASingleSpread() {
        val rendered = render(kind = 0)

        // 输入只在前 20 帧，20 之后出现的非零就是回声；
        // 展宽 30ms @1000Hz = 30 帧，第一路延迟应落在第 30 帧附近
        val firstEcho = rendered.indexOfFirstFrom(21) { it != 0.toShort() }
        assertTrue("应该出现回声", firstEcho > 0)
        assertTrue("回声不该早于第一路延迟，实际在第 $firstEcho 帧", firstEcho >= 28)
    }

    private inline fun ShortArray.indexOfFirstFrom(start: Int, predicate: (Short) -> Boolean): Int {
        for (i in start until size) if (predicate(this[i])) return i
        return -1
    }

    @Test
    fun moreVoicesKeepTheOutputAtLeastAsLoudAsFewerVoices() {
        fun peak(kind: Int): Int = render(kind).maxOf { kotlin.math.abs(it.toInt()) }

        val two = peak(0)
        val three = peak(1)
        val many = peak(2)

        // 声部多了会互相抵消一部分，但不该比两人合唱还明显更小声
        assertTrue("三人=$three 不该远低于两人=$two", three > two / 3)
        assertTrue("多人=$many 不该远低于两人=$two", many > two / 3)
    }

    @Test
    fun outputNeverClipsAndKeepsShortRange() {
        listOf(0, 1, 2).forEach { kind ->
            assertTrue(render(kind).all { it.toInt() in -32768..32767 })
        }
    }

    @Test
    fun drySignalSurvivesWhenWetIsZero() {
        val input = ShortArray(200) { 1000 }

        val rendered = PcmEffects.choir(
            PcmBuffer.of(input, sampleRateHz = rate, channels = 1),
            spreadMs = 30f,
            mix = 0f,
            kind = 2,
        )

        assertTrue(input.contentEquals(rendered.samples))
        assertEquals(input.size, rendered.samples.size)
    }
}
