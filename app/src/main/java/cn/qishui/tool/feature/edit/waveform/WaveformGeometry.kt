package cn.qishui.tool.feature.edit.waveform

enum class WaveformDragTarget {
    START_HANDLE,
    END_HANDLE,
    SELECTION,
    SEEK,
}

data class WaveformSelection(
    val startUs: Long,
    val endUs: Long,
) {
    val isEmpty: Boolean
        get() = endUs <= startUs
}

object WaveformGeometry {

    const val MIN_SELECTION_US = 1_000L

    fun timeToX(timeUs: Long, durationUs: Long, widthPx: Float): Float {
        if (durationUs <= 0L || widthPx <= 0f) return 0f
        return (timeUs.coerceIn(0L, durationUs).toFloat() / durationUs) * widthPx
    }

    fun xToTime(xPx: Float, durationUs: Long, widthPx: Float): Long {
        if (durationUs <= 0L || widthPx <= 0f) return 0L
        val ratio = (xPx / widthPx).coerceIn(0f, 1f)
        return (ratio * durationUs).toLong().coerceIn(0L, durationUs)
    }

    fun hitTest(
        xPx: Float,
        widthPx: Float,
        handleHitPx: Float,
        selection: WaveformSelection,
        durationUs: Long,
    ): WaveformDragTarget {
        if (durationUs <= 0L || widthPx <= 0f) return WaveformDragTarget.SEEK
        val startX = timeToX(selection.startUs, durationUs, widthPx)
        val endX = timeToX(selection.endUs, durationUs, widthPx)
        return when {
            kotlin.math.abs(xPx - startX) <= handleHitPx -> WaveformDragTarget.START_HANDLE
            kotlin.math.abs(xPx - endX) <= handleHitPx -> WaveformDragTarget.END_HANDLE
            xPx > startX && xPx < endX -> WaveformDragTarget.SELECTION
            else -> WaveformDragTarget.SEEK
        }
    }

    fun seekTo(xPx: Float, widthPx: Float, durationUs: Long): Long = xToTime(xPx, durationUs, widthPx)

    fun dragHandle(
        target: WaveformDragTarget,
        xPx: Float,
        widthPx: Float,
        selection: WaveformSelection,
        durationUs: Long,
    ): WaveformSelection {
        val raw = xToTime(xPx, durationUs, widthPx)
        return when (target) {
            WaveformDragTarget.START_HANDLE -> normalize(raw.coerceAtMost(selection.endUs), selection.endUs, durationUs)
            else -> normalize(selection.startUs, raw.coerceAtLeast(selection.startUs), durationUs)
        }
    }

    fun dragSelection(deltaPx: Float, widthPx: Float, selection: WaveformSelection, durationUs: Long): WaveformSelection {
        if (durationUs <= 0L || widthPx <= 0f) return selection
        val spanUs = selection.endUs - selection.startUs
        val deltaUs = ((deltaPx / widthPx) * durationUs).toLong()
        val startUs = (selection.startUs + deltaUs).coerceIn(0L, (durationUs - spanUs).coerceAtLeast(0L))
        return WaveformSelection(startUs, startUs + spanUs)
    }

    fun fullSelection(durationUs: Long): WaveformSelection = WaveformSelection(0L, durationUs.coerceAtLeast(0L))

    fun normalize(startUs: Long, endUs: Long, durationUs: Long): WaveformSelection {
        val limit = durationUs.coerceAtLeast(0L)
        val low = minOf(startUs, endUs).coerceIn(0L, limit)
        val high = maxOf(startUs, endUs).coerceIn(0L, limit)
        if (limit < MIN_SELECTION_US || high - low >= MIN_SELECTION_US) return WaveformSelection(low, high)
        return if (low + MIN_SELECTION_US <= limit) {
            WaveformSelection(low, low + MIN_SELECTION_US)
        } else {
            WaveformSelection(limit - MIN_SELECTION_US, limit)
        }
    }
}
