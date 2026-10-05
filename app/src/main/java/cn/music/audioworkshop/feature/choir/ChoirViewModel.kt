package cn.music.audioworkshop.feature.choir

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.music.audioworkshop.data.encoder.EncoderClient
import cn.music.audioworkshop.data.encoder.EncoderState
import cn.music.audioworkshop.data.media.AudioFileProbe
import cn.music.audioworkshop.domain.AudioPlayer
import cn.music.audioworkshop.domain.PlaybackSnapshot
import cn.music.audioworkshop.domain.SourceTrackRepository
import cn.music.audioworkshop.domain.media.ExportFormat
import cn.music.audioworkshop.domain.media.ExportJob
import cn.music.audioworkshop.domain.media.ExportResult
import cn.music.audioworkshop.domain.media.ExportSegment
import cn.music.audioworkshop.domain.media.ExportSource
import cn.music.audioworkshop.domain.model.ChoirPreset
import cn.music.audioworkshop.domain.model.FadeCurve
import cn.music.audioworkshop.domain.model.SourceTrack
import cn.music.audioworkshop.feature.edit.export.ExportEvent
import cn.music.audioworkshop.feature.edit.export.ExportPublisher
import cn.music.audioworkshop.feature.edit.export.ExportUiState
import cn.music.audioworkshop.feature.edit.export.reduceExport
import cn.music.audioworkshop.media.pcm.EditPreviewRenderer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cn.music.audioworkshop.feature.edit.export.historyOf

/**
 * 合唱效果：三个类型，不给参数。
 *
 * 延迟、反馈和湿声比例全部封在 [ChoirPreset] 里，用户只选「想要哪种合唱」。
 * 那些数字对没有混响经验的人没有意义，而三个预设已经覆盖了听感差异最大的维度。
 */
data class ChoirUiState(
    val track: SourceTrack? = null,
    val fileName: String = "",
    val durationUs: Long = 0L,
    /** 选中的合唱类型，null = 还没选。 */
    val preset: ChoirPreset? = null,
    val isRenderingPreview: Boolean = false,
    val isWorking: Boolean = false,
    val message: String? = null,
    val publishedLocation: String? = null,
) {
    val hasTrack: Boolean
        get() = track != null

    val canExport: Boolean
        get() = hasTrack && !isWorking && preset != null
}

/**
 * 合唱效果。
 *
 * 预览和导出都走 [cn.music.audioworkshop.media.effect.PcmEffects.choir]，参数完全一致，
 * 所以听到的就是导出的。
 */
class ChoirViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val probe: AudioFileProbe,
    private val audioPlayer: AudioPlayer,
    private val previewRenderer: EditPreviewRenderer,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ChoirUiState())
    val uiState: StateFlow<ChoirUiState> = mutableState.asStateFlow()

    private val mutableExportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = mutableExportState.asStateFlow()

    val playback: StateFlow<PlaybackSnapshot> = audioPlayer.snapshot

    private var previewJob: Job? = null
    private var previewFile: File? = null

    init {
        viewModelScope.launch {
            encoderClient.state.collect { state ->
                if (state !is EncoderState.InProgress) return@collect
                if (!mutableExportState.value.isRunning) return@collect
                mutableExportState.update { reduceExport(it, ExportEvent.Progressed(state.progress)) }
            }
        }
    }

    fun importLocalAudio(uri: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(isWorking = true, message = null) }
            sourceTrackRepository.importLocalAudioEphemeral(uri).fold(
                onSuccess = { track -> load(track) },
                onFailure = { error ->
                    mutableState.update { it.copy(isWorking = false) }
                    notify("导入失败：${error.message ?: "未知错误"}")
                },
            )
        }
    }

    private suspend fun load(track: SourceTrack) {
        val path = track.localPath
        if (path.isNullOrBlank()) {
            mutableState.update { it.copy(isWorking = false) }
            notify("这个来源没有本地文件，无法加合唱")
            return
        }
        val file = File(path)
        if (!file.isFile) {
            mutableState.update { it.copy(isWorking = false) }
            notify("源文件不可用，请重新导入")
            return
        }
        val durationUs = withContext(Dispatchers.IO) {
            runCatching { probe.probeFile(file).durationMs?.times(1000L) }.getOrNull() ?: 0L
        }
        if (durationUs <= 0L) {
            mutableState.update { it.copy(isWorking = false) }
            notify("读不出音频时长，文件可能已损坏")
            return
        }
        previewRenderer.invalidateCache()
        mutableState.update {
            it.copy(
                track = track,
                fileName = file.name,
                durationUs = durationUs,
                isWorking = false,
                message = null,
            )
        }
        // 先播原声，用户一进来就能听到东西
        audioPlayer.loadFile(file.absolutePath)
        mutableState.update { it.copy(isRenderingPreview = false) }
    }

    fun selectPreset(preset: ChoirPreset) = mutableState.update { it.copy(preset = preset) }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    fun resetExportFeedback() {
        if (mutableExportState.value.isIdle || mutableExportState.value.error != null) {
            mutableExportState.update { reduceExport(it, ExportEvent.Reset) }
        }
    }

    /** 按当前选中的类型重新渲染预览并起播。没选类型就只播原声。 */
    fun renderPreview(playWhenReady: Boolean) {
        val current = mutableState.value
        val preset = current.preset ?: return
        val path = current.track?.localPath ?: return
        previewJob?.cancel()
        mutableState.update { it.copy(isRenderingPreview = true) }
        audioPlayer.pause()
        audioPlayer.seekTo(0L)
        previewJob = viewModelScope.launch {
            val result = runCatching {
                previewRenderer.render(
                    sourcePath = path,
                    segments = listOf(
                        cn.music.audioworkshop.domain.model.EditTimeSegment(0L, current.durationUs, 0L, current.durationUs),
                    ),
                    gainDb = 0f,
                    fadeInUs = 0L,
                    fadeOutUs = 0L,
                    fadeCurve = FadeCurve.LINEAR,
                    choirPreset = preset,
                )
            }.getOrElse { error ->
                // 取消导致的异常不是错误：有更新的请求在排队，静默丢掉即可
                if (error is kotlinx.coroutines.CancellationException) null else {
                    notify("预览渲染失败：${error.message ?: "未知错误"}")
                    null
                }
            }
            val rendered = result?.getOrNull()
            if (rendered == null) {
                // 只有「没有更新的请求在排队」才复位，否则会误清掉新请求的状态
                if (previewJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    mutableState.update { it.copy(isRenderingPreview = false) }
                }
                return@launch
            }
            audioPlayer.loadFile(rendered.absolutePath)
            previewFile?.takeIf { it != rendered }?.delete()
            previewFile = rendered
            mutableState.update { it.copy(isRenderingPreview = false) }
            if (playWhenReady) audioPlayer.play()
        }
    }

    fun togglePlay() {
        if (mutableState.value.isRenderingPreview) return
        if (playback.value.isPlaying) audioPlayer.pause() else audioPlayer.play()
    }

    fun seekTo(ms: Long) = audioPlayer.seekTo(ms)

    // ---- 导出 ----

    fun suggestedFileName(): String? {
        val current = mutableState.value
        if (current.fileName.isBlank()) return null
        val base = current.fileName.substringBeforeLast('.', current.fileName).ifBlank { "音频" }
        return "$base-合唱.mp3"
    }

    fun export(confirmedFileName: String? = null) {
        val current = mutableState.value
        val preset = current.preset
        if (!current.canExport) {
            if (!current.hasTrack) notify("请先导入音频") else notify("请先选择合唱类型")
            return
        }
        if (!mutableExportState.value.isIdle) return
        val track = current.track ?: return
        viewModelScope.launch {
            val fileName = confirmedFileName?.takeIf { it.isNotBlank() }
                ?: suggestedFileName()
                ?: "${track.title ?: "音频"}-合唱.mp3"
            val jobId = "choir-${UUID.randomUUID()}"
            mutableState.update { it.copy(isWorking = true, message = null) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(jobId, fileName)) }
            val job = ExportJob(
                jobId = jobId,
                editProjectId = "choir",
                outputTempPath = File(
                    exportTempDirectory,
                    "${UUID.randomUUID()}.${ExportFormat.MP3.extension}",
                ).absolutePath,
                sources = listOf(
                    ExportSource(
                        id = track.id,
                        localPath = track.localPath.orEmpty(),
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        lyrics = track.lyrics,
                        segments = listOf(ExportSegment(0L, current.durationUs)),
                    ),
                ),
                gainDb = 0f,
                fadeInMs = 0L,
                fadeOutMs = 0L,
                fadeCurve = FadeCurve.LINEAR,
                lyricOffsetMs = 0L,
                choirPreset = preset,
                format = ExportFormat.MP3,
            )
            encoderClient.export(job) { result -> onExportResult(job, fileName, result) }
        }
    }

    fun cancelExport() {
        mutableExportState.value.jobId?.let(encoderClient::cancel)
    }

    private fun onExportResult(job: ExportJob, fileName: String, result: ExportResult) {
        when (result) {
            is ExportResult.Failed ->
                mutableExportState.update { reduceExport(it, ExportEvent.Failed(result.reason)) }

            is ExportResult.Completed -> viewModelScope.launch {
                mutableState.update { it.copy(isWorking = false) }
                runCatching { exportPublisher.publish(result.outputPath, fileName, historyOf(job, result)) }.fold(
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
        previewJob?.cancel()
        previewFile?.delete()
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
                require(modelClass.isAssignableFrom(ChoirViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return ChoirViewModel(
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


