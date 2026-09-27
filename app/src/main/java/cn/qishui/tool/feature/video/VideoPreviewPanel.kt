package cn.qishui.tool.feature.video

import android.view.SurfaceView
import cn.qishui.tool.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import cn.qishui.tool.ui.components.LucideIcon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.net.toUri
import cn.qishui.tool.ui.components.PlainCard
import cn.qishui.tool.ui.components.SecondaryButton
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 视频预览：三个功能共用。
 * 裁剪 —— 播放整段，用播放头设起点/终点，越过终点自动回到起点。
 * 拼接 —— 按列表顺序连着播，可以核对顺序和内容。
 * 变速 —— 播放时按当前速度走，ExoPlayer 默认保持音调。
 */
@Composable
fun VideoPreviewPanel(
    files: List<File>,
    tool: VideoTool,
    startSec: Float,
    endSec: Float,
    speed: Float,
    onStartAtPlayhead: (Float) -> Unit,
    onEndAtPlayhead: (Float) -> Unit,
    onDurationKnown: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember { ExoPlayer.Builder(context.applicationContext).build() }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }

    // 换视频就重新装载
    LaunchedEffect(files.map { it.absolutePath }) {
        errorText = null
        val items = files.map { MediaItem.fromUri(it.toUri()) }
        player.setMediaItems(items)
        player.prepare()
        player.seekTo(0)
        positionMs = 0L
    }

    // 变速实时生效
    LaunchedEffect(speed, tool) {
        if (tool == VideoTool.SPEED) {
            player.playbackParameters = PlaybackParameters(speed)
        } else {
            player.playbackParameters = PlaybackParameters(1f)
        }
    }

    // 轮询进度 + 裁剪模式下越过终点回到起点
    LaunchedEffect(isPlaying, tool, startSec, endSec) {
        while (true) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(0L)
            durationMs = runCatching { player.duration }.getOrDefault(0L)
            if (tool == VideoTool.TRIM && isPlaying) {
                val endMs = (endSec * 1000).toLong()
                if (endMs > startSec && positionMs >= endMs) {
                    player.seekTo((startSec * 1000).toLong())
                }
            }
            delay(120)
        }
    }

    LaunchedEffect(durationMs) {
        if (durationMs > 0L) onDurationKnown(durationMs)
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                errorText = error.errorCodeName
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(surface) {
        surface?.let { player.setVideoSurfaceView(it) }
        onDispose { surface?.let { player.clearVideoSurfaceView(it) } }
    }

    PlainCard(modifier = modifier.fillMaxWidth()) {
        Column {
            // 视频直接贴住卡片上沿，让画面和卡片边框融成一体
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).also { view ->
                        view.layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        surface = view
                        player.setVideoSurfaceView(view)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                errorText?.let {
                    Text(
                        text = "预览不可用：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PlayIconButton(
                        playing = isPlaying,
                        onClick = { if (isPlaying) player.pause() else player.play() },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Slider(
                            value = positionMs.toFloat(),
                            onValueChange = { player.seekTo(it.toLong()) },
                            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                        )
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = formatTime(positionMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = formatTime(durationMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
                if (tool == VideoTool.TRIM) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SecondaryButton(
                            text = "起点 ${formatTime((startSec * 1000).toLong())}",
                            onClick = { onStartAtPlayhead(positionMs / 1000f) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            textStyle = MaterialTheme.typography.labelMedium,
                        )
                        SecondaryButton(
                            text = "终点 ${formatTime((endSec * 1000).toLong())}",
                            onClick = { onEndAtPlayhead(positionMs / 1000f) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            textStyle = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayIconButton(playing: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            LucideIcon(
                icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                size = 22.dp,
            )
        }
    }
}

private fun previewStatus(tool: VideoTool, positionMs: Long, durationMs: Long, count: Int): String {
    val pos = formatTime(positionMs)
    val dur = formatTime(durationMs)
    return when (tool) {
        VideoTool.JOIN -> "第 1..$count 段 · $pos / $dur"
        else -> "$pos / $dur"
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(
        Locale.getDefault(),
        "%d:%02d",
        totalSeconds / 60,
        totalSeconds % 60,
    )
}
