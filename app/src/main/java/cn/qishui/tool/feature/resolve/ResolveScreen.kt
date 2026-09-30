package cn.qishui.tool.feature.resolve

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qishui.tool.data.media.LrcCodec
import cn.qishui.tool.data.media.LyricsCodec
import cn.qishui.tool.domain.model.ResolvedTrack
import cn.qishui.tool.ui.components.TopSnackbarHost
import cn.qishui.tool.ui.components.FloatingCard
import cn.qishui.tool.ui.components.MetadataField
import cn.qishui.tool.ui.components.MetadataGrid
import cn.qishui.tool.ui.components.ParsingIndicator
import cn.qishui.tool.ui.components.PrimaryButton
import cn.qishui.tool.ui.components.QishuiFieldShape
import cn.qishui.tool.ui.components.QishuiTopBar
import cn.qishui.tool.ui.components.ScreenScroll
import cn.qishui.tool.ui.components.SecondaryButton
import cn.qishui.tool.ui.components.SectionHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ResolveScreen(
    viewModel: ResolveViewModel,
    onOpenRecords: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shareInput by rememberSaveable { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var pendingLyrics by remember { mutableStateOf<String?>(null) }
    val lyricsPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val text = pendingLyrics
        pendingLyrics = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }.onSuccess { viewModel.notify("歌词文件已保存") }
            .onFailure { viewModel.notify("歌词保存失败：${it.message ?: "未知错误"}") }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val isLoading = uiState is ResolveUiState.Loading

    LaunchedEffect(uiState) {
        (uiState as? ResolveUiState.Success)?.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
        snackbarHost = { TopSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        // 只让内容区避让输入法，底部悬浮导航栏留在原位
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
            QishuiTopBar(
                title = "解析",
                menu = true,
                onAction = onOpenRecords,
            )
            ScreenScroll(modifier = Modifier.weight(1f)) {
                FloatingCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = shareInput,
                            onValueChange = { shareInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("分享内容") },
                            placeholder = { Text("粘贴含链接的分享文本") },
                            minLines = 3,
                            maxLines = 6,
                            shape = QishuiFieldShape,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SecondaryButton(
                                text = "读取剪贴板",
                                onClick = {
                                    clipboardManager.getText()?.text?.let { shareInput = it }
                                },
                                enabled = !isLoading,
                                modifier = Modifier.weight(1f),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            PrimaryButton(
                                text = if (isLoading) "解析中" else "开始解析",
                                onClick = { viewModel.resolve(shareInput) },
                                enabled = shareInput.isNotBlank() && !isLoading,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                when (val state = uiState) {
                    ResolveUiState.Idle -> Unit
                    ResolveUiState.Loading -> FloatingCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ParsingIndicator()
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text("正在解析歌曲", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "获取音频信息与逐字歌词…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    is ResolveUiState.Error -> FloatingCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            text = state.message,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }

                    is ResolveUiState.Success -> {
                        SectionHeader(title = "解析结果")
                        TrackResultCard(
                            track = state.track,
                            isEnqueueing = state.isEnqueueing,
                            isPreviewing = playback.isPlaying && playback.mediaId == state.track.audioUrl,
                            canPreview = !state.track.audioUrl.isNullOrBlank(),
                            positionMs = playback.positionMs,
                            durationMs = playback.durationMs,
                            onPreview = viewModel::togglePreview,
                            onSeek = viewModel::seekPreview,
                            onDownload = viewModel::enqueueDownload,
                            onCopyLyrics = {
                                clipboardManager.setText(AnnotatedString(lrcTextOf(state.track)))
                                viewModel.notify("歌词已复制")
                            },
                            onSaveLyrics = {
                                pendingLyrics = lrcTextOf(state.track)
                                lyricsPicker.launch(
                                    "${state.track.title ?: "歌词"}.lrc".replace(Regex("[/\\\\:*?\"<>|]"), "_"),
                                )
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TrackResultCard(
    track: ResolvedTrack,
    isEnqueueing: Boolean,
    isPreviewing: Boolean,
    canPreview: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPreview: () -> Unit,
    onSeek: (Long) -> Unit,
    onDownload: () -> Unit,
    onCopyLyrics: () -> Unit,
    onSaveLyrics: () -> Unit,
) {
    FloatingCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title ?: "未提供歌名",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = track.artist ?: "未提供歌手",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            MetadataGrid(
                items = buildList {
                    add(MetadataField("歌曲格式", track.format.orDash()))
                    add(MetadataField("编码", track.codec.orDash()))
                    add(MetadataField("音质", track.quality.orDash()))
                    add(MetadataField("码率", formatBitrate(track.bitrateBps) ?: "未知"))
                    add(MetadataField("采样率", track.sampleRateHz?.let { "$it Hz" } ?: "未知"))
                    add(MetadataField("文件大小", formatSize(track.sizeBytes) ?: "未知"))
                    add(
                        MetadataField(
                            label = "歌词状态",
                            value = if (track.lyrics.isNullOrBlank()) "未返回" else "逐字歌词",
                            fullWidth = true,
                        ),
                    )
                },
            )

            if (!track.lyrics.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SecondaryButton(
                        text = "复制歌词",
                        onClick = onCopyLyrics,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    SecondaryButton(
                        text = "下载歌词文件",
                        onClick = onSaveLyrics,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SecondaryButton(
                    text = if (isPreviewing) "暂停试听" else "试听",
                    onClick = onPreview,
                    enabled = canPreview,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = if (isEnqueueing) "加入中" else "下载歌曲",
                    onClick = onDownload,
                    enabled = track.audioUrl != null && track.format != null && !isEnqueueing,
                    modifier = Modifier.weight(1.3f),
                )
            }

            if (canPreview && durationMs > 0L) {
                PlaybackBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    enabled = isPreviewing || positionMs > 0L,
                    onSeek = onSeek,
                )
            }

            formatCacheExpiration(track.cacheExpiresAtEpochSeconds)?.let { expiry ->
                Text(
                    text = "音频地址有效至 $expiry",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun lrcTextOf(track: ResolvedTrack): String =
    LrcCodec.toLrc(LyricsCodec.parse(track.lyrics))

@Composable
private fun PlaybackBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Slider(
            value = (dragging ?: positionMs.toFloat()).coerceIn(0f, durationMs.toFloat()),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
            enabled = enabled,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatMs(dragging?.toLong() ?: positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                text = formatMs(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatMs(valueMs: Long): String {
    val totalSeconds = (valueMs / 1000).coerceAtLeast(0L)
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun String?.orDash(): String = this?.takeIf(String::isNotBlank) ?: "未提供"

private fun formatBitrate(bitrateBps: Long?): String? = bitrateBps?.let {
    "${String.format(Locale.getDefault(), "%.1f", it / 1000.0)} kbps"
}

private fun formatSize(sizeBytes: Long?): String? = sizeBytes?.let {
    if (it >= 1024L * 1024L) {
        "${String.format(Locale.getDefault(), "%.2f", it / 1024.0 / 1024.0)} MB"
    } else {
        "${String.format(Locale.getDefault(), "%.2f", it / 1024.0)} KB"
    }
}

private fun formatCacheExpiration(epochSeconds: Long?): String? = epochSeconds?.let {
    Instant.ofEpochSecond(it)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}