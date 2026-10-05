package cn.music.audioworkshop.feature.volume

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.PlaybackBar
import cn.music.audioworkshop.ui.components.PrimaryButton
import cn.music.audioworkshop.ui.components.QishuiFieldShape
import cn.music.audioworkshop.ui.components.QishuiSlider
import cn.music.audioworkshop.ui.components.SectionHeader
import cn.music.audioworkshop.ui.components.SegmentedControl
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val HINT = "仅调整电平，不改变时长与节奏。" +
    "百分比为线性倍率，分贝为对数单位。防炸音对超幅波峰做平滑压缩，而非硬削。"

/** 修改音量功能页。 */
@Composable
fun VolumeScreen(
    viewModel: VolumeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingValue by remember { mutableStateOf(false) }
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
        title = "修改音量",
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
        // ---- 试听：改参数立刻能听到效果 ----
        if (state.hasTrack) {
            SectionHeader(title = "试听")
            // PlaybackBar 内部已经是一张 PlainCard，外面不要再套 FloatingCard，
            // 否则会出现卡片套卡片的双边框。
            // 这里也不加额外 padding —— 其它卡片都靠 ScreenScroll 的 20dp 边距对齐，
            // 单独加一层会让它比别的卡片窄一圈、高度也不一致。
            PlaybackBar(
                playing = playback.isPlaying,
                // 渲染中禁用：此刻播的还是旧预览，按下去只会让人更困惑
                enabled = !state.isRenderingPreview,
                positionMs = if (state.isRenderingPreview) 0L else playback.positionMs,
                durationMs = state.durationUs / 1000L,
                onTogglePlay = viewModel::togglePlay,
                onSeek = viewModel::seekTo,
            )
            // 渲染耗时肉眼可见，必须给出明确反馈，否则用户会以为增益没生效
            if (state.isRenderingPreview) {
                Text(
                    text = "正在生成新效果…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                )
            }
        }

        // ---- 调整模式 ----
        SectionHeader(title = "调整方式")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SegmentedControl(
                    options = VolumeMode.entries.map { it.label },
                    selectedIndex = VolumeMode.entries.indexOf(state.mode),
                    onSelect = { index ->
                        VolumeMode.entries.getOrNull(index)?.let {
                            viewModel.setMode(it)
                            viewModel.renderPreview(playWhenReady = false)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- 数值 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = if (state.mode == VolumeMode.PERCENT) "音量百分比" else "增益分贝",
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "重置",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable {
                        when (state.mode) {
                            VolumeMode.PERCENT -> viewModel.setPercent(1f)
                            VolumeMode.DECIBEL -> viewModel.setDecibel(0f)
                        }
                        viewModel.renderPreview(playWhenReady = true)
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                when (state.mode) {
                    VolumeMode.PERCENT -> {
                        val percentValue = state.percent * 100f
                        ValueHeader(
                            primary = "${percentValue.roundToInt()}%",
                            secondary = "相当于 ${formatDb(state.gainDb)}",
                            onEdit = { editingValue = true },
                        )
                        QishuiSlider(
                            value = state.percent,
                            onValueChange = viewModel::setPercent,
                            valueRange = VolumeViewModel.MIN_PERCENT..VolumeViewModel.MAX_PERCENT,
                            label = "音量百分比",
                            // 只在松手后重渲染：拖动过程每帧都渲染一整首歌扛不住
                            onValueChangeFinished = { viewModel.renderPreview(playWhenReady = true) },
                        )
                        QuickValues(
                            // 上限已经是 800%，快捷值必须覆盖到实际能调的范围，
                            // 否则用户拉到 500% 附近没有任何一个快捷值可选
                            values = listOf(50f, 100f, 150f, 200f, 400f, 800f),
                            isSelected = { state.percent * 100f == it },
                            format = { "${it.roundToInt()}%" },
                            onPick = {
                                viewModel.setPercent(it / 100f)
                                viewModel.renderPreview(playWhenReady = true)
                            },
                        )
                    }

                    VolumeMode.DECIBEL -> {
                        ValueHeader(
                            primary = "${formatSignedDb(state.decibel)} dB",
                            secondary = "相当于 ${"%.2f".format(Locale.getDefault(), 10.0.pow(state.gainDb / 20.0))} 倍",
                            onEdit = { editingValue = true },
                        )
                        QishuiSlider(
                            value = state.decibel,
                            onValueChange = viewModel::setDecibel,
                            valueRange = VolumeViewModel.MIN_DECIBEL..VolumeViewModel.MAX_DECIBEL,
                            label = "增益分贝",
                            onValueChangeFinished = { viewModel.renderPreview(playWhenReady = true) },
                        )
                        QuickValues(
                            // 与 -24~+18dB 的实际范围对齐
                            values = listOf(-12f, -6f, 0f, 6f, 12f, 18f),
                            isSelected = { state.decibel.roundToInt() == it.roundToInt() },
                            format = { "${formatSignedDb(it)} dB" },
                            onPick = {
                                viewModel.setDecibel(it)
                                viewModel.renderPreview(playWhenReady = true)
                            },
                        )
                    }
                }
            }
        }

        // ---- 防炸音 ----
        SectionHeader(title = "防炸音")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.setPreventClipping(!state.preventClipping)
                        viewModel.renderPreview(playWhenReady = true)
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                androidx.compose.material3.Checkbox(
                    checked = state.preventClipping,
                    onCheckedChange = {
                        viewModel.setPreventClipping(it)
                        viewModel.renderPreview(playWhenReady = true)
                    },
                    modifier = Modifier.height(32.dp),
                )
                Text(
                    text = "防炸音",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
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

    if (editingValue) {
        NumberInputDialog(
            title = if (state.mode == VolumeMode.PERCENT) "音量百分比" else "增益分贝",
            initial = if (state.mode == VolumeMode.PERCENT) {
                (state.percent * 100f).roundToInt().toString()
            } else {
                formatSignedDb(state.decibel).toString()
            },
            suffix = if (state.mode == VolumeMode.PERCENT) "%" else "dB",
            allowNegative = state.mode == VolumeMode.DECIBEL,
            onDismiss = { editingValue = false },
            onConfirm = { text ->
                val value = text.toFloatOrNull()
                if (value == null) {
                    scope.launch { snackbarHostState.showSnackbar("请输入数字") }
                } else {
                    when (state.mode) {
                        VolumeMode.PERCENT -> viewModel.setPercent(value / 100f)
                        VolumeMode.DECIBEL -> viewModel.setDecibel(value)
                    }
                    viewModel.renderPreview(playWhenReady = true)
                }
                editingValue = false
            },
        )
    }
}

/** 单值输入弹窗。用于滑杆旁边做精确输入。 */
@Composable
private fun NumberInputDialog(
    title: String,
    initial: String,
    suffix: String,
    allowNegative: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    val filtered = input.filter { it.isDigit() || (allowNegative && (it == '-' || it == '+')) }
                    text = if (filtered.isEmpty() || filtered == "-") filtered else filtered
                },
                modifier = Modifier.fillMaxWidth(),
                shape = QishuiFieldShape,
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (allowNegative) {
                        androidx.compose.ui.text.input.KeyboardType.Number
                    } else {
                        androidx.compose.ui.text.input.KeyboardType.Number
                    },
                ),
                trailingIcon = {
                    Text(
                        text = suffix,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        },
        confirmButton = {
            Text(
                text = "确定",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clickable { onConfirm(text) },
            )
        },
        dismissButton = {
            Text(
                text = "取消",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onDismiss),
            )
        },
    )
}

@Composable
private fun ValueHeader(primary: String, secondary: String, onEdit: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier)
            .padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primary,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 有意做成可点的：点数字能直接输入，比拖滑杆精确
        Text(
            text = "点此输入",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 常用值快捷按钮：比拖滑杆精确，比打字快。 */
@Composable
private fun QuickValues(
    values: List<Float>,
    isSelected: (Float) -> Boolean,
    format: (Float) -> String,
    onPick: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 6 个快捷值一行放不下（每格约 55dp），挤在一起字会换行。
        // 分两行更稳，也方便点按。
        values.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { value ->
                    val selected = isSelected(value)
                    Text(
                        text = format(value),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                MaterialTheme.shapes.small,
                            )
                            .clickable { onPick(value) }
                            .padding(vertical = 9.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                // 补齐空位，让每行三个按钮等宽
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun formatDb(db: Float): String = if (db >= 0f) "+${db.roundToInt()} dB" else "${db.roundToInt()} dB"

private fun formatSignedDb(db: Float): String =
    if (db >= 0f) "+${db.roundToInt()}" else "${db.roundToInt()}"
