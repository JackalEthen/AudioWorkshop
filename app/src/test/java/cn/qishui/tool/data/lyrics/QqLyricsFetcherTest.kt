package cn.qishui.tool.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [extractQqLyric] 的回归测试。
 *
 * 三个 payload 都是从线上真实抓的：
 * - 正常响应（带 Referer）
 * - `-1310`：缺 Referer 时的响应，HTTP 200 但没有 lyric 字段
 * - 空歌词时的 `<?xml` 提示
 */
class QqLyricsFetcherTest {

    @Test
    fun `正常响应取出歌词`() {
        val raw = """{"retcode":0,"code":0,"subcode":0,"lyric":"[00:03.11]祈祷星星 愿你安宁\n[00:09.76]懂得珍惜 懂得爱自己"}"""
        assertEquals("[00:03.11]祈祷星星 愿你安宁\n[00:09.76]懂得珍惜 懂得爱自己", raw.extractQqLyric())
    }

    @Test
    fun `缺 Referer 的响应必须判为拿不到`() {
        // 线上原样：HTTP 200，但 retcode=-1310，没有 lyric 字段。
        // 当成「空歌词」就会误判成这首歌没歌词。
        val raw = """{"retcode":-1310,"code":-1310,"subcode":-1310}"""
        assertNull(raw.extractQqLyric())
    }

    @Test
    fun `xml 提示文本不算歌词`() {
        val raw = """{"retcode":0,"lyric":"<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n<Notify>"}"""
        assertNull(raw.extractQqLyric())
    }

    @Test
    fun `空响应不崩`() {
        assertNull("".extractQqLyric())
        assertNull("   ".extractQqLyric())
    }

    @Test
    fun `被包成 JSONP 也能剥壳`() {
        val raw = """MusicJsonCallback({"retcode":0,"lyric":"[00:01.00]测试"});"""
        assertEquals("[00:01.00]测试", raw.extractQqLyric())
    }
}
