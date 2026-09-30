package cn.qishui.tool.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放进度推进的回归测试。
 *
 * 由来：进度条和歌词都停在起播那一刻，只有暂停才同步一次。
 * 根因是 Media3 的 `onEvents` 只在离散事件触发，播放头前进不通知，
 * 所以 [cn.qishui.tool.data.media.PlaybackConnection] 得自己按间隔取位置。
 *
 * 这里只测「该不该发新状态」这个判断 —— 真正的播放器需要 Media3 session，
 * 单元测试里造不出来，但这个判断才是会漏的那一步。
 */
class PlaybackProgressTest {

    @Test
    fun `位置变了要发新状态`() {
        assertTrue(shouldPublishPosition(currentMs = 15_000L, previousMs = 12_000L))
    }

    @Test
    fun `位置没变不发新状态`() {
        // 暂停时每 200ms 都会轮询一次，位置不动，不能每次都重组
        assertFalse(shouldPublishPosition(currentMs = 15_000L, previousMs = 15_000L))
    }

    @Test
    fun `seek 之后位置跳变也要发`() {
        assertTrue(shouldPublishPosition(currentMs = 0L, previousMs = 15_000L))
        assertTrue(shouldPublishPosition(currentMs = 60_000L, previousMs = 15_000L))
    }

    @Test
    fun `轮询间隔要够细才能跟住歌词`() {
        // 歌词按行高亮，间隔太大就会明显落后于声音。
        // 200ms 是进度条跟手、歌词不迟滞之间的常规取值。
        assertEquals(200L, POSITION_TICK_MS)
    }
}
