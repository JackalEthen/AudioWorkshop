package cn.music.audioworkshop.feature.loudness

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.BottomBarScope
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.PlaybackBar
import cn.music.audioworkshop.ui.components.QishuiSlider
import cn.music.audioworkshop.ui.components.SectionHeader
import java.util.Locale
import kotlin.math.abs

private const val HINT = "按 EBU R128 整体响度（LUFS）校准音量。目标 -14 LUFS 为流媒体通用值。"

/** 响度标准化。 */
@Composable
fun LoudnessScreen(
    viewModel: LoudnessViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBarSlot: @Composable BottomBarScope.(canExport: Boolean, label: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.import(it.toString(), displayNameOf(it.toString())) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    FunctionShell(
        modifier = modifier,
        title = "响度标准化",
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
            bottomBarSlot(
                state.canExport,
                when {
                    export.isRunning -> "处理中"
                    !state.hasTrack -> "请先导入音频"
                    state.isRenderingPreview -> "生成预览中"
                    else -> "导出"
                },
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
        onExport = { viewModel.export(it) },
        onCancelExport = { viewModel.cancelExport() },
    ) {
        if (state.hasTrack) {
            SectionHeader(title = "试听")
            PlaybackBar(
                playing = playback.isPlaying,
                enabled = !state.isRenderingPreview,
                positionMs = if (state.isRenderingPreview) 0L else playback.positionMs,
                durationMs = state.durationUs / 1000L,
                onTogglePlay = { viewModel.togglePlay() },
                onSeek = { viewModel.seekTo(it) },
            )

            SectionHeader(title = "目标响度")
            // 卡片不加横向 padding，宽度和上面的 PlaybackBar 一致
            Column(modifier = Modifier.fillMaxWidth()) {
                FloatingCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        ParamRow(
                            label = "目标响度",
                            valueText = "${lufs(state.targetLufs)} LUFS",
                            percent = percentOf(state.targetLufs, state.targetRange),
                        ) {
                            QishuiSlider(
                                value = state.targetLufs,
                                onValueChange = { viewModel.setTargetLufs(it) },
                                valueRange = state.targetRange,
                                label = "目标响度",
                                enabled = !state.isRenderingPreview,
                                // 松手才重渲染：拖一次触发几十次全文件重算没法用
                                onValueChangeFinished = { viewModel.renderPreview() },
                            )
                        }
                    }
                }
            }

            SectionHeader(title = "当前状态")
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    InfoRow("实测响度", measuredText(state))
                    state.gainDb?.let { gain ->
                        InfoRow(
                            "将施加增益",
                            String.format(Locale.US, "%+.1f dB", gain),
                        )
                    }
                }
            }

            state.publishedLocation?.let { location ->
                SectionHeader(title = "已导出")
                FloatingCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "已导出 MP3",
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

/** 一行参数：左边名称，右边数值 + 百分比，下面跟滑杆。 */
@Composable
private fun ParamRow(
    label: String,
    valueText: String,
    percent: Int,
    slider: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(text = valueText, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "  $percent%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        slider()
    }
}

/** 一行「名称 | 值」，只读信息用。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun measuredText(state: LoudnessUiState): String = when {
    state.isMeasuring -> "测量中…"
    state.measuredLufs != null -> "${lufs(state.measuredLufs)} LUFS"
    else -> "无法测量"
}

/** LUFS 显示一位小数就够，读起来更省事。 */
private fun lufs(value: Float): String = String.format(Locale.US, "%.1f", value)

private fun percentOf(value: Float, range: ClosedFloatingPointRange<Float>): Int {
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0
    return (((value - range.start) / span) * 100f).toInt().coerceIn(0, 100)
}

private fun displayNameOf(uri: String): String =
    uri.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        .takeIf { it.isNotBlank() }?.take(60) ?: "音频"
