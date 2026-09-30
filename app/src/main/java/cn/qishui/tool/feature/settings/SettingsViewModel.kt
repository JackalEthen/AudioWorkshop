package cn.qishui.tool.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cn.qishui.tool.domain.SettingsRepository
import cn.qishui.tool.domain.model.AppSettings
import cn.qishui.tool.domain.model.FontScale
import cn.qishui.tool.domain.naming.AutoNamer
import cn.qishui.tool.domain.naming.NamingInput
import cn.qishui.tool.domain.naming.NamingResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NamingPreview(
    val baseName: String,
    val error: String? = null,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings

    private val mutablePreview = MutableStateFlow(NamingPreview(baseName = ""))
    val namingPreview: StateFlow<NamingPreview> = mutablePreview.asStateFlow()

    init {
        refreshPreview(settingsRepository.settings.value.autoNamePattern)
    }

    fun setTheme(themeName: String) = settingsRepository.updateTheme(themeName)

    fun setFont(fontScale: FontScale) = settingsRepository.updateFont(fontScale)

    fun setWallpaper(uri: String) = settingsRepository.updateWallpaper(uri)

    fun clearWallpaper() = settingsRepository.clearWallpaper()

    fun setWallpaperAlpha(alpha: Float) = settingsRepository.updateWallpaperAlpha(alpha)

    fun setWallpaperBlur(blurDp: Int) = settingsRepository.updateWallpaperBlur(blurDp)

    fun setCardAlpha(alpha: Float) = settingsRepository.updateCardAlpha(alpha)

    fun setCardBlur(blurDp: Int) = settingsRepository.updateCardBlur(blurDp)

    fun setDownloadDirectory(treeUri: String?) = settingsRepository.updateDownloadDirectory(treeUri)

    fun setConnections(count: Int) = settingsRepository.updateConnections(count)

    fun setAutoNamePattern(pattern: String) {
        settingsRepository.updateAutoNamePattern(pattern)
        refreshPreview(pattern)
    }

    fun setPlaybackQuality(quality: String) = settingsRepository.updatePlaybackQuality(quality)

    fun resetAppearance() = settingsRepository.resetAppearance()

    private fun refreshPreview(pattern: String) {
        when (val result = AutoNamer.render(pattern, PreviewInput)) {
            is NamingResult.Success -> mutablePreview.value = NamingPreview(baseName = result.baseName)
            is NamingResult.Failure -> mutablePreview.value = NamingPreview(baseName = "", error = result.message)
        }
    }

    companion object {
        private val PreviewInput = NamingInput(
            title = "示例歌名",
            artist = "示例歌手",
            album = "示例专辑",
            date = "20260101",
        )

        fun factory(settingsRepository: SettingsRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (!modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                        throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
                    }
                    @Suppress("UNCHECKED_CAST")
                    return SettingsViewModel(settingsRepository) as T
                }
            }
    }
}
