package cn.music.audioworkshop.feature.fade

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.ui.components.AudioImportBar
import cn.music.audioworkshop.ui.components.ExportProgressState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.NumberInputDialog
import cn.music.audioworkshop.ui.components.PlaybackBar
import cn.music.audioworkshop.ui.components.PrimaryButton
import cn.music.audioworkshop.ui.components.QishuiFieldShape
import cn.music.audioworkshop.ui.components.QishuiSlider
import cn.music.audioworkshop.ui.components.SectionHeader
import cn.music.audioworkshop.ui.components.SegmentedControl
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.launch

private const val HINT = "在首尾施加渐变包络，不改变总时长。" +
    "等功率曲线听感更均匀，线性曲线为幅度直线渐变。"

/** 淡入淡出功能页。 */
@Composable
fun FadeScreen(
    viewModel: FadeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<FadeInput?>(null) }
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
        title = "淡入淡出",
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
        // ---- 试听：改参数立刻能听到效果 ----
        if (state.hasTrack) {
            SectionHeader(title = "试听")
            // PlaybackBar 内部已经是一张 PlainCard，外面不要再套 FloatingCard，
            // 否则会出现卡片套卡片的双边框。
            // 这里也不加额外 padding —— 其它卡片都靠 ScreenScroll 的 20dp 边距对齐，
            // 单独加一层会让它比别的卡片窄一圈、高度也不一致。
            PlaybackBar(
                playing = playback.isPlaying,
                // 渲染中禁用：此刻播的还是旧预览，按下去只会让人更困惑
                enabled = !state.isRenderingPreview,
                positionMs = if (state.isRenderingPreview) 0L else playback.positionMs,
                durationMs = state.durationUs / 1000L,
                onTogglePlay = viewModel::togglePlay,
                onSeek = viewModel::seekTo,
            )
// 渲染耗时肉眼可见，必须给出明确反馈，否则用户会以为设置没生效
            if (state.isRenderingPreview) {
                Text(
                    text = "正在生成新效果…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                )
            }
        }

        // ---- 渐变模式 ----
        SectionHeader(title = "渐变模式")
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SegmentedControl(
                    options = FadeMode.entries.map { it.label },
                    selectedIndex = FadeMode.entries.indexOf(state.mode),
                    onSelect = { index ->
                        FadeMode.entries.getOrNull(index)?.let {
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

        // ---- 淡入淡出时长（秒） ----
        // 外层不能再加 padding：SectionHeader 自带 2.dp，叠加后会比「渐变模式」缩进多
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(title = "淡入淡出时长", modifier = Modifier.weight(1f))
            Text(
                text = "重置",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable {
                        viewModel.setFadeInMs(DEFAULT_FADE_IN_MS)
                        viewModel.setFadeOutMs(DEFAULT_FADE_OUT_MS)
                        viewModel.renderPreview(playWhenReady = true)
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        FloatingCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FadeSecondsRow(
                    label = "淡入",
                    caption = "开头从 0% 渐变到 100%",
                    seconds = state.fadeInMs / 1000f,
                    onSecondsChange = { viewModel.setFadeInMs((it * 1000f).roundToLong()) },
                    maxSeconds = FadeViewModel.MAX_FADE_SECONDS,
                    onFinished = { viewModel.renderPreview(playWhenReady = true) },
                    onRequestInput = { editing = FadeInput.FADE_IN },
                )
                FadeSecondsRow(
                    label = "淡出",
                    caption = "结尾前从 100% 渐变到 0",
                    seconds = state.fadeOutMs / 1000f,
                    onSecondsChange = { viewModel.setFadeOutMs((it * 1000f).roundToLong()) },
                    maxSeconds = FadeViewModel.MAX_FADE_SECONDS,
                    onFinished = { viewModel.renderPreview(playWhenReady = true) },
                    onRequestInput = { editing = FadeInput.FADE_OUT },
                )
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

    // 滑杆调不出「2.5 秒」这种值，所以点数字直接输入
    editing?.let { target ->
        val isFadeIn = target == FadeInput.FADE_IN
        NumberInputDialog(
            title = if (isFadeIn) "淡入时长" else "淡出时长",
            initial = "${(if (isFadeIn) state.fadeInMs else state.fadeOutMs) / 1000f}",
            suffix = "秒",
            allowNegative = false,
            onDismiss = { editing = null },
            onConfirm = { text ->
                val seconds = text.toFloatOrNull()
                if (seconds == null) {
                    scope.launch { snackbarHostState.showSnackbar("请输入数字") }
                } else {
                    val ms = (seconds * 1000f).roundToLong()
                    if (isFadeIn) viewModel.setFadeInMs(ms) else viewModel.setFadeOutMs(ms)
                    viewModel.renderPreview(playWhenReady = true)
                }
                editing = null
            },
        )
    }
}

/** 当前正在精确输入的是哪一项。 */
private enum class FadeInput { FADE_IN, FADE_OUT }

private const val DEFAULT_FADE_IN_MS = 2_000L
private const val DEFAULT_FADE_OUT_MS = 3_000L

/**
 * 淡入或淡出的秒数调整行：滑杆 + 可点击的数值。
 *
 * 单位是**秒**，因为用户对「淡入 3 秒」的预期远比「淡入 2000 毫秒」直观。
 * 内部仍存毫秒，导出引擎要的是微秒/毫秒。
 */
@Composable
private fun FadeSecondsRow(
    label: String,
    caption: String,
    seconds: Float,
    maxSeconds: Long,
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
            // 滑杆调不准「2.5 秒」这种值，点数字直接输入 —— 与音量页的大数字输入一致
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
            value = seconds,
            onValueChange = onSecondsChange,
            valueRange = 0f..maxSeconds.toFloat(),
            label = "$label 秒数",
            onValueChangeFinished = onFinished,
        )
    }
}

