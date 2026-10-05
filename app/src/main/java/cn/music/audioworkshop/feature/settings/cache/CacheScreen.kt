package cn.music.audioworkshop.feature.settings.cache

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.domain.cache.CacheCategory
import cn.music.audioworkshop.feature.settings.SettingGlyph
import cn.music.audioworkshop.feature.settings.SettingSectionHeader
import cn.music.audioworkshop.feature.settings.SettingsSection
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.PrimaryButton
import cn.music.audioworkshop.ui.components.SecondaryButton

private const val HINT = "缓存为自动生成的中间文件，清理不影响已下载的歌曲。" +
    "解析记录与设置项不占用缓存空间，不在清理范围内。"

/** 缓存清理页。逐类清理，不做「一键全清」以免误删。 */
@Composable
fun CacheScreen(
    viewModel: CacheViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    SettingsSection(
        modifier = modifier,
        title = "清理缓存",
        onBack = onBack,
    ) {
        Text(
            text = HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        SettingSectionHeader(glyph = SettingGlyph.Trash, title = "分类", trailing = formatBytes(state.totalBytes))

        CacheCategory.entries.forEach { category ->
            CacheRow(
                label = category.label,
                caption = category.caption,
                sizeText = formatBytes(state.sizeOf(category)),
                enabled = state.canClean(exporting),
                onClean = { viewModel.clean(category) },
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "合计 ${formatBytes(state.totalBytes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton(
            text = "清理全部",
            onClick = { viewModel.cleanAll() },
            enabled = state.canClean(exporting) && state.totalBytes > 0L,
            modifier = Modifier.fillMaxWidth(),
        )
        if (exporting) {
            Text(
                text = "导出进行中，清理会导致导出失败",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** 一行缓存分类：名称 + 说明 + 大小 + 清理按钮。 */
@Composable
private fun CacheRow(
    label: String,
    caption: String,
    sizeText: String,
    enabled: Boolean,
    onClean: () -> Unit,
) {
    FloatingCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = sizeText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            SecondaryButton(
                text = "清理",
                onClick = onClean,
                enabled = enabled,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
            )
        }
    }
}
