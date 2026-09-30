package cn.qishui.tool.domain.model

enum class FontScale(val factor: Float) {
    SMALL(0.9f),
    STANDARD(1f),
    LARGE(1.15f),
    EXTRA_LARGE(1.3f),
    ;

    companion object {
        fun fromNameOrNull(value: String?): FontScale? = entries.firstOrNull { it.name == value }
    }
}

data class AppSettings(
    val themeName: String = DEFAULT_THEME_NAME,
    val fontScale: FontScale = FontScale.STANDARD,
    val wallpaperUri: String? = null,
    val wallpaperAlpha: Float = 1f,
    val wallpaperBlurDp: Int = 0,
    val cardAlpha: Float = 0.62f,
    val cardBlurDp: Int = 0,
    val downloadDirectoryUri: String? = null,
    val downloadConnections: Int = 3,
    val autoNamePattern: String = DEFAULT_AUTO_NAME_PATTERN,
    /** 播放器音质档位，存 lx 音源协议的 type 值。本地文件播放时用不到。 */
    val playbackQuality: String = DEFAULT_PLAYBACK_QUALITY,
) {
    fun sanitized(): AppSettings = copy(
        themeName = themeName.trim().takeIf(String::isNotEmpty) ?: DEFAULT_THEME_NAME,
        wallpaperUri = wallpaperUri?.trim()?.takeIf(String::isNotEmpty),
        wallpaperAlpha = wallpaperAlpha.finiteOrDefault(DEFAULT_WALLPAPER_ALPHA)
            .coerceIn(MIN_WALLPAPER_ALPHA, MAX_WALLPAPER_ALPHA),
        wallpaperBlurDp = wallpaperBlurDp.coerceIn(0, MAX_WALLPAPER_BLUR_DP),
        cardAlpha = cardAlpha.finiteOrDefault(DEFAULT_CARD_ALPHA)
            .coerceIn(MIN_CARD_ALPHA, MAX_CARD_ALPHA),
        cardBlurDp = cardBlurDp.coerceIn(0, MAX_CARD_BLUR_DP),
        downloadDirectoryUri = downloadDirectoryUri?.trim()?.takeIf(String::isNotEmpty),
        downloadConnections = downloadConnections.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS),
        autoNamePattern = autoNamePattern.trim().takeIf(String::isNotEmpty) ?: DEFAULT_AUTO_NAME_PATTERN,
    )
}

private fun Float.finiteOrDefault(fallback: Float): Float = if (isFinite()) this else fallback

const val DEFAULT_THEME_NAME = "元气橙"
const val DEFAULT_AUTO_NAME_PATTERN = "{歌名}-{歌手}"
const val DEFAULT_PLAYBACK_QUALITY = "320k"
const val DEFAULT_WALLPAPER_ALPHA = 1f
const val DEFAULT_CARD_ALPHA = 0.62f
const val MIN_WALLPAPER_ALPHA = 0f
const val MAX_WALLPAPER_ALPHA = 1f
const val MAX_WALLPAPER_BLUR_DP = 24
const val MIN_CARD_ALPHA = 0.3f
const val MAX_CARD_ALPHA = 1f
const val MAX_CARD_BLUR_DP = 16
const val MIN_CONNECTIONS = 1
const val MAX_CONNECTIONS = 4
