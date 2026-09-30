package cn.qishui.tool.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

private val TrackHeight = 6.dp
private val ThumbRadius = 11.dp
private val ThumbStroke = 2.5.dp
private val SliderHeight = 44.dp

/**
 * 连续轨道 + 圆环拇指的滑杆。
 * 不用 M3 默认 Slider：它的断轨、竖条拇指和端点圆点跟本项目的圆角胶囊风格对不上。
 */
@Composable
fun QishuiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val emit by rememberUpdatedState(onValueChange)
    val inset = with(LocalDensity.current) { ThumbRadius.toPx() }

    fun valueAt(x: Float, widthPx: Float): Float {
        val usable = (widthPx - inset * 2f).coerceAtLeast(1f)
        val raw = ((x - inset) / usable).coerceIn(0f, 1f)
        val f = if (ltr) raw else 1f - raw
        return valueRange.start + f * span
    }

    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHigh
    val thumbFill = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SliderHeight)
            .semantics {
                if (label != null) contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, valueRange)
                setProgress {
                    emit(it.coerceIn(valueRange.start, valueRange.endInclusive))
                    true
                }
            }
            .pointerInput(valueRange, ltr) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    emit(valueAt(down.position.x, size.width.toFloat()))
                    var change = down
                    while (change.pressed) {
                        change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        emit(valueAt(change.position.x, size.width.toFloat()))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val thumbX = inset + fraction * (size.width - inset * 2f)
            val bar = TrackHeight.toPx()
            val topLeft = Offset(0f, cy - bar / 2f)
            drawRoundRect(
                color = inactive,
                topLeft = topLeft,
                size = Size(size.width, bar),
                cornerRadius = CornerRadius(bar / 2f),
            )
            drawRoundRect(
                color = active,
                topLeft = topLeft,
                size = Size(thumbX, bar),
                cornerRadius = CornerRadius(bar / 2f),
            )
            drawCircle(color = thumbFill, radius = inset, center = Offset(thumbX, cy))
            drawCircle(
                color = active,
                radius = inset - ThumbStroke.toPx(),
                center = Offset(thumbX, cy),
                style = Stroke(width = ThumbStroke.toPx()),
            )
        }
    }
}
