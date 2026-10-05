package cn.music.audioworkshop.data.media

import cn.music.audioworkshop.domain.lyrics.LyricsParser
import cn.music.audioworkshop.domain.model.LyricLine
import cn.music.audioworkshop.domain.model.LyricWord
import cn.music.audioworkshop.domain.model.LyricsTrack

class QsmusicLyricParser : LyricsParser {

    override fun parse(raw: String?): LyricsTrack {
        if (raw.isNullOrBlank()) return LyricsTrack(emptyList(), 0L)
        val lines = raw.split('\n').mapNotNull { line -> parseLine(line.trimEnd('\r')) }
        return LyricsTrack(lines, 0L)
    }

    private fun parseLine(line: String): LyricLine? {
        if (line.isBlank()) return null
        if (!line.startsWith(HEADER_OPEN) || !line.contains(HEADER_CLOSE)) return null
        val headerEnd = line.indexOf(HEADER_CLOSE)
        val header = line.substring(1, headerEnd).split(',')
        if (header.size != 2) return null
        val lineStartMs = header[0].trim().toLongOrNull() ?: return null
        val lineDurationMs = header[1].trim().toLongOrNull() ?: return null
        if (lineStartMs < 0L || lineDurationMs < 0L) return null
        val words = parseWords(line.substring(headerEnd + 1), lineStartMs * 1000L) ?: return null
        if (words.isEmpty()) return null
        return LyricLine(
            text = words.joinToString("") { it.text },
            startUs = lineStartMs * 1000L,
            endUs = (lineStartMs + lineDurationMs) * 1000L,
            words = words,
        )
    }

    private fun parseWords(body: String, lineStartUs: Long): List<LyricWord>? {
        val words = mutableListOf<LyricWord>()
        var cursor = 0
        while (cursor < body.length) {
            val open = body.indexOf('<', cursor)
            if (open < 0) break
            val close = body.indexOf('>', open + 1)
            if (close < 0) break
            val timing = parseWordTiming(body.substring(open + 1, close)) ?: return null
            val nextOpen = body.indexOf('<', close + 1)
            val text = body.substring(close + 1, if (nextOpen < 0) body.length else nextOpen)
            val startUs = lineStartUs + timing.first * 1000L
            words += LyricWord(text = text, startUs = startUs, endUs = startUs + timing.second * 1000L)
            cursor = close + 1
        }
        return words
    }

    private fun parseWordTiming(raw: String): Pair<Long, Long>? {
        val parts = raw.split(',')
        if (parts.size != 3) return null
        val relativeMs = parts[0].trim().toLongOrNull() ?: return null
        val durationMs = parts[1].trim().toLongOrNull() ?: return null
        if (durationMs < 0L || parts[2].trim().toIntOrNull() == null) return null
        return relativeMs to durationMs
    }

    private companion object {
        const val HEADER_OPEN = '['
        const val HEADER_CLOSE = ']'
    }
}
