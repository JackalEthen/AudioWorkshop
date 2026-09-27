package cn.qishui.tool.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qishui.tool.domain.download.TREE_DIRECTORY_NAME
import cn.qishui.tool.domain.model.MAX_CONNECTIONS
import cn.qishui.tool.domain.model.MIN_CONNECTIONS
import cn.qishui.tool.ui.components.PlainCard
import cn.qishui.tool.ui.components.PrimaryButton
import cn.qishui.tool.ui.components.SettingRow
import cn.qishui.tool.ui.components.SecondaryButton

@Composable
fun DownloadSettingsScreen(
    viewModel: SettingsViewModel,
    onPickDirectory: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var connectionsText by remember(settings.downloadConnections) {
        mutableStateOf("${settings.downloadConnections}")
    }
    val hasCustomDirectory = !settings.downloadDirectoryUri.isNullOrBlank()
    SettingsSection(
        modifier = modifier,
        title = "下载",
        onBack = onBack,
    ) {
        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("下载目录", style = MaterialTheme.typography.titleMedium)
                SettingRow(
                    label = "当前目录",
                    value = if (hasCustomDirectory) {
                        "$TREE_DIRECTORY_NAME/（所选目录的子文件夹）"
                    } else {
                        "应用内专属目录（filesDir/downloads）"
                    },
                )
                settings.downloadDirectoryUri?.let { uri ->
                    Text(
                        text = uri,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = "选择目录",
                        onClick = onPickDirectory,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { viewModel.setDownloadDirectory(null) },
                        enabled = hasCustomDirectory,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text("恢复默认")
                    }
                }
                Text(
                    text = "选择后会申请长期读写权限，文件写入所选目录下的 $TREE_DIRECTORY_NAME/ 子目录内。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "同时下载数量",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        value = connectionsText,
                        onValueChange = { viewModel.setConnections(it.toIntOrNull() ?: MIN_CONNECTIONS) },
                        modifier = Modifier.width(96.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = connectionsText.toIntOrNull() !in MIN_CONNECTIONS..MAX_CONNECTIONS,
                    )
                }
                Text(
                    text = "范围 $MIN_CONNECTIONS - $MAX_CONNECTIONS，超过数量的任务会排队等待。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}