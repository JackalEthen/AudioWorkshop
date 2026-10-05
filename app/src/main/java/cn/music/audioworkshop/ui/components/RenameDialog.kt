package cn.music.audioworkshop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * 导出前的重命名弹窗。所有功能页点「导出」都先走它，用户确认文件名后才开始导出。
 *
 * [initial] 传建议文件名（含扩展名）。用户可以改，也可以留空 —— 留空时
 * [onConfirm] 收到空串，由调用方决定用什么兜底名。
 */
@Composable
fun RenameDialog(
    title: String = "导出",
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = "导出",
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = QishuiFieldShape,
                    singleLine = true,
                    label = { Text("文件名") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            Text(
                text = confirmLabel,
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
                modifier = Modifier.clickable(onClick = onDismiss),
            )
        },
    )
}
