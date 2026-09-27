package cn.qishui.tool.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qishui.tool.R
import cn.qishui.tool.domain.model.FontScale
import cn.qishui.tool.domain.model.MAX_CARD_ALPHA
import cn.qishui.tool.domain.model.MAX_CARD_BLUR_DP
import cn.qishui.tool.domain.model.MAX_WALLPAPER_ALPHA
import cn.qishui.tool.domain.model.MAX_WALLPAPER_BLUR_DP
import cn.qishui.tool.domain.model.MIN_CARD_ALPHA
import cn.qishui.tool.domain.model.MIN_WALLPAPER_ALPHA
import cn.qishui.tool.ui.components.LucideIcon
import cn.qishui.tool.ui.components.PlainCard
import cn.qishui.tool.ui.components.QishuiFieldShape
import cn.qishui.tool.ui.components.rememberWallpaperBitmap
import cn.qishui.tool.ui.theme.ThemeCatalog
import kotlin.math.roundToInt

@Composable
fun AppearanceScreen(
    viewModel: SettingsViewModel,
    onPickWallpaper: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSection(
        modifier = modifier,
        title = "外观设置",
        onBack = onBack,
    ) {
        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingSectionHeader(glyph = SettingGlyph.Palette, title = "主体配色")
                ThemeGrid(
                    selected = settings.themeName,
                    onSelect = viewModel::setTheme,
                )
            }
        }

        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SettingSectionHeader(glyph = SettingGlyph.Type, title = "字体大小")
                ScaleSlider(
                    value = FontScale.entries.indexOf(settings.fontScale).toFloat(),
                    range = 0f..(FontScale.entries.size - 1).toFloat(),
                    onValueChange = { viewModel.setFont(FontScale.entries[it.roundToInt()]) },
                )
            }
        }

        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingSectionHeader(glyph = SettingGlyph.Phone, title = "主界面外观")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "主界面壁纸",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = !settings.wallpaperUri.isNullOrBlank(),
                        onCheckedChange = { if (it) onPickWallpaper() else viewModel.clearWallpaper() },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                    )
                }
                WallpaperPicker(
                    hasWallpaper = !settings.wallpaperUri.isNullOrBlank(),
                    wallpaperUri = settings.wallpaperUri,
                    onPick = onPickWallpaper,
                    onClear = viewModel::clearWallpaper,
                )
                LabelledSlider(
                    label = "壁纸透明度",
                    valueText = percent(settings.wallpaperAlpha),
                    value = settings.wallpaperAlpha,
                    range = MIN_WALLPAPER_ALPHA..MAX_WALLPAPER_ALPHA,
                    onValueChange = viewModel::setWallpaperAlpha,
                )
                LabelledSlider(
                    label = "壁纸模糊度",
                    valueText = "${settings.wallpaperBlurDp}",
                    value = settings.wallpaperBlurDp.toFloat(),
                    range = 0f..MAX_WALLPAPER_BLUR_DP.toFloat(),
                    onValueChange = { viewModel.setWallpaperBlur(it.roundToInt()) },
                )
                LabelledSlider(
                    label = "卡片透明度",
                    valueText = percent(settings.cardAlpha),
                    value = settings.cardAlpha,
                    range = MIN_CARD_ALPHA..MAX_CARD_ALPHA,
                    onValueChange = viewModel::setCardAlpha,
                )
                LabelledSlider(
                    label = "卡片模糊度",
                    valueText = "${settings.cardBlurDp}",
                    value = settings.cardBlurDp.toFloat(),
                    range = 0f..MAX_CARD_BLUR_DP.toFloat(),
                    onValueChange = { viewModel.setCardBlur(it.roundToInt()) },
                )
            }
        }
    }
}

@Composable
private fun ThemeGrid(selected: String?, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ThemeCatalog.themes.forEach { theme ->
            ThemeSwatch(
                color = theme.primary,
                selected = theme.name == selected,
                onClick = { onSelect(theme.name) },
            )
        }
    }
}

@Composable
private fun RowScope.ThemeSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            LucideIcon(
                icon = R.drawable.ic_check,
                tint = Color.White,
                size = 20.dp,
            )
        }
    }
}

@Composable
private fun WallpaperPicker(
    hasWallpaper: Boolean,
    wallpaperUri: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val density = LocalDensity.current
    val preview = rememberWallpaperBitmap(
        uri = wallpaperUri,
        targetWidthPx = with(density) { 240.dp.roundToPx() },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 128.dp)
                .clip(QishuiFieldShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hasWallpaper) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .clip(QishuiFieldShape)
                    .clickable(onClick = onClear)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "已设置壁纸",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "点此恢复默认",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** example1 的滑杆：左「更小」右「更大」，数值在右上角。 */
@Composable
private fun ScaleSlider(value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = (range.endInclusive - range.start).toInt() - 1,
            colors = accentSliderColors(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("更小", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(modifier = Modifier.weight(1f))
            Text("更大", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = 9,
            colors = accentSliderColors(),
        )
    }
}

@Composable
private fun accentSliderColors() = SliderDefaults.colors(
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTickColor = Color.Transparent,
    inactiveTickColor = MaterialTheme.colorScheme.outline,
)

internal fun percent(value: Float): String = "${(value.coerceIn(0f, 1f) * 100).roundToInt()}%"

internal fun FontScale.label(): String = when (this) {
    FontScale.SMALL -> "小"
    FontScale.STANDARD -> "标准"
    FontScale.LARGE -> "大"
    FontScale.EXTRA_LARGE -> "特大"
}
