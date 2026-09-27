package cn.qishui.tool.feature.edit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qishui.tool.domain.model.EditMode
import cn.qishui.tool.domain.model.EditOperation
import cn.qishui.tool.domain.model.SourceTrack
import cn.qishui.tool.domain.model.WaveformPeaks
import cn.qishui.tool.feature.edit.lyrics.LyricsPanel
import cn.qishui.tool.feature.edit.waveform.WaveformCanvas
import cn.qishui.tool.feature.edit.waveform.WaveformGeometry
import cn.qishui.tool.feature.edit.waveform.WaveformSelection
import cn.qishui.tool.ui.components.TopSnackbarHost
import cn.qishui.tool.ui.components.DeleteSelectionSheet
import cn.qishui.tool.ui.components.PlainCard
import cn.qishui.tool.ui.components.PrimaryButton
import cn.qishui.tool.ui.components.QishuiFieldShape
import cn.qishui.tool.ui.components.QishuiTopBar
import cn.qishui.tool.ui.components.ScreenScroll
import cn.qishui.tool.ui.components.SecondaryButton
import cn.qishui.tool.ui.components.SectionHeader
import cn.qishui.tool.ui.components.SegmentedControl
import cn.qishui.tool.ui.components.SelectBox
import cn.qishui.tool.ui.components.SelectionActionChip
import java.util.Locale

