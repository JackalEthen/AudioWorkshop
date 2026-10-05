package cn.music.audioworkshop.feature.player

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
import androidx.compose.ui.unit.dp
import cn.music.audioworkshop.ui.components.ConfirmSheet

/** 歌曲长按菜单的一项。 */
data class SongAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * 歌曲长按菜单。
 *
 * 形状参考 mica-music 的 SongActionMenuSheet，视觉换成我们的 ConfirmSheet 语汇。
 * 没有歌单，所以「加入播放列表」就是入队，「下一首播放」和「立即播放」分开给。
 */
@Composable
fun SongActionSheet(
    title: String,
    subtitle: String?,
    actions: List<SongAction>,
    onDismiss: () -> Unit,
) {
    ConfirmSheet(
        title = title,
        description = subtitle,
        confirmLabel = "关闭",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            actions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            action.onClick()
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
