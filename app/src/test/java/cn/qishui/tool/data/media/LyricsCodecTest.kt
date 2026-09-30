package cn.qishui.tool.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 歌词格式回归测试。
 *
 * [realQqLrc] 是线上 QQ 官方接口对 `songmid=003mfwSS42lDZD`（张月《信》）
 * 的原样返回，938 字符里截取的前几行。
 *
 * 这组测试的由来：歌词接口明明返回了 938 字符，播放页却一直是空的 ——
 * 因为接的解析器只认 QRC 格式，把标准 LRC 整行丢掉了。
 */
class LyricsCodecTest {

    /** 线上原样：标准 LRC，`[mm:ss.xx]歌词`，中间夹空行。 */
    private val realQqLrc = """
        [00:03.11]祈祷星星 愿你安宁
        [00:09.76]懂得珍惜 懂得爱自己

        [00:16.13]祈祷明月 愿你安心
        [00:21.68]今天是透明的精灵
    """.trimIndent()

    /** 本项目 QRC 解析器认识的逐字格式：`[起,时]` + `<相对,时,0>字`。 */
    private val qrcLine = "[311,2600]<311,2600,0>祈祷<2600,2600,0>星星<5000,2600,0>愿你安宁"

    @Test
    fun `线上返回的标准 LRC 要解析出行`() {
        val track = LyricsCodec.parse(realQqLrc)
        assertEquals(4, track.lines.size)
        assertEquals("祈祷星星 愿你安宁", track.lines.first().text)
        // 00:03.11 = 3110ms = 3110000us
        assertEquals(3_110_000L, track.lines.first().startUs)
    }

    @Test
    fun `空行不产生歌词行`() {
        // 线上返回里每两行就有一个空行，不能算进去
        assertEquals(4, LyricsCodec.parse(realQqLrc).lines.size)
        assertEquals(2, LyricsCodec.parse("[00:01.00]甲\n\n[00:02.00]乙").lines.size)
    }

    @Test
    fun `QRC 逐字格式仍然要能解析`() {
        val track = LyricsCodec.parse(qrcLine)
        assertEquals(1, track.lines.size)
        assertEquals(3, track.lines.first().words.size)
        assertEquals("祈祷星星愿你安宁", track.lines.first().text)
    }

    @Test
    fun `元信息行不产生歌词行`() {
        val withMeta = "[ti:信]\n[ar:张月]\n[00:03.11]祈祷星星 愿你安宁"
        assertEquals(1, LyricsCodec.parse(withMeta).lines.size)
    }

    @Test
    fun `空输入不崩`() {
        assertEquals(0, LyricsCodec.parse(null).lines.size)
        assertEquals(0, LyricsCodec.parse("").lines.size)
        assertEquals(0, LyricsCodec.parse("   ").lines.size)
    }

    @Test
    fun `纯文字没有时间戳就当没有歌词`() {
        assertEquals(0, LyricsCodec.parse("这是一段没有时间戳的文字").lines.size)
    }

    @Test
    fun `解析顺序按时间排`() {
        val track = LyricsCodec.parse("[00:30.00]后\n[00:10.00]前")
        assertEquals("前", track.lines.first().text)
    }
}
