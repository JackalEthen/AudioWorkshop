package cn.music.audioworkshop.domain.edit

import cn.music.audioworkshop.domain.model.EditMode
import cn.music.audioworkshop.domain.model.EditTimeRange
import cn.music.audioworkshop.domain.model.EditTimeSegment
import cn.music.audioworkshop.domain.model.LyricLine
import cn.music.audioworkshop.domain.model.LyricWord
import cn.music.audioworkshop.domain.model.LyricsTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricTimelineMapperTest {

    private fun track(vararg lines: LyricLine, offsetMs: Long = 0L) = LyricsTrack(lines.toList(), offsetMs)

    @Test
    fun keepSelectedRewritesTimesOntoOutputTimeline() {
        val result = LyricTimelineMapper.map(
            lyrics = track(
                LyricLine(
                    "一二",
                    0L,
                    1_000_000L,
                    listOf(LyricWord("一", 0L, 400_000L), LyricWord("二", 400_000L, 1_000_000L)),
                ),
            ),
            durationUs = 2_000_000L,
            selections = listOf(EditTimeRange(200_000L, 1_000_000L)),
            mode = EditMode.KEEP_SELECTED,
        )

        assertEquals(listOf(EditTimeSegment(200_000L, 1_000_000L, 0L, 800_000L)), result.segments)
        assertEquals(0L, result.lyricOffsetMs)
        assertEquals(
            listOf(
                LyricLine(
                    "一二",
                    0L,
                    800_000L,
                    listOf(LyricWord("一", 0L, 200_000L), LyricWord("二", 200_000L, 800_000L)),
                ),
            ),
            result.lines,
        )
    }

    @Test
    fun removeSelectedDropsLyricsInsideRemovedRange() {
        val result = LyricTimelineMapper.map(
            lyrics = track(
                LyricLine(
                    "一二三",
                    0L,
                    1_800_000L,
                    listOf(
                        LyricWord("一", 0L, 400_000L),
                        LyricWord("二", 600_000L, 900_000L),
                        LyricWord("三", 1_200_000L, 1_800_000L),
                    ),
                ),
            ),
            durationUs = 2_000_000L,
            selections = listOf(EditTimeRange(500_000L, 1_000_000L)),
            mode = EditMode.REMOVE_SELECTED,
        )

        assertEquals(
            listOf(
                EditTimeSegment(0L, 500_000L, 0L, 500_000L),
                EditTimeSegment(1_000_000L, 2_000_000L, 500_000L, 1_500_000L),
            ),
            result.segments,
        )
        assertEquals(
            listOf(
                LyricLine(
                    "一三",
                    0L,
                    1_300_000L,
                    listOf(LyricWord("一", 0L, 400_000L), LyricWord("三", 700_000L, 1_300_000L)),
                ),
            ),
            result.lines,
        )
    }

    @Test
    fun splitsWordSpanningRemovedRange() {
        val result = LyricTimelineMapper.map(
            lyrics = track(
                LyricLine("跨跨", 400_000L, 1_200_000L, listOf(LyricWord("跨", 400_000L, 1_200_000L))),
            ),
            durationUs = 2_000_000L,
            selections = listOf(EditTimeRange(500_000L, 1_000_000L)),
            mode = EditMode.REMOVE_SELECTED,
        )

        val line = result.lines.single()
        assertEquals("跨跨", line.text)
        assertEquals(400_000L, line.startUs)
        assertEquals(700_000L, line.endUs)
        assertEquals(
            listOf(LyricWord("跨", 400_000L, 500_000L), LyricWord("跨", 500_000L, 700_000L)),
            line.words,
        )
    }

    @Test
    fun splitsWordSpanningTwoKeptSegments() {
        val result = LyricTimelineMapper.map(
            lyrics = track(
                LyricLine("跨跨", 400_000L, 1_200_000L, listOf(LyricWord("跨", 400_000L, 1_200_000L))),
            ),
            durationUs = 2_000_000L,
            selections = listOf(EditTimeRange(0L, 500_000L), EditTimeRange(1_000_000L, 2_000_000L)),
            mode = EditMode.KEEP_SELECTED,
        )

        assertEquals(
            listOf(EditTimeSegment(0L, 500_000L, 0L, 500_000L), EditTimeSegment(1_000_000L, 2_000_000L, 500_000L, 1_500_000L)),
            result.segments,
        )
        assertEquals(
            listOf(LyricWord("跨", 400_000L, 500_000L), LyricWord("跨", 500_000L, 700_000L)),
            result.lines.single().words,
        )
    }

    @Test
    fun appliesTrackOffsetAndEditOffsetOnce() {
        val result = LyricTimelineMapper.map(
            lyrics = track(
                LyricLine("甲", 0L, 1_000_000L, listOf(LyricWord("甲", 0L, 1_000_000L))),
                offsetMs = 250L,
            ),
            durationUs = 2_000_000L,
            selections = listOf(EditTimeRange(0L, 2_000_000L)),
            mode = EditMode.KEEP_SELECTED,
            lyricOffsetMs = 100L,
        )

        assertEquals(350L, result.lyricOffsetMs)
        assertEquals(
            listOf(LyricLine("甲", 350_000L, 1_350_000L, listOf(LyricWord("甲", 350_000L, 1_350_000L)))),
            result.lines,
        )
    }

    @Test
    fun dropsLineWhoseWordsAreFullyRemoved() {
        val result = LyricTimelineMapper.map(
            lyrics = track(
                LyricLine("保留", 0L, 400_000L, listOf(LyricWord("保", 0L, 200_000L), LyricWord("留", 200_000L, 400_000L))),
                LyricLine("删除", 600_000L, 900_000L, listOf(LyricWord("删", 600_000L, 900_000L))),
            ),
            durationUs = 2_000_000L,
            selections = listOf(EditTimeRange(500_000L, 1_000_000L)),
            mode = EditMode.REMOVE_SELECTED,
        )

        assertEquals(listOf("保留"), result.lines.map { it.text })
    }

    @Test
    fun ordersOutputLinesByMappedTime() {
        val result = LyricTimelineMapper.map(
            lyrics = track(
                LyricLine("后", 1_200_000L, 1_800_000L, listOf(LyricWord("后", 1_200_000L, 1_800_000L))),
                LyricLine("前", 0L, 400_000L, listOf(LyricWord("前", 0L, 400_000L))),
            ),
            durationUs = 2_000_000L,
            selections = listOf(EditTimeRange(0L, 2_000_000L)),
            mode = EditMode.KEEP_SELECTED,
        )

        assertEquals(listOf("前", "后"), result.lines.map { it.text })
    }

    @Test
    fun rebuildsWordlessLineFromItsOwnInterval() {
        val result = LyricTimelineMapper.map(
            lyrics = track(LyricLine("纯音乐", 400_000L, 900_000L, emptyList())),
            durationUs = 2_000_000L,
            selections = listOf(EditTimeRange(300_000L, 1_000_000L)),
            mode = EditMode.KEEP_SELECTED,
        )

        assertEquals(
            listOf(
                LyricLine(
                    "纯音乐",
                    100_000L,
                    600_000L,
                    listOf(LyricWord("纯音乐", 100_000L, 600_000L)),
                ),
            ),
            result.lines,
        )
    }

    @Test
    fun emptyKeepSelectionProducesNoSegmentsAndNoLines() {
        val result = LyricTimelineMapper.map(
            lyrics = track(LyricLine("甲", 0L, 1_000_000L, listOf(LyricWord("甲", 0L, 1_000_000L)))),
            durationUs = 2_000_000L,
            selections = emptyList(),
            mode = EditMode.KEEP_SELECTED,
        )

        assertEquals(emptyList<EditTimeSegment>(), result.segments)
        assertEquals(emptyList<LyricLine>(), result.lines)
    }
}
