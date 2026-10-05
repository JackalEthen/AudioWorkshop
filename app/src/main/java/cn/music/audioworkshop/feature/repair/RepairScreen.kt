package cn.music.audioworkshop.feature.repair

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.feature.edit.export.ExportUiState
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.BottomBarScope
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.PlaybackBar
import cn.music.audioworkshop.ui.components.SectionHeader

/**
 * 音频修复。
 *
 * 修的是爆破音、咔嗒声、削波峰值和轻微连续噪声。强度越高，
 * 判定为瑕疵的阈值越宽松 —— 修得更多，干净段落也更容易被轻微影响。
 */
@Composable
fun RepairScreen(
    viewModel: RepairViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBarSlot: @Composable BottomBarScope.(canExport: Boolean, label: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.import(it.toString(), queryDisplayName(it.toString())) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    FunctionShell(
        modifier = modifier,
        title = "音频修复",
        hint = "修复爆破音、咔嗒声、削波峰值与轻微连续噪声。" +
            "强度决定瑕疵判定阈值：轻度仅处理接近满幅的样本与明显瞬态；重度放宽阈值，处理范围更大，干净段落亦可能受影响。",
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
                    exportState.isRunning -> "处理中"
                    !state.hasTrack -> "请先导入音频"
                    state.isRenderingPreview -> "生成预览中"
                    else -> "导出"
                },
            )
        },
        exportFileName = viewModel.suggestedFileName(),
        exportProgress = ExportProgressState(
            label = exportState.statusLabel,
            fraction = exportState.fraction,
            cancellable = !exportState.isCopying,
            error = exportState.error,
        ).takeIf { exportState.isRunning || exportState.isCopying || exportState.error != null },
        exportedLocation = state.publishedLocation,
        onExport = { viewModel.export(it) },
        onCancelExport = { viewModel.cancelExport() },
    ) {
        if (state.hasTrack) {
            // ---- 试听 ----
            SectionHeader(title = "试听")
            PlaybackBar(
                playing = playback.isPlaying,
                enabled = !state.isRenderingPreview,
                positionMs = if (state.isRenderingPreview) 0L else playback.positionMs,
                durationMs = state.durationUs / 1000L,
                onTogglePlay = { viewModel.togglePlay() },
                onSeek = { viewModel.seekTo(it) },
            )

            // ---- 强度 ----
            SectionHeader(title = "修复强度")
            // 不加横向 padding：卡片必须和上面的 PlaybackBar 同宽，
            // 两边缩进不一致会一眼看出来。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RepairLevel.entries.forEach { level ->
                    LevelCard(
                        level = level,
                        selected = state.level == level,
                        enabled = !state.isRenderingPreview,
                        onClick = {
                            viewModel.setLevel(level)
                            // 立刻重渲染：不然听到的还是上一个强度，
                            // 会以为调了没用。
                            viewModel.renderPreview()
                        },
                    )
                }
            }

            state.publishedLocation?.let { location ->
                SectionHeader(title = "导出完成")
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

@Composable
private fun LevelCard(
    level: RepairLevel,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FloatingCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = level.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Text(
                text = if (selected) "当前" else "选择",
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** 取文件名。查不到不阻塞流程。 */
private fun queryDisplayName(uriString: String): String {
    val tail = uriString.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
    return tail.takeIf { it.isNotBlank() }?.take(60) ?: "音频"
}

