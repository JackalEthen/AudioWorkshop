package cn.qishui.tool.feature.video

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.qishui.tool.media.video.VideoEditEngine
import cn.qishui.tool.ui.components.InfoHintAction
import cn.qishui.tool.ui.components.InfoHintBox
import cn.qishui.tool.ui.components.LucideIcon
import cn.qishui.tool.ui.components.ParamCard
import cn.qishui.tool.ui.components.ParamRow
import cn.qishui.tool.ui.components.PlainCard
import cn.qishui.tool.ui.components.PrimaryButton
import cn.qishui.tool.ui.components.QishuiTopBar
import cn.qishui.tool.ui.components.ScreenScroll
import cn.qishui.tool.ui.components.SecondaryButton
import cn.qishui.tool.ui.components.SelectBox
import cn.qishui.tool.ui.components.TopSnackbarHost
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VideoTool { TRIM, JOIN, SPEED }

@Composable
fun VideoEditScreen(
    tool: VideoTool,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoEditViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var hintVisible by rememberSaveable { mutableStateOf(false) }

    val title = when (tool) {
        VideoTool.TRIM -> "视频裁剪"
        VideoTool.JOIN -> "视频拼接"
        VideoTool.SPEED -> "视频变速"
    }
    val hint = videoToolHint(tool)

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.addVideo(context, it) } }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { uri -> uri?.let { viewModel.run(context, tool, it) } }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
        snackbarHost = { TopSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            QishuiTopBar(
                title = title,
                onBack = onBack,
                actionContent = {
                    InfoHintAction(
                        hint = hint,
                        expanded = hintVisible,
                        onToggle = { hintVisible = !hintVisible },
                    )
                },
            )
            ScreenScroll {
                InfoHintBox(hint = hint, visible = hintVisible, onDismiss = { hintVisible = false })

                SectionHeaderCompat(
                    title = if (tool == VideoTool.JOIN) "选择视频（可多选，按顺序拼接）" else "选择视频",
                    action = "导入",
                    onAction = { picker.launch(arrayOf("video/*")) },
                )
                if (state.videos.isEmpty()) {
                    PlainCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "还没有视频，点右上角「导入」选择本地视频。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    state.videos.forEachIndexed { index, video ->
                        PlainCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.removeVideo(video.uri) },
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (tool == VideoTool.JOIN) {
                                    SelectBox(
                                        selected = true,
                                        description = "第 ${index + 1} 段 ${video.name}",
                                        onToggle = { viewModel.removeVideo(video.uri) },
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${index + 1}. ${video.name}",
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "点一下移除这段",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                if (tool == VideoTool.TRIM) {
                    ParamCard(title = "裁剪范围设置", onReset = viewModel::reset) {
                        ParamRow(
                            label = "起始秒",
                            valueText = "${state.startSec}",
                            value = state.startSec,
                            range = 0f..state.maxSec,
                            onChange = { viewModel.setStartSec(it) },
                        )
                        ParamRow(
                            label = "结束秒",
                            valueText = "${state.endSec}",
                            value = state.endSec,
                            range = 0f..state.maxSec,
                            onChange = { viewModel.setEndSec(it) },
                        )
                    }
                }

                if (tool == VideoTool.SPEED) {
                    ParamCard(title = "速度设置", onReset = viewModel::reset) {
                        ParamRow(
                            label = "速度",
                            valueText = "${state.speed}x",
                            value = state.speed,
                            range = 0.25f..4f,
                            onChange = { viewModel.setSpeed(it) },
                        )
                    }
                }

                SectionHeaderCompat(title = "导出", action = null, onAction = {})
                PlainCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.running) {
                            LinearProgressIndicator(
                                progress = { state.progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "处理中 ${state.progress}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PrimaryButton(
                            text = if (state.running) "处理中" else "开始处理",
                            onClick = { exportPicker.launch("$title.mp4") },
                            enabled = state.canRun && !state.running,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (state.running) {
                            SecondaryButton(
                                text = "取消",
                                onClick = viewModel::cancel,
                                modifier = Modifier.fillMaxWidth(),
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SectionHeaderCompat(title: String, action: String?, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

private fun videoToolHint(tool: VideoTool): String = when (tool) {
    VideoTool.TRIM ->
        "裁剪会重新编码视频，所以是「导出」而不是「剪切粘贴」，画质取决于编码参数。\n" +
            "起止时间按秒设置，结束秒要大于起始秒。\n" +
            "视频越长越慢，1 分钟 1080P 大概要几十秒。"

    VideoTool.JOIN ->
        "拼接按下面列表的顺序首尾相接。\n" +
            "如果所有视频的编码参数（分辨率、帧率、编码格式）完全一致，Media3 可以直接封装，速度很快；" +
            "参数不一致时会自动重编码成统一参数，耗时明显变长。\n" +
            "建议先用同样参数录制的视频拼。"

    VideoTool.SPEED ->
        "变速同时改画面速度和音频速度，并用 SonicBoom 算法保持音调不变，" +
            "所以 2 倍速听起来只是变快，不会变成花栗鼠。\n" +
            "速度和时间是反比关系：2x 出来的一半长。\n" +
            "0.25x~4x 都能用，慢放会明显增加文件体积。"
}

class VideoEditViewModel : ViewModel() {

    data class PickedVideo(val uri: String, val name: String, val file: File)

    data class UiState(
        val videos: List<PickedVideo> = emptyList(),
        val startSec: Float = 0f,
        val endSec: Float = 0f,
        val maxSec: Float = 60f,
        val speed: Float = 2f,
        val running: Boolean = false,
        val progress: Int = 0,
        val message: String? = null,
        val tool: VideoTool = VideoTool.TRIM,
    ) {
        val canRun: Boolean
            get() = when (tool) {
                VideoTool.JOIN -> videos.size >= 2
                else -> videos.size >= 1 && endSec > startSec
            }
    }

    private val engine = VideoEditEngineHolder.instance

    private val mutableUiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = mutableUiState.asStateFlow()

    private var job: Job? = null

    fun addVideo(context: android.content.Context, uri: Uri) {
        val name = queryName(context, uri)
        val cache = File(context.cacheDir, "video_src").apply { mkdirs() }
        val target = File(cache, "${System.currentTimeMillis()}_$name")
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (!ok) {
                    mutableUiState.value = mutableUiState.value.copy(message = "读取视频失败")
                    return@withContext
                }
                val current = mutableUiState.value
                val next = current.videos + PickedVideo(uri.toString(), name, target)
                mutableUiState.value = current.copy(
                    videos = next,
                    endSec = if (current.endSec == 0f) target.length().toFloat() else current.endSec,
                )
            }
        }
    }

    fun removeVideo(uri: String) {
        val current = mutableUiState.value
        mutableUiState.value = current.copy(videos = current.videos.filterNot { it.uri == uri })
    }

    fun setStartSec(value: Float) {
        val current = mutableUiState.value
        mutableUiState.value = current.copy(startSec = value.coerceIn(0f, current.maxSec))
    }

    fun setEndSec(value: Float) {
        val current = mutableUiState.value
        mutableUiState.value = current.copy(endSec = value.coerceIn(0f, current.maxSec))
    }

    fun setSpeed(value: Float) {
        mutableUiState.value = mutableUiState.value.copy(speed = value)
    }

    fun reset() {
        val current = mutableUiState.value
        mutableUiState.value = current.copy(startSec = 0f, endSec = 0f, speed = 2f)
    }

    fun run(context: android.content.Context, tool: VideoTool, targetUri: Uri) {
        val current = mutableUiState.value
        if (!current.canRun) return
        val engine = engine ?: run {
            mutableUiState.value = current.copy(message = "视频引擎未初始化")
            return
        }
        mutableUiState.value = current.copy(tool = tool, running = true, progress = 0)
        val outDir = File(context.cacheDir, "video_out").apply { mkdirs() }
        val out = File(outDir, "out_${System.currentTimeMillis()}.mp4")
        job = viewModelScope.launch {
            val result = when (tool) {
                VideoTool.TRIM -> engine.trim(
                    current.videos.first().file,
                    (current.startSec * 1000).toLong(),
                    (current.endSec * 1000).toLong(),
                    out,
                ) { p -> mutableUiState.value = mutableUiState.value.copy(progress = p) }

                VideoTool.SPEED -> engine.speed(
                    current.videos.first().file,
                    current.speed,
                    out,
                ) { p -> mutableUiState.value = mutableUiState.value.copy(progress = p) }

                VideoTool.JOIN -> engine.join(
                    current.videos.map { it.file },
                    out,
                ) { p -> mutableUiState.value = mutableUiState.value.copy(progress = p) }
            }
            val message = result.fold(
                onSuccess = { file ->
                    val copied = runCatching {
                        context.contentResolver.openOutputStream(targetUri)?.use { output ->
                            file.inputStream().use { input -> input.copyTo(output) }
                        }
                        true
                    }.getOrDefault(false)
                    if (copied) "导出完成：已写入所选位置" else "处理完成，但写入所选位置失败"
                },
                onFailure = { it.message ?: "视频处理失败" },
            )
            mutableUiState.value = mutableUiState.value.copy(
                running = false,
                progress = 0,
                message = message,
            )
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        mutableUiState.value = mutableUiState.value.copy(running = false, progress = 0, message = "已取消")
    }

    fun consumeMessage() {
        mutableUiState.value = mutableUiState.value.copy(message = null)
    }

    private fun queryName(context: android.content.Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return name ?: "video_${System.currentTimeMillis()}.mp4"
    }
}

object VideoEditEngineHolder {
    var instance: VideoEditEngine? = null
}
