package cn.music.audioworkshop.feature.videoaudio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.music.audioworkshop.data.encoder.EncoderClient
import cn.music.audioworkshop.data.encoder.EncoderState
import cn.music.audioworkshop.data.media.AudioFileProbe
import cn.music.audioworkshop.domain.AudioPlayer
import cn.music.audioworkshop.domain.PlaybackSnapshot
import cn.music.audioworkshop.domain.media.ExportFormat
import cn.music.audioworkshop.domain.media.ExportJob
import cn.music.audioworkshop.domain.media.ExportResult
import cn.music.audioworkshop.domain.media.ExportSegment
import cn.music.audioworkshop.domain.media.ExportSource
import cn.music.audioworkshop.domain.model.FadeCurve
import cn.music.audioworkshop.feature.edit.export.ExportEvent
import cn.music.audioworkshop.feature.edit.export.ExportPublisher
import cn.music.audioworkshop.feature.edit.export.ExportUiState
import cn.music.audioworkshop.feature.edit.export.reduceExport
import cn.music.audioworkshop.media.pcm.EditPreviewRenderer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 文件名里不能出现的字符，替换成下划线。 */
private val InvalidFileName = Regex("""[<>:"/\\|?*\u0000-\u001F]""")


/** 从视频里抽出来的音轨信息，供卡片显示。 */
data class VideoAudioInfo(
    /** 视频文件名。 */
    val fileName: String = "",
    val videoPath: String = "",
    val durationUs: Long = 0L,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val hasVideoTrack: Boolean = false,
    val hasAudioTrack: Boolean = false,
) {
    /** 1080p 这类写法，比「1920×1080」省地方也更好读。 */
    val resolutionLabel: String
        get() = when {
            widthPx <= 0 || heightPx <= 0 -> "未知分辨率"
            heightPx >= 2160 -> "4K"
            heightPx >= 1440 -> "2K"
            heightPx >= 1080 -> "1080P"
            heightPx >= 720 -> "720P"
            heightPx >= 480 -> "480P"
            else -> "${widthPx}×${heightPx}"
        }
}

data class VideoAudioUiState(
    val video: VideoAudioInfo = VideoAudioInfo(),
    /** 目标格式。MP3 有损体积小，WAV 无损体积大。 */
    val format: ExportFormat = ExportFormat.MP3,
    val isWorking: Boolean = false,
    val message: String? = null,
    val publishedLocation: String? = null,
) {
    val hasVideo: Boolean
        get() = video.videoPath.isNotBlank()

    val canExport: Boolean
        get() = hasVideo && video.hasAudioTrack && !isWorking
}

/**
 * 视频提取音频。
 *
 * 不单独解码再编码来做试听：直接把视频文件交给导出引擎 ——
 * [cn.music.audioworkshop.media.pcm.PcmChunkReader] 用 MediaExtractor 取音轨，
 * 一步到位，也没有二次转码的损失。
 */
