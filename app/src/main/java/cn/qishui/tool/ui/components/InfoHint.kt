package cn.qishui.tool.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.qishui.tool.R

/**
 * 顶栏右侧的 info 按钮 + 页面上部的提示框。
 * 点 info 展开，再点提示框（或再点 info）收起。
 */
@Composable
fun InfoHintAction(
    hint: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    BarChip(onClick = onToggle, description = "使用说明") {
        LucideIcon(
            icon = R.drawable.ic_info,
            tint = if (expanded) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            size = 22.dp,
        )
    }
}

@Composable
fun InfoHintBox(
    hint: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        FloatingCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDismiss),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LucideIcon(
                    icon = R.drawable.ic_info,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 20.dp,
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 记住展开状态用的 holder，省得每个页面各写一遍。 */
@Composable
fun rememberInfoHint(): Pair<Boolean, (Boolean) -> Unit> {
    var expanded by rememberSaveable { mutableStateOf(false) }
    return expanded to { visible: Boolean -> expanded = visible }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = 2,
                )
            }
        }
        if (onClick != null) {
            LucideIcon(
                icon = R.drawable.ic_chevron_right,
                tint = muted,
                size = 18.dp,
            )
        }
    }
}
