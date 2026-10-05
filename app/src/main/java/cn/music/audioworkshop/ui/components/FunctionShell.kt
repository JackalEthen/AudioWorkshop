package cn.music.audioworkshop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.music.audioworkshop.R
import java.util.Locale

/**
 * 具体功能页的统一外壳。
 *
 * 结构自上而下：顶栏 / 固定导入条 / 可滚动内容 / 固定底栏。
 * 顶栏、导入条、底栏三段对所有功能页一致，只有内容区由各自实现。
 *
 * [importBar] 固定在顶栏与内容区之间，不随内容滚动。
 * [previewBar] 是内容区滚动内容的**最后一项**（不是底栏）：只有会改变时长或
 * 音效的功能才需要，格式转换、音乐信息这类不碰音频本身的不传。
 *
 * ## 导出三段流程
 * 底栏的导出按钮 onClick 调 [BottomBarScope.requestExport] → 重命名弹窗
 * → 进度弹窗 → 成功提示。三段都由本组件托管，页面只传状态：
 * [exportFileName]（建议名）、[exportProgress]（进度/失败）、
 * [exportedLocation]（成功落点）、[onExport]（确认后真正开始）。
 */
@Composable
fun FunctionShell(
    title: String,
    hint: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable (() -> Unit)? = null,
    importBar: @Composable (() -> Unit)? = null,
    previewBar: @Composable (() -> Unit)? = null,
    /** 导出建议文件名（含扩展名）。非空时才启用重命名弹窗。 */
    exportFileName: String? = null,
    /** 导出进行中则显示进度弹窗。null 表示当前没有导出。 */
    exportProgress: ExportProgressState? = null,
    /** 导出成功后的落点，Snackbar 会带上它。 */
    exportedLocation: String? = null,
    /** 确认了文件名后真正开始导出。 */
    onExport: ((String) -> Unit)? = null,
    onCancelExport: (() -> Unit)? = null,
    bottomBar: @Composable (BottomBarScope.() -> Unit)? = null,
    /**
     * 底栏外层描边宽度，默认 1.dp。透传给 [BottomActionBar]。
     *
     * 底栏里放两个并列按钮时传 0.dp —— 外层那一圈描边会把两个按钮
     * 围成一个整体，此时应该让每个按钮各自描边。歌词编辑页就是这么做的。
     */
    bottomBarBorderWidth: Dp = 1.dp,
    content: @Composable () -> Unit,
) {
    var hintVisible by rememberSaveable { mutableStateOf(false) }
    // 重命名弹窗的可见性与本次的建议文件名。导出三段流程的状态都在这里，各页只传数据。
    // 存建议名而不是布尔：一页两个导出目标（歌曲 / LRC）时两边名字不同。
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
        snackbarHost = { snackbarHostState?.let { TopSnackbarHost(it) } },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            QishuiTopBar(
                title = title,
                onBack = onBack,
                actionContent = {
                    if (actions != null) {
                        actions()
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    InfoHintAction(hint = hint, onOpen = { hintVisible = true })
                },
            )
            // 导入条固定在顶栏下方，不参与滚动
            if (importBar != null) {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                    importBar()
                }
            }
            // weight(1f)：滚动区吃掉顶栏和底栏之外剩下的高度，底栏才留在屏幕里
            ScreenScroll(
                modifier = Modifier.weight(1f),
                bottomPadding = if (bottomBar != null) 24.dp else 96.dp,
            ) {
                content()
                // 预览条属于内容的一部分：跟内容一起滚，不固定
                if (previewBar != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    previewBar()
                }
            }
            if (bottomBar != null) {
                BottomActionBar(
                    borderWidth = bottomBarBorderWidth,
                    content = {
                        // 底栏作用域：requestExport 走重命名弹窗，startExport 直接导出
                        val scope = BottomBarScope(
                            requestExport = {
                                val name = exportFileName
                                if (name != null && onExport != null) {
                                    renaming = name
                                } else {
                                    onExport?.invoke("")
                                }
                            },
                            requestExportAs = { suggested ->
                                if (onExport != null) renaming = suggested
                            },
                        )
                        scope.bottomBar()
                    },
                )
            }
        }
    }

    // 提示只在右上角图标里，不再在页面底部挂一条
    if (hintVisible) {
        ConfirmSheet(
            title = "使用说明",
            confirmLabel = "知道了",
            onConfirm = { hintVisible = false },
            onDismiss = { hintVisible = false },
        ) {
            HintSheetContent(hint)
        }
    }

    // ---- 导出三段流程：重命名 → 进度 → 成功提示 ----
    renaming?.let { suggested ->
        RenameDialog(
            title = "导出",
            initial = suggested,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                // 留空时用建议名兜底，别让用户导出到没有文件名的目标
                onExport?.invoke(name.ifBlank { suggested })
            },
        )
    }

    val progress = exportProgress
    if (progress != null && progress.error == null) {
        ExportProgressDialog(
            statusLabel = progress.label,
            fraction = progress.fraction,
            cancellable = progress.cancellable,
            onCancel = { onCancelExport?.invoke() },
        )
    }

    // 成功与失败都在这里出。放在 shell 而不是各页，是为了保证
    // 「导出成功 + 文件落点」这个提示不会被页面自己漏掉。
    val host = snackbarHostState
    LaunchedEffect(exportedLocation, progress?.error) {
        if (host == null) return@LaunchedEffect
        val location = exportedLocation
        when {
            location != null -> host.showSnackbar("导出成功：$location")
            progress?.error != null -> host.showSnackbar("导出失败：${progress.error}")
        }
    }
}

