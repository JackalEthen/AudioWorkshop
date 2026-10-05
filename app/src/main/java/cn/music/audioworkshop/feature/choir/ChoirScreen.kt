package cn.music.audioworkshop.feature.choir

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.domain.model.ChoirPreset
import cn.music.audioworkshop.feature.preset.PresetEffectScreen
import cn.music.audioworkshop.ui.components.PrimaryButton

private const val HINT = "将单个声部扩展为多声部同时演唱。" +
    "两人为一前一后，声部分离清晰；三人加厚中间声部；合唱团为六声部铺开，厚度最大，过量易糊。"

@Composable
fun ChoirScreen(
    viewModel: ChoirViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()

    PresetEffectScreen(
        title = "合唱",
        hint = HINT,
        fileName = state.fileName,
        hasTrack = state.hasTrack,
        durationUs = state.durationUs,
        isRenderingPreview = state.isRenderingPreview,
        isWorking = state.isWorking,
        exportState = export,
        playbackPositionMs = playback.positionMs,
        isPlaying = playback.isPlaying,
        selectedIndex = ChoirPreset.entries.indexOf(state.preset),
        presetLabels = ChoirPreset.entries.map { it.label },
        presetCaptions = ChoirPreset.entries.map { it.caption },
        onPickPreset = { index ->
            ChoirPreset.entries.getOrNull(index)?.let(viewModel::selectPreset)
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

