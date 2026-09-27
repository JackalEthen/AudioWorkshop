package cn.qishui.tool.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import cn.qishui.tool.domain.model.FontScale

val LocalFontScale = staticCompositionLocalOf { FontScale.STANDARD }

val LocalCardStyle = staticCompositionLocalOf { CardStyle(alpha = 0.92f, blurDp = 0) }

val LocalWallpaperBitmap = staticCompositionLocalOf<ImageBitmap?> { null }

data class CardStyle(
    val alpha: Float,
    val blurDp: Int,
)
