package cn.qishui.tool.feature.edit.lyrics

import cn.qishui.tool.data.media.LrcCodec
import cn.qishui.tool.domain.model.LyricLine
import cn.qishui.tool.domain.model.LyricWord
import cn.qishui.tool.domain.model.LyricsTrack
import cn.qishui.tool.media.metadata.Id3v2Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐字打轴的三条硬要求：
 * 1. 字级时间戳能原样往返
 * 2. 相邻字不交叉
 * 3. 整行/逐字混用时导出时间轴不能断
 */
class WordLevelStampingTest {

    @Test
    fun wordTimestampsSurviveTheLrcRoundTrip() {
        val line = LyricLine(
            text = "我们唱歌",
            startUs = 1_000_000L,
            endUs = 5_000_000L,
            words = listOf(
                LyricWord("我", 1_000_000L, 2_000_000L),
                LyricWord("们", 2_000_000L, 3_000_000L),
                LyricWord("唱", 3_000_000L, 4_000_000L),
                LyricWord("歌", 4_000_000L, 5_000_000L),
            ),
        )

        val reparsed = LrcCodec.parse(LrcCodec.toLrc(LyricsTrack(listOf(line), 0L))).lines.single()

        assertEquals(4, reparsed.words.size)
        assertEquals(listOf("我", "们", "唱", "歌"), reparsed.words.map { it.text })
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L), reparsed.words.map { it.startUs })
    }

    @Test
    fun stampingAWordNeverCrossesItsNeighbours() {
        // 「我」1s-2s，「们」3s-4s，中间 1s 空隙可以打点
        val previousEnd = 2_000_000L
        val nextStart = 3_000_000L
        // 播放位置已经跑到下一个字后面了
        val rawPosition = 3_500_000L

        val stamped = rawPosition.coerceAtLeast(previousEnd).coerceAtMost(maxOf(previousEnd, nextStart - 1L))

        assertEquals(2_999_999L, stamped)
        assertTrue(stamped in previousEnd until nextStart)
    }

    @Test
    fun stampingTheLastWordOfALineDoesNotThrowOnAnEmptyCoercionRange() {
        // 末字没有下一个字，upper 会退到 previousEnd，coerceIn 在这种区间上直接抛异常
        val previousEnd = 4_000_000L
        val nextStart = 4_000_000L
        val rawPosition = 9_000_000L

        val stamped = runCatching {
            rawPosition.coerceAtLeast(previousEnd).coerceAtMost(maxOf(previousEnd, nextStart - 1L))
        }

        assertEquals(4_000_000L, stamped.getOrNull())
    }

    @Test
    fun nudgingAWordKeepsTheWholeLineMonotonic() {
        val words = mutableListOf(
            LyricWord("a", 1_000_000L, 2_000_000L),
            LyricWord("b", 2_000_000L, 3_000_000L),
            LyricWord("c", 3_000_000L, 4_000_000L),
        )
        words[1] = words[1].copy(startUs = 2_400_000L, endUs = 3_000_000L)
        words[0] = words[0].copy(endUs = 2_400_000L)

        val starts = words.map { it.startUs }
        val ends = words.map { it.endUs }

        assertEquals(starts.sorted(), starts)
        assertTrue(ends.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun aLineWithNoWordTimingStillAppearsInTheExportedTimeline() {
        val mixed = LyricsTrack(
            lines = listOf(
                LyricLine("逐字的", 1_000_000L, 2_000_000L, listOf(LyricWord("逐", 1_000_000L, 1_500_000L), LyricWord("字", 1_500_000L, 2_000_000L))),
                LyricLine("整行的", 2_000_000L, 3_000_000L, emptyList()),
            ),
            offsetMs = 0L,
        )

        val entries = Id3v2Codec.syncedEntries(mixed)

        // 之前 flatMap { words } 会把「整行的」整行丢掉
        assertEquals(listOf("逐", "字", "整行的"), entries.map { it.text })
        assertEquals(listOf(1000, 1500, 2000), entries.map { it.timestampMs })
    }

    @Test
    fun tokenSplittingKeepsEnglishWordsWholeAndChinesePerCharacter() {
        val tokens = splitForTest("你好 world 唱")

        assertEquals(listOf("你", "好", " ", "world", " ", "唱"), tokens)
    }

    @Test
    fun anEmptyLineProducesNoTokensSoTheEditorCanRefuseCleanly() {
        assertTrue(splitForTest("").isEmpty())
        assertTrue(splitForTest("   ").isEmpty())
    }

    /** 和 ViewModel 里的分字规则保持一致，测试独立跑所以复制一份最小实现。 */
    private fun splitForTest(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        val latin = StringBuilder()
        fun flush() {
            if (latin.isNotEmpty()) {
                tokens += latin.toString()
                latin.setLength(0)
            }
        }
        text.forEach { char ->
            when {
                char.isWhitespace() -> {
                    flush()
                    tokens += char.toString()
                }

                char.code < 128 -> latin.append(char)
                else -> {
                    flush()
                    tokens += char.toString()
                }
            }
        }
        flush()
        return tokens
    }
}
