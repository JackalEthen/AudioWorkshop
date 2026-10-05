package cn.music.audioworkshop.domain

import cn.music.audioworkshop.domain.model.AppSettings
import cn.music.audioworkshop.domain.model.FontScale
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun updateTheme(themeName: String)
    fun updateFont(fontScale: FontScale)
    fun updateWallpaper(uri: String)
    fun clearWallpaper()
    fun updateWallpaperAlpha(alpha: Float)
    fun updateWallpaperBlur(blurDp: Int)
    fun updateCardAlpha(alpha: Float)
    fun updateCardBlur(blurDp: Int)
    fun updateDownloadDirectory(treeUri: String?)
    fun updateConnections(count: Int)
    fun updateAutoNamePattern(pattern: String)

    /** 播放器音质档位，取值见 [cn.music.audioworkshop.domain.player.PlaybackQuality]。 */
    fun updatePlaybackQuality(quality: String)
    fun resetAppearance()
}
