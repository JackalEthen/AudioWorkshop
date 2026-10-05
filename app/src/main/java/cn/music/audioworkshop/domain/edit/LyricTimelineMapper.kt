package cn.music.audioworkshop.domain.edit

import cn.music.audioworkshop.domain.model.EditMode
import cn.music.audioworkshop.domain.model.EditTimeRange
import cn.music.audioworkshop.domain.model.EditTimeSegment
import cn.music.audioworkshop.domain.model.LyricLine
import cn.music.audioworkshop.domain.model.LyricWord
import cn.music.audioworkshop.domain.model.LyricsTrack

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
        speed: Float = 1f,
    ): LyricEditTimeline {
        val effectiveOffsetMs = lyrics.offsetMs + lyricOffsetMs
        val shiftUs = effectiveOffsetMs * 1000L
        // 变速时音频被压缩/拉长，歌词时间戳必须除以同一个倍率，否则逐句漂移。
        // 斜坡和空白不跟着缩，所以这里只处理音频区间本身。
        val safeSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        var cursorUs = 0L
        val segments = EditTimelineCalculator.segments(durationUs, selections, mode).map { range ->
            val outputLengthUs = ((range.endUs - range.startUs) / safeSpeed).toLong()
            EditTimeSegment(
                sourceStartUs = range.startUs,
                sourceEndUs = range.endUs,
                outputStartUs = cursorUs,
                outputEndUs = cursorUs + outputLengthUs,
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
                // 用段自身的压缩比例而不是直接除 speed：这样变速之外的场景
                // （round 误差、空区间）也按 segment 的实际输出长度走。
                val scale = (segment.outputEndUs - segment.outputStartUs).toDouble() /
                    (segment.sourceEndUs - segment.sourceStartUs)
                mapped += LyricWord(
                    text = unit.text,
                    startUs = segment.outputStartUs + ((from - segment.sourceStartUs) * scale).toLong() + shiftUs,
                    endUs = segment.outputStartUs + ((to - segment.sourceStartUs) * scale).toLong() + shiftUs,
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