class VideoAudioViewModel(
    private val context: android.content.Context,
    private val probe: AudioFileProbe,
    private val audioPlayer: AudioPlayer,
    private val previewRenderer: EditPreviewRenderer,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
    private val importDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(VideoAudioUiState())
    val uiState: StateFlow<VideoAudioUiState> = mutableState.asStateFlow()

    private val mutableExportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = mutableExportState.asStateFlow()

    val playback: StateFlow<PlaybackSnapshot> = audioPlayer.snapshot

    init {
        viewModelScope.launch {
            encoderClient.state.collect { state ->
                if (state !is EncoderState.InProgress) return@collect
                if (!mutableExportState.value.isRunning) return@collect
                mutableExportState.update { reduceExport(it, ExportEvent.Progressed(state.progress)) }
            }
        }
    }

    /**
     * 导入视频。
     *
     * 视频容器（mp4/mov 等）不在 [AudioFileProbe] 的音频容器白名单里，
     * 所以这里自己复制并探测轨道，不用音频导入那条路 ——
     * 否则视频会被当成「不支持的格式」挡掉。
     */
    fun importVideo(uri: String, displayName: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(isWorking = true, message = null) }
            val copied = withContext(Dispatchers.IO) { copyToImportDir(uri, displayName) }
            if (copied == null) {
                mutableState.update { it.copy(isWorking = false) }
                notify("视频复制失败，请重新选择")
                return@launch
            }
            val info = withContext(Dispatchers.IO) { probeTracks(copied, displayName) }
            audioPlayer.pause()
            previewRenderer.invalidateCache()
            mutableState.update {
                it.copy(video = info, isWorking = false, message = null)
            }
            when {
                !info.hasVideoTrack -> notify("这个文件里没有视频轨，只有音频")
                !info.hasAudioTrack -> notify("这个视频没有音轨，导不出音频")
            }
        }
    }

    private fun copyToImportDir(uri: String, displayName: String): File? = runCatching {
        if (!importDirectory.exists()) importDirectory.mkdirs()
        val safe = displayName.substringBeforeLast('.', displayName)
            .replace(InvalidFileName, "_")
            .trim()
            .ifBlank { "video" }
            .take(60)
        val target = File(importDirectory, "$safe-${System.currentTimeMillis()}.mp4")
        val source = android.net.Uri.parse(uri)
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
if (target.length() <= 0L) {
            target.delete()
            return null
        }
        target
    }.getOrNull()


    private fun probeTracks(file: File, displayName: String): VideoAudioInfo {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val hasVideo = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            VideoAudioInfo(
                fileName = displayName.ifBlank { file.name },
                videoPath = file.absolutePath,
                durationUs = durationMs * 1000L,
                widthPx = width,
                heightPx = height,
                hasVideoTrack = hasVideo,
                hasAudioTrack = hasAudio,
            )
        } catch (error: Exception) {
            VideoAudioInfo(fileName = displayName, videoPath = file.absolutePath)
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun setFormat(format: ExportFormat) = mutableState.update { it.copy(format = format) }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    /** 试听：直接播视频里的音轨，ExoPlayer 能读容器里的音频轨。 */
    fun togglePlay() {
        val path = mutableState.value.video.videoPath
        if (path.isBlank()) return
        if (playback.value.isPlaying) {
            audioPlayer.pause()
            return
        }
        if (playback.value.mediaId != path) audioPlayer.loadFile(path)
        audioPlayer.play()
    }

    fun seekTo(ms: Long) = audioPlayer.seekTo(ms)

    // ---- 导出 ----

    fun suggestedFileName(): String? {
        val current = mutableState.value
        if (!current.hasVideo) return null
        val base = current.video.fileName.substringBeforeLast('.', current.video.fileName).ifBlank { "视频" }
        return "$base.${current.format.extension}"
    }

    fun export(confirmedFileName: String? = null) {
        val current = mutableState.value
        if (!current.canExport) {
            if (!current.hasVideo) notify("请先导入视频") else if (!current.video.hasAudioTrack) notify("这个视频没有音轨")
            return
        }
        if (!mutableExportState.value.isIdle) return
        viewModelScope.launch {
            val fileName = confirmedFileName?.takeIf { it.isNotBlank() }
                ?: suggestedFileName()
                ?: "视频音频.${current.format.extension}"
            val jobId = "videoaudio-${UUID.randomUUID()}"
            mutableState.update { it.copy(isWorking = true, message = null) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(jobId, fileName)) }
            val job = ExportJob(
                jobId = jobId,
                editProjectId = "video_audio",
                outputTempPath = File(
                    exportTempDirectory,
                    "${UUID.randomUUID()}.${current.format.extension}",
                ).absolutePath,
                // 整段音轨：segments 就是源文件从头到尾。
                // 引擎用 MediaExtractor 取音轨，视频容器对它来说和音频文件没区别。
                sources = listOf(
                    ExportSource(
                        id = "video:${current.video.videoPath}",
                        localPath = current.video.videoPath,
                        title = current.video.fileName.substringBeforeLast('.', ""),
                        artist = null,
                        album = null,
                        lyrics = null,
                        segments = listOf(ExportSegment(0L, current.video.durationUs)),
                    ),
                ),
                gainDb = 0f,
                fadeInMs = 0L,
                fadeOutMs = 0L,
                fadeCurve = FadeCurve.LINEAR,
                lyricOffsetMs = 0L,
                format = current.format,
            )
            encoderClient.export(job) { result -> onExportResult(fileName, result) }
        }
    }

    fun cancelExport() {
        mutableExportState.value.jobId?.let(encoderClient::cancel)
    }

    private fun onExportResult(fileName: String, result: ExportResult) {
        when (result) {
            is ExportResult.Failed ->
                mutableExportState.update { reduceExport(it, ExportEvent.Failed(result.reason)) }

            is ExportResult.Completed -> viewModelScope.launch {
                mutableState.update { it.copy(isWorking = false) }
                runCatching { exportPublisher.publish(result.outputPath, fileName) }.fold(
                    onSuccess = { published ->
                        mutableExportState.update { reduceExport(it, ExportEvent.Succeeded(published.bytes)) }
                        mutableState.update { it.copy(publishedLocation = published.location) }
                    },
                    onFailure = { error ->
                        mutableState.update { it.copy(publishedLocation = null) }
                        mutableExportState.update {
                            reduceExport(it, ExportEvent.Failed("保存到下载目录失败：${error.message}"))
                        }
                    },
                )
            }
        }
    }

    private fun notify(message: String) = mutableState.update { it.copy(message = message) }

    override fun onCleared() {
        super.onCleared()
        // 只能暂停，不能 release：audioPlayer 是 AppContainer 里的单例，全应用共用
        audioPlayer.pause()
    }

    companion object {
        fun factory(
            context: android.content.Context,
            probe: AudioFileProbe,
            audioPlayer: AudioPlayer,
            previewRenderer: EditPreviewRenderer,
            encoderClient: EncoderClient,
            exportPublisher: ExportPublisher,
            exportTempDirectory: File,
            importDirectory: File,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(VideoAudioViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return VideoAudioViewModel(
                    context = context,
                    probe = probe,
                    audioPlayer = audioPlayer,
                    previewRenderer = previewRenderer,
                    encoderClient = encoderClient,
                    exportPublisher = exportPublisher,
                    exportTempDirectory = exportTempDirectory,
                    importDirectory = importDirectory,
                ) as T
            }
        }
    }
}
