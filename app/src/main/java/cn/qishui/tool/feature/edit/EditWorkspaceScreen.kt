package cn.qishui.tool.feature.edit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qishui.tool.domain.model.EditMode
import cn.qishui.tool.domain.model.EditOperation
import cn.qishui.tool.domain.model.SourceTrack
import cn.qishui.tool.domain.model.JoinTransition
import cn.qishui.tool.domain.model.LyricWord
import cn.qishui.tool.domain.model.WaveformPeaks
import cn.qishui.tool.feature.edit.waveform.WaveformCanvas
import cn.qishui.tool.feature.edit.waveform.WaveformGeometry
import cn.qishui.tool.feature.edit.waveform.WaveformSelection
import cn.qishui.tool.ui.components.InfoHintAction
import cn.qishui.tool.ui.components.ImportTrackCard
import cn.qishui.tool.ui.components.ConfirmSheet
import cn.qishui.tool.ui.components.HintSheetContent
import cn.qishui.tool.ui.components.TopSnackbarHost
import cn.qishui.tool.ui.components.ParamCard
import cn.qishui.tool.ui.components.PlayCircleButton
import cn.qishui.tool.ui.components.PlainCard
import cn.qishui.tool.ui.components.PrimaryButton
import cn.qishui.tool.ui.components.QishuiFieldShape
import cn.qishui.tool.ui.components.QishuiTopBar
import cn.qishui.tool.ui.components.ScreenScroll
import cn.qishui.tool.ui.components.SecondaryButton
import cn.qishui.tool.ui.components.SectionHeader
import cn.qishui.tool.ui.components.TimeStepperField
import cn.qishui.tool.ui.components.SegmentedControl
import cn.qishui.tool.ui.components.SelectBox
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
    var hintVisible by rememberSaveable { mutableStateOf(false) }
    val operation = state.operation
    val durationUs = (state.track?.durationMs ?: 0L) * 1000L
    val activeLine = state.lyrics.lines.getOrNull(state.activeLineIndex)

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
                onBack = onBack,
                actionContent = {
                    InfoHintAction(
                        hint = operationHint(operation),
                        onOpen = { hintVisible = true },
                    )
                },
            )
            ScreenScroll {
                // 导入卡：没歌时显示「点击导入音乐」，选完变成歌名，再点弹系统选择器换歌。
                // 所有功能页统一这一个入口，不再单独开一个空态页。
                ImportTrackCard(
                    track = state.track,
                    onPick = { importPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                )
                if (state.track == null) {
                    Text(
                        text = "还没有歌曲，导入后即可使用「${operation.card().label}」，源歌曲永不被覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 合成是多源的，单首波形表达不了首尾相接的结果，改用下面的源序列列表。
                if (operation != EditOperation.JOIN) {
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
                                text = "已选:${formatMs(state.endMs - state.startMs)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "播放:${formatMs(playback.positionMs)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "全部:${formatMs(playback.durationMs)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f),
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
                        if (operation == EditOperation.TRIM) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                TimeStepperField(
                                    label = "开始:${formatMs(state.startMs)}",
                                    valueMs = state.startMs,
                                    onValueChange = { viewModel.setStartMs(it) },
                                    modifier = Modifier.weight(1f),
                                )
                                TimeStepperField(
                                    label = "结束:${formatMs(state.endMs)}",
                                    valueMs = state.endMs,
                                    onValueChange = { viewModel.setEndMs(it) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SecondaryButton(
                                    text = "试听时间设置为开始",
                                    onClick = { viewModel.setStartMs(playback.positionMs) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    textStyle = MaterialTheme.typography.labelMedium,
                                )
                                SecondaryButton(
                                    text = "试听时间设置为结束",
                                    onClick = { viewModel.setEndMs(playback.positionMs) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    textStyle = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
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
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PlayCircleButton(
                                playing = playback.isPlaying,
                                enabled = state.track?.localPath != null,
                                onClick = viewModel::togglePlay,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Slider(
                                    value = playback.positionMs.toFloat(),
                                    onValueChange = { viewModel.seekTo(it.toLong()) },
                                    valueRange = 0f..playback.durationMs.coerceAtLeast(1L).toFloat(),
                                    enabled = playback.durationMs > 0L,
                                )
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = formatMs(playback.positionMs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = formatMs(playback.durationMs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                ParamCard(
                    title = "${operation.card().label}设置",
                    onReset = viewModel::resetParams,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                LongField("分割点 (ms)", state.splitMs, viewModel::setSplitMs)
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

                            EditOperation.JOIN -> {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SegmentedControl(
                                        options = listOf("正常", "淡入淡出", "稳定", "无损"),
                                        selectedIndex = when (state.joinTransition) {
                                            JoinTransition.NORMAL -> 0
                                            JoinTransition.FADE -> 1
                                            JoinTransition.STABLE -> 2
                                            JoinTransition.PRESERVE -> 3
                                        },
                                        onSelect = {
                                            viewModel.setJoinTransition(
                                                when (it) {
                                                    0 -> JoinTransition.NORMAL
                                                    1 -> JoinTransition.FADE
                                                    else -> JoinTransition.STABLE
                                                },
                                            )
                                        },
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SelectBox(
                                            selected = state.previewRealEffect,
                                            description = "试听真实效果",
                                            onToggle = viewModel::togglePreviewRealEffect,
                                        )
                                        Text(
                                            text = "衔接时间",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Box(modifier = Modifier.weight(1f)) {
                                            LongField(
                                                label = "",
                                                value = state.transitionMs,
                                                onValueChange = viewModel::setTransitionMs,
                                            )
                                        }
                                        Text(
                                            text = "秒",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                JoinedTrackChips(
                                    tracks = availableTracks,
                                    joinedIds = state.joinedTrackIds,
                                    onToggle = viewModel::toggleJoinedTrack,
                                )
                            }

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
                            // 各曲时长之和，无损衔接和末尾空白都算进去，和导出结果一致
                            Text(
                                text = "合成总时长 ${formatMs(state.outputDurationUs / 1000L)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 只有歌词编辑才需要歌词栏。以前是无条件渲染，合成、分割、
                // 淡入淡出下面也挂着一块没用的歌词列表。
                if (operation == EditOperation.LYRIC_OFFSET) {
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
                                    text = "歌词",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                SecondaryButton(
                                    text = "导入歌词文件",
                                    onClick = { lyricsPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                                )
                            }

                            SegmentedControl(
                                options = listOf("整行", "逐字"),
                                selectedIndex = if (state.wordMode) 1 else 0,
                                onSelect = { viewModel.setWordMode(it == 1) },
                            )

                            // 手动输入：没歌词也照样给一个可输入的框，
                            // 不能逼用户先去导入文件才有地方打字。
                            if (state.lyrics.lines.isEmpty()) {
                                OutlinedTextField(
                                    value = "",
                                    onValueChange = { viewModel.addLyricLine(); viewModel.setLyricLineText(0, it) },
                                    label = { Text("输入第一句歌词") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = QishuiFieldShape,
                                )
                            } else {
                                state.lyrics.lines.forEachIndexed { index, line ->
                                    var draft by remember(line.text) { mutableStateOf(line.text) }
                                    var editing by remember(index) { mutableStateOf(false) }
                                    OutlinedTextField(
                                        value = draft,
                                        // 正在打点的行跟着播放位置走，其余行才由用户改
                                        onValueChange = { draft = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onFocusChanged { focus ->
                                                // 失焦才提交：每敲一个字就进一次撤销栈没意义
                                                if (editing && !focus.isFocused) viewModel.setLyricLineText(index, draft)
                                                editing = focus.isFocused
                                            },
                                        label = { Text("第 ${index + 1} 句 · ${formatMs(line.startUs / 1000L)}") },
                                        trailingIcon = {
                                            if (index == state.activeLineIndex) {
                                                SecondaryButton(
                                                    text = "删除",
                                                    onClick = {
                                                        viewModel.selectLyricLine(index)
                                                        viewModel.removeActiveLine()
                                                    },
                                                )
                                            }
                                        },
                                        singleLine = true,
                                        shape = QishuiFieldShape,
                                    )
                                }
                            }

                            if (state.wordMode) {
                                WordStrip(
                                    words = state.lyrics.lines.getOrNull(state.activeLineIndex)?.words.orEmpty(),
                                    activeIndex = state.activeWordIndex,
                                    onWordClick = viewModel::selectActiveWord,
                                )
                            }

                            // 本行 / 播放位置 / 全长，方便照着播放位置打点
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "本行:${formatMs(activeLine?.startUs?.div(1000L) ?: 0L)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "播放:${formatMs(playback.positionMs)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "全长:${formatMs(playback.durationMs)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    "打点" to {
                                        if (state.wordMode) {
                                            viewModel.stampActiveWord(playback.positionMs * 1000L)
                                        } else {
                                            viewModel.stampActiveLine(playback.positionMs * 1000L)
                                        }
                                    },
                                    "播放" to viewModel::togglePlay,
                                    "暂停" to viewModel::pausePlayback,
                                    "添加一句" to viewModel::addLyricLine,
                                ).forEach { (label, action) ->
                                    SecondaryButton(
                                        text = label,
                                        onClick = action,
                                        modifier = Modifier.weight(1f),
                                        textStyle = MaterialTheme.typography.labelMedium,
                                        contentPadding = PaddingValues(horizontal = 2.dp),
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(-1000L, -100L, 100L, 1000L).forEach { deltaMs ->
                                    SecondaryButton(
                                        text = if (deltaMs < 0) {
                                            "前${if (deltaMs <= -1000L) "1秒" else "0.1秒"}"
                                        } else {
                                            "后${if (deltaMs >= 1000L) "1秒" else "0.1秒"}"
                                        },
                                        onClick = {
                                            if (state.wordMode) {
                                                viewModel.nudgeActiveWord(deltaMs * 1000L)
                                            } else {
                                                viewModel.nudgeActiveLine(deltaMs * 1000L)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        textStyle = MaterialTheme.typography.labelMedium,
                                        contentPadding = PaddingValues(horizontal = 2.dp),
                                    )
                                }
                            }
                        }
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

                Spacer(modifier = Modifier.height(4.dp))
                // 淡入淡出和合成都需要先把参数渲染成临时 WAV 再播，
                // 直接播原文件听不出效果，所以这两个功能给渲染试听。
                if (operation == EditOperation.FADE_IN ||
                    operation == EditOperation.FADE_OUT ||
                    operation == EditOperation.JOIN
                ) {
                    val onPreview = if (operation == EditOperation.JOIN) {
                        viewModel::toggleJoinPreview
                    } else {
                        viewModel::togglePreview
                    }
                    SecondaryButton(
                        text = when {
                            state.isPreviewing -> "渲染中"
                            playback.isPlaying -> "停止试听"
                            else -> "试听效果"
                        },
                        onClick = onPreview,
                        enabled = !state.isPreviewing && state.canSave,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PrimaryButton(
                    text = if (state.isSaving) "保存中" else "保存",
                    onClick = viewModel::save,
                    enabled = state.canSave && !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
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

    // 提示和别的页面一样走右上角图标弹层
    if (hintVisible) {
        ConfirmSheet(
            title = "使用说明",
            confirmLabel = "知道了",
            onConfirm = { hintVisible = false },
            onDismiss = { hintVisible = false },
        ) {
            HintSheetContent(operationHint(operation))
        }
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

/** 逐字模式的字条：每个字/词一个块，打点位置往下推进。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordStrip(
    words: List<LyricWord>,
    activeIndex: Int,
    onWordClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (words.isEmpty()) {
        Text(
            text = "这一行还没有可打点的字",
            modifier = modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(
        modifier = modifier.fillMaxWidth().heightIn(max = 200.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        words.forEachIndexed { index, word ->
            val isCurrent = index == activeIndex
            Text(
                text = word.text,
                style = if (isCurrent) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isCurrent -> MaterialTheme.colorScheme.onPrimary
                    index < activeIndex -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .clip(QishuiFieldShape)
                    .background(
                        if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .clickable { onWordClick(index) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

private fun formatMs(value: Long): String {
    val safe = value.coerceAtLeast(0L)
    return String.format(Locale.getDefault(), "%d:%02d.%03d", safe / 60_000L, safe / 1000L % 60L, safe % 1000L)
}

private fun formatDb(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)