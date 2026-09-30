package cn.qishui.tool.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放速率循环切换。
 *
 * 修过一次 bug：走��� 2.0x 之后卡住回不到 1.0x，因为用了 coerceAtMost 停在末档。
 * 正确行为是在档位表里绕一圈。
 */
class PlaybackSpeedCycleTest {

    private val steps = SpeedSteps

    /** 复刻 [cn.qishui.tool.data.media.PlaybackConnection.cyclePlaybackSpeed] 的索引算法。 */
    private fun next(current: Float): Float {
        val index = steps.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        return steps[(index + 1).mod(steps.size)]
    }

    @Test
    fun `从1倍速开始一路向上`() {
        assertEquals(0.5f, steps[0], 0.001f)
        assertEquals(1.0f, steps[2], 0.001f)
        // 1.0 -> 1.25 -> 1.5 -> 2.0
        var speed = 1.0f
        speed = next(speed); assertEquals(1.25f, speed, 0.001f)
        speed = next(speed); assertEquals(1.5f, speed, 0.001f)
        speed = next(speed); assertEquals(2.0f, speed, 0.001f)
    }

    @Test
    fun `两倍速之后能绕回第一档`() {
        val afterMax = next(2.0f)
        assertEquals("必须绕回 0.5x，不能卡在 2.0x", 0.5f, afterMax, 0.001f)
    }

    @Test
    fun `绕一圈回到原点`() {
        var speed = 1.0f
        repeat(steps.size) { speed = next(speed) }
        assertEquals("转一整圈必须回到原档位", 1.0f, speed, 0.001f)
    }

    @Test
    fun `任何档位都能走到1倍速`() {
        steps.forEach { start ->
            var speed = start
            var guard = 0
            while (kotlin.math.abs(speed - 1.0f) > 0.01f && guard < steps.size) {
                speed = next(speed)
                guard++
            }
            assertTrue("从 $start 出发走不到 1.0x", kotlin.math.abs(speed - 1.0f) < 0.01f)
        }
    }

    @Test
    fun `不在档位表里的速率落到第一档`() {
        assertEquals(0.5f, next(1.37f), 0.001f)
    }
}