/**
 * 底栏作用域。给导出按钮提供 [requestExport]：走「先重命名，再导出」的三段流程。
 *
 * 页面的底栏通常就一个导出按钮，所以直接点它即可：
 * ```
 * bottomBar = {
 *     PrimaryButton(text = "导出", onClick = requestExport)
 * }
 * ```
 */
class BottomBarScope internal constructor(
    /** 走重命名弹窗，再交给 FunctionShell 的 onExport。 */
    val requestExport: () -> Unit,
    /**
     * 指定建议文件名的导出请求。
     *
     * 一页有两个导出目标时（比如歌词页要「导出为 LRC」和「导出歌曲」，
     * 两者建议名不同），各传各的名字，共用同一套重命名弹窗和进度流程。
     * 不需要时留空。
     */
    val requestExportAs: (String) -> Unit = {},
) {
    /**
     * 底栏的主操作按钮，铺满底栏宽度。
     *
     * 用 [PrimaryButton] 的话按钮会按文字宽度收缩，比 [BottomActionBar] 的
     * 边框窄一截 —— 视觉上像边框里嵌了个没对齐的按钮。底栏按钮一律用这个。
     */
    @Composable
    fun actionButton(
        label: String,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        PrimaryButton(
            text = label,
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 导出进度。只带渲染弹窗需要的四个字段 ——
 * 通用外壳不该反向依赖 feature 层的 ExportUiState。
 */
data class ExportProgressState(
    /** 弹窗上那行字，如「编码中 45%」。 */
    val label: String,
    val fraction: Float,
    /** 保存到下载目录阶段不能取消（文件已建出来，中途停会留残骸）。 */
    val cancellable: Boolean,
    /** 失败原因，非空时 Snackbar 显示它。 */
    val error: String? = null,
)

/** 固定在屏幕底部的主操作区，浮在滚动内容之上。 */
@Composable
fun BottomActionBar(
    modifier: Modifier = Modifier,
    /**
     * 外层描边宽度。默认 1.dp。
     *
     * 底栏只有一个按钮时，描边和按钮几乎重合，没问题。
     * 但一页有两个并列按钮时（比如歌词页的「导出为LRC」和「导出为歌曲」），
     * 这一圈描边会把两个按钮围成一个整体 —— 那种页面应该传 0.dp，
     * 改为让两个按钮各自描边。
     */
    borderWidth: Dp = 1.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    FrostedBox(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp),
        shape = QishuiCardShape,
        tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        blurDp = 0,
        borderWidth = borderWidth,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 内容多的功能（合成要选歌、格式转换要调参）原先把底栏顶满屏幕，
                // 少的又只占一条 —— 各页手感不一致。统一成可滑动并限高。
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** 试听条：播放键 + 进度 + 当前/总时长。放在固定底栏正上方。 */
@Composable
fun PlaybackBar(
    playing: Boolean,
    enabled: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlainCard(
        modifier = modifier
            .fillMaxWidth()
            // 禁用时整条压暗。只靠 Material 的 enabled 参数不会变色，
            // 用户看不出此刻不能点（预览渲染中就是这种情况）。
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayCircleButton(playing = playing, onClick = onTogglePlay, enabled = enabled)
            Column(modifier = Modifier.weight(1f)) {
                QishuiSlider(
                    value = positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                    label = "播放进度",
                    enabled = enabled,
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatMs(positionMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatMs(durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    return String.format(Locale.getDefault(), "%02d:%02d.%03d", total / 60, total % 60, ms % 1000)
}
