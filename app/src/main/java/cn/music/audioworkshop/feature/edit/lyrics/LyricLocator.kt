package cn.music.audioworkshop.feature.edit.lyrics

import cn.music.audioworkshop.domain.model.LyricLine

data class LyricCursor(
    val lineIndex: Int,
    val wordIndex: Int,
) {
    companion object {
        val NONE = LyricCursor(-1, -1)
    }
}

object LyricLocator {

    fun locate(lines: List<LyricLine>, positionUs: Long): LyricCursor {
        if (lines.isEmpty()) return LyricCursor.NONE
        val lineIndex = floorIndex(lines.size, { index -> lines[index].startUs }) { it <= positionUs }
        if (lineIndex < 0) return LyricCursor.NONE
        val words = lines[lineIndex].words
        if (words.isEmpty()) return LyricCursor(lineIndex, -1)
        val wordIndex = floorIndex(words.size, { index -> words[index].startUs }) { it <= positionUs }
        return LyricCursor(lineIndex, wordIndex)
    }

    private inline fun floorIndex(size: Int, startUs: (Int) -> Long, matches: (Long) -> Boolean): Int {
        var low = 0
        var high = size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (matches(startUs(mid))) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
