package cn.music.audioworkshop.feature.edit.waveform

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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cn.music.audioworkshop.domain.model.WaveformPeaks
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
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val waveMuted = waveColor.copy(alpha = 0.28f)
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
        val pinSpace = PIN_SPACE.toPx()
        val blockTop = pinSpace
        val blockHeight = (size.height - pinSpace * 2f).coerceAtLeast(1f)
        // DrawScope 默认不裁剪，波形会画到画布外，先夹住
        clipRect(left = 0f, top = 0f, right = width, bottom = size.height) {
        // 浅色底 + 圆角，选中区才对比得出来
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, blockTop),
            size = Size(width, blockHeight),
            cornerRadius = CornerRadius(8.dp.toPx()),
        )
        // 选区外的波形画淡一层，再压一层深色蒙版。
        // 蒙版要够深才看得出「这段没选中」——太浅时和选区几乎分不开。
        drawPeaks(peaks, waveMuted, blockTop, blockHeight)
        val startX = WaveformGeometry.timeToX(selection.startUs, durationUs, width)
        val endX = WaveformGeometry.timeToX(selection.endUs, durationUs, width)
        drawRect(
            color = trackColor.copy(alpha = SELECTION_MASK_ALPHA),
            topLeft = Offset(0f, blockTop),
            size = Size(startX.coerceIn(0f, width), blockHeight),
        )
        drawRect(
            color = trackColor.copy(alpha = SELECTION_MASK_ALPHA),
            topLeft = Offset(endX.coerceIn(0f, width), blockTop),
            size = Size((width - endX).coerceAtLeast(0f), blockHeight),
        )
        clipRect(
            left = startX.coerceIn(0f, width),
            top = blockTop,
            right = endX.coerceIn(0f, width),
            bottom = blockTop + blockHeight,
        ) {
            drawPeaks(peaks, waveColor, blockTop, blockHeight)
        }
        // 起点手柄在波形下方，终点在波形上方，都是水滴形
        drawHandle(startX, handleColor, blockTop, blockHeight, below = true)
        drawHandle(endX, handleColor, blockTop, blockHeight, below = false)
        val playheadX = WaveformGeometry.timeToX(positionUs, durationUs, width)
        drawRect(
            color = playheadColor,
            topLeft = Offset(playheadX.coerceIn(0f, (width - PLAYHEAD_WIDTH_PX).coerceAtLeast(0f)), blockTop),
            size = Size(PLAYHEAD_WIDTH_PX, blockHeight),
        )
        }
    }
}

private fun DrawScope.drawHandle(
    x: Float,
    color: Color,
    blockTop: Float,
    blockHeight: Float,
    below: Boolean,
) {
    val r = PIN_RADIUS.toPx()
    val cx = x.coerceIn(r, (size.width - r).coerceAtLeast(r))
    // 只在竖线上做「贴边就不画」：选区顶到两端（全选）时那条线会贴着画布边缘，
    // 画出来就是一条无意义的蓝边。
    // 水滴必须留着 —— 那才是手柄本体，全选时用户仍要看得见能拖。
    if (x > 0f && x < size.width) {
        drawLine(
            color = color,
            start = Offset(cx, blockTop),
            end = Offset(cx, blockTop + blockHeight),
            strokeWidth = HANDLE_LINE_PX,
        )
    }
    // 水滴：圆 + 指向波形的三角，union 后就是参考示例里的别针造型
    val cy = if (below) blockTop + blockHeight + r else blockTop - r
    val toward = if (below) -1f else 1f
    drawPath(
        path = Path().apply {
            moveTo(cx, cy + toward * r * 1.5f)
            lineTo(cx - r * 0.75f, cy)
            lineTo(cx + r * 0.75f, cy)
            close()
        },
        color = color,
    )
    drawCircle(color = color, radius = r, center = Offset(cx, cy))
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

private fun DrawScope.drawPeaks(
    peaks: WaveformPeaks,
    color: Color,
    blockTop: Float,
    blockHeight: Float,
) {
    val bucketCount = minOf(peaks.min.size, peaks.max.size)
    if (bucketCount <= 0) return
    val centerY = blockTop + blockHeight / 2f
    val step = max(1, ceil(bucketCount / size.width.coerceAtLeast(1f)).toInt())
    val bucketWidth = (size.width / ceil(bucketCount / step.toFloat()).coerceAtLeast(1f)).coerceAtLeast(1f)
    // 柱宽不足 2px 时整段波形会糊成一条细线，看不出起伏。
    // 这里给一个最小可见宽度并留出间隙，波形才有「一格一格」的形态。
    val barWidth = max(bucketWidth, MIN_BAR_WIDTH_PX)
    val gap = (bucketWidth - barWidth).coerceAtLeast(0f)
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
        val rawHeight = abs((low - high) * (blockHeight / 2f))
        // 振幅再放大一点：安静段落不至于贴成直线，起伏更明显
        val barHeight = (rawHeight * AMPLIFY).coerceAtLeast(MIN_BAR_PX).coerceAtMost(blockHeight)
        drawRect(
            color = color,
            topLeft = Offset(index * bucketWidth, centerY - barHeight / 2f),
            size = Size(barWidth, barHeight),
        )
    }
}

private val CANVAS_HEIGHT = 120.dp
private val PIN_SPACE = 18.dp
private val PIN_RADIUS = 9.dp
private val HANDLE_TOUCH_TARGET = 48.dp
private const val HANDLE_LINE_PX = 2f
private const val PLAYHEAD_WIDTH_PX = 3f
/** 波形柱最小可见宽度，太窄会糊成一条线 */
private const val MIN_BAR_WIDTH_PX = 2f
/** 波形振幅放大系数，让安静段落的起伏也看得出来 */
private const val AMPLIFY = 1.35f
/** 选区外蒙版的不透明度 */
private const val SELECTION_MASK_ALPHA = 0.55f
private const val MIN_BAR_PX = 2f
