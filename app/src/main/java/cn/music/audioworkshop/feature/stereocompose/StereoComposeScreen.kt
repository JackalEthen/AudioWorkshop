package cn.music.audioworkshop.feature.stereocompose

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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

private const val HINT = "把左声道和右声道两个文件合成一首立体声。" +
    "和立体声分离正好相反：那边一个拆成两个，这边两个并成一个。"

/** 立体声合成。底栏两态：导入齐了「开始合成」，合完「导出」。 */
@Composable
fun StereoComposeScreen(
    viewModel: StereoComposeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBarSlot: @Composable BottomBarScope.(canExport: Boolean, label: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val leftPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.import(ComposeSide.LEFT, it.toString(), displayNameOf(it.toString())) }
    }
    val rightPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.import(ComposeSide.RIGHT, it.toString(), displayNameOf(it.toString())) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    FunctionShell(
        modifier = modifier,
        title = "立体声合成",
        hint = HINT,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        importBar = {
            AudioImportBar(
                fileName = state.leftName.takeIf { it.isNotBlank() },
                onPick = {
                    // 点导入条按缺哪一路来选，省掉一个按钮
                    if (state.leftPath.isBlank()) leftPicker.launch(AudioMime) else rightPicker.launch(AudioMime)
                },
            )
        },
        bottomBar = {
            bottomBarSlot(
                state.canCompose || state.canExport,
                when {
                    export.isRunning -> "处理中"
                    state.isWorking -> "合成中"
                    state.isComposed -> "导出"
                    !state.leftPath.isBlank() && state.rightPath.isBlank() -> "还需导入右声道"
                    state.leftPath.isBlank() -> "请先导入两个声道"
                    else -> "开始合成"
                },
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
        onExport = { viewModel.export(it) },
        onCancelExport = { viewModel.cancelExport() },
    ) {
        // ---- 两路输入 ----
        SectionHeader(title = "声道")
        ComposeSide.entries.forEach { side ->
            SourceCard(
                label = side.label,
                fileName = when (side) {
                    ComposeSide.LEFT -> state.leftName
                    ComposeSide.RIGHT -> state.rightName
                },
                playing = viewModel.isPlayingSource(side),
                enabled = when (side) {
                    ComposeSide.LEFT -> state.leftPath.isNotBlank()
                    ComposeSide.RIGHT -> state.rightPath.isNotBlank()
                },
                onPick = {
                    if (side == ComposeSide.LEFT) {
                        leftPicker.launch(AudioMime)
                    } else {
                        rightPicker.launch(AudioMime)
                    }
                },
                onTogglePlay = { viewModel.togglePlaySource(side) },
            )
        }

        // ---- 两路各自的试听 ----
        if (state.hasBoth) {
            ComposeSide.entries.forEach { side ->
                SectionHeader(title = "${side.label}试听")
                PlaybackBar(
                    playing = viewModel.isPlayingSource(side),
                    enabled = true,
                    positionMs = if (viewModel.isPlayingSource(side)) playback.positionMs else 0L,
                    durationMs = state.durationUs / 1000L,
                    onTogglePlay = { viewModel.togglePlaySource(side) },
                    onSeek = { viewModel.seekTo(it) },
                )
            }
        }

        // ---- 合成结果 ----
        if (state.isComposed) {
            SectionHeader(title = "合成结果")
            PlaybackBar(
                playing = viewModel.isPlayingComposed(),
                enabled = true,
                positionMs = if (viewModel.isPlayingComposed()) playback.positionMs else 0L,
                durationMs = state.durationUs / 1000L,
                onTogglePlay = { viewModel.togglePlayComposed() },
                onSeek = { viewModel.seekTo(it) },
            )
        }

        state.publishedLocation?.let { location ->
            SectionHeader(title = "已导出")
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "已导出",
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
}

/** 一路输入的卡片：整行可点换文件，右侧小按钮试听。 */
@Composable
private fun SourceCard(
    label: String,
    fileName: String,
    playing: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
    onTogglePlay: () -> Unit,
) {
    FloatingCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(onClick = onPick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = fileName.ifBlank { "点击选择文件" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (playing) "暂停" else "试听",
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.clickable(enabled = enabled, onClick = onTogglePlay),
            )
        }
    }
}

private val AudioMime = arrayOf("audio/*", "application/octet-stream")

private fun displayNameOf(uri: String): String =
    uri.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        .takeIf { it.isNotBlank() }?.take(60) ?: "音频"
