package cn.music.audioworkshop.feature.stereoorbit

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

private const val HINT = "让声音在左右之间转圈。半圈时间决定转得多快，" +
    "环绕幅度决定绕得多远（半径）。两个都调完自动重新生成预览。"

/** 立体声环绕。 */
@Composable
fun StereoOrbitScreen(
    viewModel: StereoOrbitViewModel,
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
        title = "立体声环绕",
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

            SectionHeader(title = "环绕参数")
            // 卡片不加横向 padding，宽度和上面的 PlaybackBar 完全一致。
            // 每行都是「文字 | 滑杆 | 数值」三列 —— 光秃秃的滑杆看不出当前是多少。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                FloatingCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        ParamRow(
                            label = "半圈时间",
                            valueText = "${formatSeconds(state.halfCircleSec)} 秒",
                            percent = fractionOf(state.halfCircleSec, viewModel.halfCircleRange),
                        ) {
                            QishuiSlider(
                                value = state.halfCircleSec,
                                onValueChange = { viewModel.setHalfCircleSec(it) },
                                valueRange = viewModel.halfCircleRange,
                                label = "半圈时间",
                                enabled = !state.isRenderingPreview,
                                onValueChangeFinished = { viewModel.renderPreview() },
                            )
                        }
                        ParamRow(
                            label = "环绕幅度",
                            valueText = "${state.degrees.toInt()}°",
                            percent = fractionOf(state.degrees, viewModel.degreesRange),
                        ) {
                            QishuiSlider(
                                value = state.degrees,
                                onValueChange = { viewModel.setDegrees(it) },
                                valueRange = viewModel.degreesRange,
                                label = "环绕幅度",
                                enabled = !state.isRenderingPreview,
                                onValueChangeFinished = { viewModel.renderPreview() },
                            )
                        }
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

/**
 * 一行参数：左边名称，中间滑杆，右边数值 + 百分比。
 *
 * 数值和百分比都要 —— 滑杆只看得出「大概在哪」，看不出「到底是几秒几度」。
 */
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
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "  $percent%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        slider()
    }
}

/** 当前值在区间里的百分比，四舍五入到整数。 */
private fun fractionOf(value: Float, range: ClosedFloatingPointRange<Float>): Int {
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0
    return (((value - range.start) / span) * 100f).toInt().coerceIn(0, 100)
}

private fun formatSeconds(value: Float): String =
    if (value >= 10f) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

private fun displayNameOf(uri: String): String =
    uri.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        .takeIf { it.isNotBlank() }?.take(60) ?: "音频"

