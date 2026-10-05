package cn.music.audioworkshop.feature.echo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.domain.model.EchoPreset
import cn.music.audioworkshop.feature.preset.PresetEffectScreen
import cn.music.audioworkshop.ui.components.PrimaryButton

private const val HINT = "声音经空间反射后延迟返回，形成回声。" +
    "房间回声延迟短、单层反射；双重回声可辨识两次反射；山谷回声为一高一低的多重反射，层次最复杂。"

@Composable
fun EchoScreen(
    viewModel: EchoViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()

    PresetEffectScreen(
        title = "回声",
        hint = HINT,
        fileName = state.fileName,
        hasTrack = state.hasTrack,
        durationUs = state.durationUs,
        isRenderingPreview = state.isRenderingPreview,
        isWorking = state.isWorking,
        exportState = export,
        playbackPositionMs = playback.positionMs,
        isPlaying = playback.isPlaying,
        selectedIndex = EchoPreset.entries.indexOf(state.preset),
        presetLabels = EchoPreset.entries.map { it.label },
        presetCaptions = EchoPreset.entries.map { it.caption },
        onPickPreset = { index ->
            EchoPreset.entries.getOrNull(index)?.let(viewModel::selectPreset)
        },
        publishedLocation = state.publishedLocation,
        suggestedFileName = viewModel.suggestedFileName(),
        exportResultLabel = "导出成功",
        message = state.message,
        onConsumeMessage = viewModel::consumeMessage,
        onImport = viewModel::importLocalAudio,
        onTogglePlay = viewModel::togglePlay,
        onSeek = viewModel::seekTo,
        onRenderPreview = { viewModel.renderPreview(playWhenReady = true) },
        onExport = viewModel::export,
        onCancelExport = viewModel::cancelExport,
        onBack = onBack,
        modifier = modifier,
        bottomBarSlot = { canExport, label ->
            PrimaryButton(
                text = label,
                onClick = { requestExport() },
                enabled = canExport,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
