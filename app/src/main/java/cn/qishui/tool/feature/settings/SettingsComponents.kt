package cn.qishui.tool.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import cn.qishui.tool.R
import cn.qishui.tool.ui.components.LucideIcon
import cn.qishui.tool.ui.components.QishuiFieldShape
import cn.qishui.tool.ui.components.QishuiTopBar
import cn.qishui.tool.ui.theme.ThemeCatalog

@Composable
internal fun SettingsSection(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        QishuiTopBar(title = title, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

/** example1 风格：卡片内的小标题 = 圆角图标块 + 粗体标题 + 可选右侧数值 + 分隔线。 */
@Composable
internal fun SettingSectionHeader(
    glyph: SettingGlyph,
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(QishuiFieldShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(icon = glyph.icon, tint = MaterialTheme.colorScheme.onPrimaryContainer, size = 16.dp)
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

internal enum class SettingGlyph(val icon: Int) {
    Palette(R.drawable.ic_palette),
    Type(R.drawable.ic_type),
    Phone(R.drawable.ic_smartphone),
    Download(R.drawable.ic_download),
    Grid(R.drawable.ic_grid_2x2),
    Info(R.drawable.ic_info),
    Audio(R.drawable.ic_headphones),
}

internal object ThemeCatalogLookup {
    fun nameOf(themeName: String?): String = ThemeCatalog.themes
        .firstOrNull { it.name == themeName }
        ?.name
        ?: ThemeCatalog.themes.first().name
}
