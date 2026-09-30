package cn.qishui.tool.feature.video

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.qishui.tool.data.encoder.EncoderClient
import cn.qishui.tool.domain.media.ExportFormat
import cn.qishui.tool.domain.media.ExportJob
import cn.qishui.tool.domain.media.ExportResult
import cn.qishui.tool.domain.media.ExportSegment
import cn.qishui.tool.domain.media.ExportSource
import cn.qishui.tool.feature.edit.export.ExportTargetWriter
import cn.qishui.tool.media.video.VideoTools
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 提取音轨时的两种模式。 */
enum class ExtractMode(val label: String) {
    /** 音轨什么样就导什么样，采样率和声道数都跟源视频。 */
    NORMAL("正常模式"),

    /** 统一到 44100Hz 立体声并把峰值拉到 -1dBFS，避免源视频参数奇怪导致成品没法用。 */
    STABLE("稳定模式"),
}

/**
 * 视频提取音频工作台。
 *
 * 选视频 → 看预览 → 选模式和格式 → 保存到指定位置。
 * 提取出来的音轨先写成 WAV 中间件，再交给现有导出链路编码，
 * 所以格式、采样率、声道都和别的导出保持一致。
 */
class VideoExtractAudioViewModel(
    private val videoTools: VideoTools,
    private val encoderClient: EncoderClient,
    private val exportTargetWriter: ExportTargetWriter,
    private val exportTempDirectory: File,
) : ViewModel() {

    data class UiState(
        val uri: String? = null,
        val name: String? = null,
        /** 缓存到应用目录的视频副本，提取和预览都读它。 */
        val file: File? = null,
        val durationMs: Long = 0L,
        val mode: ExtractMode = ExtractMode.STABLE,
        val format: ExportFormat = ExportFormat.WAV,
        val isWorking: Boolean = false,
        val message: String? = null,
    ) {
        val hasVideo: Boolean get() = file != null
    }

    private val mutableUiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = mutableUiState.asStateFlow()

    private var intermediate: File? = null

    fun pick(context: Context, uri: Uri) {
        val name = displayName(context, uri) ?: "视频"
        // 换视频时把上一份缓存删掉，否则 cache/video-src 会一直涨
        mutableUiState.value.file?.delete()
        viewModelScope.launch {
            mutableUiState.update { it.copy(isWorking = true, message = null) }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val directory = File(context.cacheDir, "video-src").apply { mkdirs() }
                    val target = File(directory, "${System.currentTimeMillis()}_$name")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法读取所选视频")
                    target to videoTools.durationMsOf(target)
                }
            }
            outcome.fold(
                onSuccess = { (file, durationMs) ->
                    mutableUiState.update {
                        it.copy(
                            uri = uri.toString(),
                            name = name,
                            file = file,
                            durationMs = durationMs,
                            isWorking = false,
                            message = null,
                        )
                    }
                },
                onFailure = { error ->
                    mutableUiState.update {
                        it.copy(isWorking = false, message = "读取视频失败：${error.message ?: "未知错误"}")
                    }
                },
            )
        }
    }

    fun setMode(mode: ExtractMode) = mutableUiState.update { it.copy(mode = mode) }

    fun setFormat(format: ExportFormat) = mutableUiState.update { it.copy(format = format) }

    fun consumeMessage() = mutableUiState.update { it.copy(message = null) }

    /** 提取音轨 → 编码成选定格式 → 写入用户选的位置。 */
    fun save(context: Context, targetUri: String) {
        val current = mutableUiState.value
        val source = current.file
        if (current.isWorking) return
        if (source == null || !source.isFile) {
            notify("先选一个视频")
            return
        }
        viewModelScope.launch {
            mutableUiState.update { it.copy(isWorking = true, message = null) }
            intermediate?.delete()
            val extracted = withContext(Dispatchers.IO) {
                // 文件名带时间戳：VideoTools 写的是固定路径，两次并发保存会互相覆盖
                runCatching { videoTools.extractAudio(source, "extract-${System.currentTimeMillis()}") }.getOrNull()
            }
            val wav = extracted?.getOrNull()
            if (wav == null) {
                mutableUiState.update {
                    it.copy(
                        isWorking = false,
                        message = "提取音轨失败：${extracted?.exceptionOrNull()?.message ?: "未知错误"}",
                    )
                }
                return@launch
            }
            intermediate = wav
            val job = withContext(Dispatchers.IO) { buildJob(wav, current) }
            if (job == null) {
                mutableUiState.update { it.copy(isWorking = false, message = "无法读取音轨时长") }
                return@launch
            }
            encoderClient.export(job) { result -> onResult(result, targetUri) }
        }
    }

    private fun buildJob(wav: File, current: UiState): ExportJob? {
        if (!exportTempDirectory.exists()) exportTempDirectory.mkdirs()
        val durationUs = videoTools.durationMsOf(wav) * 1000L
        if (durationUs <= 0L) return null
        val stable = current.mode == ExtractMode.STABLE
        return ExportJob(
            jobId = UUID.randomUUID().toString(),
            editProjectId = "video-extract",
            outputTempPath = File(
                exportTempDirectory,
                "${UUID.randomUUID()}.${current.format.extension}",
            ).path,
            format = current.format,
            sources = listOf(
                ExportSource(
                    id = "video-audio",
                    localPath = wav.absolutePath,
                    title = current.name?.substringBeforeLast('.')?.takeIf(String::isNotBlank),
                    artist = null,
                    album = null,
                    lyrics = null,
                    segments = listOf(ExportSegment(0L, durationUs)),
                ),
            ),
            gainDb = 0f,
            fadeInMs = 0L,
            fadeOutMs = 0L,
            lyricOffsetMs = 0L,
            bitrateKbps = 0,
            // 稳定模式统一参数；正常模式全部交给源文件
            sampleRateHz = if (stable) STABLE_RATE else 0,
            channelMode = if (stable) STABLE_CHANNELS else 0,
        )
    }

    private fun onResult(result: ExportResult, targetUri: String) {
        when (result) {
            is ExportResult.Failed -> finish("提取失败：${result.reason}")
            is ExportResult.Completed -> viewModelScope.launch {
                val copied = runCatching {
                    exportTargetWriter.copy(result.outputPath, Uri.parse(targetUri))
                }
                copied.fold(
                    onSuccess = { finish("已保存 ${it / 1024} KB") },
                    onFailure = { finish("写入所选位置失败：${it.message ?: "未知错误"}") },
                )
            }
        }
    }

    private fun finish(message: String) {
        intermediate?.delete()
        intermediate = null
        mutableUiState.update { it.copy(isWorking = false, message = message) }
    }

    private fun notify(message: String) = mutableUiState.update { it.copy(message = message) }

    override fun onCleared() {
        super.onCleared()
        intermediate?.delete()
        mutableUiState.value.file?.delete()
    }

    companion object {
        const val STABLE_RATE = 44_100
        const val STABLE_CHANNELS = 2

        fun factory(
            videoTools: VideoTools,
            encoderClient: EncoderClient,
            exportTargetWriter: ExportTargetWriter,
            exportTempDirectory: File,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(VideoExtractAudioViewModel::class.java)) {
                    throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
                }
                return VideoExtractAudioViewModel(
                    videoTools = videoTools,
                    encoderClient = encoderClient,
                    exportTargetWriter = exportTargetWriter,
                    exportTempDirectory = exportTempDirectory,
                ) as T
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}.getOrNull()
