package cn.music.audioworkshop.feature.convert

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.domain.media.ExportFormat
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.ConfirmSheet
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.OptionRow
import cn.music.audioworkshop.ui.components.PrimaryButton
import cn.music.audioworkshop.ui.components.SecondaryButton
import cn.music.audioworkshop.ui.components.SectionHeader

private const val HINT = "仅更换容器，不重新编码音频。" +
    "无损格式（wav / flac）不设比特率；mp3 不支持 8000 Hz 一类低采样率。导出至设置指定的下载目录，不覆盖源文件。"

/** 格式转换功能页。 */
@Composable
fun ConvertScreen(
    viewModel: ConvertViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<Picker?>(null) }

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
        title = "格式转换",
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
                text = if (export.isRunning) "导出中" else "开始转换",
                // 点它先弹重命名框，确认文件名才真的开始转换
                onClick = requestExport,
                // 未导入也可点，点了给「请先导入音频」，不静默禁用让用户猜
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
        onExport = { name -> viewModel.convert(name) },
        onCancelExport = viewModel::cancelExport,
    ) {
        SectionHeader(title = "输出格式")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OptionRow(
                    label = "格式",
                    value = state.format.label,
                    onClick = { picking = Picker.FORMAT },
                )
                OptionRow(
                    label = "采样率",
                    value = state.sampleRateHz.takeIf { it > 0 }?.let { "$it Hz" } ?: "跟随源",
                    onClick = { picking = Picker.SAMPLE_RATE },
                )
                OptionRow(
                    label = "比特率",
                    value = when {
                        !state.format.supportsBitrate -> "无损格式不适用"
                        state.bitrateKbps > 0 -> "${state.bitrateKbps} kbps"
                        else -> "自动"
                    },
                    onClick = { picking = Picker.BITRATE },
                )
                OptionRow(
                    label = "声道",
                    value = state.channels.label,
                    onClick = { picking = Picker.CHANNELS },
                )
            }
        }

        // 导出成功后明确告诉用户文件落在哪、叫什么
        state.publishedLocation?.let { location ->
            SectionHeader(title = "导出结果")
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "导出成功",
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = location,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    // 进度弹窗与成功/失败提示都由 FunctionShell 托管，这里不再重复弹一次。

    picking?.let { which ->
        val options = when (which) {
            Picker.FORMAT -> ExportFormat.entries.map { it.label }
            // 只列该格式真支持的采样率，非法组合在界面就选不到（规格 5A）
            Picker.SAMPLE_RATE -> listOf("跟随源") + state.format.supportedSampleRates.map { "$it Hz" }
            Picker.BITRATE -> listOf("自动") + state.format.supportedBitrates.map { "$it kbps" }
            Picker.CHANNELS -> ChannelOption.entries.map { it.label }
        }
        val selected = when (which) {
            Picker.FORMAT -> state.format.label
            Picker.SAMPLE_RATE -> state.sampleRateHz.takeIf { it > 0 }?.let { "$it Hz" } ?: "跟随源"
            Picker.BITRATE -> when {
                !state.format.supportsBitrate -> null
                state.bitrateKbps > 0 -> "${state.bitrateKbps} kbps"
                else -> "自动"
            }
            Picker.CHANNELS -> state.channels.label
        }
        ConfirmSheet(
            title = when (which) {
                Picker.FORMAT -> "输出格式"
                Picker.SAMPLE_RATE -> "采样率"
                Picker.BITRATE -> "比特率"
                Picker.CHANNELS -> "声道"
            },
            confirmLabel = "关闭",
            onConfirm = { picking = null },
            onDismiss = { picking = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    SecondaryButton(
                        text = if (option == selected) "$option ✓" else option,
                        onClick = {
                            when (which) {
                                Picker.FORMAT -> ExportFormat.entries.first { it.label == option }
                                    .let(viewModel::setFormat)

                                Picker.SAMPLE_RATE -> viewModel.setSampleRate(
                                    option.removeSuffix(" Hz").toIntOrNull() ?: 0,
                                )

                                Picker.BITRATE -> viewModel.setBitrate(
                                    option.removeSuffix(" kbps").toIntOrNull() ?: 0,
                                )

                                Picker.CHANNELS -> ChannelOption.entries
                                    .first { it.label == option }
                                    .let(viewModel::setChannels)
                            }
                            picking = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private enum class Picker { FORMAT, SAMPLE_RATE, BITRATE, CHANNELS }
