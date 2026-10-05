package cn.music.audioworkshop.feature.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.music.audioworkshop.ui.components.PlainCard
import cn.music.audioworkshop.ui.components.SettingRow

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val packageInfo = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName}（code $code）"
        }.getOrNull() ?: "未知"
    }
    SettingsSection(
        modifier = modifier,
        title = "关于",
        onBack = onBack,
    ) {
        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingRow(label = "版本", value = packageInfo)
                SettingRow(label = "包名", value = context.packageName)
                SettingRow(label = "应用名称", value = context.getString(cn.music.audioworkshop.R.string.app_name))
            }
        }
        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingRow(label = "系统版本", value = Build.VERSION.RELEASE ?: "未知")
                SettingRow(label = "API 级别", value = Build.VERSION.SDK_INT.toString())
                SettingRow(label = "设备", value = "${Build.MANUFACTURER} ${Build.MODEL}")
            }
        }
        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("开源许可证", style = MaterialTheme.typography.titleMedium)
                listOf(
                    "MP3 编码：LAME（LGPL 2.0）",
                    "界面：Jetpack Compose / Material 3（Apache 2.0）",
                    "应用基础：AndroidX Core KTX / Activity Compose（Apache 2.0）",
                    "生命周期：AndroidX Lifecycle（Apache 2.0）",
                    "导航：AndroidX Navigation Compose（Apache 2.0）",
                    "数据库：AndroidX Room（Apache 2.0）",
                    "播放与导出：AndroidX Media3 ExoPlayer / Transformer / Session（Apache 2.0）",
                    "设置存储：AndroidX DataStore（Apache 2.0）",
                    "文档访问：AndroidX DocumentFile（Apache 2.0）",
                    "图片加载：Coil（Apache 2.0）",
                    "网络：OkHttp（Apache 2.0）",
                    "脚本引擎：QuickJS（wang.harlon.quickjs wrapper，MIT）",
                    "JSON 解析：org.json（Public Domain）",
                    "测试：JUnit 4（Eclipse Public License 1.0）",
                ).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}