@Composable
fun EditWorkspaceScreen(
    viewModel: EditWorkspaceViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val availableTracks by viewModel.availableTracks.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectingTracks by rememberSaveable { mutableStateOf(false) }
    var selectedTrackIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var confirmDeleteTracks by remember { mutableStateOf(false) }
    val operation = state.operation
    val durationUs = (state.track?.durationMs ?: 0L) * 1000L

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mpeg"),
    ) { uri -> uri?.let { viewModel.startExport(it.toString()) } }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importLocalAudio(it.toString()) } }

    val context = LocalContext.current
    val lyricsPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            viewModel.notify("歌词文件读取失败")
        } else {
            viewModel.importLyrics(text)
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(exportState.isSuccess, exportState.error) {
        when {
            exportState.isSuccess -> {
                snackbarHostState.showSnackbar("导出完成：已写入所选位置")
                viewModel.resetExportFeedback()
            }

            exportState.error != null -> {
                snackbarHostState.showSnackbar(exportState.error!!)
                viewModel.resetExportFeedback()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
        snackbarHost = { TopSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            QishuiTopBar(
                title = state.track?.title ?: operation.card().label,
                subtitle = listOfNotNull(operation.card().label, state.track?.artist)
                    .joinToString(" · ")
                    .ifBlank { operation.card().label },
                onBack = onBack,
            )
            ScreenScroll {
                if (state.track == null) {
                    PlainCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "还没有歌曲",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "导入本地音频后即可使用「${operation.card().label}」，源歌曲永不被覆盖。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PrimaryButton(
                                text = "导入歌曲",
                                onClick = { importPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (availableTracks.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "或选择已有歌曲",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            SelectionActionChip(
                                selecting = selectingTracks,
                                canDelete = selectedTrackIds.isNotEmpty(),
                                onToggleSelecting = { selectingTracks = true },
                                onDelete = { confirmDeleteTracks = true },
                            )
                        }
                        availableTracks.forEach { track ->
                            PlainCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (selectingTracks) {
                                            selectedTrackIds = if (track.id in selectedTrackIds) {
                                                selectedTrackIds - track.id
                                            } else {
                                                selectedTrackIds + track.id
                                            }
                                        } else {
                                            viewModel.selectTrack(track.id)
                                        }
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (selectingTracks) {
                                        SelectBox(
                                            selected = track.id in selectedTrackIds,
                                            description = "选择 ${track.title ?: "歌曲"}",
                                            onToggle = {
                                                selectedTrackIds = if (track.id in selectedTrackIds) {
                                                    selectedTrackIds - track.id
                                                } else {
                                                    selectedTrackIds + track.id
                                                }
                                            },
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = track.title ?: "未命名",
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = track.artist ?: "未知歌手",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    return@ScreenScroll
                }
                PlainCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "波形",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "${formatMs(state.startMs)} - ${formatMs(state.endMs)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when (val waveform = state.waveform) {
                            is WaveformUiState.Error -> Text(
                                text = waveform.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )

                            is WaveformUiState.Ready -> WaveformCanvas(
                                peaks = waveform.peaks,
                                durationUs = durationUs,
                                selection = WaveformSelection(
                                    startUs = state.startMs * 1000L,
                                    endUs = state.endMs * 1000L,
                                ),
                                positionUs = playback.positionMs * 1000L,
                                onSelectionChange = viewModel::setSelection,
                                onSeek = { viewModel.seekTo(it / 1000L) },
                            )

                            WaveformUiState.Loading -> WaveformCanvas(
                                peaks = null,
                                durationUs = durationUs,
                                selection = WaveformSelection(
                                    startUs = state.startMs * 1000L,
                                    endUs = state.endMs * 1000L,
                                ),
                                positionUs = playback.positionMs * 1000L,
                                onSelectionChange = viewModel::setSelection,
                                onSeek = { viewModel.seekTo(it / 1000L) },
                            )
                        }
                        val track = state.track
                        if (track != null) {
                            Text(
                                text = listOfNotNull(
                                    track.format?.uppercase(),
                                    track.sampleRateHz?.let { "$it Hz" },
                                    track.bitrateBps?.let { "${it / 1000} kbps" },
                                    if (track.lyrics.isNullOrBlank()) "无歌词" else "含歌词",
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                PlainCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("播放", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SecondaryButton(
                                text = if (playback.isPlaying) "暂停" else "播放",
                                onClick = viewModel::togglePlay,
                                enabled = state.track?.localPath != null,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "${formatMs(playback.positionMs)} / ${formatMs(playback.durationMs)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Slider(
                            value = playback.positionMs.toFloat(),
                            onValueChange = { viewModel.seekTo(it.toLong()) },
                            valueRange = 0f..playback.durationMs.coerceAtLeast(1L).toFloat(),
                            enabled = playback.durationMs > 0L,
                        )
                    }
                }

                PlainCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(operation.card().label, style = MaterialTheme.typography.titleMedium)
                        when (operation) {
                            EditOperation.TRIM -> {
                                LongField("起始 (ms)", state.startMs) {
                                    viewModel.setSelection(
                                        WaveformGeometry.normalize(it * 1000L, state.endMs * 1000L, durationUs),
                                    )
                                }
                                LongField("结束 (ms)", state.endMs) {
                                    viewModel.setSelection(
                                        WaveformGeometry.normalize(state.startMs * 1000L, it * 1000L, durationUs),
                                    )
                                }
                            }

                            EditOperation.SPLIT -> {
                                Text(
                                    text = "分割模式",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SegmentedControl(
                                    options = listOf("保留选中", "删除选中"),
                                    selectedIndex = if (state.mode == EditMode.KEEP_SELECTED) 0 else 1,
                                    onSelect = {
                                        viewModel.setMode(
                                            if (it == 0) EditMode.KEEP_SELECTED else EditMode.REMOVE_SELECTED,
                                        )
                                    },
                                )
                                LongField("分割点((ms)", state.splitMs, viewModel::setSplitMs)
                                PrimaryButton(
                                    text = "添加分割点",
                                    onClick = viewModel::addSplitPoint,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (state.splitPointsMs.isEmpty()) {
                                    Text(
                                        text = if (state.mode == EditMode.KEEP_SELECTED) {
                                            "至少添加一个分割点才能生成可保留区间，当前不可保存。"
                                        } else {
                                            "删除选中且没有选择时保留整轨。"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    state.splitPointsMs.forEach { point ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "分割点：${formatMs(point)}",
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            SecondaryButton(
                                                text = "移除",
                                                onClick = { viewModel.removeSplitPoint(point) },
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                modifier = Modifier.heightIn(min = 48.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            EditOperation.JOIN -> JoinedTrackChips(
                                tracks = availableTracks,
                                joinedIds = state.joinedTrackIds,
                                onToggle = viewModel::toggleJoinedTrack,
                            )

                            EditOperation.FADE_IN, EditOperation.FADE_OUT -> {
                                LongField("淡入 (ms)", state.fadeInMs, viewModel::setFadeInMs)
                                LongField("淡出 (ms)", state.fadeOutMs, viewModel::setFadeOutMs)
                            }

                            EditOperation.GAIN -> FloatField("增益 (dB)", state.gainDb, viewModel::setGainDb)
                            EditOperation.LYRIC_OFFSET -> LongField(
                                label = "歌词偏移 (ms)",
                                value = state.lyricOffsetMs,
                                onValueChange = viewModel::setLyricOffsetMs,
                            )
                        }
                    }
                }

                if (operation == EditOperation.JOIN) {
                    PlainCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("源序列", style = MaterialTheme.typography.titleMedium)
                            if (state.joinedTracks.isEmpty()) {
                                Text(
                                    text = "尚未选择歌曲",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            state.joinedTracks.forEachIndexed { index, joined ->
                                Text(
                                    text = "${index + 1}. ${joined.title ?: "未命名"}" +
                                        (if (index == 0) "（主轨）" else ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                PlainCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "歌词",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            SecondaryButton(
                                text = "导入歌词文件",
                                onClick = { lyricsPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                                modifier = Modifier.height(44.dp),
                            )
                        }
                        LyricsPanel(lyrics = state.lyrics, positionUs = playback.positionMs * 1000L)
                    }
                }

                PlainCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("时间线", style = MaterialTheme.typography.titleMedium)
                        if (state.segments.isEmpty()) {
                            Text(
                                text = "当前参数不产生任何区间",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            state.segments.forEachIndexed { index, segment ->
                                Text(
                                    text = "第 ${index + 1} 段：${formatMs(segment.sourceStartUs / 1000L)} - " +
                                        formatMs(segment.sourceEndUs / 1000L),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text(
                                text = "输出时长 ${formatMs(state.outputDurationUs / 1000L)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                SectionHeader(title = "导出")
                PlainCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PrimaryButton(
                            text = if (state.isSaving) "保存中" else "保存编辑项目",
                            onClick = viewModel::save,
                            enabled = state.canSave && !state.isSaving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SecondaryButton(
                            text = "导出 MP3",
                            onClick = { exportPicker.launch(state.exportFileName) },
                            enabled = exportState.isIdle && state.canSave,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (!exportState.isIdle || exportState.isSuccess || exportState.error != null) {
                            if (!exportState.isIdle) {
                                LinearProgressIndicator(
                                    progress = { exportState.fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Text(
                                text = exportState.statusLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (exportState.isRunning || exportState.isCopying) {
                            SecondaryButton(
                                text = "取消导出",
                                onClick = viewModel::cancelExport,
                                modifier = Modifier.fillMaxWidth(),
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (confirmDeleteTracks) {
        DeleteSelectionSheet(
            count = selectedTrackIds.size,
            description = "已选择 ${selectedTrackIds.size} 首歌曲。",
            onDismiss = { confirmDeleteTracks = false },
            onConfirm = { deleteFile ->
                viewModel.deleteTracks(selectedTrackIds.toList(), deleteFile)
                selectedTrackIds = emptySet()
                selectingTracks = false
                confirmDeleteTracks = false
            },
        )
    }
}

@Composable
private fun JoinedTrackChips(
    tracks: List<SourceTrack>,
    joinedIds: List<String>,
    onToggle: (String) -> Unit,
) {
    if (tracks.isEmpty()) {
        Text(
            text = "没有可拼接的歌曲",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tracks.forEach { track ->
            FilterChip(
                selected = track.id in joinedIds,
                onClick = { onToggle(track.id) },
                label = {
                    Text(
                        text = "${if (track.id in joinedIds) joinedIds.indexOf(track.id) + 1 else ""} " +
                            "${track.title ?: "未命名"}".trim(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun LongField(label: String, value: Long, onValueChange: (Long) -> Unit) {
    var text by remember(label) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { updated ->
            text = updated
            updated.toLongOrNull()?.let(onValueChange)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        shape = QishuiFieldShape,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun FloatField(label: String, value: Float, onValueChange: (Float) -> Unit) {
    var text by remember(label) { mutableStateOf(formatDb(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { updated ->
            text = updated
            updated.toFloatOrNull()?.let(onValueChange)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        shape = QishuiFieldShape,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun formatMs(value: Long): String {
    val safe = value.coerceAtLeast(0L)
    return String.format(Locale.getDefault(), "%d:%02d.%03d", safe / 60_000L, safe / 1000L % 60L, safe % 1000L)
}

private fun formatDb(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)