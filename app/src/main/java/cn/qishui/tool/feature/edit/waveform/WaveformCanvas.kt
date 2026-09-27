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
import androidx.compose.ui.graphics.drawscope.clipRect
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
    val trackColor = Color(0xFF3A3550)
    val dimColor = Color.Black.copy(alpha = 0.55f)
    val handleColor = MaterialTheme.colorScheme.primary
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
        // DrawScope 默认不裁剪，波形会画到画布外，先夹住
        clipRect(left = 0f, top = 0f, right = width, bottom = size.height) {
        // 深色底 + 圆角，选中区才对比得出来
        drawRoundRect(
            color = trackColor,
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
        )
        drawPeaks(peaks, waveColor)
        val startX = WaveformGeometry.timeToX(selection.startUs, durationUs, width)
        val endX = WaveformGeometry.timeToX(selection.endUs, durationUs, width)
        // 选区外压暗
        drawRect(color = dimColor, size = Size(startX.coerceIn(0f, width), size.height))
        drawRect(
            color = dimColor,
            topLeft = Offset(endX.coerceIn(0f, width), 0f),
            size = Size((width - endX).coerceAtLeast(0f), size.height),
        )
        // 选区两端的手柄：竖线 + 顶部圆点 + 底部圆点
        drawHandle(startX, handleColor)
        drawHandle(endX, handleColor)
        val playheadX = WaveformGeometry.timeToX(positionUs, durationUs, width)
        drawRect(
            color = playheadColor,
            topLeft = Offset(playheadX.coerceIn(0f, (width - PLAYHEAD_WIDTH_PX).coerceAtLeast(0f)), 0f),
            size = Size(PLAYHEAD_WIDTH_PX, size.height),
        )
        }
    }
}

private fun DrawScope.drawHandle(x: Float, color: Color) {
    val cx = x.coerceIn(HANDLE_DOT_R, size.width - HANDLE_DOT_R)
    drawLine(
        color = color,
        start = Offset(cx, 0f),
        end = Offset(cx, size.height),
        strokeWidth = HANDLE_LINE_PX,
    )
    drawCircle(color = color, radius = HANDLE_DOT_R, center = Offset(cx, HANDLE_DOT_R))
    drawCircle(color = color, radius = HANDLE_DOT_R, center = Offset(cx, size.height - HANDLE_DOT_R))
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
private const val HANDLE_DOT_R = 7f
private const val HANDLE_LINE_PX = 2f
private const val PLAYHEAD_WIDTH_PX = 3f
private const val MIN_BAR_PX = 2f
