package cn.music.audioworkshop.feature.edit.lyrics

import cn.music.audioworkshop.domain.model.LyricLine
import cn.music.audioworkshop.domain.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricLocatorTest {

    @Test
    fun emptyLyricsYieldNoCursor() {
        assertEquals(LyricCursor.NONE, LyricLocator.locate(emptyList(), 5_000L))
    }

    @Test
    fun positionBeforeFirstLineYieldNoCursor() {
        val late = listOf(LyricLine("晚开场", 2_000L, 3_000L, emptyList()))
        assertEquals(LyricCursor.NONE, LyricLocator.locate(late, 500L))
    }

    @Test
    fun positionInsideLinePicksThatLine() {
        assertEquals(LyricCursor(1, 1), LyricLocator.locate(LINES, 5_500L))
    }

    @Test
    fun positionBetweenLinesKeepsTheEarlierLine() {
        assertEquals(LyricCursor(0, 0), LyricLocator.locate(LINES, 3_000L))
    }

    @Test
    fun positionOnLineBoundaryPicksTheNewLine() {
        assertEquals(LyricCursor(1, 0), LyricLocator.locate(LINES, 4_000L))
    }

    @Test
    fun positionAfterLastLineKeepsLastLine() {
        assertEquals(LyricCursor(2, -1), LyricLocator.locate(LINES, 9_999L))
    }

    @Test
    fun wordLevelHighlightAdvancesInsideTheLine() {
        assertEquals(LyricCursor(1, 0), LyricLocator.locate(LINES, 4_200L))
        assertEquals(LyricCursor(1, 1), LyricLocator.locate(LINES, 5_500L))
        assertEquals(LyricCursor(1, 2), LyricLocator.locate(LINES, 5_900L))
    }

    @Test
    fun lineWithoutWordsFallsBackToWholeLine() {
        val plain = listOf(LyricLine(text = "纯音乐", startUs = 0L, endUs = 3_000L, words = emptyList()))
        assertEquals(LyricCursor(0, -1), LyricLocator.locate(plain, 1_000L))
    }

    private companion object {
        val LINES = listOf(
            LyricLine("前奏", 0L, 2_000L, listOf(LyricWord("前", 0L, 1_000L))),
            LyricLine(
                text = "你好世界",
                startUs = 4_000L,
                endUs = 7_000L,
                words = listOf(
                    LyricWord("你", 4_000L, 4_500L),
                    LyricWord("好", 5_000L, 5_600L),
                    LyricWord("世界", 5_800L, 6_600L),
                ),
            ),
            LyricLine("尾声", 8_000L, 9_000L, emptyList()),
        )
    }
}
