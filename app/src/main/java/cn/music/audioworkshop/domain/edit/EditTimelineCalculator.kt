package cn.music.audioworkshop.domain.edit

import cn.music.audioworkshop.domain.model.EditMode
import cn.music.audioworkshop.domain.model.EditTimeRange

object EditTimelineCalculator {

    fun segments(
        durationUs: Long,
        selections: List<EditTimeRange>,
        mode: EditMode,
    ): List<EditTimeRange> {
        if (durationUs <= 0L) return emptyList()
        val merged = merge(selections.normalizeAgainst(durationUs))
        return when (mode) {
            EditMode.KEEP_SELECTED -> merged
            EditMode.REMOVE_SELECTED -> complement(merged, durationUs)
        }
    }

    private fun List<EditTimeRange>.normalizeAgainst(durationUs: Long): List<EditTimeRange> = mapNotNull { range ->
        val start = range.startUs.coerceIn(0L, durationUs)
        val end = range.endUs.coerceIn(0L, durationUs)
        val low = minOf(start, end)
        val high = maxOf(start, end)
        if (high > low) EditTimeRange(low, high) else null
    }

    private fun merge(selections: List<EditTimeRange>): List<EditTimeRange> {
        if (selections.isEmpty()) return emptyList()
        val sorted = selections.sortedWith(compareBy({ it.startUs }, { it.endUs }))
        val merged = mutableListOf(EditTimeRange(sorted.first().startUs, sorted.first().endUs))
        for (range in sorted.drop(1)) {
            val last = merged.last()
            if (range.startUs <= last.endUs) {
                merged[merged.lastIndex] = last.copy(endUs = maxOf(last.endUs, range.endUs))
            } else {
                merged += range
            }
        }
        return merged
    }

    private fun complement(merged: List<EditTimeRange>, durationUs: Long): List<EditTimeRange> {
        if (merged.isEmpty()) return listOf(EditTimeRange(0L, durationUs))
        val kept = mutableListOf<EditTimeRange>()
        var cursor = 0L
        for (range in merged) {
            if (range.startUs > cursor) kept += EditTimeRange(cursor, range.startUs)
            cursor = maxOf(cursor, range.endUs)
        }
        if (cursor < durationUs) kept += EditTimeRange(cursor, durationUs)
        return kept
    }
}
