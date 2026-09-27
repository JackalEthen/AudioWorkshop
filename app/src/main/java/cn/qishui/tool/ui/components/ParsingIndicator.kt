package cn.qishui.tool.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private const val BarCount = 5

@Composable
fun ParsingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ParsingIndicator")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "progress",
    )
    val color = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier.size(width = 72.dp, height = 32.dp),
    ) {
        val barWidth = size.width / (BarCount * 2f - 1f)
        repeat(BarCount) { index ->
            val phase = (progress + index / BarCount.toFloat()) % 1f
            val wave = (sin(phase * 2f * PI.toFloat()).toFloat() + 1f) / 2f
            val barHeight = size.height * (0.25f + 0.75f * wave)
            drawRoundRect(
                color = color.copy(alpha = 0.45f + 0.55f * wave),
                topLeft = Offset(
                    x = index * barWidth * 2f,
                    y = (size.height - barHeight) / 2f,
                ),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
