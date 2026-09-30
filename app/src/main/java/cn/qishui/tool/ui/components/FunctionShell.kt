package cn.qishui.tool.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.qishui.tool.R
import java.util.Locale

/**
 * 具体功能页的统一外壳：顶栏 + 滚动内容 + 固定底栏 + 底部「温馨提示」折叠行。
 * 所有编辑/音效/音乐信息/视频功能页共用，只有内容区由各自实现。
 */
@Composable
fun FunctionShell(
    title: String,
    hint: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable (() -> Unit)? = null,
    bottomBar: @Composable (ColumnScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var hintVisible by rememberSaveable { mutableStateOf(false) }

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
            // weight(1f)：滚动区吃掉顶栏和底栏之外剩下的高度，底栏才留在屏幕里
            ScreenScroll(
                modifier = Modifier.weight(1f),
                bottomPadding = if (bottomBar != null) 24.dp else 96.dp,
            ) {
                content()
            }
            if (bottomBar != null) {
                BottomActionBar(content = bottomBar)
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
}

/** 固定在屏幕底部的主操作区，浮在滚动内容之上。 */
@Composable
fun BottomActionBar(
    modifier: Modifier = Modifier,
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
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
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
    PlainCard(modifier = modifier.fillMaxWidth()) {
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
