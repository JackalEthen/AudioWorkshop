package cn.music.audioworkshop.feature.preset

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable

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
import cn.music.audioworkshop.feature.edit.export.ExportUiState
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.ConfirmSheet
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.PlaybackBar

import cn.music.audioworkshop.ui.components.SectionHeader


/**
 * 「选一个预设 + 试听 + 导出」的页面骨架。
 *
 * 回声和合唱只有预设内容不同，页面结构完全一样 —— 所以共用这一个，
 * 两边各传自己的预设列表和导出方法，避免同一个页面抄两遍再各自漂移。
 */
@Composable
fun PresetEffectScreen(
    title: String,
    hint: String,
    fileName: String,
    hasTrack: Boolean,
    durationUs: Long,
    isRenderingPreview: Boolean,
    isWorking: Boolean,
    exportState: ExportUiState,
    playbackPositionMs: Long,
    isPlaying: Boolean,
    /** 当前选中的预设下标，-1 表示未选。 */
    selectedIndex: Int,
    presetLabels: List<String>,
    presetCaptions: List<String>,
    onPickPreset: (Int) -> Unit,
    publishedLocation: String?,
    suggestedFileName: String?,
    exportResultLabel: String,
    message: String?,
    onConsumeMessage: () -> Unit,
    onImport: (String) -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onRenderPreview: () -> Unit,
    onExport: (String) -> Unit,
    onCancelExport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
/**
     * 底栏按钮。接收者是 [BottomBarScope] —— 只有通过它的 `requestExport`
     * 才会弹重命名框，直接调 onExport 会跳过那一步。
     */
    bottomBarSlot: @Composable cn.music.audioworkshop.ui.components.BottomBarScope.(canExport: Boolean, label: String) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
var picking by remember { mutableStateOf(false) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onImport(it.toString()) }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            onConsumeMessage()
        }
    }

    FunctionShell(
        modifier = modifier,
        title = title,
        hint = hint,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        importBar = {
            AudioImportBar(
                fileName = fileName.takeIf { it.isNotBlank() },
                onPick = { importPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
            )
        },
bottomBar = {
            // 重命名弹窗的触发路径在 BottomBarScope 里，所以按钮由调用方构造后传进来。
            // 这里把 scope 作为接收者传下去，调用方就能拿到 requestExport。
// enabled 只看忙碌状态，不看有没有导入 —— 未导入时按钮保持常态色，
            // 「请先导入音频」只是文案，点下去由 ViewModel 拒绝并提示。
            this.bottomBarSlot(
                !exportState.isRunning && !isWorking,
                when {
                    exportState.isRunning -> "处理中"
                    !hasTrack -> "请先导入音频"
                    selectedIndex < 0 -> "请先选择预览"
                    else -> "导出"
                },
            )
        },
        exportFileName = suggestedFileName,
        exportProgress = ExportProgressState(
            label = exportState.statusLabel,
            fraction = exportState.fraction,
            cancellable = !exportState.isCopying,
            error = exportState.error,
        ).takeIf { exportState.isRunning || exportState.isCopying || exportState.error != null },
        exportedLocation = publishedLocation,
        onExport = onExport,
        onCancelExport = onCancelExport,
    ) {
        // ---- 试听 ----
        if (hasTrack) {
            SectionHeader(title = "试听")
            PlaybackBar(
                playing = isPlaying,
                enabled = !isRenderingPreview,
                positionMs = if (isRenderingPreview) 0L else playbackPositionMs,
                durationMs = durationUs / 1000L,
                onTogglePlay = onTogglePlay,
                onSeek = onSeek,
            )
            if (isRenderingPreview) {
                Text(
                    text = "正在生成新效果…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                )
            }
        }

        // ---- 预设选择 ----
        // 一行只显示当前预设，点开再选：预设名两三个字，挤在分段控件里必然互相遮挡
        SectionHeader(title = "类型")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { picking = true }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedIndex.takeIf { it >= 0 }?.let { presetLabels[it] } ?: "点击选择",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selectedIndex >= 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = selectedIndex.takeIf { it >= 0 }
                            ?.let { presetCaptions[it] }
                            ?: "还没有选择类型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "切换 ▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 导出结果 ----
        publishedLocation?.let { location ->
            SectionHeader(title = "导出结果")
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = exportResultLabel,
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

    if (picking) {
        ConfirmSheet(
            title = "选择${title}类型",
            confirmLabel = "关闭",
            onConfirm = { picking = false },
            onDismiss = { picking = false },
        ) {
            presetLabels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            picking = false
                            onPickPreset(index)
                            onRenderPreview()
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            text = presetCaptions[index],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) {
                        Text(
                            text = "当前",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

