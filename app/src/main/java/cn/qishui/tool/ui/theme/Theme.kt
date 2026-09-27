package cn.qishui.tool.ui.theme

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
import cn.qishui.tool.domain.model.FontScale

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
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = QishuiTypography,
            content = content,
        )
    }
}
