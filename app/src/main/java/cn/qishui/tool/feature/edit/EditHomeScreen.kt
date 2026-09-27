package cn.qishui.tool.feature.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qishui.tool.domain.model.EditOperation
import cn.qishui.tool.ui.components.TopSnackbarHost
import cn.qishui.tool.ui.components.FloatingCard
import cn.qishui.tool.ui.components.QishuiCardShape
import cn.qishui.tool.ui.components.QishuiTopBar
import cn.qishui.tool.ui.components.ScreenScroll
import cn.qishui.tool.ui.components.SecondaryButton
import cn.qishui.tool.ui.components.SectionHeader
import cn.qishui.tool.ui.components.StatusPill
import cn.qishui.tool.ui.components.StatusTone

@Composable
fun EditHomeScreen(
    viewModel: EditHomeViewModel,
    onOpenWorkspace: (EditOperation, String, List<String>) -> Unit,
    onOpenRecords: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
        snackbarHost = { TopSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            QishuiTopBar(
                title = "编辑",
                menu = true,
                onAction = onOpenRecords,
            )
            ScreenScroll {
                SectionHeader(title = "选择编辑功能")
                EditFeatureCards.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { card ->
                            FeatureCard(
                                card = card,
                                highlighted = card.operation == EditOperation.TRIM,
                                onClick = { onOpenWorkspace(card.operation, "", emptyList()) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }

                SectionHeader(title = "最近编辑", caption = "查看全部")
                if (state.recentEdits.isEmpty()) {
                    FloatingCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "还没有保存过的编辑项目，功能卡片使用后会出现在这里。",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    state.recentEdits.forEach { recent ->
                        RecentEditCard(
                            recent = recent,
                            onContinue = {
                                onOpenWorkspace(
                                    recent.project.type,
                                    recent.project.sourceTrackId,
                                    recent.project.joinedTrackIds,
                                )
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun FeatureCard(
    card: EditFeatureCard,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.background
    }
    val onContainer = if (highlighted) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    FloatingCard(
        modifier = modifier
            .height(96.dp)
            .clickable(onClick = onClick),
        containerColor = container,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                text = card.label,
                style = MaterialTheme.typography.headlineSmall,
                color = onContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecentEditCard(recent: RecentEdit, onContinue: () -> Unit) {
    FloatingCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${recent.trackTitle} · ${recent.project.type.label()}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "保留 ${recent.project.segments.size} 段 · ${formatStamp(recent.project.updatedAtEpochMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(text = "已应用", tone = StatusTone.Active)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryButton(
                    text = "继续编辑",
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun EditOperation.label(): String = when (this) {
    EditOperation.TRIM -> "裁剪"
    EditOperation.SPLIT -> "分割"
    EditOperation.JOIN -> "拼接"
    EditOperation.FADE_IN -> "淡入"
    EditOperation.FADE_OUT -> "淡出"
    EditOperation.GAIN -> "增益"
    EditOperation.LYRIC_OFFSET -> "歌词校正"
}

private fun formatStamp(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()