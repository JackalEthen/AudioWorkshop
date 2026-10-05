package cn.music.audioworkshop.feature.lrc

import cn.music.audioworkshop.data.media.LrcCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词打点的时间换算与格式校验。
 *
 * 这些是整个功能最容易写错一位的地方：LRC 小数部分是百分之一秒，
 * 而且导出后再读回来必须还是同一个时间。
 */
class LrcTimingTest {

    @Test
    fun `时间戳格式是分秒毫秒`() {
        assertEquals("00:12.500", LrcTiming.stamp(12_500_000L))
        assertEquals("01:00.000", LrcTiming.stamp(60_000_000L))
        assertEquals("03:05.070", LrcTiming.stamp(185_070_000L))
    }

    @Test
    fun `超过一小时照样进位`() {
        // 分钟位不封顶：1 小时 2 分 3 秒必须写成 62:03 而不是 1:02:03
        assertEquals("62:03.000", LrcTiming.stamp(3_723_000_000L))
    }

    @Test
    fun `负时间被夹到零`() {
        assertEquals("00:00.000", LrcTiming.stamp(-5_000L))
    }

    @Test
    fun `显示格式保留一位小数便于人眼读`() {
        assertEquals("1:05.3", LrcTiming.display(65_320_000L))
        assertEquals("0:00.0", LrcTiming.display(0L))
    }

    @Test
    fun `导出再读回时间不变`() {
        // 打点最怕的就是「存进去是 12.5 秒，读出来变成 12.05 秒」这类偏移
        listOf(0L, 1_000L, 12_500L, 59_999L, 60_000L, 185_070L).forEach { ms ->
            val lrc = LrcCodec.toLrc(
                cn.music.audioworkshop.domain.model.LyricsTrack(
                    lines = listOf(
                        cn.music.audioworkshop.domain.model.LyricLine("词", ms * 1000L, 0L, emptyList()),
                    ),
                    offsetMs = 0L,
                ),
            )
            val parsed = LrcCodec.parse(lrc).lines.single()
            assertEquals("回读时间应为 ${ms}ms", ms * 1000L, parsed.startUs)
        }
    }

@Test
    fun `带时间戳的歌词没有提示`() {
        assertNull(LrcTiming.describeTimestamps("[00:12.500]第一句\n[00:18.000]第二句"))
    }

    @Test
    fun `空文本没有提示`() {
        assertNull(LrcTiming.describeTimestamps(""))
        assertNull(LrcTiming.describeTimestamps("   \n  "))
    }

    @Test
    fun `纯文本提示会自动分配时间并告知怎么校准`() {
        val hint = LrcTiming.describeTimestamps("第一句\n第二句\n第三句")!!
        assertTrue("应说明句数：$hint", hint.contains("3 句"))
        assertTrue("应告知校准方式：$hint", hint.contains("−1s"))
    }

    @Test
    fun `只有元信息行时提示没有歌词内容`() {
        assertEquals("没有识别到歌词内容，只有元信息行", LrcTiming.describeTimestamps("[ti:歌名]\n[ar:歌手]"))
    }

    @Test
    fun `混着来的歌词不算纯文本因此不提示`() {
        // 已有时间戳的行不该被重新分配，也不该被说成「没时间戳」
        assertNull(LrcTiming.describeTimestamps("[00:12.500]对好的\n没有时间的"))
    }
}

/** 纯文本歌词：没有时间戳也要能粘贴。 */
class LrcPlainTextTest {

    @Test
    fun `纯文本被识别为没有时间戳`() {
        assertTrue(LrcTiming.hasNoTimestamp("第一句\n第二句\n第三句"))
    }

    @Test
    fun `带时间戳的不算纯文本`() {
        assertFalse(LrcTiming.hasNoTimestamp("[00:12.500]第一句"))
    }

    @Test
    fun `混合粘贴只要有一行有时间戳就不算纯文本`() {
        // 否则会把已经对好时间的行也重新分配，毁掉用户的工作
        assertFalse(LrcTiming.hasNoTimestamp("[00:12.500]对好的\n没有时间的"))
    }

