package cn.qishui.tool.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qishui.tool.domain.source.LxSourceInfo
import cn.qishui.tool.domain.source.LxSourceState
import cn.qishui.tool.domain.source.MusicSource
import cn.qishui.tool.feature.source.MusicSourceViewModel
import cn.qishui.tool.ui.components.EmptyState
import cn.qishui.tool.ui.components.PlainCard

/**
 * 音源管理区。
 *
 * 只管渲染，不自己弹文件选择器（那要在有 ActivityResult 宿主的 Composable 里发起），
 * 所以 [onPickFile] 由 [PlayerSourceSettingsScreen] 传进来。
 *
 * 这里用普通 Column 而不是 LazyColumn：外层 [SettingsSection] 已经是
 * verticalScroll 的 Column，再套 LazyColumn 会因为无限高度约束直接崩。
 * 音源数量是用户手动导入的，量级很小，不需要惰性布局。
 */
@Composable
internal fun MusicSourceSectionBody(
    viewModel: MusicSourceViewModel,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var urlText by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ImportCard(
            urlText = urlText,
            onUrlChange = { urlText = it },
            busy = busy,
            onImportUrl = { viewModel.importFromUrl(urlText) },
            onPickFile = onPickFile,
        )

        EngineStatusCard(engine = engine)

        if (sources.isEmpty()) {
            Text(
                text = "还没有导入音源。用上面的链接或文件导入 lx 自定义源脚本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            sources.forEach { source ->
                MusicSourceRow(
                    source = source,
                    onCheckedChange = { viewModel.setSelected(source, it) },
                    onDelete = { viewModel.delete(source) },
                )
            }
        }
    }
}

@Composable
private fun ImportCard(
    urlText: String,
    onUrlChange: (String) -> Unit,
    busy: Boolean,
    onImportUrl: () -> Unit,
    onPickFile: () -> Unit,
) {
    PlainCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("导入音源", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "支持 lx 自定义源脚本。可以导入多个，但同时只有一个生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = urlText,
                onValueChange = onUrlChange,
                label = { Text("脚本链接") },
                placeholder = { Text("https://…/source.js") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onImportUrl,
                    enabled = !busy && urlText.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("从链接导入")
                }
                OutlinedButton(
                    onClick = onPickFile,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("从文件导入")
                }
            }
        }
    }
}

@Composable
private fun EngineStatusCard(engine: LxSourceState) {
    val (title, body, tint) = when (engine) {
        is LxSourceState.Idle -> Triple(
            "未启用音源",
            "勾选下面任意一个音源来启用。",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is LxSourceState.Loading -> Triple(
            "正在加载…",
            "正在启动脚本引擎。",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is LxSourceState.Failed -> Triple(
            "音源加载失败",
            engine.message,
            MaterialTheme.colorScheme.error,
        )
        is LxSourceState.Ready -> {
            val info = engine.info
            val platforms = info.platforms.joinToString("、") {
                LxSourceInfo.PLATFORM_LABELS[it] ?: it
            }
            Triple(
                "音源已就绪",
                "可用平台：${platforms.ifBlank { "无" }}\n" +
                    "可提供：${info.actions.values.flatten().distinct().joinToString("、").ifBlank { "无" }}",
                MaterialTheme.colorScheme.primary,
            )
        }
    }
    PlainCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MusicSourceRow(
    source: MusicSource,
    onCheckedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    PlainCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 复选框就是「用哪个音源」的唯一操作面
            Checkbox(
                checked = source.selected,
                onCheckedChange = onCheckedChange,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = source.name.ifBlank { "未命名音源" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(source.origin)
                        if (source.version.isNotBlank()) append(" · v${source.version}")
                        if (source.author.isNotBlank()) append(" · ${source.author}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!source.description.isNullOrBlank()) {
                    Text(
                        text = source.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                source.lastError?.let { error ->
                    Text(
                        text = "加载失败：$error",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = "删除",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
