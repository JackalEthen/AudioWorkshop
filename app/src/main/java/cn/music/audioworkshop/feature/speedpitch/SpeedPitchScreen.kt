package cn.music.audioworkshop.feature.speedpitch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.NumberInputDialog
import cn.music.audioworkshop.ui.components.PlaybackBar
import cn.music.audioworkshop.ui.components.PrimaryButton
import cn.music.audioworkshop.ui.components.QishuiSlider
import cn.music.audioworkshop.ui.components.SectionHeader
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val HINT = "速度与音调为两个独立参数，互不影响。" +
    "单独变速不改变音调，单独变调不改变时长。"

/** 变速变调功能页。 */
@Composable
fun SpeedPitchScreen(
    viewModel: SpeedPitchViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
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
        title = "变速变调",
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
                onClick = { viewModel.export() },
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
                enabled = !state.isRenderingPreview,
                positionMs = if (state.isRenderingPreview) 0L else playback.positionMs,
                // 预览只渲染开头一段，所以这里显示那一段变速后的长度
                durationMs = state.previewDurationMs,
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

        // ---- 速度 ----
        SectionHeader(title = "速度")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                ParamRow(
                    label = "速度",
                    valueText = "${"%.2f".format(Locale.getDefault(), state.speed)}x",
                    caption = "2x 是一半时长，0.5x 是一倍时长",
                    onRequestInput = { editing = EditTarget.SPEED },
                )
                QishuiSlider(
                    value = state.speed,
                    onValueChange = viewModel::setSpeed,
                    valueRange = SpeedPitchUiState.MIN_SPEED..SpeedPitchUiState.MAX_SPEED,
                    label = "速度倍率",
                    onValueChangeFinished = { viewModel.renderPreview(playWhenReady = true) },
                )
            }
        }

        // ---- 音调 ----
        SectionHeader(title = "音调")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                ParamRow(
                    label = "音调",
                    valueText = formatSemitones(state.semitones),
                    caption = "正数升调、负数降调，一个八度是 12 个半音",
                    onRequestInput = { editing = EditTarget.SEMITONES },
                )
                QishuiSlider(
                    value = state.semitones,
                    onValueChange = viewModel::setSemitones,
                    valueRange = SpeedPitchUiState.MIN_SEMITONES..SpeedPitchUiState.MAX_SEMITONES,
                    label = "变调半音数",
                    onValueChangeFinished = { viewModel.renderPreview(playWhenReady = true) },
                )
            }
        }

        // ---- 时长变化 ----
        if (state.hasTrack && state.isModified) {
            SectionHeader(title = "时长变化")
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    DurationStat("原曲", state.durationUs / 1000L)
                    DurationStat(
                        label = "变调后",
                        valueMs = state.outputDurationMs,
                        accent = true,
                    )
                }
            }
        }

        // ---- 重置 ----
        if (state.hasTrack && state.isModified) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(title = "重置", modifier = Modifier.weight(1f))
                Text(
                    text = "回到原速原调",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            viewModel.reset()
                            viewModel.renderPreview(playWhenReady = true)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
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
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    // 滑杆调不出 1.37x 这种值，点数字直接输入
    editing?.let { target ->
        val isSpeed = target == EditTarget.SPEED
        NumberInputDialog(
            title = if (isSpeed) "速度倍率" else "变调半音数",
            initial = if (isSpeed) {
                "${state.speed}"
            } else {
                "${state.semitones}"
            },
            suffix = if (isSpeed) "x" else "半音",
            allowNegative = !isSpeed,
            onDismiss = { editing = null },
            onConfirm = { text ->
                val value = text.toFloatOrNull()
                if (value == null) {
                    scope.launch { snackbarHostState.showSnackbar("请输入数字") }
                } else {
                    if (isSpeed) viewModel.setSpeed(value) else viewModel.setSemitones(value)
                    viewModel.renderPreview(playWhenReady = true)
                }
                editing = null
            },
        )
    }
}

private enum class EditTarget { SPEED, SEMITONES }

/**
 * 参数行：标题 + 可点击的数值。
 *
 * 只有滑杆和点击输入，没有加减按钮 —— 步进按钮在手机上很容易被误触，
 * 而且真要 ±0.05 这种精度，用户直接输数字更快。
 */
@Composable
private fun ParamRow(
    label: String,
    valueText: String,
    caption: String,
    onRequestInput: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .clickable(onClick = onRequestInput)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DurationStat(label: String, valueMs: Long, accent: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatMs(valueMs),
            style = MaterialTheme.typography.headlineSmall,
            color = if (accent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatSemitones(value: Float): String = when {
    abs(value) < 0.01f -> "原调"
    value > 0f -> "+${trimNumber(value)} 半音"
    else -> "-${trimNumber(-value)} 半音"
}

/** 去掉小数末尾多余的 0：1.50 -> 1.5，12.00 -> 12。 */
private fun trimNumber(value: Float): String =
    "%.2f".format(Locale.getDefault(), value).trimEnd('0').trimEnd('.')

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) {
        "${minutes}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${seconds} 秒"
    }
}
