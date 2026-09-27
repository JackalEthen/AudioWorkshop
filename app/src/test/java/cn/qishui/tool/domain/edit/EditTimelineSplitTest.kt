package cn.qishui.tool.domain.edit

import cn.qishui.tool.domain.model.EditMode
import cn.qishui.tool.domain.model.EditTimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditTimelineSplitTest {

    @Test
    fun singleCutProducesTwoNonEmptySegments() {
        val ranges = EditTimelineCalculator.splitIntervals(1_000_000L, listOf(500_000L))
        assertEquals(listOf(EditTimeRange(0L, 500_000L), EditTimeRange(500_000L, 1_000_000L)), ranges)
    }

    @Test
    fun multipleCutsProduceOrderedNonEmptySegments() {
        val ranges = EditTimelineCalculator.splitIntervals(1_000_000L, listOf(300_000L, 700_000L))
        assertEquals(
            listOf(
                EditTimeRange(0L, 300_000L),
                EditTimeRange(300_000L, 700_000L),
                EditTimeRange(700_000L, 1_000_000L),
            ),
            ranges,
        )
        assertTrue(ranges.all { it.endUs > it.startUs })
    }

    @Test
    fun unsortedAndDuplicateCutsAreNormalized() {
        val ranges = EditTimelineCalculator.splitIntervals(1_000L, listOf(700L, 300L, 300L))
        assertEquals(listOf(EditTimeRange(0L, 300L), EditTimeRange(300L, 700L), EditTimeRange(700L, 1_000L)), ranges)
    }

    @Test
    fun boundaryCutsAreDropped() {
        val ranges = EditTimelineCalculator.splitIntervals(1_000L, listOf(0L, 1_000L))
        assertEquals(listOf(EditTimeRange(0L, 1_000L)), ranges)
    }

    @Test
    fun outOfRangeCutsAreClampedAway() {
        val ranges = EditTimelineCalculator.splitIntervals(1_000L, listOf(-50L, 5_000L, 400L))
        assertEquals(listOf(EditTimeRange(0L, 400L), EditTimeRange(400L, 1_000L)), ranges)
    }

    @Test
    fun noCutsKeepsTheWholeTrack() {
        assertEquals(
            listOf(EditTimeRange(0L, 1_000L)),
            EditTimelineCalculator.splitIntervals(1_000L, emptyList()),
        )
    }

    @Test
    fun nonPositiveDurationProducesNoIntervals() {
        assertEquals(
            emptyList<EditTimeRange>(),
            EditTimelineCalculator.splitIntervals(0L, listOf(100L)),
        )
    }

    @Test
    fun keepSelectedWithCutsMergesAdjacentSplitIntervals() {
        val cuts = listOf(300_000L, 700_000L)
        val selections = EditTimelineCalculator.splitIntervals(1_000_000L, cuts)
        val segments = EditTimelineCalculator.segments(1_000_000L, selections, EditMode.KEEP_SELECTED)
        assertEquals(listOf(EditTimeRange(0L, 1_000_000L)), segments)
        assertEquals(1_000_000L, segments.sumOf { it.endUs - it.startUs })
    }

    @Test
    fun keepSelectedWithoutCutsProducesNoSegments() {
        val segments = EditTimelineCalculator.segments(
            durationUs = 1_000_000L,
            selections = emptyList(),
            mode = EditMode.KEEP_SELECTED,
        )
        assertTrue(segments.isEmpty())
    }

    @Test
    fun removeSelectedWithoutCutsKeepsTheWholeTrack() {
        val segments = EditTimelineCalculator.segments(
            durationUs = 1_000_000L,
            selections = emptyList(),
            mode = EditMode.REMOVE_SELECTED,
        )
        assertEquals(listOf(EditTimeRange(0L, 1_000_000L)), segments)
    }
}
