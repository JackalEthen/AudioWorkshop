package cn.music.audioworkshop.feature.metadata

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.domain.model.SourceTrack
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.InfoHintAction
import cn.music.audioworkshop.ui.components.InfoHintBox
import cn.music.audioworkshop.ui.components.LucideIcon
import cn.music.audioworkshop.ui.components.PlainCard
import cn.music.audioworkshop.ui.components.PrimaryButton
import cn.music.audioworkshop.ui.components.QishuiFieldShape
import cn.music.audioworkshop.ui.components.QishuiTopBar
import cn.music.audioworkshop.ui.components.ScreenScroll
import cn.music.audioworkshop.ui.components.SectionHeader
import cn.music.audioworkshop.ui.components.TopSnackbarHost
import cn.music.audioworkshop.R
import java.util.Locale

private const val HINT = "仅重写标签，不重新编码音频，无损。" +
    "保存位置可选，源文件不被覆盖。非 MP3 格式写入 ID3 标签后，部分播放器可能不显示。"

@Composable
fun MetadataScreen(
    viewModel: MetadataViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var hintVisible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditField?>(null) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importLocalAudio(it.toString()) }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.setCover(it.toString()) }
    }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mpeg"),
    ) { uri -> uri?.let { viewModel.writeTo(it.toString()) } }

    LaunchedEffect(Unit) { viewModel.attachContext(context) }
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
                title = "修改音乐信息",
                onBack = onBack,
                actionContent = {
                    InfoHintAction(
                        hint = HINT,
                        onOpen = { hintVisible = true },
                    )
                },
            )
            ScreenScroll(modifier = Modifier.weight(1f)) {
                InfoHintBox(
                    hint = HINT,
                    visible = hintVisible,
                    onDismiss = { hintVisible = false },
                )

                TrackPicker(
                    track = state.track,
                    onPick = { importPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                )

                SectionHeader(title = "文件信息")
                FloatingCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ValueRow(
                            label = "文件名",
                            value = state.fileName,
                            onClick = { editing = EditField.FILE_NAME },
                        )
                        ValueRow(label = "格式", value = state.facts.format)
                    }
                }

                SectionHeader(title = "音乐信息")
                FloatingCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ValueRow(
                            label = "音乐名称",
                            value = state.title,
                            onClick = { editing = EditField.TITLE },
                        )
                        ValueRow(
                            label = "演唱者",
                            value = state.artist,
                            onClick = { editing = EditField.ARTIST },
                        )
                        ValueRow(
                            label = "专辑名",
                            value = state.album,
                            onClick = { editing = EditField.ALBUM },
                        )
                        ValueRow(
                            label = "发行年份",
                            value = state.year,
                            onClick = { editing = EditField.YEAR },
                        )
                        CoverRow(
                            uri = state.coverUri,
                            onPick = { coverPicker.launch("image/*") },
                            onClear = { viewModel.setCover(null) },
                        )
                        ValueRow(
                            label = "备注",
                            value = state.comment,
                            onClick = { editing = EditField.COMMENT },
                        )
                    }
                }

                SectionHeader(title = "基础信息")
                FloatingCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ValueRow(
                            label = "采样率",
                            value = state.facts.sampleRateHz.takeIf { it > 0L }?.let { "$it Hz" }.orEmpty(),
                        )
                        ValueRow(
                            label = "比特率",
                            value = state.facts.bitrateBps.takeIf { it > 0L }?.let {
                                String.format(Locale.getDefault(), "%.0f kbps", it / 1000.0)
                            }.orEmpty(),
                        )
                        ValueRow(
                            label = "声道数",
                            value = when (state.facts.channels) {
                                0 -> "未知"
                                1 -> "Mono"
                                2 -> "Stereo"
                                else -> "${state.facts.channels} 声道"
                            },
                        )
                        ValueRow(
                            label = "音乐时长",
                            value = state.facts.durationMs.takeIf { it > 0L }?.let { formatDuration(it) }.orEmpty(),
                        )
                        ValueRow(
                            label = "文件大小",
                            value = formatSize(state.facts.sizeBytes),
                        )
                        ValueRow(label = "文件路径", value = state.facts.path)
                    }
                }

                PlainCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        PrimaryButton(
                            text = if (state.isWorking) "写入中" else "保存到新文件",
                            onClick = {
                                exportPicker.launch(
                                    "${state.fileName.substringBeforeLast('.', state.fileName)}.mp3"
                                )
                            },
                            enabled = state.track != null && !state.isWorking,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    editing?.let { field ->
        val initial = when (field) {
            EditField.FILE_NAME -> state.fileName
            EditField.TITLE -> state.title
            EditField.ARTIST -> state.artist
            EditField.ALBUM -> state.album
            EditField.YEAR -> state.year
            EditField.COMMENT -> state.comment
        }
        EditValueDialog(
            title = field.label,
            initial = initial,
            onDismiss = { editing = null },
            onConfirm = { value ->
                when (field) {
                    EditField.FILE_NAME -> viewModel.setFileName(value)
                    EditField.TITLE -> viewModel.setTitle(value)
                    EditField.ARTIST -> viewModel.setArtist(value)
                    EditField.ALBUM -> viewModel.setAlbum(value)
                    EditField.YEAR -> viewModel.setYear(value)
                    EditField.COMMENT -> viewModel.setComment(value)
                }
                editing = null
            },
        )
    }
}

private enum class EditField(val label: String) {
    FILE_NAME("文件名"),
    TITLE("音乐名称"),
    ARTIST("演唱者"),
    ALBUM("专辑名"),
    YEAR("发行年份"),
    COMMENT("备注"),
}

@Composable
private fun TrackPicker(track: SourceTrack?, onPick: () -> Unit) {
    FloatingCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LucideIcon(
                icon = R.drawable.ic_music,
                tint = MaterialTheme.colorScheme.primary,
                size = 24.dp,
            )
            Text(
                text = track?.title ?: "点击导入音乐",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            LucideIcon(
                icon = R.drawable.ic_chevron_right,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
            )
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value.ifBlank { "未设置" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
        if (onClick != null) {
            LucideIcon(
                icon = R.drawable.ic_chevron_right,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
            )
        }
    }
}

@Composable
private fun CoverRow(uri: String?, onPick: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(onClick = onPick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "封面",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (uri == null) "未设置" else "已选择",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
        if (uri != null) {
            Text(
                text = "清除",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clickable(onClick = onClear),
            )
        }
        LucideIcon(
            icon = R.drawable.ic_chevron_right,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
        )
    }
}

@Composable
private fun EditValueDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(title) { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                shape = QishuiFieldShape,
                singleLine = title != "备注",
            )
        },
        confirmButton = {
            Text(
                text = "确定",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clickable { onConfirm(text.trim()) },
            )
        },
        dismissButton = {
            Text(
                text = "取消",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onDismiss() },
            )
        },
    )
}

private fun formatDuration(durationMs: Long): String {
    val minutes = durationMs / 60_000L
    val seconds = durationMs / 1000L % 60L
    val millis = durationMs % 1000L
    return String.format(Locale.getDefault(), "%02d分%02d秒%03d毫秒", minutes, seconds, millis)
}

private fun formatSize(sizeBytes: Long): String = when {
    sizeBytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.2f MB", sizeBytes / 1_048_576.0)
    sizeBytes >= 1_024L -> String.format(Locale.getDefault(), "%.2f KB", sizeBytes / 1_024.0)
    sizeBytes > 0L -> "$sizeBytes B"
    else -> ""
}
