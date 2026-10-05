package cn.music.audioworkshop.feature.reverb

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.ConfirmSheet
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.NumberInputDialog
import cn.music.audioworkshop.ui.components.PlaybackBar
import cn.music.audioworkshop.ui.components.PrimaryButton
import cn.music.audioworkshop.ui.components.QishuiSlider
import cn.music.audioworkshop.ui.components.SectionHeader
import cn.music.audioworkshop.ui.components.SegmentedControl
import kotlinx.coroutines.launch
import java.util.Locale

private const val HINT = "为信号添加房间空间感。" +
    "混响强度、空间大小、衰减时间、预延迟、高频阻尼分别对应湿度、尺寸、RT60、延迟与明暗。调整任一参数即切换为自定义。"

/** 混响功能页。 */
@Composable
fun ReverbScreen(
    viewModel: ReverbViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pickingPreset by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ParamTarget?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importLocalAudio(it.toString()) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    FunctionShell(
        modifier = modifier,
        title = "混响",
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
            PrimaryButton(
                text = when {
                    export.isRunning -> "导出中"
                    !state.hasTrack -> "请先导入音频"
                    else -> "导出"
                },
                onClick = requestExport,
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
                durationMs = state.durationUs / 1000L,
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

        // ---- 预设 ----
        // 6 个空间名挤在一行必然互相遮挡（"录音棚"和"小房间"都是三字），
        // 改成一行只显示当前预设，点开再选。
        SectionHeader(title = "空间预设")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pickingPreset = true }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.matchedPreset.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = state.matchedPreset.caption,
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

        // ---- 五个参数 ----
        SectionHeader(title = "参数调节")
        ParamSlider(
            label = "混响强度",
            caption = "湿声占多少。0 = 完全干声，等于不混响",
            value = state.mix,
            display = "${(state.mix * 100).toInt()}%",
            onChange = { viewModel.apply(mix = it) },
            onFinished = { viewModel.renderPreview(playWhenReady = true) },
            onRequestInput = { editing = ParamTarget.MIX },
        )
        ParamSlider(
            label = "空间大小",
            caption = "房间有多大。越大早期反射越远",
            value = state.roomSize,
            display = "${(state.roomSize * 100).toInt()}%",
            onChange = { viewModel.apply(roomSize = it) },
            onFinished = { viewModel.renderPreview(playWhenReady = true) },
            onRequestInput = { editing = ParamTarget.ROOM_SIZE },
        )
        ParamSlider(
            label = "衰减时间",
            caption = "尾音拖多久。大空间配大衰减才自然",
            value = state.decay,
            display = "${(state.decay * 100).toInt()}%",
            onChange = { viewModel.apply(decay = it) },
            onFinished = { viewModel.renderPreview(playWhenReady = true) },
            onRequestInput = { editing = ParamTarget.DECAY },
        )
        ParamSlider(
            label = "预延迟",
            caption = "回声比原声晚多少。0 = 贴在一起，大空间留一点更真实",
            value = state.predelayMs,
            maxValue = ReverbUiState.MAX_PREDELAY_MS,
            display = "${state.predelayMs.toInt()} ms",
            onChange = { viewModel.apply(predelayMs = it) },
            onFinished = { viewModel.renderPreview(playWhenReady = true) },
            onRequestInput = { editing = ParamTarget.PREDELAY },
        )
        ParamSlider(
            label = "高频阻尼",
            caption = "回声听起来亮还是闷。浴室偏亮、录音棚偏闷",
            value = state.damping,
            display = "${(state.damping * 100).toInt()}%",
            onChange = { viewModel.apply(damping = it) },
            onFinished = { viewModel.renderPreview(playWhenReady = true) },
            onRequestInput = { editing = ParamTarget.DAMPING },
        )

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

    // 6 个空间名挤在一行必然互相遮挡，所以点开底部弹窗再选
    if (pickingPreset) {
        ConfirmSheet(
            title = "选择空间",
            confirmLabel = "关闭",
            onConfirm = { pickingPreset = false },
            onDismiss = { pickingPreset = false },
        ) {
            ReverbPreset.entries.forEach { preset ->
                val selected = preset == state.matchedPreset
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            pickingPreset = false
                            // CUSTOM 没有固定参数，选它等于回到原样而不是套一组值
                            if (!preset.isCustom) {
                                viewModel.applyPreset(preset)
                                viewModel.renderPreview(playWhenReady = true)
                            }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
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

    // 滑杆调不出 0.37 这种值，点数字直接输入
    editing?.let { target ->
        val current = when (target) {
            ParamTarget.MIX -> state.mix
            ParamTarget.ROOM_SIZE -> state.roomSize
            ParamTarget.DECAY -> state.decay
            ParamTarget.DAMPING -> state.damping
            ParamTarget.PREDELAY -> state.predelayMs
        }
        val isPercent = target != ParamTarget.PREDELAY
        NumberInputDialog(
            title = target.label,
            initial = if (isPercent) "%.2f".format(Locale.US, current) else current.toInt().toString(),
            suffix = if (isPercent) "" else "ms",
            allowNegative = false,
            onDismiss = { editing = null },
            onConfirm = { text ->
                val value = text.toFloatOrNull()
                if (value == null) {
                    scope.launch { snackbarHostState.showSnackbar("请输入数字") }
                } else {
                    val normalized = if (isPercent) value / 100f else value
                    when (target) {
                        ParamTarget.MIX -> viewModel.apply(mix = normalized)
                        ParamTarget.ROOM_SIZE -> viewModel.apply(roomSize = normalized)
                        ParamTarget.DECAY -> viewModel.apply(decay = normalized)
                        ParamTarget.DAMPING -> viewModel.apply(damping = normalized)
                        ParamTarget.PREDELAY -> viewModel.apply(predelayMs = normalized)
                    }
                    viewModel.renderPreview(playWhenReady = true)
                }
                editing = null
            },
        )
    }
}

private enum class ParamTarget(val label: String) {
    MIX("混响强度"),
    ROOM_SIZE("空间大小"),
    DECAY("衰减时间"),
    PREDELAY("预延迟"),
    DAMPING("高频阻尼"),
}

/** 参数行：滑杆 + 可点击数值 + 说明。 */
@Composable
private fun ParamSlider(
    label: String,
    caption: String,
    value: Float,
    display: String,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    onRequestInput: () -> Unit,
    maxValue: Float = 1f,
) {
    FloatingCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = display,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
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
                value = value,
                onValueChange = onChange,
                valueRange = 0f..maxValue,
                label = label,
                onValueChangeFinished = onFinished,
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

