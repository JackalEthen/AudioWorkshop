package cn.music.audioworkshop.feature.repair

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
import cn.music.audioworkshop.domain.SourceTrackRepository
import cn.music.audioworkshop.domain.model.EditTimeSegment
import cn.music.audioworkshop.domain.model.FadeCurve
import cn.music.audioworkshop.domain.model.SourceTrack
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

/**
 * 修复强度。
 *
 * [strength] 直接喂给 [cn.music.audioworkshop.media.effect.PcmEffects.repair]：
 * 越高，判定为瑕疵的阈值越宽松 —— 修得更多，也更容易伤到干净的段落。
 */
enum class RepairLevel(
    val label: String,
    val caption: String,
    val strength: Float,
) {
    LIGHT("轻度", "只处理明显的爆破音和咔嗒声，几乎不碰其他声音", 0.3f),
    STANDARD("标准", "加上削波峰值和轻微连续噪声，日常录音够用", 0.6f),
    HEAVY("重度", "阈值最宽，什么瑕疵都往里修，干净段落也可能有轻微影响", 0.95f),
}

data class RepairUiState(
    val fileName: String = "",
    val sourcePath: String = "",
    val durationUs: Long = 0L,
    val level: RepairLevel = RepairLevel.STANDARD,
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

/** 音频修复：处理爆破音、咔嗒声、削波峰值和轻微连续噪声。 */
class RepairViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val probe: AudioFileProbe,
    private val audioPlayer: AudioPlayer,
    private val previewRenderer: EditPreviewRenderer,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(RepairUiState())
    val uiState: StateFlow<RepairUiState> = mutableState.asStateFlow()

    private val mutableExportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = mutableExportState.asStateFlow()

    val playback: StateFlow<PlaybackSnapshot> = audioPlayer.snapshot

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
            // 编辑页导入是一次性的，不进播放器列表
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
                            isWorking = false,
                            message = null,
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

    fun setLevel(level: RepairLevel) = mutableState.update { it.copy(level = level) }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    /**
     * 实时预览：渲染修复后的音频，再交给共享播放器。
     *
     * 换强度就重新渲染 —— 不然听到的还是上一个强度的效果，
     * 会以为调了没用。
     */
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
                repairStrength = current.level.strength,
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
        return "$base-修复.${ExportFormat.MP3.extension}"
    }

    fun export(confirmedFileName: String? = null) {
        val current = mutableState.value
        if (!current.canExport) {
            if (!current.hasTrack) mutableState.update { it.copy(message = "请先导入音频") }
            return
        }
        if (!mutableExportState.value.isIdle) return
        viewModelScope.launch {
            val fileName = confirmedFileName?.takeIf { it.isNotBlank() } ?: suggestedFileName() ?: "音频-修复.mp3"
            val jobId = "repair-${UUID.randomUUID()}"
            mutableState.update { it.copy(isWorking = true, message = null) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(jobId, fileName)) }
            val format = ExportFormat.MP3
            val job = ExportJob(
                jobId = jobId,
                editProjectId = "audio_repair",
                outputTempPath = File(exportTempDirectory, "${UUID.randomUUID()}.${format.extension}").absolutePath,
                sources = listOf(
                    ExportSource(
                        id = "repair:${current.sourcePath}",
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
                format = format,
                repairStrength = current.level.strength,
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
                        mutableExportState.update {
                            reduceExport(it, ExportEvent.Failed("保存到下载目录失败：${error.message}"))
                        }
                    },
                )
            }
        }
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
            probe: AudioFileProbe,
            audioPlayer: AudioPlayer,
            previewRenderer: EditPreviewRenderer,
            encoderClient: EncoderClient,
            exportPublisher: ExportPublisher,
            exportTempDirectory: File,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(RepairViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return RepairViewModel(
                    sourceTrackRepository = sourceTrackRepository,
                    probe = probe,
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



