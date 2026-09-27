package cn.qishui.tool.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.qishui.tool.R

/** 列表行左侧的复选框：整块可点，语义和无障碍描述一并给出。 */
@Composable
fun SelectBox(
    selected: Boolean,
    description: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onToggle)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(
            icon = if (selected) R.drawable.ic_check else R.drawable.ic_square,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            size = 22.dp,
        )
    }
}

/** 批量删除确认：只勾了「删除本地文件」才会连文件一起清掉。 */
@Composable
fun DeleteSelectionSheet(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (deleteFile: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "删除所选记录",
    description: String? = null,
    confirmLabel: String = "确认删除",
    fileOptionEnabled: Boolean = true,
) {
    var deleteFile by remember { mutableStateOf(false) }
    ConfirmSheet(
        title = title,
        description = description ?: "已选择 $count 项。",
        confirmLabel = confirmLabel,
        onConfirm = { onConfirm(deleteFile) },
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CheckRow(
                label = "删除本地文件",
                description = "不勾选则只删除记录，保留音频文件",
                checked = deleteFile,
                onCheckedChange = { deleteFile = it },
                modifier = Modifier.heightIn(min = 48.dp).padding(bottom = 4.dp),
            )
        }
    }
}

/** 顶栏右侧的「进入复选 / 确认删除」切换按钮。 */
@Composable
fun SelectionActionChip(
    selecting: Boolean,
    canDelete: Boolean,
    onToggleSelecting: () -> Unit,
    onDelete: () -> Unit,
) {
    BarChip(
        onClick = if (selecting) onDelete else onToggleSelecting,
        description = if (selecting) "删除所选" else "选择",
    ) {
        LucideIcon(
            icon = if (selecting) R.drawable.ic_trash_2 else R.drawable.ic_square,
            tint = when {
                selecting && canDelete -> MaterialTheme.colorScheme.error
                selecting -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            size = 22.dp,
        )
    }
}

/** 复选模式下的行容器：整行点选/取消，右上角不再放单条操作。 */
@Composable
fun SelectableRow(
    selecting: Boolean,
    selected: Boolean,
    description: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            SelectBox(selected = selected, description = description, onToggle = onToggle)
        }
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}
