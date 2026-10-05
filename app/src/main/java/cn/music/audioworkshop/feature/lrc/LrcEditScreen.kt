package cn.music.audioworkshop.feature.lrc

import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.PlaybackBar
import cn.music.audioworkshop.ui.components.PrimaryButton
import cn.music.audioworkshop.ui.components.QishuiFieldShape
import cn.music.audioworkshop.ui.components.SectionHeader
import cn.music.audioworkshop.ui.components.SecondaryButton
import kotlinx.coroutines.launch

private const val HINT = "支持导入 LRC 文件或纯文本，文本按每句 4 秒自动分配时间。" +
    "可逐句增减并按播放位置打点校准。歌词写入新文件，源文件不改动。"

/**
 * 底栏按钮自己的描边。
 *
 * 颜色和粗细跟 [FrostedBox] 默认那圈一致（primary 16% 透明度、1dp），
 * 这样这一页看起来和其他页面一样，区别只在「两个按钮各自描边」，
 * 而不是被一圈描边围在一起。用主题色而非硬编码，跟随主题切换。
 */
@Composable
private fun buttonStroke() = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))

/** 歌词编辑功能页。 */
@Composable
fun LrcEditScreen(
    viewModel: LrcEditViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingText by remember { mutableStateOf<EditTextTarget?>(null) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 当前正在唱的那句，用来在列表里高亮并自动滚动
    val playingIndex = state.currentLineIndexAt(playback.positionMs)

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importLocalAudio(it.toString()) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    // 播放头移动时高亮当前句，打点要用
    LaunchedEffect(playback.positionMs, playback.isPlaying) {
        val index = state.currentLineIndexAt(playback.positionMs)
        if (index >= 0) viewModel.selectLine(index)
    }

    FunctionShell(
        modifier = modifier,
        title = "歌词编辑",
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
// 这一页有两个导出目标（LRC 文本 / 音频文件），走同一套重命名与进度流程。
            // 外层底栏描边关掉（bottomBarBorderWidth = 0.dp）：那圈描边会把两个按钮
            // 围成一个整体。改为每个按钮各自描边，它们才是两个独立操作。
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    text = if (export.isRunning) "处理中" else "导出为LRC",
                    modifier = Modifier.weight(1f),
                    border = buttonStroke(),
                    enabled = !state.isWorking && !export.isRunning && state.lines.isNotEmpty(),
                    onClick = { requestExportAs(viewModel.suggestedLrcFileName() ?: "音频.lrc") },
                )
                PrimaryButton(
                    text = "导出歌曲",
                    modifier = Modifier.weight(1f),
                    border = buttonStroke(),
                    enabled = !state.isWorking && !export.isRunning && state.lines.isNotEmpty(),
                    onClick = requestExport,
                )
            }
        },
        exportFileName = viewModel.suggestedFileName(),
        exportProgress = ExportProgressState(
            label = export.statusLabel,
            fraction = export.fraction,
            cancellable = !export.isCopying,
            error = export.error,
        ).takeIf { export.isRunning || export.isCopying || export.error != null },
        exportedLocation = state.publishedLocation,
        onExport = { name ->
            // 两个按钮共用这一个回调，靠后缀分派：.lrc 走文本导出，其余走歌曲导出
            if (name.substringAfterLast('.', "").lowercase() == "lrc") {
                viewModel.exportLrc(name)
            } else {
                viewModel.export(name)
            }
        },
        onCancelExport = viewModel::cancelExport,
        // 关掉底栏外层描边：两个并列按钮各自描边，外层那圈会把它们围成一个整体
        bottomBarBorderWidth = 0.dp,
    ) {
        // ---- 播放进度条（打点的时基） ----
        if (state.hasTrack) {
            SectionHeader(title = "播放")
            PlaybackBar(
                playing = playback.isPlaying,
                enabled = true,
                positionMs = playback.positionMs,
                durationMs = state.durationUs / 1000L,
                onTogglePlay = viewModel::togglePlay,
                onSeek = viewModel::seekTo,
            )
        }

        // ---- 逐句列表 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(title = "逐句歌词（${state.lines.size}）", modifier = Modifier.weight(1f))
            if (state.hasTrack) {
                Text(
                    text = "添加一句",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { viewModel.addLineAtPlayback(playback.positionMs) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        if (state.lines.isEmpty()) {
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (state.hasTrack) {
                        "还没有歌词。可以把进度条拖到位置后点「添加一句」开始打点，" +
                            "也可以在下方「查看 / 切换歌词」里粘贴一份现成的。"
                    } else {
                        "先导入音频"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(state.lines) { index, line ->
                    LrcLineRow(
                        index = index,
                        line = line,
                        selected = index == state.selectedIndex,
                        playing = index == playingIndex,
                        onSelect = { viewModel.selectLine(index) },
                        onEditText = { editingText = EditTextTarget(index, line.text) },
                        onNudge = { delta -> viewModel.nudgeStart(index, delta) },
                        onDelete = { viewModel.deleteLine(index) },
                    )
                }
            }
        }

        // ---- 查看 / 切换歌词 ----
        SectionHeader(title = "歌词源码")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (state.isSourceVisible) "收起歌词文本" else "查看 / 切换歌词",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { viewModel.toggleSourceVisible() }
                            .padding(vertical = 4.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (state.isSourceVisible) {
                        Text(
                            text = if (state.sourceDirty) "有未保存的修改" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (state.isSourceVisible) {
                    OutlinedTextField(
                        value = state.sourceText,
                        onValueChange = viewModel::setSourceText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp),
                        shape = QishuiFieldShape,
                        placeholder = {
                            Text(
                                text = "[00:12.500]带时间戳的写法\n" +
                                    "也可以直接粘歌词正文，不带时间戳",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton(
                            text = "复制",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                clipboard.setText(AnnotatedString(viewModel.copySourceText()))
                                scope.launch { snackbarHostState.showSnackbar("已复制歌词") }
                            },
                        )
                        SmallButton(
                            text = "粘贴",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val pasted = runCatching { clipboard.getText()?.text }.getOrNull()
                                if (pasted.isNullOrBlank()) {
                                    scope.launch { snackbarHostState.showSnackbar("剪贴板是空的") }
                                } else {
                                    viewModel.pasteSourceText(pasted)
                                }
                            },
                        )
                        SmallButton(
                            text = "清空",
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.clearSource() },
                        )
                        SmallButton(
                            text = "保存",
                            modifier = Modifier.weight(1f),
                            highlighted = true,
                            onClick = { viewModel.saveSource() },
                        )
                    }
                }
            }
        }
        state.publishedLocation?.let { location ->
            SectionHeader(title = "导出结果")
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "导出成功，歌词已写入文件",
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

    // 逐句改文本：单行输入比在列表里直接编辑更好按键盘
    editingText?.let { target ->
        LyricTextDialog(
            initial = target.text,
            onDismiss = { editingText = null },
            onConfirm = { text ->
                viewModel.setLineText(target.index, text)
                editingText = null
            },
        )
    }
}

private data class EditTextTarget(val index: Int, val text: String)

/** 列表里的一行：序号、时间戳、文本，以及微调与删除。 */
@Composable
private fun LrcLineRow(
    index: Int,
    line: LrcLine,
    selected: Boolean,
    playing: Boolean,
    onSelect: () -> Unit,
    onEditText: () -> Unit,
    onNudge: (Long) -> Unit,
    onDelete: () -> Unit,
) {
    val background = when {
        // 正在唱的那一句最显眼，其次是用户选中的那一行
        playing -> MaterialTheme.colorScheme.primaryContainer
        selected -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = LrcTiming.display(line.startUs),
            style = MaterialTheme.typography.labelLarge,
            color = if (playing) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.width(58.dp),
        )
        Text(
            text = if (line.text.isBlank()) "（空行，点这里输入歌词）" else line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (line.text.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else if (playing) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEditText)
                .padding(horizontal = 8.dp),
        )
        MicroButton(text = "−1s") { onNudge(-1_000L) }
        MicroButton(text = "+1s") { onNudge(1_000L) }
        MicroButton(text = "删", danger = true) { onDelete() }
    }
}

@Composable
private fun MicroButton(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .width(if (danger) 34.dp else 44.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

@Composable
private fun SmallButton(
    text: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 38.dp)
            .background(
                if (highlighted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 逐句改文本的单行输入框。 */
@Composable
private fun LyricTextDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这一句的歌词") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                shape = QishuiFieldShape,
                singleLine = true,
                placeholder = { Text("留空表示这句没歌词") },
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

