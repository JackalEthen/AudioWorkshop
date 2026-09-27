package cn.qishui.tool.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qishui.tool.R
import cn.qishui.tool.ui.components.FloatingCard
import cn.qishui.tool.ui.components.LucideIcon
import cn.qishui.tool.ui.components.QishuiFieldShape
import cn.qishui.tool.ui.components.QishuiTopBar
import cn.qishui.tool.ui.components.ScreenScroll

object SettingsRoute {
    const val Home = "settings"
    const val Appearance = "settings/appearance"
    const val Download = "settings/download"
    const val Naming = "settings/naming"
    const val About = "settings/about"
}

@Composable
fun SettingsHomeScreen(
    viewModel: SettingsViewModel,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            QishuiTopBar(
                title = "设置",
                actionText = "重置",
                onAction = viewModel::resetAppearance,
            )
            ScreenScroll {
                listOf(
                    Entry("外观", SettingsRoute.Appearance, SettingGlyph.Palette),
                    Entry("下载", SettingsRoute.Download, SettingGlyph.Download),
                    Entry("其他", SettingsRoute.Naming, SettingGlyph.Grid),
                    Entry("关于", SettingsRoute.About, SettingGlyph.Info),
                ).forEach { entry ->
                    SettingsEntryCard(
                        title = entry.title,
                        glyph = entry.glyph,
                        onClick = { onOpen(entry.route) },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

private data class Entry(
    val title: String,
    val route: String,
    val glyph: SettingGlyph,
)

@Composable
private fun SettingsEntryCard(
    title: String,
    glyph: SettingGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingCard(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(QishuiFieldShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(
                    icon = glyph.icon,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    size = 18.dp,
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
internal fun RowScope.ThemeSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .weight(1f)
            .height(64.dp)
            .clip(QishuiFieldShape)
            .background(color)
            .clickable(onClick = onClick),
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(10.dp)
                    .clip(QishuiFieldShape)
                    .background(Color.White),
            )
        }
    }
}
