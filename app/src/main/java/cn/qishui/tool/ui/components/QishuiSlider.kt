package cn.qishui.tool.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.lerp
import androidx.compose.material3.MaterialTheme

private val TrackHeight = 3.dp
private val TrackHeightDragging = 7.dp
private val ThumbRadius = 5.dp
private val ThumbRadiusDragging = 8.dp
private val SliderHeight = 28.dp

/**
 * 连续轨道 + 圆点拇指的滑杆。
 *
 * 不用 M3 默认 Slider：它的断轨、竖条拇指和端点圆点跟本项目的圆角胶囊风格对不上。
 *
 * 静止时轨道细、拇指小；**按住才变粗变���** —— 平时不抢视觉，操作时又必须好抓。
 */
@Composable
fun QishuiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val emit by rememberUpdatedState(onValueChange)
    val density = LocalDensity.current

    var dragging by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(PressGrowMs),
        label = "slider-press",
    )
    val trackHeight by animateDpAsState(
        targetValue = lerp(TrackHeight, TrackHeightDragging, pressScale),
        animationSpec = tween(PressGrowMs),
        label = "slider-track",
    )
    val thumbRadius by animateDpAsState(
        targetValue = lerp(ThumbRadius, ThumbRadiusDragging, pressScale),
        animationSpec = tween(PressGrowMs),
        label = "slider-thumb",
    )
    val thumbPx = with(density) { thumbRadius.toPx() }
    val inset = with(density) { ThumbRadiusDragging.toPx() }

    fun valueAt(x: Float, widthPx: Float): Float {
        val usable = (widthPx - inset * 2f).coerceAtLeast(1f)
        val raw = ((x - inset) / usable).coerceIn(0f, 1f)
        val f = if (ltr) raw else 1f - raw
        return valueRange.start + f * span
    }

    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHigh
    val thumbColor = if (enabled) active else active.copy(alpha = 0.35f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SliderHeight)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics {
                if (label != null) contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, valueRange)
                if (enabled) {
                    setProgress {
                        emit(it.coerceIn(valueRange.start, valueRange.endInclusive))
                        true
                    }
                }
            }
            .pointerInput(valueRange, ltr, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    emit(valueAt(down.position.x, size.width.toFloat()))
                    var change = down
                    while (change.pressed) {
                        change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        emit(valueAt(change.position.x, size.width.toFloat()))
                    }
                    dragging = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val thumbX = inset + fraction * (size.width - inset * 2f)
            val bar = trackHeight.toPx()
            val topLeft = Offset(0f, cy - bar / 2f)
            drawRoundRect(
                color = inactive,
                topLeft = topLeft,
                size = Size(size.width, bar),
                cornerRadius = CornerRadius(bar / 2f),
            )
            drawRoundRect(
                color = thumbColor,
                topLeft = topLeft,
                size = Size(thumbX, bar),
                cornerRadius = CornerRadius(bar / 2f),
            )
            // 圆点，不是竖条
            drawCircle(color = thumbColor, radius = thumbPx, center = Offset(thumbX, cy))
        }
    }
}

/** 按下到变粗的过渡时长（毫秒）。太慢会显得迟钝，太快就看不出「变粗」了。 */
private const val PressGrowMs = 140
