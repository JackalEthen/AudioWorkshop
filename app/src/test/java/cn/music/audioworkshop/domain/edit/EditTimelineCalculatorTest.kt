package cn.music.audioworkshop.domain.edit

import cn.music.audioworkshop.domain.model.EditMode
import cn.music.audioworkshop.domain.model.EditTimeRange
import org.junit.Assert.assertEquals
import org.junit.Test

class EditTimelineCalculatorTest {

    @Test
    fun keepSelectedReturnsMergedSortedRanges() {
        val segments = EditTimelineCalculator.segments(
            durationUs = 1_000L,
            selections = listOf(
                EditTimeRange(600L, 800L),
                EditTimeRange(0L, 200L),
                EditTimeRange(150L, 250L),
            ),
            mode = EditMode.KEEP_SELECTED,
        )

        assertEquals(
            listOf(EditTimeRange(0L, 250L), EditTimeRange(600L, 800L)),
            segments,
        )
    }

    @Test
    fun keepSelectedMergesAdjacentRanges() {
        val segments = EditTimelineCalculator.segments(
            durationUs = 1_000L,
            selections = listOf(EditTimeRange(0L, 400L), EditTimeRange(400L, 700L)),
            mode = EditMode.KEEP_SELECTED,
        )

        assertEquals(listOf(EditTimeRange(0L, 700L)), segments)
    }

    @Test
    fun keepSelectedClampsInvertedAndOutOfRangeSelections() {
        val segments = EditTimelineCalculator.segments(
            durationUs = 1_000L,
            selections = listOf(
                EditTimeRange(800L, 300L),
                EditTimeRange(-50L, 200L),
                EditTimeRange(900L, 5_000L),
                EditTimeRange(400L, 400L),
            ),
            mode = EditMode.KEEP_SELECTED,
        )

        assertEquals(
            listOf(EditTimeRange(0L, 200L), EditTimeRange(300L, 800L), EditTimeRange(900L, 1_000L)),
            segments,
        )
    }

    @Test
    fun keepSelectedWithoutSelectionKeepsNothing() {
        val segments = EditTimelineCalculator.segments(
            durationUs = 1_000L,
            selections = emptyList(),
            mode = EditMode.KEEP_SELECTED,
        )

        assertEquals(emptyList<EditTimeRange>(), segments)
    }

    @Test
    fun removeSelectedSplitsAroundMiddleSelection() {
        val segments = EditTimelineCalculator.segments(
            durationUs = 1_000L,
            selections = listOf(EditTimeRange(400L, 600L)),
            mode = EditMode.REMOVE_SELECTED,
        )

        assertEquals(
            listOf(EditTimeRange(0L, 400L), EditTimeRange(600L, 1_000L)),
            segments,
        )
    }

    @Test
    fun removeSelectedTrimsHeadAndTail() {
        val segments = EditTimelineCalculator.segments(
            durationUs = 1_000L,
            selections = listOf(EditTimeRange(0L, 200L), EditTimeRange(800L, 1_000L)),
            mode = EditMode.REMOVE_SELECTED,
        )

        assertEquals(listOf(EditTimeRange(200L, 800L)), segments)
    }

    @Test
    fun removeSelectedWithoutSelectionKeepsWholeDuration() {
        val segments = EditTimelineCalculator.segments(
            durationUs = 1_000L,
            selections = emptyList(),
            mode = EditMode.REMOVE_SELECTED,
        )

        assertEquals(listOf(EditTimeRange(0L, 1_000L)), segments)
    }

    @Test
    fun removeSelectedCoveringWholeDurationKeepsNothing() {
        val segments = EditTimelineCalculator.segments(
            durationUs = 1_000L,
            selections = listOf(EditTimeRange(-10L, 1_000L)),
            mode = EditMode.REMOVE_SELECTED,
        )

        assertEquals(emptyList<EditTimeRange>(), segments)
    }

    @Test
    fun nonPositiveDurationProducesNoSegments() {
        assertEquals(
            emptyList<EditTimeRange>(),
            EditTimelineCalculator.segments(0L, listOf(EditTimeRange(0L, 100L)), EditMode.KEEP_SELECTED),
        )
        assertEquals(
            emptyList<EditTimeRange>(),
            EditTimelineCalculator.segments(0L, listOf(EditTimeRange(0L, 100L)), EditMode.REMOVE_SELECTED),
        )
    }
}
