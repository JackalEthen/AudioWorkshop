package cn.qishui.tool.feature.edit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qishui.tool.domain.model.ExportPackage
import cn.qishui.tool.domain.model.ExportValidationStatus
import cn.qishui.tool.ui.components.TopSnackbarHost
import cn.qishui.tool.ui.components.DeleteSelectionSheet
import cn.qishui.tool.ui.components.EmptyState
import cn.qishui.tool.ui.components.PlainCard
import cn.qishui.tool.ui.components.FilterRow
import cn.qishui.tool.ui.components.QishuiTopBar
import cn.qishui.tool.ui.components.ScreenListPadding
import cn.qishui.tool.ui.components.SecondaryButton
import cn.qishui.tool.ui.components.SelectBox
import cn.qishui.tool.ui.components.SelectionActionChip
import cn.qishui.tool.ui.components.StatusPill
import cn.qishui.tool.ui.components.StatusTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun EditRecordsScreen(
    viewModel: EditRecordsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedIndex by remember { mutableIntStateOf(0) }
    val card = EditFeatureCards.getOrElse(selectedIndex) { EditFeatureCards.first() }
    val records = state.groupedProjects[card].orEmpty()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                title = "编辑记录",
                onBack = {
                    if (selecting) {
                        selecting = false
                        selectedIds = emptySet()
                    } else {
                        onBack()
                    }
                },
                actionContent = {
                    if (records.isNotEmpty()) {
                        SelectionActionChip(
                            selecting = selecting,
                            canDelete = selectedIds.isNotEmpty(),
                            onToggleSelecting = { selecting = true },
                            onDelete = { confirmDelete = true },
                        )
                    }
                },
            )
            FilterRow(
                options = EditFeatureCards.map { it.label },
                selectedIndex = selectedIndex,
                onSelect = {
                    selectedIndex = it
                    selecting = false
                    selectedIds = emptySet()
                },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (records.isEmpty()) {
                EmptyState(
                    text = "暂无${card.label}记录",
                    hint = "使用${card.label}并保存后，项目会归档在这里里",
                )
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = ScreenListPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(records, key = { it.project.id }) { record ->
                    EditProjectCard(
                        record = record,
                        selecting = selecting,
                        selected = record.project.id in selectedIds,
                        onToggleSelect = {
                            selectedIds = if (record.project.id in selectedIds) {
                                selectedIds - record.project.id
                            } else {
                                selectedIds + record.project.id
                            }
                        },
                        onOpen = { pkg ->
                            scope.launch {
                                val message = openExport(context, pkg.outputPath)
                                if (message != null) snackbarHostState.showSnackbar(message)
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteSelectionSheet(
            count = selectedIds.size,
            description = "已选择 ${selectedIds.size} 个编辑项目。",
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
private fun EditProjectCard(
    record: EditProjectRecord,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: (ExportPackage) -> Unit,
) {
    val project = record.project
    PlainCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    SelectBox(
                        selected = selected,
                        description = "选择 ${project.type.card().label} 记录",
                        onToggle = onToggleSelect,
                    )
                }
                Text(
                    text = "${project.type.card().label} · ${project.segments.size} 段",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusPill(text = "已应用", tone = StatusTone.Active)
            }
            if (project.joinedTrackIds.isNotEmpty()) {
                Text(
                    text = "合成 ${project.joinedTrackIds.size} 首：${project.joinedTrackIds.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            project.gainDb?.let {
                Text(
                    text = "增益 ${it} dB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if ((project.fadeInMs ?: 0L) > 0L || (project.fadeOutMs ?: 0L) > 0L) {
                Text(
                    text = "淡入 ${project.fadeInMs ?: 0L} ms · 淡出 ${project.fadeOutMs ?: 0L} ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            project.lyricOffsetMs?.takeIf { it != 0L }?.let {
                Text(
                    text = "歌词偏移 ${it} ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "保存时间 ${formatTime(project.updatedAtEpochMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (record.exports.isEmpty()) {
                Text(
                    text = "尚未导出",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                record.exports.forEach { pkg ->
                    ExportRow(pkg = pkg, onOpen = { onOpen(pkg) })
                }
            }
        }
    }
}

@Composable
private fun ExportRow(pkg: ExportPackage, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${pkg.format.uppercase()} · ${formatDuration(pkg.durationMs)} · " +
                    formatSize(pkg.sizeBytes),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "校验：${pkg.validationStatus.label()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        SecondaryButton(
            text = "打开",
            onClick = onOpen,
            enabled = pkg.validationStatus == ExportValidationStatus.PASSED,
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }
}

private fun openExport(context: Context, outputPath: String): String? {
    val uri = runCatching { Uri.parse(outputPath) }.getOrNull()
        ?: return "无法识别导出位置"
    return try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        null
    } catch (missing: ActivityNotFoundException) {
        "没有可打开该音频的应用"
    } catch (denied: SecurityException) {
        "没有权限打开该文件：${denied.message ?: "未知错误"}"
    }
}

private fun ExportValidationStatus.label(): String = when (this) {
    ExportValidationStatus.PENDING -> "待校验"
    ExportValidationStatus.PASSED -> "通过"
    ExportValidationStatus.FAILED -> "失败"
}

private fun formatDuration(durationMs: Long): String =
    String.format(Locale.getDefault(), "%d:%02d", durationMs / 60_000L, durationMs / 1000L % 60L)

private fun formatSize(sizeBytes: Long): String = when {
    sizeBytes >= 1_048_576L ->
        String.format(Locale.getDefault(), "%.1f MB", sizeBytes / 1_048_576.0)

    sizeBytes >= 1_024L ->
        String.format(Locale.getDefault(), "%.1f KB", sizeBytes / 1_024.0)

    else -> "$sizeBytes B"
}

private fun formatTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

private const val MIME_TYPE = "audio/mpeg"