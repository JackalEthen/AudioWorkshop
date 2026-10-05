package cn.music.audioworkshop.feature.player

import androidx.compose.animation.core.animate
import cn.music.audioworkshop.ui.components.QishuiSlider
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import cn.music.audioworkshop.R
import cn.music.audioworkshop.data.media.PlaybackUiState
import cn.music.audioworkshop.domain.model.LyricsTrack
import cn.music.audioworkshop.domain.player.QueueItem
import cn.music.audioworkshop.domain.player.RepeatMode
import cn.music.audioworkshop.feature.edit.lyrics.LyricsPanel
import cn.music.audioworkshop.ui.components.LucideIcon
import cn.music.audioworkshop.ui.components.PlayCircleButton
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 全屏播放页。
 *
 * 布局照 example5（封面态）/ example6（歌词态）：
 * 顶栏 ← 歌名 + 歌手 ｜ 定时关闭 · 播放设置
 * 中间 封面 ⇄ 歌词（LRC 键切换）
 * 底部 进度条 → 上一首 · 播放暂停 · 下一首 → LRC · 收藏 · 播放方式 · 播放速率
 *
 * 歌名只出现在顶栏，封面下方不再重复。
 * 同页覆盖在播放器页之上（不是新路由），下滑收起。
 */
@Composable
fun PlayerSheet(
    state: PlaybackUiState,
    current: QueueItem?,
    lyrics: LyricsTrack,
    showLyrics: Boolean,
    isFavorite: Boolean,
    playbackSpeed: Float,
    sleepRemainingMs: Long,
    queueSize: Int,
    particleCoverEnabled: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleRepeatMode: () -> Unit,
    onCyclePlaybackSpeed: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 下滑手势：跟手指走，释放时超过阈值就收起
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val hasSong = state.hasCurrentSong
    val queueIsNotEmpty = queueSize > 0

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 背景：主题色渐层
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                ),
        )

        // 浮尘铺满整页，不只是封面。放在渐层之上、内容之下，
        // 所以不会被歌名、控制条盖住，也不会吃掉点击。
        if (particleCoverEnabled) {
            ParticleCoverOverlay(
                color = MaterialTheme.colorScheme.onPrimary,
                particleCount = 42,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, dragOffset.roundToInt()) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragOffset > DragDismissThreshold) onDismiss()
                            dragOffset = 0f
                        },
                        onDragCancel = { dragOffset = 0f },
                    ) { change, dragAmount ->
                        // 只接受向下拖，往上推不动，避免和系统手势打架
                        if (dragAmount > 0f || dragOffset > 0f) {
                            dragOffset = (dragOffset + dragAmount).coerceAtLeast(0f)
                        }
                        change.consume()
                    }
                }
                // 顶部避开状态栏、底部避开系统导航栏
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp),
        ) {
            // ---- 顶栏：歌名 + 歌手 ｜ 定时关闭 · 播放设置 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetIconButton(
                    icon = R.drawable.ic_chevron_left,
                    description = "收起",
                    onClick = onDismiss,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    Text(
                        text = current?.title ?: "还没有播放",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 演唱者用小字另起一行，不能和歌名挤在同一行
                    Text(
                        text = current?.artist.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SheetIconButton(
                    icon = R.drawable.ic_timer,
                    description = "定时关闭",
                    onClick = onOpenSleepTimer,
                    badge = if (sleepRemainingMs > 0L) formatClock(sleepRemainingMs) else null,
                )
                SheetIconButton(
                    icon = R.drawable.ic_settings,
                    description = "播放设置",
                    onClick = onOpenSettings,
                )
            }
            // 封面 ⇄ 歌词：点击切换。不用左右滑 —— 两页同屏叠加时拖拽手势会和
            // 父级的下拉关闭打架，而且半透明叠加看着脏。AnimatedContent 只画当前页。
            AnimatedContent(
                targetState = showLyrics,
                transitionSpec = {
                    val forward = targetState
                    (slideInHorizontally(tween(PageSwapMs)) { width -> if (forward) width / 3 else -width / 3 } +
                        fadeIn(tween(PageSwapMs)))
                        .togetherWith(
                            slideOutHorizontally(tween(PageSwapMs)) { width -> if (forward) -width / 3 else width / 3 } +
                                fadeOut(tween(PageFadeOutMs)),
                        )
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "cover-lyrics",
            ) { lyricsVisible ->
                if (lyricsVisible) {
                    LyricsPage(
                        lyrics = lyrics,
                        positionMs = state.positionMs,
                        onClick = onToggleLyrics,
                    )
                } else {
                    CoverPage(
                        current = current,
                        onClick = onToggleLyrics,
                    )
                }
            }

            // ---- 进度条 ----
            val duration = state.durationMs.coerceAtLeast(1L)
            // 不用 M3 默认 Slider：它的断轨和竖条拇指跟项目风格对不上。
            // 轨道收窄、拇指是圆点，按住才变粗。
            QishuiSlider(
                value = state.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .alpha(if (hasSong) 1f else 0.35f),
                label = "播放进度",
                enabled = hasSong,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = formatClock(state.positionMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatClock(state.durationMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- 上一首 · 播放暂停 · 下一首 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetIconButton(
                    icon = R.drawable.ic_skip_back,
                    description = "上一首",
                    enabled = hasSong,
                    onClick = onPrevious,
                    iconSize = 38.dp,
                )
                PlayCircleButton(
                    playing = state.isPlaying,
                    enabled = hasSong,
                    onClick = onTogglePlay,
                    size = 66.dp,
                )
                SheetIconButton(
                    icon = R.drawable.ic_skip_forward,
                    description = "下一首",
                    enabled = hasSong,
                    onClick = onNext,
                    iconSize = 38.dp,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ---- 收藏 · 播放方式 · 播放速率 · 均衡器 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SheetIconButton(
                    icon = R.drawable.ic_heart,
                    description = "收藏",
                    enabled = hasSong,
                    active = isFavorite,
                    onClick = onToggleFavorite,
                )
                SheetIconButton(
                    icon = when (state.repeatMode) {
                        RepeatMode.OFF -> R.drawable.ic_repeat
                        RepeatMode.ALL -> R.drawable.ic_shuffle
                        RepeatMode.ONE -> R.drawable.ic_repeat_1
                    },
                    description = "播放方式",
                    enabled = hasSong,
                    active = state.repeatMode != RepeatMode.OFF,
                    onClick = onCycleRepeatMode,
                )
                SheetIconButton(
                    icon = R.drawable.ic_gauge,
                    description = "播放速率",
                    enabled = hasSong,
                    active = kotlin.math.abs(playbackSpeed - 1.0f) > 0.01f,
                    onClick = onCyclePlaybackSpeed,
                    badge = if (kotlin.math.abs(playbackSpeed - 1.0f) > 0.01f) {
                        "${trimTrailingZero(playbackSpeed)}x"
                    } else {
                        null
                    },
                )
                SheetIconButton(
                    icon = R.drawable.ic_list_music,
                    description = "播放列表",
                    enabled = queueIsNotEmpty,
                    active = false,
                    onClick = onOpenQueue,
                )
            }
        }
    }
}

private val DragDismissThreshold = 160f

/** 封面 ⇄ 歌词切换动画时长（毫秒）。 */
private const val PageSwapMs = 260

/** 旧页淡出比新页淡入略短，切换才显得干脆，不拖泥带水。 */
private const val PageFadeOutMs = 180

/** 封面页。整页可点，点一下切到歌词。 */
@Composable
private fun CoverPage(
    current: QueueItem?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // indication = null：整页切换不要水波纹/按压阴影，
            // 满屏的遮罩反馈看着像出错了，而不是「切了一下页」。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = current != null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (!current?.coverUri.isNullOrBlank()) {
                AsyncImage(
                    model = current?.coverUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LucideIcon(
                    icon = R.drawable.ic_music,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 64.dp,
                )
            }
        }
    }
}

/** 歌词页。整页可点，点一下切回封面。没歌词时显示「暂无歌词」，照样能进来。 */
@Composable
private fun LyricsPage(
    lyrics: LyricsTrack,
    positionMs: Long,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = lyrics.lines.isNotEmpty(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (lyrics.lines.isEmpty()) {
                Text(
                    text = "暂无歌词",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LyricsPanel(
                    lyrics = lyrics,
                    positionUs = positionMs * 1000L,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = "点一下回到封面",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun SheetIconButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    /** 选中态：用主题色。 */
    active: Boolean = false,
    iconSize: Dp = 24.dp,
    /** 角标文字。播放速率和定时关闭用它显示当前值。 */
    badge: String? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            LucideIcon(
                icon = icon,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    active -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                size = iconSize,
            )
        }
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun formatClock(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** 1.25f 显示成 1.25x，1.0f 显示成 1x。 */
private fun trimTrailingZero(value: Float): String {
    val text = value.toString()
    return if (text.endsWith(".0")) text.dropLast(2) else text
}