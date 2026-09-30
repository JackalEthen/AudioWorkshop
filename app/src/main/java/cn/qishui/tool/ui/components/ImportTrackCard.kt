package cn.qishui.tool.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.qishui.tool.R
import cn.qishui.tool.domain.model.SourceTrack

/**
 * 功能页顶部的导入卡：没歌时显示「点击导入音乐」，选完变成歌名。
 * 点它一律弹系统文件选择器，所以两个页面用的是同一套交互。
 */
@Composable
fun ImportTrackCard(
    track: SourceTrack?,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    label: String? = null,
    /** 主标题。传了就用它盖掉「歌曲名 / 点击导入音乐」，视频页传视频名。 */
    primaryText: String? = null,
    placeholder: String = "点击导入音乐",
) {
    PlainCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 参考示例里是一枚圆形底的音符图标
            LucideIcon(
                icon = R.drawable.ic_music,
                tint = MaterialTheme.colorScheme.primary,
                size = 22.dp,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = primaryText ?: track?.title ?: placeholder,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LucideIcon(
                icon = R.drawable.ic_chevron_right,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
            )
        }
    }
}
