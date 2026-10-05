package cn.music.audioworkshop.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSettingsValidationTest {

    @Test
    fun defaultsMatchTheApprovedAppearance() {
        val defaults = AppSettings()
        assertEquals("晴空蓝", defaults.themeName)
        assertEquals(FontScale.STANDARD, defaults.fontScale)
        assertEquals("{歌名}-{歌手}", defaults.autoNamePattern)
        assertEquals(1f, defaults.wallpaperAlpha, 0f)
        assertEquals(0, defaults.wallpaperBlurDp)
        assertEquals(0.62f, defaults.cardAlpha, 0.0001f)
        assertEquals(0, defaults.cardBlurDp)
        assertEquals(3, defaults.downloadConnections)
        assertNull(defaults.wallpaperUri)
        assertNull(defaults.downloadDirectoryUri)
    }

    @Test
    fun clampsWallpaperAlphaIntoUnitRange() {
        assertEquals(0f, AppSettings(wallpaperAlpha = -3f).sanitized().wallpaperAlpha, 0.0001f)
        assertEquals(1f, AppSettings(wallpaperAlpha = 7f).sanitized().wallpaperAlpha, 0.0001f)
        assertEquals(0.4f, AppSettings(wallpaperAlpha = 0.4f).sanitized().wallpaperAlpha, 0.0001f)
    }

    @Test
    fun clampsCardAlphaIntoItsNarrowerRange() {
        assertEquals(0.3f, AppSettings(cardAlpha = 0.05f).sanitized().cardAlpha, 0.0001f)
        assertEquals(1f, AppSettings(cardAlpha = 1.5f).sanitized().cardAlpha, 0.0001f)
    }

    @Test
    fun clampsBlurRadii() {
        val sanitized = AppSettings(wallpaperBlurDp = 99, cardBlurDp = -4).sanitized()
        assertEquals(24, sanitized.wallpaperBlurDp)
        assertEquals(0, sanitized.cardBlurDp)
    }

    @Test
    fun clampsConnectionCount() {
        assertEquals(1, AppSettings(downloadConnections = 0).sanitized().downloadConnections)
        assertEquals(4, AppSettings(downloadConnections = 12).sanitized().downloadConnections)
    }

    @Test
    fun ignoresNonFiniteAlpha() {
        assertEquals(0.62f, AppSettings(cardAlpha = Float.NaN).sanitized().cardAlpha, 0.0001f)
        assertEquals(1f, AppSettings(wallpaperAlpha = Float.POSITIVE_INFINITY).sanitized().wallpaperAlpha, 0.0001f)
    }

    @Test
    fun restoresDefaultsForBlankTextFields() {
        val sanitized = AppSettings(themeName = "   ", autoNamePattern = " ").sanitized()
        assertEquals("晴空蓝", sanitized.themeName)
        assertEquals("{歌名}-{歌手}", sanitized.autoNamePattern)
    }

    @Test
    fun treatsBlankUrisAsAbsent() {
        val sanitized = AppSettings(wallpaperUri = "  ", downloadDirectoryUri = " ").sanitized()
        assertNull(sanitized.wallpaperUri)
        assertNull(sanitized.downloadDirectoryUri)
    }

    @Test
    fun fontScaleFactorsAreOrdered() {
        assertEquals(
            listOf(0.9f, 1f, 1.15f, 1.3f),
            FontScale.entries.map { it.factor },
        )
    }

    @Test
    fun fontScaleParsesUnknownNameAsNull() {
        assertNull(FontScale.fromNameOrNull("HUGE"))
        assertEquals(FontScale.LARGE, FontScale.fromNameOrNull("LARGE"))
    }
}
