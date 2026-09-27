package cn.qishui.tool.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCatalogTest {
    @Test
    fun containsFiveApprovedThemesInOrder() {
        assertEquals(
            listOf("元气橙", "晴空蓝", "薄荷青", "星云紫", "桃桃粉"),
            ThemeCatalog.themes.map { it.name },
        )
    }

    @Test
    fun primaryColorsMatchApprovedOklchConversions() {
        assertEquals(
            listOf(
                0xFFDD5C00.toInt(),
                0xFF0086DD.toInt(),
                0xFF009A7B.toInt(),
                0xFF8F5DD6.toInt(),
                0xFFD14693.toInt(),
            ),
            ThemeCatalog.themes.map { it.primary.toArgb() },
        )
    }

    @Test
    fun accentColorsMatchApprovedOklchConversions() {
        assertEquals(
            listOf(
                0xFF24ABE8.toInt(),
                0xFFE76C2B.toInt(),
                0xFFA672DC.toInt(),
                0xFF00ABA4.toInt(),
                0xFFDE7515.toInt(),
            ),
            ThemeCatalog.themes.map { it.accent.toArgb() },
        )
    }

    @Test
    fun textPairsMeetTheirContrastTargets() {
        ThemeCatalog.themes.forEach { theme ->
            assertTrue(contrastRatio(theme.onSurface, theme.background) >= 7.0)
            assertTrue(contrastRatio(theme.onPrimaryContainer, theme.primaryContainer) >= 4.5)
            assertTrue(contrastRatio(theme.onAccent, theme.accent) >= 4.5)
            assertTrue(contrastRatio(theme.onPrimary, theme.primary) >= 4.5)
        }
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val foregroundLuminance = relativeLuminance(foreground)
        val backgroundLuminance = relativeLuminance(background)
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) {
                value / 12.92
            } else {
                Math.pow((value + 0.055) / 1.055, 2.4)
            }
        }

        val argb = color.toArgb()
        val red = linear((argb shr 16) and 0xFF)
        val green = linear((argb shr 8) and 0xFF)
        val blue = linear(argb and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }
}
