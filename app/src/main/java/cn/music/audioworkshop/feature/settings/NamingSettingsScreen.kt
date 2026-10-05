package cn.music.audioworkshop.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.ui.components.PlainCard
import cn.music.audioworkshop.ui.components.QishuiFieldShape

@Composable
fun NamingSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val preview by viewModel.namingPreview.collectAsStateWithLifecycle()
    var pattern by remember { mutableStateOf(settings.autoNamePattern) }
    SettingsSection(
        modifier = modifier,
        title = "其他",
        onBack = onBack,
    ) {
        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("自动命名格式", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = pattern,
                    onValueChange = {
                        pattern = it
                        viewModel.setAutoNamePattern(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("格式") },
                    singleLine = true,
                    shape = QishuiFieldShape,
                    isError = preview.error != null,
                )
                val error = preview.error
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("实时预览", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (preview.error != null) "格式不可用" else "${preview.baseName}.mp3",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "示例来源：示例歌名 / 示例歌手 / 示例专辑 / 20260101",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("可用占位符", style = MaterialTheme.typography.titleMedium)
                listOf(
                    "{歌名} 歌曲标题",
                    "{歌手} 艺术家",
                    "{专辑} 专辑名，本地导入时来自音频标签",
                    "{日期} 下载当天日期，格式 20260101",
                ).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("正则重命名", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "以 re: 开头时，先按默认格式渲染出基础名，再用 Kotlin 正则匹配。" +
                        "捕获组可用 {1} {2} 依次替换；正则后可用空格加替换模板，例如：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "re:\\((.+?)\\)-\\(.+?\\) {1}（现场版）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "命名完成后会统一清理非法字符、合并重复分隔符，扩展名由调用方补上。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}