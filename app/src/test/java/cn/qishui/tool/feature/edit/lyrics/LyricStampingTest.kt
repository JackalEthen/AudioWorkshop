package cn.qishui.tool.feature.edit.lyrics

import cn.qishui.tool.data.media.LrcCodec
import cn.qishui.tool.domain.model.LyricLine
import cn.qishui.tool.domain.model.LyricsTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打点编辑后的歌词必须还能被自己解析回来，且不覆盖源曲。
 * 这里覆盖的是「编辑 -> toLrc -> parse -> 结构一致」这条最容易断的链。
 */
class LyricStampingTest {

    @Test
    fun editingLinesThenReSerialisingRoundTripsBackToTheSameStructure() {
        val original = LyricsTrack(
            lines = listOf(
                LyricLine("第一句", 1_000_000L, 6_000_000L, emptyList()),
                LyricLine("第二句", 6_000_000L, 11_000_000L, emptyList()),
            ),
            offsetMs = 0L,
        )

        val reparsed = LrcCodec.parse(LrcCodec.toLrc(original))

        assertEquals(2, reparsed.lines.size)
        assertEquals("第一句", reparsed.lines[0].text)
        assertEquals("第二句", reparsed.lines[1].text)
        assertEquals(1_000_000L, reparsed.lines[0].startUs)
        assertEquals(6_000_000L, reparsed.lines[1].startUs)
    }

    @Test
    fun linesStaySortedByStartTimeSoStampingOutOfOrderIsSafe() {
        val out = mutableListOf(
            LyricLine("晚", 9_000_000L, 12_000_000L, emptyList()),
            LyricLine("早", 1_000_000L, 4_000_000L, emptyList()),
        )
        val ordered = out.sortedBy { it.startUs }

        assertEquals(listOf("早", "晚"), ordered.map { it.text })
    }

    @Test
    fun editingTextDoesNotMoveTheTimestamps() {
        val line = LyricLine("旧词", 2_000_000L, 7_000_000L, emptyList())

        val edited = line.copy(text = "新词")

        assertEquals(2_000_000L, edited.startUs)
        assertEquals(7_000_000L, edited.endUs)
        assertEquals("新词", LrcCodec.parse(LrcCodec.toLrc(LyricsTrack(listOf(edited), 0L))).lines.single().text)
    }

    @Test
    fun anEmptyLineStillSerialisesSoAStampedPlaceholderSurvives() {
        val placeholder = LyricLine("", 3_000_000L, 4_000_000L, emptyList())

        val reparsed = LrcCodec.parse(LrcCodec.toLrc(LyricsTrack(listOf(placeholder), 0L)))

        assertEquals(1, reparsed.lines.size)
        assertEquals(3_000_000L, reparsed.lines.single().startUs)
    }

    @Test
    fun stampingKeepsTheLineSpanInsteadOfCollapsingIt() {
        val line = LyricLine("词", 1_000_000L, 6_000_000L, emptyList())
        val span = line.endUs - line.startUs

        val stamped = line.copy(startUs = 20_000_000L, endUs = 20_000_000L + span)

        assertEquals(5_000_000L, stamped.endUs - stamped.startUs)
    }

    @Test
    fun nudgeNeverProducesANegativeTimestamp() {
        val line = LyricLine("词", 500_000L, 2_000_000L, emptyList())
        val deltaUs = -1_000_000L

        val nudged = line.copy(
            startUs = (line.startUs + deltaUs).coerceAtLeast(0L),
            endUs = (line.endUs + deltaUs).coerceAtLeast(0L),
        )

        assertEquals(0L, nudged.startUs)
        assertTrue(nudged.endUs >= 0L)
    }
}
