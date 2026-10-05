package cn.music.audioworkshop.feature.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

/**
 * 封面浮尘。
 *
 * 每个粒子有固定的起点、相位、横向漂移，位置全靠时间算出来，
 * 所以不需要每帧改状态，也不需要重组。
 */
@Composable
fun ParticleCoverOverlay(
    color: Color,
    particleCount: Int = 18,
    modifier: Modifier = Modifier,
) {
    // 12 秒一个循环，够长所以看不出接缝
    val transition = rememberInfiniteTransition(label = "particles")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "particleTime",
    )

    val particles = remember(particleCount) { newParticles(particleCount) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        for (p in particles) {
            // 相位不同，所以粒子不会齐步走
            val local = (time + p.phase) % 1f
            val x = w * (p.x + p.driftX * local)
            val y = h * (1f - easeOutCubic(local))
            drawCircle(
                color = color.copy(alpha = p.alpha * fadeInOut(local)),
                radius = p.radiusDp * density,
                center = Offset(x, y),
            )
        }
    }
}

/** 出生和消失时淡入淡出，避免粒子凭空出现或消失。 */
private fun fadeInOut(t: Float): Float =
    (t / 0.12f).coerceAtMost(1f) * ((1f - t) / 0.18f).coerceAtMost(1f)

private fun easeOutCubic(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

private class Particle(
    /** 起始横向位置 0..1。 */
    val x: Float,
    /** 循环相位 0..1。 */
    val phase: Float,
    /** 上升过程中的横向漂移，占宽度比例。 */
    val driftX: Float,
    /** 圆点半径 dp。 */
    val radiusDp: Float,
    /** 峰值不透明度。 */
    val alpha: Float,
)

private fun newParticles(count: Int): List<Particle> = List(count) {
    Particle(
        x = Random.nextFloat(),
        phase = Random.nextFloat(),
        driftX = (Random.nextFloat() - 0.5f) * 0.25f,
        radiusDp = 1.2f + Random.nextFloat() * 2.2f,
        alpha = 0.18f + Random.nextFloat() * 0.32f,
    )
}
