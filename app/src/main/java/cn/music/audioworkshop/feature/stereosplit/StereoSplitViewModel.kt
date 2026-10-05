package cn.music.audioworkshop.feature.stereosplit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.music.audioworkshop.data.encoder.EncoderClient
import cn.music.audioworkshop.data.encoder.EncoderState
import cn.music.audioworkshop.domain.AudioPlayer
import cn.music.audioworkshop.domain.PlaybackSnapshot
import cn.music.audioworkshop.domain.SourceTrackRepository
import cn.music.audioworkshop.domain.media.ExportFormat
import cn.music.audioworkshop.domain.media.ExportJob
import cn.music.audioworkshop.domain.media.ExportResult
import cn.music.audioworkshop.domain.media.ExportProgress
import cn.music.audioworkshop.domain.media.ExportSegment
import cn.music.audioworkshop.domain.media.ExportStage
import cn.music.audioworkshop.domain.media.ExportSource
import cn.music.audioworkshop.domain.model.EditTimeSegment
import cn.music.audioworkshop.domain.model.FadeCurve
import cn.music.audioworkshop.feature.edit.export.ExportEvent
import cn.music.audioworkshop.feature.edit.export.ExportPublisher
import cn.music.audioworkshop.feature.edit.export.ExportUiState
import cn.music.audioworkshop.feature.edit.export.reduceExport
import cn.music.audioworkshop.media.pcm.EditPreviewRenderer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 拆出来的声道。 */
enum class SplitSide(val label: String, val suffix: String, val channelIndex: Int) {
    LEFT("左声道", "-左声道", 0),
    RIGHT("右声道", "-右声道", 1),
}

data class StereoSplitUiState(
    val fileName: String = "",
    val sourcePath: String = "",
    val durationUs: Long = 0L,
    /** 拆分是否已完成。没拆完不给导出。 */
    val isSplit: Boolean = false,
    val isWorking: Boolean = false,
    val message: String? = null,
    val publishedLeft: String? = null,
    val publishedRight: String? = null,
) {
    val hasTrack: Boolean
        get() = sourcePath.isNotBlank()

    /** 底栏两态：导入后「开始分离」，拆完「导出」。 */
    val canSplit: Boolean
        get() = hasTrack && !isWorking && !isSplit

    val canExport: Boolean
        get() = isSplit && !isWorking
}

/**
 * 立体声分离：把一首立体声拆成左、右两个声道文件。
 *
 * 拆一次、两个预览、导出两个文件 —— 所以预览和导出都各自跑两遍引擎。
 * 导出串行跑而不是并发：两个 job 抢同一个 codec 会互相打断。
 */
class StereoSplitViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val audioPlayer: AudioPlayer,
    private val previewRenderer: EditPreviewRenderer,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(StereoSplitUiState())
    val uiState: StateFlow<StereoSplitUiState> = mutableState.asStateFlow()

    private val mutableExportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = mutableExportState.asStateFlow()

    val playback: StateFlow<PlaybackSnapshot> = audioPlayer.snapshot

    private var leftPreviewFile: File? = null
    private var rightPreviewFile: File? = null
    private var splitJob: Job? = null

    init {
        viewModelScope.launch {
            encoderClient.state.collect { state ->
                if (state !is EncoderState.InProgress) return@collect
                if (!mutableExportState.value.isRunning) return@collect
                mutableExportState.update { reduceExport(it, ExportEvent.Progressed(state.progress)) }
            }
        }
    }

    fun import(uri: String, displayName: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(isWorking = true, message = null) }
            sourceTrackRepository.importLocalAudioEphemeral(uri).fold(
                onSuccess = { track ->
                    clearPreviewFiles()
                    splitJob?.cancel()
                    previewRenderer.invalidateCache()
                    audioPlayer.pause()
                    mutableState.update {
                        it.copy(
                            fileName = track.title?.takeIf(String::isNotBlank) ?: displayName,
                            sourcePath = track.localPath.orEmpty(),
                            durationUs = (track.durationMs ?: 0L) * 1000L,
                            isSplit = false,
                            publishedLeft = null,
                            publishedRight = null,
                            isWorking = false,
                        )
                    }
                },
                onFailure = { error ->
                    mutableState.update { it.copy(isWorking = false) }
                    mutableState.update { it.copy(message = "导入失败：${error.message}") }
                },
            )
        }
    }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    /** 拆一次，渲染出左右两个预览文件。 */
    fun split() {
        val current = mutableState.value
        // 底栏按钮未导入时也可点（保持常态色），所以这里必须给提示而不是静默返回
        if (!current.canSplit) {
            mutableState.update { it.copy(message = "请先导入音频") }
            return
        }
        splitJob?.cancel()
        splitJob = viewModelScope.launch {
            mutableState.update { it.copy(isWorking = true, message = null) }
            val path = current.sourcePath
            val segments = listOf(EditTimeSegment(0L, current.durationUs, 0L, current.durationUs))
            // 一个声道一个声道地渲染。渲染器是全文件单进程，跑两次而不是并发。
            val left = previewRenderer.render(
                sourcePath = path,
                segments = segments,
                gainDb = 0f,
                fadeInUs = 0L,
                fadeOutUs = 0L,
                fadeCurve = FadeCurve.LINEAR,
                extractChannel = SplitSide.LEFT.channelIndex,
            ).getOrElse { return@launch failSplit(it) }
            val right = previewRenderer.render(
                sourcePath = path,
                segments = segments,
                gainDb = 0f,
                fadeInUs = 0L,
                fadeOutUs = 0L,
                fadeCurve = FadeCurve.LINEAR,
                extractChannel = SplitSide.RIGHT.channelIndex,
            ).getOrElse { return@launch failSplit(it) }
            clearPreviewFiles()
            leftPreviewFile = left
            rightPreviewFile = right
            audioPlayer.pause()
            mutableState.update { it.copy(isSplit = true, isWorking = false) }
        }
    }

    private fun failSplit(error: Throwable) {
        mutableState.update {
            it.copy(isWorking = false, message = "分离失败：${error.message}")
        }
    }

    fun togglePlay(side: SplitSide) {
        val file = when (side) {
            SplitSide.LEFT -> leftPreviewFile
            SplitSide.RIGHT -> rightPreviewFile
        } ?: return
        if (playback.value.isPlaying && playback.value.mediaId == file.absolutePath) {
            audioPlayer.pause()
            return
        }
        if (playback.value.mediaId != file.absolutePath) audioPlayer.loadFile(file.absolutePath)
        audioPlayer.play()
    }

    fun seekTo(ms: Long) = audioPlayer.seekTo(ms)

    fun isPreviewing(side: SplitSide): Boolean {
        val file = when (side) {
            SplitSide.LEFT -> leftPreviewFile
            SplitSide.RIGHT -> rightPreviewFile
        }
        return playback.value.isPlaying && file != null && playback.value.mediaId == file.absolutePath
    }

    fun mediaIdOf(side: SplitSide): String = when (side) {
        SplitSide.LEFT -> leftPreviewFile?.absolutePath.orEmpty()
        SplitSide.RIGHT -> rightPreviewFile?.absolutePath.orEmpty()
    }

    // ---- 导出：一次任务产出两个文件 ----

    fun suggestedBaseName(): String? {
        val current = mutableState.value
        if (!current.isSplit) return null
        return current.fileName.substringBeforeLast('.', current.fileName).ifBlank { "音频" }
    }

    fun export(confirmedFileName: String? = null) {
        val current = mutableState.value
        if (!current.canExport) return
        if (!mutableExportState.value.isIdle) return
        viewModelScope.launch {
            // 重命名弹窗给的是基名（不含扩展名），两个声道各加自己的后缀。
            val base = stripExtension(
                confirmedFileName?.takeIf { it.isNotBlank() } ?: suggestedBaseName() ?: "音频",
            )
            mutableState.update { it.copy(isWorking = true) }
            var exported = 0
            var failure: String? = null
            var left: String? = null
            var right: String? = null
            for ((index, side) in SplitSide.entries.withIndex()) {
                val jobId = "stereosplit-${side.name.lowercase()}-${UUID.randomUUID()}"
                val fileName = "$base${side.suffix}.${ExportFormat.MP3.extension}"
                mutableExportState.update { reduceExport(it, ExportEvent.Started(jobId, fileName)) }
                // 串行：EncoderClient 同时只允许一个 job，两个并发会被直接拒掉。
                when (val result = exportAndAwait(buildExportJob(jobId, side, current))) {
                    is ExportResult.Failed -> {
                        failure = result.reason
                        break
                    }
                    is ExportResult.Completed -> {
                        runCatching { exportPublisher.publish(result.outputPath, fileName) }.fold(
                            onSuccess = { published ->
                                if (side == SplitSide.LEFT) left = published.location else right = published.location
                                exported++
                            },
                            onFailure = { error -> failure = "保存失败：${error.message}" },
                        )
                    }
                }
                if (index < SplitSide.entries.lastIndex) {
                    mutableExportState.update {
                        reduceExport(
                            it,
                            ExportEvent.Progressed(
                                ExportProgress(
                                    jobId = jobId,
                                    stage = ExportStage.PREPARING,
                                    fraction = (index + 1) * 0.5f,
                                ),
                            ),
                        )
                    }
                }
            }
            mutableState.update {
                it.copy(isWorking = false, publishedLeft = left, publishedRight = right)
            }
mutableExportState.update {
                val reason = failure
                if (reason != null) {
                    reduceExport(it, ExportEvent.Failed(reason))
                } else {
                    reduceExport(it, ExportEvent.Succeeded(0L))
                }
            }
            if (failure == null) notify("已导出 $exported 个声道文件")
        }
    }

    /** [EncoderClient.export] 只有回调版，这里包一层好串行 await。 */
    private suspend fun exportAndAwait(job: ExportJob): ExportResult =
        suspendCancellableCoroutine { continuation ->
            encoderClient.export(job) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }

    /** 去掉用户可能带上的扩展名。 */
    private fun stripExtension(name: String): String {
        val known = listOf(".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".opus")
        return known.firstOrNull { name.endsWith(it, ignoreCase = true) }
            ?.let { name.dropLast(it.length) }
            ?.takeIf { it.isNotBlank() }
            ?: name
    }

    private fun buildExportJob(
        jobId: String,
        side: SplitSide,
        current: StereoSplitUiState,
    ): ExportJob = ExportJob(
        jobId = jobId,
        editProjectId = "stereo_split_${side.name.lowercase()}",
        outputTempPath = File(
            exportTempDirectory,
            "${UUID.randomUUID()}.${ExportFormat.MP3.extension}",
        ).absolutePath,
        sources = listOf(
            ExportSource(
                id = "split:${current.sourcePath}",
                localPath = current.sourcePath,
                title = current.fileName.substringBeforeLast('.', ""),
                artist = null,
                album = null,
                lyrics = null,
                segments = listOf(ExportSegment(0L, current.durationUs)),
            ),
        ),
        gainDb = 0f,
        fadeInMs = 0L,
        fadeOutMs = 0L,
        fadeCurve = FadeCurve.LINEAR,
        lyricOffsetMs = 0L,
        format = ExportFormat.MP3,
        extractChannel = side.channelIndex,
    )

    fun cancelExport() {
        mutableExportState.value.jobId?.let(encoderClient::cancel)
    }

    private fun clearPreviewFiles() {
        leftPreviewFile?.delete()
        rightPreviewFile?.delete()
        leftPreviewFile = null
        rightPreviewFile = null
    }

    private fun notify(message: String) = mutableState.update { it.copy(message = message) }

    override fun onCleared() {
        super.onCleared()
        splitJob?.cancel()
        clearPreviewFiles()
        // 只能暂停，不能 release：audioPlayer 是 AppContainer 里的单例，全应用共用
        audioPlayer.pause()
    }

    companion object {
        fun factory(
            sourceTrackRepository: SourceTrackRepository,
            audioPlayer: AudioPlayer,
            previewRenderer: EditPreviewRenderer,
            encoderClient: EncoderClient,
            exportPublisher: ExportPublisher,
            exportTempDirectory: File,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(StereoSplitViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return StereoSplitViewModel(
                    sourceTrackRepository = sourceTrackRepository,
                    audioPlayer = audioPlayer,
                    previewRenderer = previewRenderer,
                    encoderClient = encoderClient,
                    exportPublisher = exportPublisher,
                    exportTempDirectory = exportTempDirectory,
                ) as T
            }
        }
    }
}

