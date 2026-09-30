package cn.qishui.tool.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.qishui.tool.R
import cn.qishui.tool.domain.player.QueueItem
import cn.qishui.tool.domain.player.SoundEffectPreset
import cn.qishui.tool.ui.components.CheckRow
import cn.qishui.tool.ui.components.ConfirmSheet
import cn.qishui.tool.ui.components.LucideIcon
import cn.qishui.tool.ui.components.QishuiFieldShape
import cn.qishui.tool.ui.components.SongRow

/**
 * 定时关闭弹窗：用户自己填分钟数，不给固定档位。
 */
@Composable
fun SleepTimerSheet(
    remainingMs: Long,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var minutesText by remember {
        mutableStateOf(
            if (remainingMs > 0L) {
                ((remainingMs / 60_000L) + 1L).toString()
            } else {
                ""
            },
        )
    }
    val minutes = minutesText.trim().toIntOrNull() ?: 0

    ConfirmSheet(
        title = "定时关闭",
        description = "到点自动暂停播放，填分钟数",
        confirmLabel = "确定",
        onConfirm = {
            if (minutes > 0) onPick(minutes) else onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = minutesText,
                onValueChange = { updated ->
                    // 只收数字，最多 4 位（9999 分钟约 7 天，够用了）
                    val digits = updated.filter { it.isDigit() }.take(4)
                    minutesText = digits
                },
                label = { Text("分钟") },
                placeholder = { Text("例如 30") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = QishuiFieldShape,
            )
            Text(
                text = if (minutes > 0) "$minutes 分钟后自动暂停" else "填一个大于 0 的分钟数",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (remainingMs > 0L) {
                SheetMenuRow(label = "取消当前定时（剩余 ${formatClock(remainingMs)}）") { onPick(0) }
            }
        }
    }
}

/**
 * 播放设置面板。
 *
 * 底部第 4 个按钮是播放列表；音效预设也放这里。
 */
@Composable
fun PlayerSettingsSheet(
    appearance: PlayerAppearance,
    soundEffect: SoundEffectPreset,
    onPickSoundEffect: (SoundEffectPreset) -> Unit,
    onToggleParticle: (Boolean) -> Unit,
    onToggleLyricDrag: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmSheet(
        title = "播放设置",
        description = "音效会实时作用于播放音频",
        confirmLabel = "关闭",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "音效",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SoundEffectPresetList(
                selected = soundEffect,
                onPick = onPickSoundEffect,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "外观",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CheckRow(
                label = "粒子动画",
                checked = appearance.particleCoverEnabled,
                onCheckedChange = onToggleParticle,
            )
            CheckRow(
                label = "允许拖拽歌词调整进度",
                checked = appearance.lyricDragEnabled,
                onCheckedChange = onToggleLyricDrag,
            )
        }
    }
}

/**
 * 播放列表面板。
 *
 * 没有歌单，队列是手工累积出来的，所以必须能一键清空。
 */
@Composable
fun QueueSheet(
    queue: List<QueueItem>,
    currentMediaId: String?,
    onPlayAt: (Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmSheet(
        title = "播放列表",
        description = if (queue.isEmpty()) "还没有加入歌曲" else "共 ${queue.size} 首",
        confirmLabel = "关闭",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (queue.isEmpty()) {
                Text(
                    text = "队列是空的。在歌曲列表里点歌会自动加入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                queue.forEachIndexed { index, item ->
                    SongRow(
                        title = item.title,
                        artist = item.artist,
                        coverUri = item.coverUri,
                        durationMs = item.durationMs.takeIf { it > 0L },
                        isPlaying = item.mediaId == currentMediaId,
                        onClick = { onPlayAt(index) },
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                SheetMenuRow(label = "清空播放列表") { onClear() }
            }
        }
    }
}

/**
 * 音效预设列表。当前项打勾，点一下立刻生效。
 */
@Composable
private fun SoundEffectPresetList(
    selected: SoundEffectPreset,
    onPick: (SoundEffectPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SoundEffectPreset.selectable.forEach { preset ->
            SoundEffectRow(
                preset = preset,
                selected = preset == selected,
                onClick = { onPick(preset) },
            )
        }
    }
}

@Composable
private fun SoundEffectRow(
    preset: SoundEffectPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(QishuiFieldShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preset.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            LucideIcon(
                icon = R.drawable.ic_check,
                tint = MaterialTheme.colorScheme.primary,
                size = 18.dp,
            )
        }
    }
}

private fun formatClock(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun SheetMenuRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
