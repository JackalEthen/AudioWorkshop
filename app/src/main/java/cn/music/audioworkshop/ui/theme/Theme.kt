package cn.music.audioworkshop.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import cn.music.audioworkshop.domain.model.FontScale

private val PureWhite = Color(0xFFFFFFFF)

fun AppThemeSpec.toColorScheme(): ColorScheme {
    val muted = lerp(onSurface, PureWhite, 0.34f)
    val soft = lerp(surface, onSurface, 0.07f)
    return lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = primaryContainer,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = primaryContainer,
        onSecondaryContainer = onPrimaryContainer,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = primaryContainer,
        onTertiaryContainer = onPrimaryContainer,
        background = background,
        onBackground = onSurface,
        surface = background,
        onSurface = onSurface,
        surfaceVariant = soft,
        onSurfaceVariant = muted,
        surfaceContainerLowest = PureWhite,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = soft,
        surfaceContainerHighest = lerp(surface, onSurface, 0.12f),
        surfaceTint = primary,
        inverseSurface = onSurface,
        inverseOnSurface = background,
        outline = outline,
        outlineVariant = lerp(outline, onSurface, 0.18f),
        error = Color(0xFFB3261E),
        onError = PureWhite,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410E0B),
        scrim = Color.Black,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QishuiTheme(
    themeSpec: AppThemeSpec,
    fontScale: FontScale,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scaledDensity = remember(density, fontScale) {
        Density(density = density.density, fontScale = density.fontScale * fontScale.factor)
    }
    val colorScheme = remember(themeSpec) { themeSpec.toColorScheme() }
    CompositionLocalProvider(
        LocalFontScale provides fontScale,
        LocalDensity provides scaledDensity,
        // 全应用禁用按压/悬停涟漪。
        //
        // 这个项目里「hover 态」指的是按下时那圈扩散的水波纹。逐个组件关要改 165+ 处
        // （clickable 52、Button 100、开关 12），漏一处就前功尽弃，而在主题层关一次
        // 全部生效，以后新加的按钮也自动继承这个规则。
        //
        // 只关视觉反馈，不禁用交互 —— 选中态颜色、禁用态透明度都照旧，
        // 滑杆那种自绘的拖动反馈也不受影响（它不走 indication）。
        LocalRippleConfiguration provides null,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = QishuiTypography,
            content = content,
        )
    }
}


