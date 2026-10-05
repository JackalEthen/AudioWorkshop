package cn.music.audioworkshop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.music.audioworkshop.R

/**
 * 功能页固定的文件导入条。放在 [FunctionShell] 的顶栏与内容区之间，不随内容滚动。
 *
 * [fileName] 为 null 表示还没导入，未导入时整条仍可点（点一下唤起系统选择器）。
 */
@Composable
fun AudioImportBar(
    fileName: String?,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlainCard(modifier = modifier.fillMaxWidth().clickable(onClick = onPick)) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LucideIcon(
                icon = if (fileName == null) R.drawable.ic_file_audio else R.drawable.ic_music,
                tint = if (fileName == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                size = 22.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName ?: "点击导入音频",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (fileName == null) "支持 MP3 / WAV / FLAC / M4A 等常见格式" else "点击可更换",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = if (fileName == null) "导入" else "更换",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
