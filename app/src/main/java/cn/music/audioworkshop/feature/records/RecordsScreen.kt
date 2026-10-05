package cn.music.audioworkshop.feature.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.domain.model.DownloadStatus
import cn.music.audioworkshop.domain.model.DownloadTask
import cn.music.audioworkshop.domain.model.ParseRecord
import cn.music.audioworkshop.ui.components.TopSnackbarHost
import cn.music.audioworkshop.ui.components.DeleteSelectionSheet
import cn.music.audioworkshop.ui.components.EmptyState
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.QishuiTopBar
import cn.music.audioworkshop.ui.components.ScreenListPadding
import cn.music.audioworkshop.ui.components.SecondaryButton
import cn.music.audioworkshop.ui.components.SegmentedControl
import cn.music.audioworkshop.ui.components.SelectBox
import cn.music.audioworkshop.ui.components.SelectionActionChip
import cn.music.audioworkshop.ui.components.StatusPill
import cn.music.audioworkshop.ui.components.StatusTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RecordsScreen(
    viewModel: RecordsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(1) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }

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
                title = "记录中心",
                onBack = {
                    if (selecting) {
                        selecting = false
                        selectedIds = emptySet()
                    } else {
                        onBack()
                    }
                },
                actionContent = {
                    if (selectedTab == 1 && state.downloads.isNotEmpty()) {
                        SelectionActionChip(
                            selecting = selecting,
                            canDelete = selectedIds.isNotEmpty(),
                            onToggleSelecting = { selecting = true },
                            onDelete = { confirmDelete = true },
                        )
                    }
                },
            )
            SegmentedControl(
                options = listOf(
                    "解析历史 · ${state.parseRecords.size}",
                    "下载中心 · ${state.downloads.size}",
                ),
                selectedIndex = selectedTab,
                onSelect = {
                    selectedTab = it
                    selecting = false
                    selectedIds = emptySet()
                },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (selectedTab == 0) {
                ParseRecordList(state.parseRecords)
            } else {
                DownloadList(
                    tasks = state.downloads,
                    busyIds = state.busyIds,
                    selecting = selecting,
                    selectedIds = selectedIds,
                    onToggleSelect = { id ->
                        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                    },
                    onPause = viewModel::pause,
                    onResume = viewModel::resume,
                    onRetry = viewModel::retry,
                )
            }
        }
    }

    if (confirmDelete) {
        DeleteSelectionSheet(
            count = selectedIds.size,
            description = "已选择 ${selectedIds.size} 个下载任务。",
            onDismiss = { confirmDelete = false },
            onConfirm = { deleteFile ->
                viewModel.deleteAll(selectedIds.toList(), deleteFile)
                selectedIds = emptySet()
                selecting = false
                confirmDelete = false
            },
        )
    }
}

@Composable
private fun ParseRecordList(records: List<ParseRecord>) {
    if (records.isEmpty()) {
        EmptyState(
            text = "暂无解析历史",
            hint = "在解析页粘贴分享文本后，成功结果会自动留档。",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScreenListPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(records, key = ParseRecord::id) { record ->
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = record.title ?: "未提供歌名",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatusPill(text = "可恢复", tone = StatusTone.Neutral)
                    }
                    Text(
                        text = record.artist ?: "未提供歌手",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = buildString {
                            append("解析成功 · ")
                            append(record.format?.uppercase() ?: "格式未知")
                            append(" · ")
                            append(formatSize(record.sizeBytes))
                            if (!record.lyrics.isNullOrBlank()) append(" · 含歌词")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "解析时间：${formatTime(record.createdAtEpochMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadList(
    tasks: List<DownloadTask>,
    busyIds: Set<String>,
    selecting: Boolean,
    selectedIds: Set<String>,
    onToggleSelect: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    if (tasks.isEmpty()) {
        EmptyState(
            text = "暂无下载任务",
            hint = "解析成功后点击下载歌曲，任务会显示在这里。",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScreenListPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(tasks, key = DownloadTask::id) { task ->
            DownloadTaskCard(
                task = task,
                busy = task.id in busyIds,
                selecting = selecting,
                selected = task.id in selectedIds,
                onToggleSelect = { onToggleSelect(task.id) },
                onPause = { onPause(task.id) },
                onResume = { onResume(task.id) },
                onRetry = { onRetry(task.id) },
            )
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    busy: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
) {
    val progress = task.expectedSizeBytes
        ?.takeIf { it > 0L }
        ?.let { (task.downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
    FloatingCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    SelectBox(
                        selected = selected,
                        description = "选择 ${task.title ?: "下载任务"}",
                        onToggle = onToggleSelect,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title ?: "未提供歌名",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = task.artist ?: "未提供歌手",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(text = task.status.label(), tone = task.status.tone())
            }
            if (progress != null && task.status.showProgress()) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = "${(progress?.times(100) ?: 0f).toInt()}% · " +
                    "${formatSize(task.downloadedBytes)} / ${formatSize(task.expectedSizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            task.errorMessage?.takeIf(String::isNotBlank)?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (task.status == DownloadStatus.COMPLETED && !task.finalPath.isNullOrBlank()) {
                Text(
                    text = task.finalPath.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!selecting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (task.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)) {
                        SecondaryButton(
                            text = "暂停",
                            onClick = onPause,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                    }
                    if (task.status == DownloadStatus.PAUSED) {
                        SecondaryButton(
                            text = "继续",
                            onClick = onResume,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                    }
                    if (task.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELED)) {
                        SecondaryButton(
                            text = "重试",
                            onClick = onRetry,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                    }
                }
            }
        }
    }
}

private fun DownloadStatus.label(): String = when (this) {
    DownloadStatus.QUEUED -> "等待"
    DownloadStatus.DOWNLOADING -> "下载中"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.FAILED -> "失败"
    DownloadStatus.COMPLETED -> "已完成"
    DownloadStatus.CANCELED -> "已取消"
}

private fun DownloadStatus.tone(): StatusTone = when (this) {
    DownloadStatus.QUEUED -> StatusTone.Neutral
    DownloadStatus.DOWNLOADING -> StatusTone.Active
    DownloadStatus.PAUSED -> StatusTone.Neutral
    DownloadStatus.FAILED -> StatusTone.Danger
    DownloadStatus.COMPLETED -> StatusTone.Solid
    DownloadStatus.CANCELED -> StatusTone.Danger
}

private fun DownloadStatus.showProgress(): Boolean = when (this) {
    DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED -> true
    else -> false
}

private fun formatSize(sizeBytes: Long?): String = sizeBytes?.let {
    when {
        it >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f MB", it / 1024.0 / 1024.0)
        it >= 1024L -> String.format(Locale.getDefault(), "%.2f KB", it / 1024.0)
        else -> "$it B"
    }
} ?: "未知"

private fun formatTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))