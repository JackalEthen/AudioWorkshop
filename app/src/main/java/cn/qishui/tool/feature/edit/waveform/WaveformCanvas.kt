package cn.qishui.tool.feature.edit.waveform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cn.qishui.tool.domain.model.WaveformPeaks
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

@Composable
fun WaveformCanvas(
    peaks: WaveformPeaks?,
    durationUs: Long,
    selection: WaveformSelection,
    positionUs: Long,
    onSelectionChange: (WaveformSelection) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val handleHitPx = with(LocalDensity.current) { HANDLE_TOUCH_TARGET.toPx() / 2f }
    val currentSelection by rememberUpdatedState(selection)
    val currentSelectionChange by rememberUpdatedState(onSelectionChange)
    val currentSeek by rememberUpdatedState(onSeek)

    if (peaks == null || peaks.min.isEmpty() || durationUs <= 0L) {
        WaveformSkeleton(modifier)
        return
    }

    val waveColor = MaterialTheme.colorScheme.primary
    val dimColor = Color.Black.copy(alpha = 0.45f)
    val handleColor = MaterialTheme.colorScheme.onPrimary
    val playheadColor = MaterialTheme.colorScheme.tertiary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(CANVAS_HEIGHT)
            .pointerInput(durationUs) {
                awaitEachGesture {
                    val width = size.width.toFloat()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val target = WaveformGeometry.hitTest(
                        xPx = down.position.x,
                        widthPx = width,
                        handleHitPx = handleHitPx,
                        selection = currentSelection,
                        durationUs = durationUs,
                    )
                    var pointerId = down.id
                    var lastX = down.position.x
                    if (target == WaveformDragTarget.SEEK) {
                        currentSeek(WaveformGeometry.seekTo(down.position.x, width, durationUs))
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val x = change.position.x
                        when (target) {
                            WaveformDragTarget.SEEK ->
                                currentSeek(WaveformGeometry.seekTo(x, width, durationUs))

                            WaveformDragTarget.SELECTION -> currentSelectionChange(
                                WaveformGeometry.dragSelection(
                                    deltaPx = x - lastX,
                                    widthPx = width,
                                    selection = currentSelection,
                                    durationUs = durationUs,
                                ),
                            )

                            else -> currentSelectionChange(
                                WaveformGeometry.dragHandle(
                                    target = target,
                                    xPx = x,
                                    widthPx = width,
                                    selection = currentSelection,
                                    durationUs = durationUs,
                                ),
                            )
                        }
                        lastX = x
                        change.consume()
                    }
                }
            },
    ) {
        val width = size.width
        if (width <= 0f) return@Canvas
        drawPeaks(peaks, waveColor)
        val startX = WaveformGeometry.timeToX(selection.startUs, durationUs, width)
        val endX = WaveformGeometry.timeToX(selection.endUs, durationUs, width)
        drawRect(color = dimColor, size = Size(startX.coerceIn(0f, width), size.height))
        drawRect(
            color = dimColor,
            topLeft = Offset(endX.coerceIn(0f, width), 0f),
            size = Size((width - endX).coerceAtLeast(0f), size.height),
        )
        val playheadX = WaveformGeometry.timeToX(positionUs, durationUs, width)
        drawRect(
            color = playheadColor,
            topLeft = Offset(playheadX.coerceIn(0f, (width - PLAYHEAD_WIDTH_PX).coerceAtLeast(0f)), 0f),
            size = Size(PLAYHEAD_WIDTH_PX, size.height),
        )
    }
}

@Composable
private fun WaveformSkeleton(modifier: Modifier) {
    val barColor = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = modifier.fillMaxWidth().height(CANVAS_HEIGHT)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(CANVAS_HEIGHT)) {
            val barHeight = 4.dp.toPx()
            val gap = size.width / 7f
            var x = gap
            while (x + gap < size.width) {
                drawRect(
                    color = barColor,
                    topLeft = Offset(x, size.height / 2f - barHeight / 2f),
                    size = Size(gap * 2f, barHeight),
                )
                x += gap * 3f
            }
        }
    }
}

private fun DrawScope.drawPeaks(peaks: WaveformPeaks, waveColor: Color) {
    val bucketCount = minOf(peaks.min.size, peaks.max.size)
    if (bucketCount <= 0) return
    val centerY = size.height / 2f
    val step = max(1, ceil(bucketCount / size.width.coerceAtLeast(1f)).toInt())
    val bucketWidth = (size.width / ceil(bucketCount / step.toFloat()).coerceAtLeast(1f)).coerceAtLeast(1f)
    var index = 0
    while (index < bucketCount) {
        var low = peaks.max[index]
        var high = peaks.min[index]
        var cursor = index + 1
        while (cursor < bucketCount && cursor < index + step) {
            if (peaks.max[cursor] > low) low = peaks.max[cursor]
            if (peaks.min[cursor] < high) high = peaks.min[cursor]
            cursor++
        }
        index += step
        val barHeight = abs((low - high) * centerY).coerceAtLeast(MIN_BAR_PX)
        drawRect(
            color = waveColor,
            topLeft = Offset(index * bucketWidth, centerY - barHeight / 2f),
            size = Size(bucketWidth, barHeight),
        )
    }
}

private val CANVAS_HEIGHT = 120.dp
private val HANDLE_TOUCH_TARGET = 48.dp
private const val HANDLE_WIDTH_PX = 3f
private const val PLAYHEAD_WIDTH_PX = 3f
private const val MIN_BAR_PX = 2f
