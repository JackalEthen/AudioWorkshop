package cn.qishui.tool.domain.edit

import cn.qishui.tool.domain.model.EditMode
import cn.qishui.tool.domain.model.EditTimeRange
import cn.qishui.tool.domain.model.EditTimeSegment
import cn.qishui.tool.domain.model.LyricLine
import cn.qishui.tool.domain.model.LyricWord
import cn.qishui.tool.domain.model.LyricsTrack

data class LyricEditTimeline(
    val segments: List<EditTimeSegment>,
    val lyricOffsetMs: Long,
    val lines: List<LyricLine>,
)

object LyricTimelineMapper {

    fun map(
        lyrics: LyricsTrack,
        durationUs: Long,
        selections: List<EditTimeRange>,
        mode: EditMode,
        lyricOffsetMs: Long = 0L,
    ): LyricEditTimeline {
        val effectiveOffsetMs = lyrics.offsetMs + lyricOffsetMs
        val shiftUs = effectiveOffsetMs * 1000L
        var cursorUs = 0L
        val segments = EditTimelineCalculator.segments(durationUs, selections, mode).map { range ->
            EditTimeSegment(
                sourceStartUs = range.startUs,
                sourceEndUs = range.endUs,
                outputStartUs = cursorUs,
                outputEndUs = cursorUs + (range.endUs - range.startUs),
            ).also { cursorUs = it.outputEndUs }
        }
        val lines = lyrics.lines
            .mapNotNull { line -> mapLine(line, segments, shiftUs) }
            .sortedWith(compareBy({ it.startUs }, { it.endUs }))
        return LyricEditTimeline(segments, effectiveOffsetMs, lines)
    }

    private fun mapLine(line: LyricLine, segments: List<EditTimeSegment>, shiftUs: Long): LyricLine? {
        val units = if (line.words.isEmpty()) listOf(LyricWord(line.text, line.startUs, line.endUs)) else line.words
        val mapped = mutableListOf<LyricWord>()
        for (unit in units) {
            for (segment in segments) {
                val from = maxOf(unit.startUs, segment.sourceStartUs)
                val to = minOf(unit.endUs, segment.sourceEndUs)
                if (to <= from) continue
                mapped += LyricWord(
                    text = unit.text,
                    startUs = segment.outputStartUs + (from - segment.sourceStartUs) + shiftUs,
                    endUs = segment.outputStartUs + (to - segment.sourceStartUs) + shiftUs,
                )
            }
        }
        if (mapped.isEmpty()) return null
        val ordered = mapped.sortedWith(compareBy({ it.startUs }, { it.endUs }))
        return LyricLine(
            text = ordered.joinToString("") { it.text },
            startUs = ordered.first().startUs,
            endUs = ordered.last().endUs,
            words = ordered,
        )
    }
}
