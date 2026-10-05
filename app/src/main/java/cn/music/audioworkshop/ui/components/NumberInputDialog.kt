package cn.music.audioworkshop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 单值输入弹窗。用于滑杆旁边做精确输入。 */
@Composable
fun NumberInputDialog(
    title: String,
    initial: String,
    suffix: String,
    allowNegative: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    val filtered = input.filter { it.isDigit() || (allowNegative && (it == '-' || it == '+')) }
                    text = if (filtered.isEmpty() || filtered == "-") filtered else filtered
                },
                modifier = Modifier.fillMaxWidth(),
                shape = QishuiFieldShape,
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
                trailingIcon = {
                    androidx.compose.material3.Text(
                        text = suffix,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        },
        confirmButton = {
            androidx.compose.material3.Text(
                text = "确定",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clickable { onConfirm(text) },
            )
        },
        dismissButton = {
            androidx.compose.material3.Text(
                text = "取消",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onDismiss),
            )
        },
    )
}

/** 一排快捷值按钮，每行三个。 */
@Composable
fun QuickValues(
    values: List<Float>,
    isSelected: (Float) -> Boolean,
    format: (Float) -> String,
    onPick: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        values.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { value ->
                    val selected = isSelected(value)
                    androidx.compose.material3.Text(
                        text = format(value),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                MaterialTheme.shapes.small,
                            )
                            .clickable { onPick(value) }
                            .padding(vertical = 9.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
