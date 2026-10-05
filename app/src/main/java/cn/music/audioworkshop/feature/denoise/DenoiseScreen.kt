package cn.music.audioworkshop.feature.denoise

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.domain.model.DenoiseMode
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
import java.util.Locale

private const val HINT = "通用降噪按频段抑制，设定区间外的成分衰减。" +
    "录音降噪以语音模型区分人声与噪声，适配两者频谱接近的场景。"

/** 降噪功能页。 */
@Composable
fun DenoiseScreen(
    viewModel: DenoiseViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScopeCompat()
    var editingFreq by remember { mutableStateOf<FreqTarget?>(null) }

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
        title = "降噪",
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

        // ---- 降噪方式 ----
        SectionHeader(title = "降噪方式")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SegmentedControl(
                    options = listOf(DenoiseMode.GENERAL.label, DenoiseMode.SPEECH.label),
                    selectedIndex = if (state.mode == DenoiseMode.SPEECH) 1 else 0,
                    onSelect = { index ->
                        viewModel.setMode(if (index == 1) DenoiseMode.SPEECH else DenoiseMode.GENERAL)
                        viewModel.renderPreview(playWhenReady = true)
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

        // ---- 频段（只有通用模式有） ----
        if (state.showsFrequency) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(title = "保留频段", modifier = Modifier.weight(1f))
                Text(
                    text = "恢复默认",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            viewModel.resetFrequency()
                            viewModel.renderPreview(playWhenReady = true)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    FreqRow(
                        label = "起始频率",
                        caption = "低于 ${state.lowHz}Hz 的部分被压掉，比如隆隆声和震动",
                        hz = state.lowHz,
                        maxHz = state.highHz,
                        onHzChange = { viewModel.setLowHz(it) },
                        onFinished = { viewModel.renderPreview(playWhenReady = true) },
                        onRequestInput = { editingFreq = FreqTarget.LOW },
                    )
                    FreqRow(
                        label = "结束频率",
                        caption = "高于 ${state.highHz}Hz 的部分被压掉，比如嘶嘶声",
                        hz = state.highHz,
                        minHz = state.lowHz,
                        onHzChange = { viewModel.setHighHz(it) },
                        onFinished = { viewModel.renderPreview(playWhenReady = true) },
                        onRequestInput = { editingFreq = FreqTarget.HIGH },
                    )
                }
            }
        } else {
            // 录音降噪不给频率参数，但要说清原因，否则用户以为是漏了
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "录音降噪不需要设频率。语音模型按波形判断哪一段是人声、哪一段是噪声，" +
                        "自动避开人声 —— 这正是它比通用降噪更适合人声录音和采访的原因。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        // ---- 强度 ----
        SectionHeader(title = "降噪强度")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "强度",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${(state.strength * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "往右噪声压得越狠，但也可能把人声细节一起削掉",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QishuiSlider(
                    value = state.strength,
                    onValueChange = viewModel::setStrength,
                    valueRange = 0f..1f,
                    label = "降噪强度",
                    onValueChangeFinished = { viewModel.renderPreview(playWhenReady = true) },
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

    // 频率跨度太大（20Hz~20kHz），滑杆调不准，点数字直接输入
    editingFreq?.let { target ->
        val isLow = target == FreqTarget.LOW
        NumberInputDialog(
            title = if (isLow) "起始频率" else "结束频率",
            initial = (if (isLow) state.lowHz else state.highHz).toString(),
            suffix = "Hz",
            onDismiss = { editingFreq = null },
            onConfirm = { text ->
                val hz = text.toIntOrNull()
                if (hz == null) {
                    scope.launch { snackbarHostState.showSnackbar("请输入整数") }
                } else {
                    if (isLow) viewModel.setLowHz(hz) else viewModel.setHighHz(hz)
                    viewModel.renderPreview(playWhenReady = true)
                }
                editingFreq = null
            },
        )
    }
}

private enum class FreqTarget { LOW, HIGH }

/**
 * 频率行：滑杆 + 可点击数值。
 *
 * 用对数刻度而不是线性 —— 频率是倍频关系，线性滑杆会让 20~200Hz 挤在
 * 起点一点点上，实际根本拖不准。
 */
@Composable
private fun FreqRow(
    label: String,
    caption: String,
    hz: Int,
    onHzChange: (Int) -> Unit,
    onFinished: () -> Unit,
    onRequestInput: () -> Unit,
    minHz: Int = DenoiseMode.MIN_FREQ_HZ,
    maxHz: Int = DenoiseMode.MAX_FREQ_HZ,
) {
    val minLog = kotlin.math.ln(minHz.toFloat())
    val maxLog = kotlin.math.ln(maxHz.toFloat())
    val fraction = ((kotlin.math.ln(hz.toFloat()) - minLog) / (maxLog - minLog)).coerceIn(0f, 1f)

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${formatHz(hz)} Hz",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
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
        QishuiSlider(
            value = fraction,
            onValueChange = { f ->
                val target = kotlin.math.exp(minLog + f * (maxLog - minLog))
                onHzChange((Math.round(target)).toInt())
            },
            valueRange = 0f..1f,
            label = "$label 赫兹",
            onValueChangeFinished = onFinished,
        )
    }
}

/** 大频率用 kHz 显示，1000 以上读 `16.0k` 比 `16000` 好认。 */
private fun formatHz(hz: Int): String =
    if (hz >= 1000) String.format(Locale.US, "%.1fk", hz / 1000f) else hz.toString()

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()

