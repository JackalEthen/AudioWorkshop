package cn.qishui.tool.feature.effect

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qishui.tool.R
import cn.qishui.tool.domain.model.SourceTrack
import cn.qishui.tool.media.effect.EffectParam
import cn.qishui.tool.ui.components.EmptyState
import cn.qishui.tool.ui.components.InfoHintAction
import cn.qishui.tool.ui.components.InfoHintBox
import cn.qishui.tool.ui.components.LucideIcon
import cn.qishui.tool.ui.components.ParamCard
import cn.qishui.tool.ui.components.ParamRow
import cn.qishui.tool.ui.components.PlainCard
import cn.qishui.tool.ui.components.PrimaryButton
import cn.qishui.tool.ui.components.QishuiTopBar
import cn.qishui.tool.ui.components.ScreenScroll
import cn.qishui.tool.ui.components.SecondaryButton
import cn.qishui.tool.ui.components.SectionHeader
import cn.qishui.tool.ui.components.SelectBox
import cn.qishui.tool.ui.components.TopSnackbarHost
import cn.qishui.tool.ui.components.formatNumber

@Composable
fun EffectWorkspaceScreen(
    viewModel: EffectWorkspaceViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val definition = state.definition
    val currentTrack = remember(state.selectedTrackIds, tracks) {
        tracks.firstOrNull { it.id == state.selectedTrackIds.firstOrNull() }
    }
    val hint = effectHint(definition.id)
    var hintVisible by remember { mutableStateOf(false) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mpeg"),
    ) { uri -> uri?.let { viewModel.processAndExport(it.toString()) } }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importLocalAudio(it.toString()) } }

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
                title = definition.label,
                onBack = onBack,
                actionContent = {
                    InfoHintAction(
                        hint = hint,
                        expanded = hintVisible,
                        onToggle = { hintVisible = !hintVisible },
                    )
                },
            )
            ScreenScroll {
                InfoHintBox(hint = hint, visible = hintVisible, onDismiss = { hintVisible = false })

                TrackPicker(
                    track = currentTrack,
                    label = if (definition.multiTrack) "已选 ${state.selectedTrackIds.size} 首" else null,
                    onPick = { importPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                )

                if (definition.params.isNotEmpty()) {
                    ParamCard(
                        title = "${definition.label}设置",
                        onReset = { viewModel.resetParams() },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            definition.params.forEach { param ->
                                ParamRow(
                                    label = param.label,
                                    valueText = "${formatNumber(state.values[param.id] ?: param.default)}${param.unit}",
                                    value = state.values[param.id] ?: param.default,
                                    range = param.min..param.max,
                                    onChange = { viewModel.setValue(param.id, it) },
                                    stepButtons = param.step < 5f,
                                )
                            }
                        }
                    }
                }

                SectionHeader(
                    title = if (definition.multiTrack) "选择歌曲（可多选）" else "选择歌曲",
                )
                if (tracks.isEmpty()) {
                    EmptyState(text = "还没有歌曲", hint = "先导入本地音频，或在解析页下载歌曲。")
                } else {
                    tracks.forEach { track ->
                        TrackRow(
                            track = track,
                            selecting = definition.multiTrack,
                            selected = track.id in state.selectedTrackIds,
                            onToggle = { viewModel.toggleTrack(track.id) },
                        )
                    }
                }

                SectionHeader(title = "导出")
                PlainCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.isProcessing) {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        PrimaryButton(
                            text = if (state.isProcessing) "处理中" else "处理并导出 MP3",
                            onClick = { exportPicker.launch("${definition.id}.mp3") },
                            enabled = state.selectedTrackIds.isNotEmpty() &&
                                !state.isProcessing &&
                                exportState.isIdle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (!exportState.isIdle) {
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
}

@Composable
private fun TrackPicker(
    track: SourceTrack?,
    label: String?,
    onPick: () -> Unit,
) {
    PlainCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LucideIcon(
                icon = R.drawable.ic_music,
                tint = MaterialTheme.colorScheme.primary,
                size = 24.dp,
            )
            Text(
                text = track?.title ?: "点击导入音乐",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LucideIcon(
                icon = R.drawable.ic_chevron_right,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: SourceTrack,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    PlainCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                SelectBox(
                    selected = selected,
                    description = "选择 ${track.title ?: "歌曲"}",
                    onToggle = onToggle,
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