    @Test
    fun `纯文本每行一句按固定间隔分配时间`() {
        val assigned = LrcTiming.assignPlainTextTimings("第一句\n第二句\n第三句")
        assertEquals(3, assigned.size)
        assertEquals(0L, assigned[0].first)
        assertEquals(4_000_000L, assigned[1].first)
        assertEquals(8_000_000L, assigned[2].first)
        assertEquals("第一句", assigned[0].second)
    }

    @Test
    fun `纯文本里的空行和元信息被跳过`() {
        val assigned = LrcTiming.assignPlainTextTimings("第一句\n\n[ti:歌名]\n\n第二句")
        assertEquals(2, assigned.size)
        assertEquals("第一句", assigned[0].second)
        assertEquals("第二句", assigned[1].second)
        // 跳过的行不占时间轴
        assertEquals(4_000_000L, assigned[1].first)
    }

    @Test
    fun `首尾空白被裁掉`() {
        val assigned = LrcTiming.assignPlainTextTimings("  第一句  \n  第二句  ")
        assertEquals("第一句", assigned[0].second)
        assertEquals("第二句", assigned[1].second)
    }

    @Test
    fun `纯文本通过校验不被拦下`() {
        // 这是本次的核心需求：手上只有词，没有时间戳，也要能粘贴
        assertNotNull(LrcTiming.describeTimestamps("第一句\n第二句"))
    }

    @Test
    fun `纯文本分配后能导出成合法LRC`() {
        val lines = LrcTiming.assignPlainTextTimings("第一句\n第二句").map { (startUs, body) -> LrcLine(startUs, body) }
        val lrc = LrcCodec.toLrc(
            cn.music.audioworkshop.domain.model.LyricsTrack(
                lines = lines.map {
                    cn.music.audioworkshop.domain.model.LyricLine(it.text, it.startUs, it.startUs + 5_000_000L, emptyList())
                },
                offsetMs = 0L,
            ),
        )
        val parsed = LrcCodec.parse(lrc)
        assertEquals(2, parsed.lines.size)
        assertEquals("第一句", parsed.lines[0].text)
        assertEquals(4_000_000L, parsed.lines[1].startUs)
    }
}

/** 列表状态规则：高亮与插入位置。 */
class LrcUiStateTest {

    private fun lines(vararg timesMs: Long) = timesMs.map {
        LrcLine(it * 1000L, "词$it")
    }

    private fun fakeTrack() = cn.music.audioworkshop.domain.model.SourceTrack(
        id = "t",
        origin = cn.music.audioworkshop.domain.model.SourceOrigin.LOCAL_IMPORT,
        sourceShareUrl = null,
        title = null,
        artist = null,
        album = null,
        localPath = "/tmp/a.mp3",
        format = "mp3",
        durationMs = 180_000L,
        bitrateBps = null,
        sizeBytes = null,
        sampleRateHz = null,
        lyrics = null,
        fileHash = null,
        coverUri = null,
    )

    @Test
    fun `播放位置落在最后一行已开始的行上`() {
        val state = LrcUiState(lines = lines(0L, 10_000L, 20_000L))
        assertEquals(0, state.currentLineIndexAt(0L))
        assertEquals(1, state.currentLineIndexAt(10_000L))
        assertEquals(2, state.currentLineIndexAt(50_000L))
    }

    @Test
    fun `播放位置在第一行之前时不高亮任何行`() {
        // 负一还没开始时不该高亮第 0 行，否则界面一开始就是「正在唱」
        val state = LrcUiState(lines = lines(10_000L))
        assertEquals(-1, state.currentLineIndexAt(0L))
    }

    @Test
    fun `没有歌词时不高亮`() {
        assertEquals(-1, LrcUiState().currentLineIndexAt(5_000L))
    }

    @Test
    fun `没有歌词时不能导出`() {
        assertTrue(LrcUiState(track = fakeTrack(), lines = emptyList()).canExport.not())
        assertTrue(LrcUiState(track = fakeTrack(), lines = lines(0L)).canExport)
    }

    @Test
    fun `没导入音频时不能导出`() {
        assertTrue(LrcUiState(track = null, lines = lines(0L)).canExport.not())
    }
}



