package cn.music.audioworkshop.domain.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「下一首播放」的插入位置算法。
 *
 * 语义见 PLAYER_PLAN.md 第 2 节：长按下一首播放 = 插到当前曲的下一位，不打断当前播放；
 * 直接点击 = 插到下一位并立即跳过去播。
 */
class QueueInsertIndexTest {

    @Test
    fun `没播过任何一项时插到队首`() {
        assertEquals(0, queueInsertIndex(currentIndex = -1, itemCount = 0))
        assertEquals(0, queueInsertIndex(currentIndex = -1, itemCount = 5))
    }

    @Test
    fun `插到当前曲的下一位`() {
        assertEquals(1, queueInsertIndex(currentIndex = 0, itemCount = 1))
        assertEquals(3, queueInsertIndex(currentIndex = 2, itemCount = 5))
    }

    @Test
    fun `当前曲在队尾时追加到末尾而不是越界`() {
        assertEquals(3, queueInsertIndex(currentIndex = 2, itemCount = 3))
        assertEquals(1, queueInsertIndex(currentIndex = 0, itemCount = 1))
    }

    @Test
    fun `下标永远不超过 itemCount`() {
        for (current in -1..5) {
            for (count in 0..5) {
                val index = queueInsertIndex(current, count)
                assertTrue("current=$current count=$count 得到 $index", index in 0..count)
            }
        }
    }
}
