package cn.music.audioworkshop.feature.edit.waveform

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformGeometryTest {

    @Test
    fun timeToXMapsDurationAcrossWidth() {
        assertEquals(0f, WaveformGeometry.timeToX(0L, 1_000L, 400f), 0.01f)
        assertEquals(200f, WaveformGeometry.timeToX(500L, 1_000L, 400f), 0.01f)
        assertEquals(400f, WaveformGeometry.timeToX(1_000L, 1_000L, 400f), 0.01f)
    }

    @Test
    fun timeToXClampsOutOfRangeAndDegenerateInput() {
        assertEquals(400f, WaveformGeometry.timeToX(5_000L, 1_000L, 400f), 0.01f)
        assertEquals(0f, WaveformGeometry.timeToX(-100L, 1_000L, 400f), 0.01f)
        assertEquals(0f, WaveformGeometry.timeToX(500L, 0L, 400f), 0.01f)
        assertEquals(0f, WaveformGeometry.timeToX(500L, 1_000L, 0f), 0.01f)
    }

    @Test
    fun xToTimeClampsAndIgnoresDegenerateInput() {
        assertEquals(250L, WaveformGeometry.xToTime(100f, 1_000L, 400f))
        assertEquals(0L, WaveformGeometry.xToTime(-40f, 1_000L, 400f))
        assertEquals(1_000L, WaveformGeometry.xToTime(900f, 1_000L, 400f))
        assertEquals(0L, WaveformGeometry.xToTime(100f, 0L, 400f))
    }

    @Test
    fun hitTestDistinguishesHandlesSelectionAndTrack() {
        val selection = WaveformSelection(20_000L, 60_000L)
        assertEquals(
            WaveformDragTarget.START_HANDLE,
            WaveformGeometry.hitTest(60f, 400f, 20f, selection, DURATION),
        )
        assertEquals(
            WaveformDragTarget.END_HANDLE,
            WaveformGeometry.hitTest(250f, 400f, 20f, selection, DURATION),
        )
        assertEquals(
            WaveformDragTarget.SELECTION,
            WaveformGeometry.hitTest(160f, 400f, 20f, selection, DURATION),
        )
        assertEquals(
            WaveformDragTarget.SEEK,
            WaveformGeometry.hitTest(20f, 400f, 20f, selection, DURATION),
        )
    }

    @Test
    fun hitTestOnCollapsedSelectionOnlyHitsTheStartHandle() {
        val collapsed = WaveformSelection(50_000L, 50_000L)
        assertEquals(
            WaveformDragTarget.START_HANDLE,
            WaveformGeometry.hitTest(200f, 400f, 20f, collapsed, DURATION),
        )
        assertEquals(
            WaveformDragTarget.SEEK,
            WaveformGeometry.hitTest(20f, 400f, 20f, collapsed, DURATION),
        )
    }

    @Test
    fun draggingStartHandlePastEndClampsToEnd() {
        val result = WaveformGeometry.dragHandle(
            target = WaveformDragTarget.START_HANDLE,
            xPx = 300f,
            widthPx = 400f,
            selection = WaveformSelection(20_000L, 60_000L),
            durationUs = DURATION,
        )
        assertEquals(WaveformSelection(60_000L, 60_000L + WaveformGeometry.MIN_SELECTION_US), result)
    }

    @Test
    fun draggingEndHandlePastStartClampsToStart() {
        val result = WaveformGeometry.dragHandle(
            target = WaveformDragTarget.END_HANDLE,
            xPx = 10f,
            widthPx = 400f,
            selection = WaveformSelection(20_000L, 60_000L),
            durationUs = DURATION,
        )
        assertEquals(WaveformSelection(20_000L, 20_000L + WaveformGeometry.MIN_SELECTION_US), result)
    }

    @Test
    fun draggingEndHandleExtendsToDuration() {
        val result = WaveformGeometry.dragHandle(
            target = WaveformDragTarget.END_HANDLE,
            xPx = 420f,
            widthPx = 400f,
            selection = WaveformSelection(20_000L, 60_000L),
            durationUs = DURATION,
        )
        assertEquals(WaveformSelection(20_000L, DURATION), result)
    }

    @Test
    fun draggingSelectionPreservesSpanAndClampsToDuration() {
        val selection = WaveformSelection(20_000L, 60_000L)
        assertEquals(
            WaveformSelection(60_000L, DURATION),
            WaveformGeometry.dragSelection(400f, 400f, selection, DURATION),
        )
        assertEquals(
            WaveformSelection(0L, 40_000L),
            WaveformGeometry.dragSelection(-400f, 400f, selection, DURATION),
        )
    }

    @Test
    fun draggingSelectionIsNoOpWithoutDuration() {
        val selection = WaveformSelection(20_000L, 60_000L)
        assertEquals(selection, WaveformGeometry.dragSelection(50f, 400f, selection, 0L))
    }

    @Test
    fun normalizeEnforcesMinimumSelectionInsideDuration() {
        assertEquals(
            WaveformSelection(0L, WaveformGeometry.MIN_SELECTION_US),
            WaveformGeometry.normalize(0L, 0L, DURATION),
        )
        assertEquals(
            WaveformSelection(DURATION - WaveformGeometry.MIN_SELECTION_US, DURATION),
            WaveformGeometry.normalize(DURATION, DURATION, DURATION),
        )
    }

    @Test
    fun normalizeOrdersAndClampsBothEnds() {
        assertEquals(
            WaveformSelection(0L, DURATION),
            WaveformGeometry.normalize(9_999_999L, -500L, DURATION),
        )
    }

    @Test
    fun normalizeKeepsUndersizedDurationUntouched() {
        assertEquals(
            WaveformSelection(0L, 0L),
            WaveformGeometry.normalize(0L, 0L, 10L),
        )
    }

    @Test
    fun fullSelectionCoversDuration() {
        assertEquals(WaveformSelection(0L, DURATION), WaveformGeometry.fullSelection(DURATION))
        assertEquals(WaveformSelection(0L, 0L), WaveformGeometry.fullSelection(0L))
    }

    private companion object {
        const val DURATION = 100_000L
    }
}
