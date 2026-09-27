package cn.qishui.tool.data.media

import cn.qishui.tool.domain.model.LyricLine
import cn.qishui.tool.domain.model.LyricWord
import cn.qishui.tool.domain.model.LyricsTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class QsmusicLyricParserTest {
    private val parser = QsmusicLyricParser()

    @Test
    fun parsesMultiLineWordLyrics() {
        val track = parser.parse(
            "[18034,3519]<0,331,0>我<440,331,0>站<880,331,0>在\n[21000,2000]<0,500,0>你<500,500,0>好",
        )

        assertEquals(2, track.lines.size)
        assertEquals(0L, track.offsetMs)
        assertEquals(
            LyricLine(
                text = "我站在",
                startUs = 18_034_000L,
                endUs = 21_553_000L,
                words = listOf(
                    LyricWord("我", 18_034_000L, 18_365_000L),
                    LyricWord("站", 18_474_000L, 18_805_000L),
                    LyricWord("在", 18_914_000L, 19_245_000L),
                ),
            ),
            track.lines[0],
        )
        assertEquals(
            LyricLine(
                text = "你好",
                startUs = 21_000_000L,
                endUs = 23_000_000L,
                words = listOf(
                    LyricWord("你", 21_000_000L, 21_500_000L),
                    LyricWord("好", 21_500_000L, 22_000_000L),
                ),
            ),
            track.lines[1],
        )
    }

    @Test
    fun keepsChineseSpacesAndPunctuationWhileIgnoringFlag() {
        val track = parser.parse("[0,3000]<0,500,0>你好，<500,500,0> 世界 <1000,500,1>！")

        val line = track.lines.single()
        assertEquals("你好， 世界 ！", line.text)
        assertEquals(
            listOf(
                LyricWord("你好，", 0L, 500_000L),
                LyricWord(" 世界 ", 500_000L, 1_000_000L),
                LyricWord("！", 1_000_000L, 1_500_000L),
            ),
            line.words,
        )
    }

    @Test
    fun handlesCrlfAndTrailingLineBreak() {
        val track = parser.parse("[0,1000]<0,500,0>甲<500,500,0>乙\r\n[1000,1000]<0,500,0>丙\r\n")

        assertEquals(listOf("甲乙", "丙"), track.lines.map { it.text })
    }

    @Test
    fun skipsBlankAndWhitespaceOnlyLines() {
        val track = parser.parse("\n[0,1000]<0,500,0>甲\n\n   \n[1000,1000]<0,500,0>乙\n")

        assertEquals(listOf("甲", "乙"), track.lines.map { it.text })
    }

    @Test
    fun recoversFromTruncatedTrailingToken() {
        val track = parser.parse("[0,1000]<0,500,0>甲<500,500")

        assertEquals(
            LyricLine("甲", 0L, 1_000_000L, listOf(LyricWord("甲", 0L, 500_000L))),
            track.lines.single(),
        )
    }

    @Test
    fun recoversFromBareTrailingOpenBracket() {
        val track = parser.parse("[0,1000]<0,500,0>甲<")

        assertEquals(listOf("甲"), track.lines.map { it.text })
    }

    @Test
    fun skipsMalformedLinesAndKeepsRecoverableOnes() {
        val track = parser.parse(
            "not a lyric line\n" +
                "[0,1000]<0,500,0>甲\n" +
                "[abc,1000]<0,500,0>乙\n" +
                "[1000,1000]<0,500>丙\n" +
                "[2000,1000]<x,500,0>丁\n" +
                "[3000,-5]<0,500,0>戊\n" +
                "[4000,1000]\n" +
                "[5000,1000]<0,500,0>己",
        )

        assertEquals(listOf("甲", "己"), track.lines.map { it.text })
    }

    @Test
    fun dropsLinesWithoutWords() {
        assertEquals(emptyList<LyricLine>(), parser.parse("[0,1000]").lines)
    }

    @Test
    fun blankAndNullInputProduceEmptyTrack() {
        val expected = LyricsTrack(emptyList(), 0L)

        assertEquals(expected, parser.parse(""))
        assertEquals(expected, parser.parse("   \n  \n"))
        assertEquals(expected, parser.parse(null))
    }
}
