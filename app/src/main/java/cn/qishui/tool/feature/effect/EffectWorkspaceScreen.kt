package cn.qishui.tool.feature.effect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qishui.tool.R
import cn.qishui.tool.domain.media.ExportFormat
import cn.qishui.tool.media.effect.EffectDefinition
import cn.qishui.tool.media.effect.EffectParam
import cn.qishui.tool.media.effect.EffectPreset
import cn.qishui.tool.media.effect.SegmentRange
import cn.qishui.tool.ui.components.formatClockMs
import cn.qishui.tool.domain.model.SourceTrack
import cn.qishui.tool.ui.components.ChoiceRow
import cn.qishui.tool.ui.components.BandRow
import cn.qishui.tool.ui.components.ConfirmSheet
import cn.qishui.tool.ui.components.EmptyState
import cn.qishui.tool.ui.components.FunctionShell
import cn.qishui.tool.ui.components.FilterRow
import cn.qishui.tool.ui.components.LucideIcon
import cn.qishui.tool.ui.components.OptionRow
import cn.qishui.tool.ui.components.ParamCard
import cn.qishui.tool.ui.components.ParamRow
import cn.qishui.tool.ui.components.PlayCircleButton
import cn.qishui.tool.ui.components.PlaybackBar
import cn.qishui.tool.ui.components.PlainCard
import cn.qishui.tool.ui.components.PrimaryButton
import cn.qishui.tool.ui.components.SecondaryButton
import cn.qishui.tool.ui.components.SectionHeader
import cn.qishui.tool.ui.components.SelectBox
import cn.qishui.tool.ui.components.QishuiSlider
import cn.qishui.tool.ui.components.StepperRow
import cn.qishui.tool.ui.components.ValueDisplayBox
import cn.qishui.tool.ui.components.StepperTile
import cn.qishui.tool.ui.components.TimeStepperField
import cn.qishui.tool.ui.components.ImportTrackCard
import cn.qishui.tool.ui.components.LabeledTimeField
import cn.qishui.tool.ui.components.TimeValueTile
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
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val definition = state.definition
    val currentTrack = remember(state.selectedTrackIds, tracks) {
        tracks.firstOrNull { it.id == state.selectedTrackIds.firstOrNull() }
    }
    val busy = state.isProcessing || exportState.isRunning || exportState.isCopying
    var pickingTrack by rememberSaveable { mutableStateOf(false) }
    // 立体声合成：0=左声道槽，1=右声道槽，null=没在选歌
    // 立体声合成的左右槽：null = 走普通导入，0/1 = 导入结果填进对应声道
    var pickingSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    // 混音时间轴缩放，1f = 最窄
    var mixZoom by rememberSaveable { mutableFloatStateOf(1f) }
    // 收音机音效 / 降噪：哪一项选择项的下拉弹层正开着
    var pickingRowChoice by rememberSaveable { mutableStateOf<String?>(null) }
    // 合成：关掉就只播原文件，不渲染
    var previewRealEffect by rememberSaveable { mutableStateOf(true) }
    // 正在编辑的片段下标，null = 没在编辑
    var editingSegment by rememberSaveable { mutableStateOf<Int?>(null) }
    var pickingFormat by rememberSaveable { mutableStateOf(false) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(state.exportFormat.mimeType),
    ) { uri -> uri?.let { viewModel.processAndExport(it.toString()) } }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // pickingSlot 决定结果填进普通选歌还是左右声道槽
        val slot = pickingSlot
        pickingSlot = null
        uri?.let { viewModel.importLocalAudio(it.toString(), slot) }
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

    FunctionShell(
        title = definition.label,
        hint = effectHint(definition.id),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        bottomBar = {
            PlaybackBar(
                playing = playback.isPlaying,
                enabled = currentTrack?.localPath != null,
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                onTogglePlay = viewModel::togglePlay,
                onSeek = viewModel::seekTo,
            )
            if (state.isProcessing) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!exportState.isIdle) {
                LinearProgressIndicator(
                    progress = { exportState.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = exportState.statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (busy) {
                    SecondaryButton(
                        text = "取消",
                        onClick = viewModel::cancelExport,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (definition.isMultiOutput) {
                    SecondaryButton(
                        text = if (state.isPreviewing) "处理中" else "试听",
                        onClick = viewModel::previewEffect,
                        enabled = !state.isPreviewing && !busy && state.selectedTrackIds.isNotEmpty(),
                    )
                    PrimaryButton(
                        text = if (busy) "写入中" else "开始分离",
                        onClick = viewModel::exportSplit,
                        enabled = state.splitFiles.isNotEmpty() && !busy,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    SecondaryButton(
                        text = when {
                            state.isPreviewing -> "渲染中"
                            playback.isPlaying -> "停止试听"
                            else -> "试听"
                        },
                        onClick = {
                            if (playback.isPlaying) {
                                viewModel.togglePlay()
                            } else if (definition.id == "synth" && !previewRealEffect) {
                                viewModel.previewFirstTrack()
                            } else {
                                viewModel.previewEffect()
                            }
                        },
                        enabled = !state.isPreviewing && !busy && state.selectedTrackIds.isNotEmpty(),
                    )
                    PrimaryButton(
                        text = if (busy) "处理中" else bottomLabelFor(definition.id),
                        onClick = { exportPicker.launch(viewModel.exportFileName()) },
                        enabled = state.selectedTrackIds.isNotEmpty() && !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) {
        EffectBody(
            state = state,
            tracks = tracks,
            definition = definition,
            mixZoom = mixZoom,
            previewRealEffect = previewRealEffect,
            viewModel = viewModel,
            onZoomIn = { mixZoom = (mixZoom * 1.5f).coerceAtMost(4f) },
            onZoomOut = { mixZoom = (mixZoom / 1.5f).coerceAtLeast(1f) },
            // 导入卡一律弹系统文件选择器：没歌时导入，已选歌时换歌。
            // 以前已选歌会改弹应用内的选歌列表，和别的页面不一致。
            onPickTrack = { importPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
            onPickSlot = { slot ->
                pickingSlot = slot
                importPicker.launch(arrayOf("audio/*", "application/octet-stream"))
            },
            onAddSegment = viewModel::addSegment,
            onRemoveSegment = viewModel::removeSegment,
            onEditSegment = { editingSegment = it },
            onPreviewRealEffectToggle = { previewRealEffect = !previewRealEffect },
            onPickRowChoice = { id -> pickingRowChoice = id },
            onPickFormat = { pickingFormat = true },
        )
    }

    // 弹层一律放在 FunctionShell 外面。以前它们被塞进某个参数分支里，
    // 结果只有「参数非空」的功能才有得选，别的功能点了没反应。

    if (pickingFormat) {
        ConfirmSheet(
            title = "导出格式",
            description = "wav 无损但没有封面和歌词，mp3 体积小且标签完整",
            confirmLabel = "关闭",
            onConfirm = { pickingFormat = false },
            onDismiss = { pickingFormat = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormat.entries.forEach { option ->
                    SecondaryButton(
                        text = if (option == state.exportFormat) "${option.label} ✓" else option.label,
                        onClick = {
                            viewModel.setExportFormat(option)
                            pickingFormat = false
                        },
                        containerColor = if (option == state.exportFormat) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (pickingRowChoice != null) {
        val rowParam = definition.params.firstOrNull { it.id == pickingRowChoice }
        val options = rowParam?.choices.orEmpty()
        ConfirmSheet(
            title = rowParam?.label ?: "选择",
            description = when (definition.id) {
                "radio_fx" -> "越窄越像老式收音机喇叭，越宽越接近原声"
                "denoise" -> "稳定处理得轻一些，正常按标准力度"
                "convert" -> "无损格式（wav / flac）不设比特率"
                else -> ""
            },
            confirmLabel = "关闭",
            onConfirm = { pickingRowChoice = null },
            onDismiss = { pickingRowChoice = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { index, option ->
                    val selected = (state.values[rowParam?.id.orEmpty()] ?: rowParam?.default ?: 0f).toInt() == index
                    SecondaryButton(
                        text = if (selected) "${option} ✓" else option,
                        onClick = {
                            viewModel.setValue(rowParam?.id.orEmpty(), index.toFloat())
                            pickingRowChoice = null
                        },
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    editingSegment?.let { index ->
        val segment = state.segments.getOrNull(index)
        if (segment != null) {
            val unit = if (definition.id == "segment_gain") "dB" else "半音"
            val range = if (definition.id == "segment_gain") SEGMENT_GAIN_RANGE else SEGMENT_SEMITONE_RANGE
            ConfirmSheet(
                title = "片段 ${index + 1}",
                description = "时间是源曲上的位置，$unit 只在这一段生效，段外原样保留",
                confirmLabel = "完成",
                onConfirm = { editingSegment = null },
                onDismiss = { editingSegment = null },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TimeStepperField(
                            label = "起点",
                            valueMs = segment.startMs,
                            onValueChange = { viewModel.updateSegment(index, it, segment.endMs, null) },
                            modifier = Modifier.weight(1f),
                        )
                        TimeStepperField(
                            label = "终点",
                            valueMs = segment.endMs,
                            onValueChange = { viewModel.updateSegment(index, segment.startMs, it, null) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ParamRow(
                        label = if (definition.id == "segment_gain") "增益" else "音高",
                        valueText = formatSegmentValue(segment.value, unit),
                        value = segment.value,
                        range = range,
                        onChange = { viewModel.updateSegment(index, segment.startMs, segment.endMs, it) },
                    )
                }
            }
        }
    }
}

/**
 * 滚动区的全部内容。平铺，没有分支套分支：
 * 选歌 → 分段 → 参数 → 分离试听 → 额外选项行。
 */
@Composable
private fun EffectBody(
    state: EffectUiState,
    tracks: List<SourceTrack>,
    definition: EffectDefinition,
    mixZoom: Float,
    previewRealEffect: Boolean,
    viewModel: EffectWorkspaceViewModel,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onPickTrack: () -> Unit,
    onPickSlot: (Int) -> Unit,
    onAddSegment: () -> Unit,
    onRemoveSegment: (Int) -> Unit,
    onEditSegment: (Int) -> Unit,
    onPreviewRealEffectToggle: () -> Unit,
    onPickRowChoice: (String) -> Unit,
    onPickFormat: () -> Unit,
) {
    val currentTrack = state.selectedTrackIds.firstOrNull()?.let { id -> tracks.firstOrNull { it.id == id } }

    // 1. 选歌
    if (definition.id == "mix" || definition.id == "synth") {
        MixTrackStrip(
            ids = state.selectedTrackIds,
            tracks = tracks,
            zoom = mixZoom,
            onZoomIn = onZoomIn,
            onZoomOut = onZoomOut,
            onAdd = onPickTrack,
            onRemove = viewModel::removeTrackAt,
        )
    } else if (definition.leftRightSlots) {
        listOf("左声道", "右声道").forEachIndexed { slot, channel ->
            ImportTrackCard(
                track = tracks.firstOrNull { it.id == state.selectedTrackIds.getOrNull(slot) },
                title = channel,
                label = null,
                onPick = { onPickSlot(slot) },
            )
        }
    } else {
        ImportTrackCard(
            track = currentTrack,
            label = if (definition.multiTrack) "已选 ${state.selectedTrackIds.size} 首" else null,
            onPick = onPickTrack,
        )
    }

    // 2. 分段列表
    if (definition.applySegmented != null) {
        SegmentListCard(
            segments = state.segments,
            valueLabel = if (definition.id == "segment_gain") "增益" else "音高",
            valueUnit = if (definition.id == "segment_gain") "dB" else "半音",
            valueRange = if (definition.id == "segment_gain") SEGMENT_GAIN_RANGE else SEGMENT_SEMITONE_RANGE,
            onAdd = onAddSegment,
            onRemove = onRemoveSegment,
            onEdit = onEditSegment,
        )
    }

    // 3. 参数区。三种形状，按定义自己声明的字段选，不靠 id 猜。
    when {
        definition.id == "speed_pitch" -> SpeedPitchCards(state = state, definition = definition, viewModel = viewModel)

        definition.id == "equalizer" -> EqualizerCard(state = state, definition = definition, viewModel = viewModel)

        definition.stepperTiles -> StepperCard(
            state = state,
            definition = definition,
            mixZoomZoom = mixZoom,
            previewRealEffect = previewRealEffect,
            viewModel = viewModel,
            onPreviewRealEffectToggle = onPreviewRealEffectToggle,
        )

        definition.params.isNotEmpty() -> GenericParamsCard(
            state = state,
            definition = definition,
            previewRealEffect = previewRealEffect,
            viewModel = viewModel,
            onPreviewRealEffectToggle = onPreviewRealEffectToggle,
        )
    }

    // 4. 立体声分离：左右各一行、各带一个试听
    if (definition.isMultiOutput) {
        listOf("左声道", "右声道").forEachIndexed { index, label ->
            PlainCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.playSplit(index) },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayCircleButton(
                        playing = state.playingSplitIndex == index,
                        enabled = !state.isPreviewing,
                        onClick = { viewModel.playSplit(index) },
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (state.splitFiles.isEmpty()) "音乐尚未处理" else "点击试听",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // 5. 合成：格式化音乐 + 插入空白音乐
    if (definition.id == "synth") {
        val normalizeOn = (state.values["normalizeRates"] ?: 1f) >= 0.5f
        OptionRow(
            label = "格式化音乐",
            value = if (normalizeOn) "已开启" else "已关闭",
            onClick = { viewModel.setValue("normalizeRates", if (normalizeOn) 0f else 1f) },
        )
        OptionRow(label = "插入空白音乐", value = "2 秒", onClick = viewModel::insertSilence)
    }

    // 6. 声明成下拉行的选项（收音机模式、降噪模式、格式转换的采样率/比特率/声道）
    definition.params.filter { it.asRow }.forEach { param ->
        val disabled = param.id == "bitrate" && !state.exportFormat.supportsBitrate
        val index = (state.values[param.id] ?: param.default).toInt().coerceIn(0, param.choices.lastIndex.coerceAtLeast(0))
        OptionRow(
            label = param.label,
            value = if (disabled) "无损" else param.choices.getOrElse(index) { "" },
            onClick = { if (!disabled) onPickRowChoice(param.id) },
        )
    }

    // 7. 格式栏只出现在和格式有关的功能上
    if (definition.showFormatRow) {
        OptionRow(
            label = "格式",
            value = state.exportFormat.label,
            onClick = onPickFormat,
        )
    }
}

@Composable
private fun SpeedPitchCards(
    state: EffectUiState,
    definition: EffectDefinition,
    viewModel: EffectWorkspaceViewModel,
) {
    val pitchParam = definition.params.first { it.id == "pitchPct" }
    val speedParam = definition.params.first { it.id == "speedPct" }
    val pitch = state.values["pitchPct"] ?: pitchParam.default
    val speed = state.values["speedPct"] ?: speedParam.default
    ParamCard(title = "音调设置", onReset = { viewModel.setValue("pitchPct", pitchParam.default) }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ValueDisplayBox(valueText = "%.1f".format(pitch), unit = "%")
            QishuiSlider(
                value = pitch.coerceIn(pitchParam.min, pitchParam.max),
                onValueChange = { viewModel.setValue("pitchPct", it) },
                valueRange = pitchParam.min..pitchParam.max,
                label = pitchParam.label,
            )
            StepperRow(
                labels = arrayOf("减半音", "减0.5%", "加0.5%", "加半音"),
                deltas = floatArrayOf(SEMITONE_DOWN, -0.5f, 0.5f, SEMITONE_UP),
                onChange = { delta ->
                    val next = when (delta) {
                        SEMITONE_UP -> pitch * SEMITONE_RATIO
                        SEMITONE_DOWN -> pitch / SEMITONE_RATIO
                        else -> pitch + delta
                    }
                    viewModel.setValue("pitchPct", next.coerceIn(pitchParam.min, pitchParam.max))
                },
            )
        }
    }
    ParamCard(title = "速度设置", onReset = { viewModel.setValue("speedPct", speedParam.default) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StepperRow(
                labels = arrayOf("减5%", "减0.5%", "加0.5%", "加5%"),
                deltas = floatArrayOf(-5f, -0.5f, 0.5f, 5f),
                onChange = { delta ->
                    viewModel.setValue("speedPct", (speed + delta).coerceIn(speedParam.min, speedParam.max))
                },
            )
            Text(
                text = "%.1f%%（100%% 为原速）".format(speed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EqualizerCard(
    state: EffectUiState,
    definition: EffectDefinition,
    viewModel: EffectWorkspaceViewModel,
) {
    ParamCard(title = state.presetName.ifBlank { "默认" }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            definition.params.filter { it.id.startsWith("band") }.forEach { param ->
                val value = state.values[param.id] ?: param.default
                BandRow(
                    label = param.label,
                    value = value,
                    range = param.min..param.max,
                    onChange = { viewModel.setValue(param.id, it) },
                )
            }
            PresetRow(
                presets = definition.preset.presets,
                current = state.presetName,
                onPick = viewModel::applyPreset,
                onReset = viewModel::resetParams,
                onClear = viewModel::clearParams,
            )
        }
    }
}

/** 整数档位的效果：双列竖排步进块 + 生效区间滑杆 + 预设。 */
@Composable
private fun StepperCard(
    state: EffectUiState,
    definition: EffectDefinition,
    mixZoomZoom: Float,
    previewRealEffect: Boolean,
    viewModel: EffectWorkspaceViewModel,
    onPreviewRealEffectToggle: () -> Unit,
) {
    val sliders = definition.params.filter { it.choices.isEmpty() }
    ParamCard(title = "${definition.label}设置", onReset = { viewModel.resetParams() }) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                sliders.take(2).forEach { param ->
                    StepperTile(
                        label = param.label,
                        value = (state.values[param.id] ?: param.default).toInt(),
                        unit = param.unit,
                        range = param.min.toInt()..param.max.toInt(),
                        onChange = { viewModel.setValue(param.id, it.toFloat()) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (sliders.size > 2) {
                ParamRowList(sliders.drop(2), state, viewModel)
            }
            if (definition.preset.presets.isNotEmpty()) {
                PresetRow(
                    presets = definition.preset.presets,
                    current = state.presetName,
                    onPick = viewModel::applyPreset,
                    onReset = viewModel::resetParams,
                    onClear = viewModel::clearParams,
                )
            }
        }
    }
}

/** 连续参数的效果：选择项一张卡、滑杆一张卡，混音/合成的并排块走这里。 */
@Composable
private fun GenericParamsCard(
    state: EffectUiState,
    definition: EffectDefinition,
    previewRealEffect: Boolean,
    viewModel: EffectWorkspaceViewModel,
    onPreviewRealEffectToggle: () -> Unit,
) {
    val choices = definition.params.filter { it.choices.isNotEmpty() && !it.asRow }
    val sliders = definition.params.filter { it.choices.isEmpty() }

    if (choices.isNotEmpty()) {
        ParamCard(title = choices.first().label) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                choices.forEach { param ->
                    ChoiceRow(
                        label = param.label,
                        options = param.choices,
                        selectedIndex = (state.values[param.id] ?: param.default).toInt(),
                        onSelect = { viewModel.setValue(param.id, it.toFloat()) },
                        caption = param.caption.ifBlank {
                            if (definition.id == "invert") channelCaption(state.channels) else ""
                        },
                    )
                }
            }
        }
    }

    if (sliders.isNotEmpty()) {
        ParamCard(title = "${definition.label}设置", onReset = { viewModel.resetParams() }) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 合成：试听开关 + 衔接时间 并排
                if (definition.id == "synth") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ToggleCard(
                            label = "试听真实效果",
                            on = previewRealEffect,
                            onClick = onPreviewRealEffectToggle,
                            modifier = Modifier.weight(1f),
                        )
                        LabeledTimeField(
                            label = "衔接时间",
                            valueMs = (state.values["joinMs"] ?: 2000f).toLong(),
                            onValueChange = { viewModel.setValue("joinMs", it.toFloat()) },
                            clockFormat = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // 混音：淡入淡出时间 + 格式化音乐 并排
                if (definition.id == "mix") {
                    val normalizeOn = (state.values["normalizeRates"] ?: 1f) >= 0.5f
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // 参考图里这个值是直接敲的 2000，不是 00:00:02
                        LabeledTimeField(
                            label = "淡入淡出时间",
                            valueMs = (state.values["crossfadeMs"] ?: 2000f).toLong(),
                            onValueChange = { viewModel.setValue("crossfadeMs", it.toFloat()) },
                            clockFormat = false,
                            modifier = Modifier.weight(1f),
                        )
                        ToggleCard(
                            label = "格式化音乐",
                            on = normalizeOn,
                            onClick = { viewModel.setValue("normalizeRates", if (normalizeOn) 0f else 1f) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // 去除头尾：头尾两块并排，数值按 00:03.000 直接敲
                if (definition.id == "silence_trim") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("headMs", "tailMs").forEach { id ->
                            val param = definition.params.first { it.id == id }
                            val value = state.values[id] ?: param.default
                            LabeledTimeField(
                                label = param.label,
                                valueMs = value.toLong(),
                                onValueChange = { viewModel.setValue(id, it.toFloat()) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                sliders.forEach { param ->
                    val value = state.values[param.id] ?: param.default
                    ParamRow(
                        label = param.label,
                        valueText = "${formatNumber(value)}${param.unit}",
                        value = value,
                        range = param.min..param.max,
                        onChange = { viewModel.setValue(param.id, it) },
                        stepButtons = param.step < 5f,
                    )
                }
            }
        }
    }
}

/** 步进块之外剩下的滑杆（生效区间之类）。 */
@Composable
private fun ParamRowList(
    params: List<EffectParam>,
    state: EffectUiState,
    viewModel: EffectWorkspaceViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        params.forEach { param ->
            val value = state.values[param.id] ?: param.default
            ParamRow(
                label = param.label,
                valueText = "${formatNumber(value)}${param.unit}",
                value = value,
                range = param.min..param.max,
                onChange = { viewModel.setValue(param.id, it) },
                stepButtons = param.step < 5f,
            )
        }
    }
}

/**
 * 分段处理的片段列表。参考示例这一页只有片段区（空态显示「暂无片段」），
 * 付费卡我们不做，所以「添加片段」按钮放这里。
 */
@Composable
private fun SegmentListCard(
    segments: List<SegmentRange>,
    valueLabel: String,
    valueUnit: String,
    valueRange: ClosedFloatingPointRange<Float>,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onEdit: (Int) -> Unit,
) {
    PlainCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (segments.isEmpty()) {
                Text(
                    text = "暂无片段",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                )
            } else {
                segments.forEachIndexed { index, segment ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(index) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "片段 ${index + 1}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${formatClockMs(segment.startMs)} - ${formatClockMs(segment.endMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatSegmentValue(segment.value, valueUnit),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        LucideIcon(
                            icon = R.drawable.ic_chevron_right,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 16.dp,
                        )
                    }
                }
            }
            SecondaryButton(
                text = "添加片段",
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
            )
            if (segments.isNotEmpty()) {
                Text(
                    text = "点片段行可以改时间和$valueLabel，范围 $valueUnit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryButton(
                    text = "清空片段",
                    onClick = { for (index in segments.indices.reversed()) onRemove(index) },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 分段变调显示半音（带正负号），分段音量显示 dB。 */
private fun formatSegmentValue(value: Float, unit: String): String = when (unit) {
    "半音" -> "%+.1f %s".format(value, unit)
    else -> "%+.1f %s".format(value, unit)
}

private val SEGMENT_SEMITONE_RANGE = -12f..12f
private val SEGMENT_GAIN_RANGE = -24f..24f

/**
 * 混音的横向时间轴：按选择顺序排成一列色块，+/- 缩放色块宽度。
 * 点色块移除该首，点「＋曲」继续选歌。
 */
@Composable
private fun MixTrackStrip(
    ids: List<String>,
    tracks: List<SourceTrack>,
    zoom: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    PlainCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "按选择顺序拼接",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ZoomButton("−", onZoomOut)
                ZoomButton("+", onZoomIn)
                Spacer(modifier = Modifier.width(8.dp))
                ZoomButton("加曲", onAdd)
            }
            if (ids.isEmpty()) {
                Text(
                    text = "请先导入音乐",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                )
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ids.forEachIndexed { index, id ->
                        Surface(
                            modifier = Modifier
                                .width((76f * zoom).dp)
                                .clickable { onRemove(index) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (id == SILENCE_TRACK_ID) {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                        ) {
                            Text(
                                text = if (id == SILENCE_TRACK_ID) {
                                    "空白音乐"
                                } else {
                                    tracks.firstOrNull { it.id == id }?.title ?: "已失效"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomButton(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(34.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 「格式化音乐」那种灰底开关卡：开着就高亮成主色。 */
@Composable
private fun ToggleCard(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (on) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (on) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (on) "✓" else "关",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 均衡器底部那一排动作：选预设 / 重置 / 清空。 */
@Composable
private fun PresetRow(
    presets: List<EffectPreset>,
    current: String,
    onPick: (String) -> Unit,
    onReset: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FilterRow(
            options = presets.map { it.name },
            selectedIndex = presets.indexOfFirst { it.name == current }.coerceAtLeast(0),
            onSelect = { onPick(presets[it].name) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "重置",
                onClick = onReset,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.labelMedium,
                contentPadding = PaddingValues(horizontal = 2.dp),
            )
            SecondaryButton(
                text = "清空",
                onClick = onClear,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.labelMedium,
                contentPadding = PaddingValues(horizontal = 2.dp),
            )
        }
    }
}

/** 底栏主按钮文案跟着功能走，参考示例里每个功能都是专属动词。 */
/** 一个半音的频率比，音调按钮用它做乘性调整。 */
private const val SEMITONE_RATIO = 1.0594631f
private const val SEMITONE_UP = 9_000f
private const val SEMITONE_DOWN = -9_000f

private fun bottomLabelFor(effectId: String): String = when (effectId) {    "denoise" -> "开始降噪"
    "convert" -> "开始转换"
    "mix" -> "开始混音"
    "silence_trim" -> "去除头尾"
    "speed_pitch" -> "开始变速"
    "stereo_mix" -> "开始合成"
    "loudness" -> "开始标准化"
    else -> "处理并导出"
}

private fun channelCaption(channels: Int): String = when (channels) {
    0 -> "检测声道：未知"
    1 -> "检测声道：单声道"
    2 -> "检测声道：立体声"
    else -> "检测声道：$channels 声道"
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
