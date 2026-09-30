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
import androidx.compose.ui.graphics.graphicsLayer
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
 * 只放外观相关的开关。**音效不列在这里** —— 预设有十多个，摊开会把面板撑爆，
 * 所以只留一个入口，点进去是独立的音效页（[SoundEffectSheet]）。
 */
@Composable
fun PlayerSettingsSheet(
    appearance: PlayerAppearance,
    soundEffect: SoundEffectPreset,
    onOpenSoundEffect: () -> Unit,
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
            SheetMenuRow(
                label = "音效",
                value = soundEffect.displayName,
            ) { onOpenSoundEffect() }
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
 * 音效页。
 *
 * 结构照 lx-music-desktop 的思路：EQ → 干湿并联 → 限幅。
 * 这里只做混响那半，参数按空间从小到大排，尾音越长湿声越多。
 *
 * 点一下立刻生效，不用确认 —— 音效的意义就是当场听出来。
 */
@Composable
fun SoundEffectSheet(
    selected: SoundEffectPreset,
    onPick: (SoundEffectPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmSheet(
        title = "音效",
        description = "点一下立刻生效，按空间从小到大排",
        confirmLabel = "关闭",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
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
            // 把可听维度摆出来，省得「听起来差不多」又变成猜参数。
            // 电话和磁性立体声不是房间，尾音/湿声对它们没意义，各标一句。
            when {
                preset.telephoneBand -> Text(
                    text = "窄带 300–3400Hz · 沙哑",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )

                preset.rotating -> Text(
                    text = "声场环绕 · ${"%.0f".format(ROTATION_SECONDS)} 秒一圈 · 满宽",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )

                preset.isEnabled -> Text(
                    text = "尾音 %.1fs · 湿声 %d%% · 宽度 %d%%".format(
                        preset.rt60Seconds,
                        (preset.wet * 100).toInt(),
                        (preset.stereoWidth * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
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

/** 声场转一圈的秒数，和引擎里的常量保持一致。 */
private const val ROTATION_SECONDS = 12f

private fun formatClock(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * 一行入口。带 [value] 时在右侧显示当前值（比如「音效 · 大厅」）。
 */
@Composable
private fun SheetMenuRow(label: String, value: String? = null, onClick: () -> Unit) {
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
        if (value != null) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        LucideIcon(
            icon = R.drawable.ic_chevron_left,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
            modifier = Modifier.graphicsLayer { scaleX = -1f },
        )
    }
}
