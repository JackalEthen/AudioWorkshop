package cn.qishui.tool.feature.video

import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.qishui.tool.domain.media.ExportFormat
import cn.qishui.tool.ui.components.ConfirmSheet
import cn.qishui.tool.ui.components.FunctionShell
import cn.qishui.tool.ui.components.ImportTrackCard
import cn.qishui.tool.ui.components.OptionRow
import cn.qishui.tool.ui.components.PlaybackBar
import cn.qishui.tool.ui.components.PlainCard
import cn.qishui.tool.ui.components.PrimaryButton
import cn.qishui.tool.ui.components.SecondaryButton
import cn.qishui.tool.ui.components.SegmentedControl
import kotlinx.coroutines.delay

/**
 * 视频提取音频。选视频 → 预览确认内容 → 选模式和格式 → 保存到指定位置。
 *
 * 原来这里是一个「选完立刻提取成 wav 塞进曲库」的一次性动作，没有页面、
 * 不能选格式、也不能存到用户要的位置，所以重做成一个工作台。
 */
@Composable
fun VideoExtractAudioScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoExtractAudioViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.pick(context, it) } }

    var pickingFormat by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    FunctionShell(
        title = "视频提取音频",
        hint = EXTRACT_HINT,
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        bottomBar = {
            if (state.isWorking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            // CreateDocument 的 mime 在注册时就固定了，换格式必须重新注册，
            // 所以整个 bottomBar 按格式做 key，否则选了 mp3 也会按 wav 过滤
            key(state.format) {
                val savePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument(state.format.mimeType),
                ) { uri -> uri?.let { viewModel.save(context, it.toString()) } }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PrimaryButton(
                        text = if (state.isWorking) "处理中" else "保存",
                        onClick = {
                            savePicker.launch("${state.name ?: "视频音频"}.${state.format.extension}")
                        },
                        enabled = state.hasVideo && !state.isWorking,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) {
        // 导入卡和编辑/音效页统一：点一下弹系统文件选择器，换视频也是点它
        ImportTrackCard(
            track = null,
            onPick = { videoPicker.launch(arrayOf("video/*")) },
            label = if (state.hasVideo) "换一个" else "导入",
            primaryText = state.name ?: "点击导入视频",
        )

        if (state.hasVideo) {
            val videoFile = state.file
            VideoPlayerCard(
                path = videoFile?.absolutePath.orEmpty(),
                durationMs = state.durationMs,
            )
        }

        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SegmentedControl(
                    options = ExtractMode.entries.map { it.label },
                    selectedIndex = ExtractMode.entries.indexOf(state.mode),
                    onSelect = { viewModel.setMode(ExtractMode.entries[it]) },
                )
                OptionRow(
                    label = "格式",
                    value = state.format.label,
                    onClick = { pickingFormat = true },
                )
            }
        }
    }

    if (pickingFormat) {
        ConfirmSheet(
            title = "导出格式",
            description = "无损格式（wav / flac）不设比特率，也不会写封面和歌词",
            confirmLabel = "关闭",
            onConfirm = { pickingFormat = false },
            onDismiss = { pickingFormat = false },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExportFormat.entries.forEach { option ->
                    val selected = option == state.format
                    SecondaryButton(
                        text = if (selected) "${option.label} ✓" else option.label,
                        onClick = {
                            viewModel.setFormat(option)
                            pickingFormat = false
                        },
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 视频画面 + 播放控制。VideoView 是系统组件，不引入新的播放器依赖。 */
@Composable
private fun VideoPlayerCard(
    path: String,
    durationMs: Long,
    onDurationKnown: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val videoView = remember(context) { VideoView(context) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var actualDurationMs by remember { mutableLongStateOf(durationMs) }
    val currentPath by rememberUpdatedState(path)

    DisposableEffect(currentPath) {
        if (currentPath.isBlank()) return@DisposableEffect onDispose { }
        videoView.setVideoPath(currentPath)
        videoView.setOnPreparedListener { player ->
            actualDurationMs = player.duration.toLong()
            onDurationKnown(actualDurationMs)
        }
        videoView.setOnCompletionListener { playing = false }
        onDispose {
            runCatching { videoView.stopPlayback() }
        }
    }

    // VideoView 没有进度回调，自己轮询
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = videoView.currentPosition.toLong().coerceAtLeast(0L)
            delay(PROGRESS_INTERVAL_MS)
        }
    }

    PlainCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(VIDEO_ASPECT),
                contentAlignment = Alignment.Center,
            ) {
                if (path.isBlank()) {
                    Text(
                        text = "先选一个视频",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AndroidView(factory = { videoView }, modifier = Modifier.fillMaxWidth())
                }
            }
            PlaybackBar(
                playing = playing,
                enabled = path.isNotBlank(),
                positionMs = positionMs,
                durationMs = if (actualDurationMs > 0L) actualDurationMs else durationMs,
                onTogglePlay = {
                    if (videoView.isPlaying) {
                        videoView.pause()
                        playing = false
                    } else {
                        videoView.start()
                        playing = true
                    }
                },
                onSeek = { target ->
                    positionMs = target
                    videoView.seekTo(target.toInt())
                },
            )
        }
    }
}

private const val PROGRESS_INTERVAL_MS = 250L
private const val VIDEO_ASPECT = 16f / 9f

private val EXTRACT_HINT =
    "视频提取音频会把视频里的音轨抽出来，存成一个纯音频文件。" +
        "正常模式保持音轨原本的采样率和声道数；稳定模式统一到 44100Hz 立体声，" +
        "源视频参数奇怪（比如单声道、32kHz）导致成品不好用时选它。" +
        "没有音轨的视频会直接报错，不会生成空文件。" +
        "保存位置由你在系统文件选择器里挑，支持 mp3 / wav / flac / aac / m4a，" +
        "其中 wav 和 flac 无损但体积大，也不会写封面和歌词。"
