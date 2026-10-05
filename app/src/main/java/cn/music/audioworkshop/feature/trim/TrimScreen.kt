package cn.music.audioworkshop.feature.trim

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.feature.edit.waveform.WaveformCanvas
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.PlaybackBar
import cn.music.audioworkshop.ui.components.PrimaryButton
import cn.music.audioworkshop.ui.components.SectionHeader
import cn.music.audioworkshop.ui.components.SegmentedControl
import cn.music.audioworkshop.ui.components.TimeRangeStepperRow
import cn.music.audioworkshop.ui.components.TimeStepperField
import java.util.Locale

private const val HINT = "仅保留波形选中的区间，源文件不改动。" +
    "快速模式为硬切；正常模式可设淡入淡出时长以平滑接缝。"

/** 剪切功能页。 */
@Composable
fun TrimScreen(
    viewModel: TrimViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var cutting by remember { mutableStateOf(false) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importLocalAudio(it.toString()) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(playback.positionMs, playback.isPlaying) {
        viewModel.onPlaybackTick(playback)
    }

    FunctionShell(
        modifier = modifier,
        title = "剪切",
        hint = HINT,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        importBar = {
            AudioImportBar(
                fileName = state.fileName.takeIf { it.isNotBlank() },
                onPick = { importPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
            )
        },
        bottomBar = {
            PrimaryButton(
                text = when {
                    export.isRunning -> "导出中"
                    !state.hasTrack -> "请先导入音频"
                    else -> "导出剪切片段"
                },
                onClick = requestExport,
                enabled = !state.isLoading && !export.isRunning,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        exportFileName = viewModel.suggestedFileName(),
        exportProgress = ExportProgressState(
            label = export.statusLabel,
            fraction = export.fraction,
            cancellable = !export.isCopying,
            error = export.error,
        ).takeIf { export.isRunning || export.isCopying || export.error != null },
        exportedLocation = state.publishedLocation,
        onExport = viewModel::export,
        onCancelExport = viewModel::cancelExport,
    ) {
        // ---- 波形选区 ----
        SectionHeader(title = "选区")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                WaveformCanvas(
                        peaks = state.peaks,
                        durationUs = state.durationUs,
                        selection = state.selection,
                        positionUs = playback.positionMs * 1000L,
                        onSelectionChange = viewModel::setSelection,
                        onSeek = viewModel::seekToUs,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                    )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "已选 ${formatMs(state.selectedMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "/ 全长 ${formatMs(state.durationUs / 1000L)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- 精确时间：开始 / 结束 同一行 ----
        SectionHeader(title = "精确时间")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                TimeRangeStepperRow(
                    startLabel = "开始时间",
                    startMs = state.selection.startUs / 1000L,
                    onStartChange = viewModel::setStartMs,
                    endLabel = "结束时间",
                    endMs = state.selection.endUs / 1000L,
                    onEndChange = viewModel::setEndMs,
                    maxMs = state.durationUs / 1000L,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- 试听：播放条 + 开关，位置在导出模式上方 ----
        if (state.hasTrack) {
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 播放条只覆盖选区：位置与时长都以选区为基准，
                    // 用户拖了选区就能立刻看到「这一段有多长」。
                    val selectionStartMs = state.selection.startUs / 1000L
                    val selectionSpanMs = state.selectedMs.coerceAtLeast(1L)
                    val positionInSelection = (playback.positionMs - selectionStartMs)
                        .coerceIn(0L, selectionSpanMs)
                    PlaybackBar(
                        playing = playback.isPlaying,
                        enabled = true,
                        positionMs = positionInSelection,
                        durationMs = selectionSpanMs,
                        onTogglePlay = viewModel::togglePlay,
                        onSeek = viewModel::seekTo,
                    )
                    CheckRow(
                        label = "调整时长自动播放",
                        checked = state.replayAfterAdjust,
                        onToggle = viewModel::setReplayAfterAdjust,
                    )
                }
            }
        }

        // ---- 模式：分段控件，快/正常一键切 ----
        SectionHeader(title = "导出模式")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SegmentedControl(
                    options = TrimMode.entries.map { it.label },
                    selectedIndex = TrimMode.entries.indexOf(state.mode),
                    onSelect = { index ->
                        TrimMode.entries.getOrNull(index)?.let(viewModel::setMode)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = state.mode.caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        // ---- 淡入淡出：只在正常模式出现 ----
        if (state.mode == TrimMode.NORMAL) {
            SectionHeader(title = "淡入淡出")
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TimeRangeStepperRow(
                        startLabel = "淡入时长",
                        startMs = state.fadeInMs,
                        onStartChange = viewModel::setFadeIn,
                        endLabel = "淡出时长",
                        endMs = state.fadeOutMs,
                        onEndChange = viewModel::setFadeOut,
                        maxMs = state.selectedMs,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "超过选区一半会自动收敛，避免淡入淡出互相吃掉内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- 导出结果 ----
        state.publishedLocation?.let { location ->
            SectionHeader(title = "导出结果")
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "导出成功",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

private fun formatMs(ms: Long): String {
    val total = ms.coerceAtLeast(0L)
    val minutes = total / 60_000L
    val seconds = (total / 1000L) % 60L
    val millis = total % 1000L
    return String.format(Locale.getDefault(), "%02d:%02d.%03d", minutes, seconds, millis)
}

/**
 * 复选行。整行可点。
 *
 * 只放标签，不带说明文字 —— 说明写在功能的「使用说明」里，
 * 在这一行下面挂小字会把卡片撑松散。
 */
@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(horizontal = 2.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.material3.Checkbox(
            checked = checked,
            onCheckedChange = onToggle,
            modifier = Modifier.height(32.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
