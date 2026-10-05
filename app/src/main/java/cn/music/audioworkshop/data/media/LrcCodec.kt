package cn.music.audioworkshop.data.media

import cn.music.audioworkshop.domain.model.LyricLine
import cn.music.audioworkshop.domain.model.LyricWord
import cn.music.audioworkshop.domain.model.LyricsTrack
import java.util.Locale

/**
 * 标准 LRC 读写：解析页导出用，编辑页导入用。
 * 逐字歌词写成行级时间戳 + 行内 `<mm:ss.xx>` 增强标签，导入时两种都认。
 */
object LrcCodec {

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val ENHANCED = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val META = Regex("""^\[[a-zA-Z]+:.*]$""")
    private const val FALLBACK_LINE_US = 5_000_000L

    fun toLrc(track: LyricsTrack): String = buildString {
        append("[re:qishui]\n")
        append("[by:音频工坊]\n")
        track.lines.sortedBy { it.startUs }.forEach { line ->
            append('[').append(timestamp(line.startUs)).append(']')
            if (line.words.isNotEmpty()) {
                line.words.forEach { word ->
                    append('<').append(timestamp(word.startUs)).append('>').append(word.text)
                }
            } else {
                append(line.text)
            }
            append('\n')
        }
    }

    fun parse(raw: String?): LyricsTrack {
        if (raw.isNullOrBlank()) return LyricsTrack.EMPTY
        val lines = mutableListOf<LyricLine>()
        raw.lineSequence().forEach { entry ->
            val line = entry.trim().trimEnd('\r')
            if (line.isBlank() || META.matches(line)) return@forEach
            val stamps = TIMESTAMP.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val body = line.substring(stamps.last().range.last + 1)
            stamps.forEach { stamp ->
                val startUs = millisOf(stamp) * 1000L
                val words = parseEnhanced(body)
                lines += if (words.isNotEmpty()) {
                    LyricLine(
                        text = words.joinToString("") { it.text },
                        startUs = startUs,
                        endUs = words.last().endUs,
                        words = words,
                    )
                } else {
                    LyricLine(
                        text = body.trim(),
                        startUs = startUs,
                        endUs = startUs + FALLBACK_LINE_US,
                        words = emptyList(),
                    )
                }
            }
        }
        return LyricsTrack(lines.sortedBy { it.startUs }, 0L)
    }

    private fun parseEnhanced(body: String): List<LyricWord> {
        val matches = ENHANCED.findAll(body).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexed { index, match ->
            val startUs = millisOf(match) * 1000L
            val textStart = match.range.last + 1
            val textEnd = matches.getOrNull(index + 1)?.range?.first ?: body.length
            val endUs = matches.getOrNull(index + 1)?.let { millisOf(it) * 1000L }
                ?: (startUs + 1_000_000L)
            LyricWord(text = body.substring(textStart, textEnd), startUs = startUs, endUs = endUs)
        }
    }

    private fun millisOf(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3]
        val millis = when (fraction.length) {
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            3 -> fraction.take(3).toLong()
            else -> 0L
        }
        return (minutes * 60_000L) + (seconds * 1000L) + millis
    }

    private fun timestamp(timeUs: Long): String {
        val totalMs = timeUs / 1000L
        return String.format(
            Locale.US,
            "%02d:%02d.%03d",
            totalMs / 60_000L,
            (totalMs / 1000L) % 60L,
            totalMs % 1000L,
        )
    }
}

