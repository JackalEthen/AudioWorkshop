package cn.qishui.tool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qishui.tool.ui.QishuiApp
import cn.qishui.tool.ui.theme.QishuiTheme
import cn.qishui.tool.ui.theme.ThemeCatalog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as QishuiApplication).container
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            val spec = ThemeCatalog.themes.firstOrNull { it.name == settings.themeName }
                ?: ThemeCatalog.themes.first()
            QishuiTheme(themeSpec = spec, fontScale = settings.fontScale) {
                QishuiApp(container = container, settings = settings)
            }
        }
    }
}
