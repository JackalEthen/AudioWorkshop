package cn.music.audioworkshop.feature.join

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
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
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.NumberInputDialog
import cn.music.audioworkshop.ui.components.PlaybackBar
import cn.music.audioworkshop.ui.components.PrimaryButton
import cn.music.audioworkshop.ui.components.QishuiSlider
import cn.music.audioworkshop.ui.components.ScreenScroll
import cn.music.audioworkshop.ui.components.SectionHeader
import cn.music.audioworkshop.ui.components.SegmentedControl
import kotlinx.coroutines.launch
import java.util.Locale

private const val HINT = "按列表顺序拼接多段音频，次序可调整，各段之间可插入空白。" +
"接缝方式：硬切、交叉淡化、稳定化、无损（不裁剪原样本）。"

/** 合成功能页。多段音频按顺序拼接。 */
@Composable
fun JoinScreen(
    viewModel: JoinViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScopeCompat()
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    // 多选：一次可以选好几首
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.addTracks(uris.map { it.toString() }) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    FunctionShell(
        modifier = modifier,
        title = "合成",
        hint = HINT,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        importBar = {
            AudioImportBar(
                fileName = if (state.items.isEmpty()) {
                    null
                } else {
                    "共 ${state.items.size} 段音频"
                },
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
                durationMs = state.durationMs,
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

        // ---- 音频列表 ----
        SectionHeader(title = "音频顺序")
        if (state.items.isEmpty()) {
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "点上方导入条添加音频，可一次多选",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            // 所有音频放在同一张卡片里：它们是一个整体，拆成多张会让人以为是多个独立步骤
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    state.items.forEachIndexed { index, item ->
                        JoinItemRow(
                            index = index,
                            item = item,
                            isFirst = index == 0,
                            isLast = index == state.items.lastIndex,
                            onMoveUp = { viewModel.moveUp(index) },
                            onMoveDown = { viewModel.moveDown(index) },
                            onRemove = { viewModel.removeAt(index) },
                            onEditGap = { editing = EditTarget.Gap(index) },
                        )
                    }
                }
            }
        }

        // ---- 接缝方式 ----
        SectionHeader(title = "接缝方式")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SegmentedControl(
                    options = JoinMode.entries.map { it.label },
                    selectedIndex = JoinMode.entries.indexOf(state.mode),
                    onSelect = { index ->
                        JoinMode.entries.getOrNull(index)?.let {
                            viewModel.setMode(it)
                            viewModel.renderPreview(playWhenReady = true)
                        }
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

        // ---- 接缝时长（正常模式不需要） ----
        if (state.mode != JoinMode.NORMAL && state.hasTrack) {
            SectionHeader(title = "接缝时长")
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    SecondsRow(
                        label = "过渡",
                        caption = if (state.mode == JoinMode.PRESERVE) {
                            "在每处接缝插入等长空白，不裁任何原样本"
                        } else {
                            "前一首淡出接后一首淡入，消除接缝爆音"
                        },
                        seconds = state.transitionMs / 1000f,
                        maxSeconds = (state.maxTransitionMs() / 1000f).coerceAtLeast(0.1f),
                        onSecondsChange = { viewModel.setTransitionMs((it * 1000f).toLong()) },
                        onFinished = { viewModel.renderPreview(playWhenReady = true) },
                        onRequestInput = { editing = EditTarget.Transition },
                    )
                }
            }
        }

        // ---- 末尾空白 ----
        if (state.hasTrack) {
            SectionHeader(title = "末尾空白")
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    SecondsRow(
                        label = "结尾",
                        caption = "最后一首播完之后再多留一段静音",
                        seconds = state.trailingSilenceMs / 1000f,
                        maxSeconds = JoinUiState.MAX_GAP_MS / 1000f,
                        onSecondsChange = { viewModel.setTrailingSilenceMs((it * 1000f).toLong()) },
                        onFinished = { viewModel.renderPreview(playWhenReady = true) },
                        onRequestInput = { editing = EditTarget.Trailing },
                    )
                }
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

    editing?.let { target ->
        val index = (target as? EditTarget.Gap)?.index ?: -1
        val currentMs = when (target) {
            is EditTarget.Gap -> state.items.getOrNull(index)?.gapAfterMs ?: 0L
            EditTarget.Transition -> state.transitionMs
            EditTarget.Trailing -> state.trailingSilenceMs
        }
        NumberInputDialog(
            title = when (target) {
                is EditTarget.Gap -> "第 ${index + 1} 项后面的空白"
                EditTarget.Transition -> "接缝时长"
                EditTarget.Trailing -> "末尾空白"
            },
            initial = "${currentMs / 1000f}",
            suffix = "秒",
            onDismiss = { editing = null },
            onConfirm = { text ->
                val seconds = text.toFloatOrNull()
                if (seconds == null) {
                    scope.launch { snackbarHostState.showSnackbar("请输入数字") }
                } else {
                    val ms = (seconds * 1000f).toLong()
                    when (target) {
                        is EditTarget.Gap -> viewModel.setGapAfterMs(index, ms)
                        EditTarget.Transition -> viewModel.setTransitionMs(ms)
                        EditTarget.Trailing -> viewModel.setTrailingSilenceMs(ms)
                    }
                    viewModel.renderPreview(playWhenReady = true)
                }
                editing = null
            },
        )
    }
}

/** 正在精确输入的是哪一项。 */
private sealed interface EditTarget {
    data class Gap(val index: Int) : EditTarget
    data object Transition : EditTarget
    data object Trailing : EditTarget
}

/**
 * 列表里的一项：序号、文件名、时长、排序与删除、以及这一项后面的空白。
 *
 * 每项之间有一条分隔线 —— 都在同一张卡片里，没有卡片边框来区分层级了。
 */
@Composable
private fun JoinItemRow(
    index: Int,
    item: JoinItem,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onEditGap: () -> Unit,
) {
    Column {
        if (!isFirst) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = formatDuration(item.durationUs / 1000L),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MoveArrow(enabled = !isFirst, onClick = onMoveUp, glyph = "↑")
                MoveArrow(enabled = !isLast, onClick = onMoveDown, glyph = "↓")
                Text(
                    text = "删除",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable(onClick = onRemove)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            // 空白行：点数字输秒数
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEditGap)
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "后面插入空白",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (item.gapAfterMs <= 0L) {
                        "无"
                    } else {
                        "${"%.1f".format(Locale.getDefault(), item.gapAfterMs / 1000f)} 秒"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (item.gapAfterMs <= 0L) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

/** 排序箭头。首项的上移、末项的下移置灰但保留位置 —— 隐藏会让布局在排序后跳动。 */
@Composable
private fun MoveArrow(enabled: Boolean, onClick: () -> Unit, glyph: String) {
    Text(
        text = glyph,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        },
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 秒数调整行：滑杆 + 可点击数值。与淡入淡出页同一套控件。 */
@Composable
private fun SecondsRow(
    label: String,
    caption: String,
    seconds: Float,
    maxSeconds: Float,
    onSecondsChange: (Float) -> Unit,
    onFinished: () -> Unit,
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
                text = "${"%.1f".format(Locale.getDefault(), seconds)} 秒",
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
            value = seconds.coerceIn(0f, maxSeconds.coerceAtLeast(0.1f)),
            onValueChange = onSecondsChange,
            valueRange = 0f..maxSeconds.coerceAtLeast(0.1f),
            label = "$label 秒数",
            onValueChangeFinished = onFinished,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) "${minutes}分${seconds}秒" else "${seconds}秒"
}

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()

