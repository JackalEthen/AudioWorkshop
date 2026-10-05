package cn.music.audioworkshop.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.domain.player.PlaybackQuality
import cn.music.audioworkshop.feature.source.MusicSourceViewModel
import cn.music.audioworkshop.ui.components.CheckRow
import cn.music.audioworkshop.ui.components.PlainCard
import cn.music.audioworkshop.ui.components.TopSnackbarHost

/**
 * 播放器音源设置。
 *
 * 音质档位 + lx 音源的导入与管理都在这一页。
 * 音源列表可以有很多个，但同一时刻只有一个生效（每行前面的复选框）。
 */
@Composable
fun PlayerSourceSettingsScreen(
    viewModel: SettingsViewModel,
    sourceViewModel: MusicSourceViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val current = PlaybackQuality.entries.firstOrNull { it.lxType == settings.playbackQuality }
        ?: PlaybackQuality.DEFAULT
    val snackbarHostState = remember { SnackbarHostState() }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri) ?: "音源脚本.js"
        sourceViewModel.importFromFile(uri, name)
    }

    // 不要再套 Box/Column。SettingsSection 自带顶栏和滚动容器，
    // 外面再套一层会让整页半透明叠加，点击落到被压住的下层控件上。
    SettingsSection(
        modifier = modifier,
        title = "播放器音源",
        onBack = onBack,
    ) {
        PlainCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("音质档位", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "播放 lx 音源时向音源脚本请求的音质。本地文件不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlaybackQuality.entries.forEach { quality ->
                    CheckRow(
                        label = quality.label,
                        checked = quality == current,
                        onCheckedChange = { checked ->
                            if (checked) viewModel.setPlaybackQuality(quality.lxType)
                        },
                    )
                }
            }
        }

        MusicSourceSectionBody(
            viewModel = sourceViewModel,
            onPickFile = { filePicker.launch(arrayOf("text/javascript", "*/*")) },
        )
    }

    // snackbar 要浮在最上层，单独一层 Box
    Box(modifier = Modifier.fillMaxSize()) {
        TopSnackbarHost(snackbarHostState)
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver
        .query(uri, null, null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}.getOrNull()
