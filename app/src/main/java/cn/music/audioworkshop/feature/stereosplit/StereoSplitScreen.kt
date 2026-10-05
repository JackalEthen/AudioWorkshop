package cn.music.audioworkshop.feature.stereosplit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.BottomBarScope
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.PlaybackBar
import cn.music.audioworkshop.ui.components.SectionHeader
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme

private const val HINT = "把一首立体声拆成左、右两个声道，各存成一个文件。" +
    "原曲是单声道的话，两个输出是同一份声音。"

/** 立体声分离。底栏两态：导入后「开始分离」，拆完「导出」。 */
@Composable
fun StereoSplitScreen(
    viewModel: StereoSplitViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBarSlot: @Composable BottomBarScope.(canExport: Boolean, label: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.import(it.toString(), displayNameOf(it.toString())) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    FunctionShell(
        modifier = modifier,
        title = "立体声分离",
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
            // 两态：没拆给「开始分离」，拆完给「导出」。
            bottomBarSlot(
                state.canSplit || state.canExport,
                when {
                    export.isRunning -> "处理中"
                    state.isWorking -> "分离中"
                    state.isSplit -> "导出"
                    state.hasTrack -> "开始分离"
                    else -> "请先导入音频"
                },
            )
        },
        exportFileName = viewModel.suggestedBaseName(),
        exportProgress = ExportProgressState(
            label = export.statusLabel,
            fraction = export.fraction,
            cancellable = !export.isCopying,
            error = export.error,
        ).takeIf { export.isRunning || export.isCopying || export.error != null },
        exportedLocation = state.publishedLeft,
        onExport = { viewModel.export(it) },
        onCancelExport = { viewModel.cancelExport() },
    ) {
        if (state.hasTrack) {
            // 每个声道一节：SectionHeader + PlaybackBar，和别的页面的结构完全一致。
            // 不额外加标签文字，也不套额外容器 —— 那类补丁会让预览条和卡片宽度对不齐。
            SplitSide.entries.forEach { side ->
                SectionHeader(title = side.label)
                PlaybackBar(
                    playing = viewModel.isPreviewing(side),
                    enabled = state.isSplit,
                    positionMs = if (viewModel.isPreviewing(side)) playback.positionMs else 0L,
                    durationMs = state.durationUs / 1000L,
                    onTogglePlay = { viewModel.togglePlay(side) },
                    onSeek = { viewModel.seekTo(it) },
                )
            }
            if (!state.isSplit) {
                Text(
                    text = "还没分离，两条预览条暂时不可用。点底栏「开始分离」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            listOfNotNull(
                state.publishedLeft?.let { "左声道 → $it" },
                state.publishedRight?.let { "右声道 → $it" },
            ).takeIf { it.isNotEmpty() }?.let { lines ->
                SectionHeader(title = "已导出")
                FloatingCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        lines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

private fun displayNameOf(uri: String): String =
    uri.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        .takeIf { it.isNotBlank() }?.take(60) ?: "音频"

