package cn.music.audioworkshop.feature.stereoorbit

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

private const val DEFAULT_HALF_CIRCLE_SEC = 8f
private const val DEFAULT_DEGREES = 45f
private const val MIN_HALF_CIRCLE_SEC = 1f
private const val MAX_HALF_CIRCLE_SEC = 20f
private const val MAX_DEGREES = 90f

data class StereoOrbitUiState(
    val fileName: String = "",
    val sourcePath: String = "",
    val durationUs: Long = 0L,
    /** 走半圈要多久（秒）。值越小转得越快。 */
    val halfCircleSec: Float = DEFAULT_HALF_CIRCLE_SEC,
    /** 环绕幅度，也就是半径。角度越大听感上绕得越远。 */
    val degrees: Float = DEFAULT_DEGREES,
    val isRenderingPreview: Boolean = false,
    val isWorking: Boolean = false,
    val message: String? = null,
    val publishedLocation: String? = null,
) {
    val hasTrack: Boolean
        get() = sourcePath.isNotBlank()

    val canExport: Boolean
        get() = hasTrack && !isWorking && !isRenderingPreview
}

/**
 * 立体声环绕：声音在左右之间转圈。
 *
 * [halfCircleSec] 是走完半圈的时间，[degrees] 是环绕幅度（半径）。
 * 合成和导出都走 [cn.music.audioworkshop.media.effect.PcmEffects.stereoOrbit]。
 */
class StereoOrbitViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val audioPlayer: AudioPlayer,
    private val previewRenderer: EditPreviewRenderer,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(StereoOrbitUiState())
    val uiState: StateFlow<StereoOrbitUiState> = mutableState.asStateFlow()

    private val mutableExportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = mutableExportState.asStateFlow()

    val playback: StateFlow<PlaybackSnapshot> = audioPlayer.snapshot

    val halfCircleRange = MIN_HALF_CIRCLE_SEC..MAX_HALF_CIRCLE_SEC
    val degreesRange = 0f..MAX_DEGREES

    private var previewJob: Job? = null

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
                    previewJob?.cancel()
                    previewRenderer.invalidateCache()
                    audioPlayer.pause()
                    mutableState.update {
                        it.copy(
                            fileName = track.title?.takeIf(String::isNotBlank) ?: displayName,
                            sourcePath = track.localPath.orEmpty(),
                            durationUs = (track.durationMs ?: 0L) * 1000L,
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

    fun setHalfCircleSec(value: Float) = mutableState.update {
        it.copy(halfCircleSec = value.coerceIn(MIN_HALF_CIRCLE_SEC, MAX_HALF_CIRCLE_SEC))
    }

    fun setDegrees(value: Float) = mutableState.update {
        it.copy(degrees = value.coerceIn(0f, MAX_DEGREES))
    }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    fun renderPreview() {
        val current = mutableState.value
        if (!current.hasTrack) return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            mutableState.update { it.copy(isRenderingPreview = true, message = null) }
            val result = previewRenderer.render(
                sourcePath = current.sourcePath,
                segments = listOf(
                    EditTimeSegment(0L, current.durationUs, 0L, current.durationUs),
                ),
                gainDb = 0f,
                fadeInUs = 0L,
                fadeOutUs = 0L,
                fadeCurve = FadeCurve.LINEAR,
                orbitHalfCircleSec = current.halfCircleSec,
                orbitDegrees = current.degrees,
            ).getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) return@launch
                mutableState.update {
                    it.copy(isRenderingPreview = false, message = "预览失败：${error.message}")
                }
                return@launch
            }
            audioPlayer.pause()
            audioPlayer.loadFile(result.absolutePath)
            mutableState.update { it.copy(isRenderingPreview = false) }
        }
    }

    fun togglePlay() {
        val current = mutableState.value
        if (!current.hasTrack || current.isRenderingPreview) return
        if (playback.value.isPlaying) {
            audioPlayer.pause()
            return
        }
        audioPlayer.play()
    }

    fun seekTo(ms: Long) = audioPlayer.seekTo(ms)

    // ---- 导出 ----

    fun suggestedFileName(): String? {
        val current = mutableState.value
        if (!current.hasTrack) return null
        val base = current.fileName.substringBeforeLast('.', current.fileName).ifBlank { "音频" }
        return "$base-环绕.${ExportFormat.MP3.extension}"
    }

    fun export(confirmedFileName: String? = null) {
        val current = mutableState.value
        // 底栏按钮未导入时也可点（保持常态色），所以这里必须给提示而不是静默返回
        if (!current.canExport) {
            mutableState.update { it.copy(message = "请先导入音频") }
            return
        }
        if (!mutableExportState.value.isIdle) return
        viewModelScope.launch {
            val fileName = confirmedFileName?.takeIf { it.isNotBlank() }
                ?: suggestedFileName()
                ?: "音频-环绕.${ExportFormat.MP3.extension}"
            val jobId = "stereoorbit-${UUID.randomUUID()}"
            mutableState.update { it.copy(isWorking = true, message = null) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(jobId, fileName)) }
            val job = ExportJob(
                jobId = jobId,
                editProjectId = "stereo_orbit",
                outputTempPath = File(
                    exportTempDirectory,
                    "${UUID.randomUUID()}.${ExportFormat.MP3.extension}",
                ).absolutePath,
                sources = listOf(
                    ExportSource(
                        id = "orbit:${current.sourcePath}",
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
                orbitHalfCircleSec = current.halfCircleSec,
                orbitDegrees = current.degrees,
            )
            encoderClient.export(job) { result ->
                when (result) {
                    is ExportResult.Failed ->
                        mutableExportState.update { reduceExport(it, ExportEvent.Failed(result.reason)) }
                    is ExportResult.Completed -> viewModelScope.launch {
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
    }

    fun cancelExport() {
        mutableExportState.value.jobId?.let(encoderClient::cancel)
    }

    override fun onCleared() {
        super.onCleared()
        previewJob?.cancel()
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
                require(modelClass.isAssignableFrom(StereoOrbitViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return StereoOrbitViewModel(
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
