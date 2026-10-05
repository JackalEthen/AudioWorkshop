package cn.music.audioworkshop.feature.equalizer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.media.effect.PcmEffects
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.NumberInputDialog
import cn.music.audioworkshop.ui.components.PlaybackBar
import cn.music.audioworkshop.ui.components.PrimaryButton
import cn.music.audioworkshop.ui.components.QishuiSlider
import cn.music.audioworkshop.ui.components.SectionHeader
import cn.music.audioworkshop.ui.components.SegmentedControl
import kotlinx.coroutines.launch

private const val HINT = "八段图形均衡，频段由低到高排列。" +
    "衰减用于抑制特定频段的刺耳感，全部归零即为原样。"

/** 均衡器功能页。 */
@Composable
fun EqualizerScreen(
    viewModel: EqualizerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingBand by remember { mutableStateOf<Int?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importLocalAudio(it.toString()) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    FunctionShell(
        modifier = modifier,
        title = "均衡器",
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
                    else -> "导出"
                },
                onClick = requestExport,
                enabled = !state.isWorking && !export.isRunning,
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
        // ---- 试听 ----
        if (state.hasTrack) {
            SectionHeader(title = "试听")
            PlaybackBar(
                playing = playback.isPlaying,
                // 渲染中禁用：此刻播的还是旧预览，按下去只会让人更困惑
                enabled = !state.isRenderingPreview,
                positionMs = if (state.isRenderingPreview) 0L else playback.positionMs,
                durationMs = state.durationUs / 1000L,
                onTogglePlay = viewModel::togglePlay,
                onSeek = viewModel::seekTo,
            )
            if (state.isRenderingPreview) {
                Text(
                    text = "正在生成新效果…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                )
            }
        }

        // ---- 预设 ----
        SectionHeader(title = "预设曲线")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                SegmentedControl(
                    options = EqPreset.LABELS,
                    selectedIndex = EqPreset.entries.indexOf(state.matchedPreset),
                    onSelect = { index ->
                        EqPreset.entries.getOrNull(index)?.let {
                            viewModel.applyPreset(it)
                            viewModel.renderPreview(playWhenReady = true)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.isModified) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已自定义，可点「自定义」回到全平直",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "全部归零",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable {
                                    viewModel.reset()
                                    viewModel.renderPreview(playWhenReady = true)
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // ---- 八个频段 ----
        SectionHeader(title = "频段调节")
        state.gainsDb.forEachIndexed { index, db ->
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = bandLabel(index),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (kotlin.math.abs(db) < 0.05f) {
                                "0.0 dB"
                            } else if (db > 0f) {
                                "+%.1f dB".format(java.util.Locale.US, db)
                            } else {
                                "%.1f dB".format(java.util.Locale.US, db)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (kotlin.math.abs(db) < 0.05f) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier
                                .clickable { editingBand = index }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    QishuiSlider(
                        value = db,
                        onValueChange = { viewModel.setGain(index, it) },
                        valueRange = -EqualizerUiState.MAX_GAIN_DB..EqualizerUiState.MAX_GAIN_DB,
                        label = "${bandLabel(index)} 增益",
                        onValueChangeFinished = { viewModel.renderPreview(playWhenReady = true) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
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
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    // 滑杆调不出 +3.7 这种值，点数字直接输入
    editingBand?.let { index ->
        val current = state.gainsDb.getOrElse(index) { 0f }
        NumberInputDialog(
            title = "${bandLabel(index)} 增益",
            initial = "%.1f".format(java.util.Locale.US, current),
            suffix = "dB",
            allowNegative = true,
            onDismiss = { editingBand = null },
            onConfirm = { text ->
                text.toFloatOrNull()?.let { viewModel.setGain(index, it) }
                    ?: scope.launch { snackbarHostState.showSnackbar("请输入数字") }
                if (text.toFloatOrNull() != null) viewModel.renderPreview(playWhenReady = true)
                editingBand = null
            },
        )
    }
}

/** 频段名直接复用引擎层的标签，预览和导出用的是同一套划分。 */
private fun bandLabel(index: Int): String =
    PcmEffects.EQ_BAND_LABELS.getOrElse(index) { "${index + 1}段" }

