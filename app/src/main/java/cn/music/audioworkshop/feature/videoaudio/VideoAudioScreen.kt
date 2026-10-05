package cn.music.audioworkshop.feature.videoaudio

import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.domain.media.ExportFormat
import cn.music.audioworkshop.feature.edit.export.ExportUiState
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.PlaybackBar
import cn.music.audioworkshop.ui.components.SectionHeader
import cn.music.audioworkshop.ui.components.formatDuration

private val VideoMimeTypes = arrayOf("video/*")

/**
 * 视频提取音频。
 *
 * 视频用系统 [VideoView] 直接播，不引 media3-ui —— 一个只用来「确认这是哪个视频」
 * 的预览，不值得为它加依赖。
 */
@Composable
fun VideoAudioScreen(
    viewModel: VideoAudioViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBarSlot: @Composable cn.music.audioworkshop.ui.components.BottomBarScope.(canExport: Boolean, label: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            viewModel.importVideo(
                uri = it.toString(),
                displayName = queryDisplayName(it.toString()),
            )
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    FunctionShell(
        modifier = modifier,
        title = "视频提取音频",
        hint = "提取视频音轨并导出为音频文件。视频容器对解码器等同音频文件，不做二次转码。" +
            "无音轨的视频无法导出。",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        importBar = {
            AudioImportBar(
                fileName = state.video.fileName.takeIf { it.isNotBlank() },
                onPick = { importPicker.launch(VideoMimeTypes) },
            )
        },
        bottomBar = {
            bottomBarSlot(
                state.canExport,
                when {
                    exportState.isRunning -> "处理中"
                    !state.hasVideo -> "请先导入视频"
                    !state.video.hasAudioTrack -> "这个视频没有音轨"
                    else -> "导出音频"
                },
            )
        },
        exportFileName = viewModel.suggestedFileName(),
        exportProgress = ExportProgressState(
            label = exportState.statusLabel,
            fraction = exportState.fraction,
            cancellable = !exportState.isCopying,
            error = exportState.error,
        ).takeIf { exportState.isRunning || exportState.isCopying || exportState.error != null },
        exportedLocation = state.publishedLocation,
        onExport = { viewModel.export(it) },
        onCancelExport = { viewModel.cancelExport() },
    ) {
        if (state.hasVideo) {
            // ---- 视频预览卡片 ----
            SectionHeader(title = "视频")
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    VideoPreview(videoPath = state.video.videoPath)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = state.video.fileName,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MetaChip(label = state.video.resolutionLabel)
                        MetaChip(label = formatDuration(state.video.durationUs / 1000L))
                        if (state.video.hasAudioTrack) MetaChip(label = "含音轨")
                    }
                    if (!state.video.hasAudioTrack) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "这个视频没有音频轨，导不出音频。换一个试试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ---- 音频试听 ----
            SectionHeader(title = "音频试听")
            PlaybackBar(
                playing = playback.isPlaying,
                enabled = state.video.hasAudioTrack,
                positionMs = playback.positionMs,
                durationMs = state.video.durationUs / 1000L,
                onTogglePlay = { viewModel.togglePlay() },
                onSeek = { viewModel.seekTo(it) },
            )

// ---- 输出格式 ----
            SectionHeader(title = "输出格式")
            // 两个卡片等宽等高：Row + weight 保证等宽，
            // height 保证等高 —— 标题字号一样但加上注解会因换行不同而错位。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FormatCard(
                    title = "MP3",
                    selected = state.format == ExportFormat.MP3,
                    onClick = { viewModel.setFormat(ExportFormat.MP3) },
                    modifier = Modifier.weight(1f),
                )
                FormatCard(
                    title = "WAV",
                    selected = state.format == ExportFormat.WAV,
                    onClick = { viewModel.setFormat(ExportFormat.WAV) },
                    modifier = Modifier.weight(1f),
                )
            }

            state.publishedLocation?.let { location ->
                SectionHeader(title = "导出完成")
                FloatingCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "已导出 ${state.format.extension.uppercase()}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * 视频画面。
 *
 * 用固定 16:9 而不是按真实分辨率算比例：竖屏视频塞进 16:9 容器会被裁掉上下，
 * 按真实比例又会让页面高度随每个视频跳动。
 */
@Composable
private fun VideoPreview(videoPath: String) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
factory = { context ->
            VideoView(context).apply {
                setMediaController(null)
                setVideoPath(videoPath)
                setOnPreparedListener { player ->
                    player.isLooping = false
                    // 只 setVideoPath 画面会停在第一帧不动 —— 必须显式 start。
                    // 从 1ms 起播而不是 0，否则部分解码器在第一帧上会卡住不动。
                    seekTo(1)
                    start()
                }
            }
        },
        update = { view ->
            if (view.tag != videoPath) {
                view.tag = videoPath
                view.setVideoPath(videoPath)
                view.seekTo(1)
                view.start()
            }
        },
        onRelease = { view ->
            runCatching { view.stopPlayback() }
        },
    )
}

@Composable
private fun MetaChip(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 10.dp),
    )
}

/**
 * 格式选择卡片。
 *
 * 固定高度，两个卡片才不会因为内容差异错开。
 * 点击不给涟漪：这是二选一的单选语义，按下去闪一下反而像有额外状态。
 */
@Composable
private fun FormatCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    FloatingCard(modifier = modifier.height(64.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/** 取文件名，拿不到就用 uri 尾段。查不到不阻塞流程。 */
private fun queryDisplayName(uriString: String): String {
    val path = uriString.substringBefore('?')
    val tail = path.substringAfterLast('/').substringBeforeLast('.').takeIf { it.isNotBlank() }
    return tail?.take(60) ?: "视频"
}



