package cn.music.audioworkshop.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import cn.music.audioworkshop.R

/** 一圈唱片要转多久。33⅓ 转/分 ≈ 1.8s，慢到能看清是唱片、快到不显得急。 */
private const val SpinPeriodMs = 1800

/**
 * 唱片图标：黑胶盘 + 封面 + 中心轴孔。
 *
 * 播放中匀速旋转，暂停时停在原角度 —— 直接把无限动画的播放速度降到 0，
 * 不用记当前角度再补间，那样松手会跳。
 */
@Composable
fun VinylRecord(
    coverUri: String?,
    playing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    val transition = rememberInfiniteTransition(label = "vinyl")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(SpinPeriodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "vinyl-angle",
    )
    // 盘身整体转，不是只转中心那张封面 —— 只转中心标看着像贴纸在飘。
    // 没有歌时角度固定 0，静止。
    val rotation = if (playing) angle else 0f

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF1B1B1B))
            // 只看有没有歌，不看有没有封面：没封面的歌也该能点进去
            .clickable(enabled = enabled, onClick = onClick)
            .graphicsLayer { rotationZ = rotation },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val radius = this.size.minDimension / 2f
            val center = this.center
            // 唱片纹路：几道同心暗环，转起来才看得出在动
            val grooveColor = Color.White.copy(alpha = 0.05f)
            for (ratio in listOf(0.92f, 0.84f, 0.76f, 0.68f)) {
                drawCircle(grooveColor, radius * ratio, center)
            }
            // 中心轴孔
            drawCircle(Color(0xFF1B1B1B), radius * 0.12f, center)
        }

        if (!coverUri.isNullOrBlank()) {
            AsyncImage(
                model = coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size * 0.58f)
                    .clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(size * 0.58f)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(
                    icon = R.drawable.ic_music,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = size * 0.3f,
                )
            }
        }
    }
}
