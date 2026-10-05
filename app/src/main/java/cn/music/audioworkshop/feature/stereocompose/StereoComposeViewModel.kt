package cn.music.audioworkshop.feature.stereocompose

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 合成需要的两路输入。 */
enum class ComposeSide(val label: String) {
    LEFT("左声道"),
    RIGHT("右声道"),
}

data class StereoComposeUiState(
    val leftName: String = "",
    val leftPath: String = "",
    val rightName: String = "",
    val rightPath: String = "",
    /** 取两路里较长的那个作为合成时长。 */
    val durationUs: Long = 0L,
    val isComposed: Boolean = false,
    val isWorking: Boolean = false,
    val message: String? = null,
    val publishedLocation: String? = null,
) {
    val hasBoth: Boolean
        get() = leftPath.isNotBlank() && rightPath.isNotBlank()

    val canCompose: Boolean
        get() = hasBoth && !isWorking && !isComposed

    val canExport: Boolean
        get() = isComposed && !isWorking
}

/**
 * 立体声合成：把左、右两个文件合成一首立体声。
 *
 * 和立体声分离正好相反：那边一个输入两个输出，这边两个输入一个输出。
 */
class StereoComposeViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val audioPlayer: AudioPlayer,
    private val previewRenderer: EditPreviewRenderer,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(StereoComposeUiState())
    val uiState: StateFlow<StereoComposeUiState> = mutableState.asStateFlow()

    private val mutableExportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = mutableExportState.asStateFlow()

    val playback: StateFlow<PlaybackSnapshot> = audioPlayer.snapshot

    private var composedPreviewFile: File? = null
    private var composeJob: Job? = null

    init {
        viewModelScope.launch {
            encoderClient.state.collect { state ->
                if (state !is EncoderState.InProgress) return@collect
                if (!mutableExportState.value.isRunning) return@collect
                mutableExportState.update { reduceExport(it, ExportEvent.Progressed(state.progress)) }
            }
        }
    }

    fun import(side: ComposeSide, uri: String, displayName: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(isWorking = true, message = null) }
            sourceTrackRepository.importLocalAudioEphemeral(uri).fold(
                onSuccess = { track ->
                    composedPreviewFile?.delete()
                    composedPreviewFile = null
                    composeJob?.cancel()
                    previewRenderer.invalidateCache()
                    audioPlayer.pause()
                    val durationUs = (track.durationMs ?: 0L) * 1000L
                    mutableState.update { current ->
                        val name = track.title?.takeIf(String::isNotBlank) ?: displayName
                        val updated = when (side) {
                            ComposeSide.LEFT -> current.copy(
                                leftName = name,
                                leftPath = track.localPath.orEmpty(),
                            )
                            ComposeSide.RIGHT -> current.copy(
                                rightName = name,
                                rightPath = track.localPath.orEmpty(),
                            )
                        }
                        updated.copy(
                            // 两路时长不同就取长的，短的那路后面补静音
                            durationUs = maxOf(updated.durationUs, durationUs),
                            isComposed = false,
                            publishedLocation = null,
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

    fun togglePlaySource(side: ComposeSide) {
        val path = when (side) {
            ComposeSide.LEFT -> mutableState.value.leftPath
            ComposeSide.RIGHT -> mutableState.value.rightPath
        }
        if (path.isBlank()) return
        if (playback.value.isPlaying && playback.value.mediaId == path) {
            audioPlayer.pause()
            return
        }
        if (playback.value.mediaId != path) audioPlayer.loadFile(path)
        audioPlayer.play()
    }

    /** 试听合成结果。 */
    fun togglePlayComposed() {
        val file = composedPreviewFile ?: return
        if (playback.value.isPlaying && playback.value.mediaId == file.absolutePath) {
            audioPlayer.pause()
            return
        }
        if (playback.value.mediaId != file.absolutePath) audioPlayer.loadFile(file.absolutePath)
        audioPlayer.play()
    }

    fun isPlayingSource(side: ComposeSide): Boolean {
        val path = when (side) {
            ComposeSide.LEFT -> mutableState.value.leftPath
            ComposeSide.RIGHT -> mutableState.value.rightPath
        }
        return playback.value.isPlaying && playback.value.mediaId == path
    }

    fun isPlayingComposed(): Boolean =
        playback.value.isPlaying &&
            composedPreviewFile != null &&
            playback.value.mediaId == composedPreviewFile?.absolutePath

    fun seekTo(ms: Long) = audioPlayer.seekTo(ms)

    /** 合成一次，渲染出立体声预览。 */
    fun compose() {
        val current = mutableState.value
        // 底栏按钮缺声道时也可点（保持常态色），所以这里必须说清楚缺哪一路
        if (!current.canCompose) {
            mutableState.update {
                it.copy(
                    message = when {
                        !it.hasBoth && it.leftPath.isBlank() && it.rightPath.isBlank() -> "请先导入两个声道"
                        it.leftPath.isBlank() -> "请先导入左声道"
                        it.rightPath.isBlank() -> "请先导入右声道"
                        else -> null
                    },
                )
            }
            return
        }
        composeJob?.cancel()
        composeJob = viewModelScope.launch {
            mutableState.update { it.copy(isWorking = true, message = null) }
            val file = previewRenderer.renderStereoPair(
                leftPath = current.leftPath,
                rightPath = current.rightPath,
            ).getOrElse { error ->
                mutableState.update {
                    it.copy(isWorking = false, message = "合成失败：${error.message}")
                }
                return@launch
            }
            composedPreviewFile?.delete()
            composedPreviewFile = file
            audioPlayer.pause()
            mutableState.update { it.copy(isComposed = true, isWorking = false) }
        }
    }

    // ---- 导出 ----

    fun suggestedFileName(): String? {
        val current = mutableState.value
        if (!current.isComposed) return null
        val base = current.leftName.substringBeforeLast('.', current.leftName).ifBlank { "音频" }
        return "$base.${ExportFormat.MP3.extension}"
    }

    fun export(confirmedFileName: String? = null) {
        val current = mutableState.value
        if (!current.canExport) return
        if (!mutableExportState.value.isIdle) return
        viewModelScope.launch {
            val fileName = confirmedFileName?.takeIf { it.isNotBlank() }
                ?: suggestedFileName()
                ?: "音频.${ExportFormat.MP3.extension}"
            val jobId = "stereocompose-${UUID.randomUUID()}"
            mutableState.update { it.copy(isWorking = true) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(jobId, fileName)) }
            // 直接复用合成预览那个立体声 WAV 当唯一 source。
            // 引擎的多 source 是混音不是左右分配，两个单声道丢进去只会混成一个单声道。
            val composedPath = composedPreviewFile?.absolutePath
            if (composedPath.isNullOrBlank()) {
                mutableState.update { it.copy(isWorking = false) }
                mutableExportState.update {
                    reduceExport(it, ExportEvent.Failed("还没有合成结果，请先点「开始合成」"))
                }
                return@launch
            }
            val job = ExportJob(
                jobId = jobId,
                editProjectId = "stereo_compose",
                outputTempPath = File(
                    exportTempDirectory,
                    "${UUID.randomUUID()}.${ExportFormat.MP3.extension}",
                ).absolutePath,
                sources = listOf(
                    ExportSource(
                        id = "composed",
                        localPath = composedPath,
                        title = current.leftName.substringBeforeLast('.', ""),
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
            )
            when (val result = exportAndAwait(job)) {
                is ExportResult.Failed ->
                    mutableExportState.update { reduceExport(it, ExportEvent.Failed(result.reason)) }
                is ExportResult.Completed -> {
                    runCatching { exportPublisher.publish(result.outputPath, fileName) }.fold(
                        onSuccess = { published ->
                            mutableState.update { it.copy(isWorking = false) }
                            mutableExportState.update {
                                reduceExport(it, ExportEvent.Succeeded(published.bytes))
                            }
                            mutableState.update { it.copy(publishedLocation = published.location) }
                        },
                        onFailure = { error ->
                            mutableState.update { it.copy(isWorking = false) }
                            mutableExportState.update {
                                reduceExport(it, ExportEvent.Failed("保存失败：${error.message}"))
                            }
                        },
                    )
                }
            }
        }
    }

    private suspend fun exportAndAwait(job: ExportJob): ExportResult =
        suspendCancellableCoroutine { continuation ->
            encoderClient.export(job) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }

    fun cancelExport() {
        mutableExportState.value.jobId?.let(encoderClient::cancel)
    }

    override fun onCleared() {
        super.onCleared()
        composeJob?.cancel()
        composedPreviewFile?.delete()
        composedPreviewFile = null
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
                require(modelClass.isAssignableFrom(StereoComposeViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return StereoComposeViewModel(
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
