package cn.qishui.tool.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import cn.qishui.tool.domain.SettingsRepository
import cn.qishui.tool.domain.model.AppSettings
import cn.qishui.tool.domain.model.DEFAULT_CARD_ALPHA
import cn.qishui.tool.domain.model.DEFAULT_WALLPAPER_ALPHA
import cn.qishui.tool.domain.model.FontScale
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : SettingsRepository {
    private val state = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
                .collect { preferences -> state.value = read(preferences) }
        }
    }

    override fun updateTheme(themeName: String) = write(Keys.theme, themeName.trim())

    override fun updateFont(fontScale: FontScale) = write(Keys.font, fontScale.name)

    override fun updateWallpaper(uri: String) = write(Keys.wallpaper, uri.trim())

    override fun clearWallpaper() = remove(Keys.wallpaper)

    override fun updateWallpaperAlpha(alpha: Float) = write(Keys.wallpaperAlpha, alpha)

    override fun updateWallpaperBlur(blurDp: Int) = write(Keys.wallpaperBlur, blurDp)

    override fun updateCardAlpha(alpha: Float) = write(Keys.cardAlpha, alpha)

    override fun updateCardBlur(blurDp: Int) = write(Keys.cardBlur, blurDp)

    override fun updateDownloadDirectory(treeUri: String?) {
        val value = treeUri?.trim()
        if (value.isNullOrEmpty()) remove(Keys.downloadDirectory) else write(Keys.downloadDirectory, value)
    }

    override fun updateConnections(count: Int) = write(Keys.connections, count)

    override fun updateAutoNamePattern(pattern: String) = write(Keys.autoNamePattern, pattern)

    override fun resetAppearance() {
        scope.launch {
            dataStore.edit { preferences ->
                Keys.appearanceKeys.forEach { key -> preferences.remove(key) }
            }
        }
    }

    private fun <T> write(key: Preferences.Key<T>, value: T) {
        scope.launch { dataStore.edit { preferences -> preferences[key] = value } }
    }

    private fun remove(key: Preferences.Key<*>) {
        scope.launch { dataStore.edit { preferences -> preferences.remove(key) } }
    }

    private fun read(preferences: Preferences): AppSettings = AppSettings(
        themeName = preferences[Keys.theme].orEmpty(),
        fontScale = FontScale.fromNameOrNull(preferences[Keys.font]) ?: FontScale.STANDARD,
        wallpaperUri = preferences[Keys.wallpaper],
        wallpaperAlpha = preferences[Keys.wallpaperAlpha] ?: DEFAULT_WALLPAPER_ALPHA,
        wallpaperBlurDp = preferences[Keys.wallpaperBlur] ?: 0,
        cardAlpha = preferences[Keys.cardAlpha] ?: DEFAULT_CARD_ALPHA,
        cardBlurDp = preferences[Keys.cardBlur] ?: 0,
        downloadDirectoryUri = preferences[Keys.downloadDirectory],
        downloadConnections = preferences[Keys.connections] ?: 3,
        autoNamePattern = preferences[Keys.autoNamePattern].orEmpty(),
    ).sanitized()

    private object Keys {
        val theme = stringPreferencesKey("theme_name")
        val font = stringPreferencesKey("font_scale")
        val wallpaper = stringPreferencesKey("wallpaper_uri")
        val wallpaperAlpha = floatPreferencesKey("wallpaper_alpha")
        val wallpaperBlur = intPreferencesKey("wallpaper_blur_dp")
        val cardAlpha = floatPreferencesKey("card_alpha")
        val cardBlur = intPreferencesKey("card_blur_dp")
        val downloadDirectory = stringPreferencesKey("download_directory_uri")
        val connections = intPreferencesKey("download_connections")
        val autoNamePattern = stringPreferencesKey("auto_name_pattern")
        val appearanceKeys = listOf(
            theme,
            font,
            wallpaper,
            wallpaperAlpha,
            wallpaperBlur,
            cardAlpha,
            cardBlur,
        )
    }
}

object SettingsDataStore {
    fun create(context: Context, scope: CoroutineScope): SettingsRepository {
        val applicationContext = context.applicationContext
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { applicationContext.preferencesDataStoreFile("qishui_settings") },
        )
        return DataStoreSettingsRepository(dataStore, scope)
    }
}
