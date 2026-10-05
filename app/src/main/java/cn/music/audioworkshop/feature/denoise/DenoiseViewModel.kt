package cn.music.audioworkshop.feature.denoise

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
import cn.music.audioworkshop.domain.model.DenoiseMode
import cn.music.audioworkshop.domain.model.FadeCurve
import cn.music.audioworkshop.domain.model.SourceTrack
import cn.music.audioworkshop.feature.edit.export.ExportEvent
import cn.music.audioworkshop.feature.edit.export.ExportPublisher
import cn.music.audioworkshop.feature.edit.export.ExportUiState
import cn.music.audioworkshop.feature.edit.export.reduceExport
import cn.music.audioworkshop.media.pcm.EditPreviewRenderer
import cn.music.audioworkshop.domain.model.EditTimeSegment
import java.io.File
import java.util.UUID
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cn.music.audioworkshop.feature.edit.export.historyOf

/**
 * 淡入淡出模式。
 *
 * [NORMAL] 等功率（sin）曲线：渐变过程中听感音量更均匀，是绝大多数场景该用的。
 * [LINEAR] 线性：幅度直线上升下降，实现最简单，中段听感偏轻。
 */
data class DenoiseUiState(
    val track: SourceTrack? = null,
    val fileName: String = "",
    val durationUs: Long = 0L,
    val mode: DenoiseMode = DenoiseMode.GENERAL,
    /** [DenoiseMode.GENERAL] 的起始频率，Hz。只有这一个模式用得到。 */
    val lowHz: Int = DenoiseMode.DEFAULT_LOW_HZ,
    /** [DenoiseMode.GENERAL] 的结束频率，Hz。 */
    val highHz: Int = DenoiseMode.DEFAULT_HIGH_HZ,
    /** 强度 0~1。1 = 最强。 */
    val strength: Float = 1f,
    /** 预览是否正在重新渲染。 */
    val isRenderingPreview: Boolean = false,
    val isWorking: Boolean = false,
    val message: String? = null,
    val publishedLocation: String? = null,
) {
    val hasTrack: Boolean
        get() = track != null

    val canExport: Boolean
        get() = hasTrack && !isWorking

    /** 只有通用模式暴露频率参数。 */
    val showsFrequency: Boolean
        get() = mode == DenoiseMode.GENERAL

    /**
     * 起始频率不能高于结束频率，否则会把要保留的中频整段切掉，
     * 结果是人声消失只剩下的声音 —— 比不降噪还糟。
     */
    fun clamped(): DenoiseUiState {
        val low = lowHz.coerceIn(DenoiseMode.MIN_FREQ_HZ, DenoiseMode.MAX_FREQ_HZ)
        val high = highHz.coerceIn(DenoiseMode.MIN_FREQ_HZ, DenoiseMode.MAX_FREQ_HZ)
        return copy(lowHz = low, highHz = high.coerceAtLeast(low), strength = strength.coerceIn(0f, 1f))
    }
}

/**
 * 降噪：通用频段压制与录音语音降噪两种方式。
 *
 * 两种都复用 [cn.music.audioworkshop.media.effect.PcmEffects] 里现成的实现，
 * 预览与导出走同一条链。
 */
class DenoiseViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val probe: AudioFileProbe,
    private val audioPlayer: AudioPlayer,
    private val previewRenderer: EditPreviewRenderer,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(DenoiseUiState())
    val uiState: StateFlow<DenoiseUiState> = mutableState.asStateFlow()

    private val mutableExportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<cn.music.audioworkshop.feature.edit.export.ExportUiState> =
        mutableExportState.asStateFlow()

    val playback: StateFlow<PlaybackSnapshot> = audioPlayer.snapshot

    /** 正在跑的预览渲染。参数连续改动时它要被取消，避免旧结果覆盖新的。 */
    private var previewJob: Job? = null

    /** 预览用的临时文件，切换参数时替换。 */
    private var previewFile: File? = null

    init {
        viewModelScope.launch {
            encoderClient.state.collect { state ->
                if (state !is EncoderState.InProgress) return@collect
                val active = mutableExportState.value
                if (!active.isRunning) return@collect
                mutableExportState.update { reduceExport(it, ExportEvent.Progressed(state.progress)) }
            }
        }
    }

    fun importLocalAudio(uri: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(isWorking = true, message = null) }
            // 编辑页导入是一次性的，不写库 —— 否则会出现在播放器的歌曲列表里
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
            notify("这个来源没有本地文件，无法修改音量")
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
        mutableState.update {
            it.copy(
                track = track,
                fileName = file.name,
                durationUs = durationUs,
                isWorking = false,
                message = null,
            ).clamped()
        }
        // 先播原声，用户一进来就能听到东西
        audioPlayer.loadFile(file.absolutePath)
        mutableState.update { it.copy(isRenderingPreview = false) }
        renderPreview(playWhenReady = false)
    }

    // ---- 参数 ----

    fun setMode(mode: DenoiseMode) = mutableState.update { it.copy(mode = mode).clamped() }

    fun setLowHz(hz: Int) = mutableState.update { it.copy(lowHz = hz).clamped() }

    fun setHighHz(hz: Int) = mutableState.update { it.copy(highHz = hz).clamped() }

    fun setStrength(value: Float) = mutableState.update { it.copy(strength = value).clamped() }

    /** 恢复该模式自己的默认频率。 */
    fun resetFrequency() = mutableState.update {
        it.copy(lowHz = DenoiseMode.DEFAULT_LOW_HZ, highHz = DenoiseMode.DEFAULT_HIGH_HZ)
    }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    fun resetExportFeedback() {
        if (mutableExportState.value.isIdle || mutableExportState.value.error != null) {
            mutableExportState.update { reduceExport(it, ExportEvent.Reset) }
        }
    }

    // ---- 预览 ----

    /**
     * 按当前参数重渲染预览并起播。
     *
     * 参数连续改动（比如拖滑杆）会连续触发，每次都渲染一整首歌太慢。
     * 靠取消上一次协程来合并：只有最后一次的结果会被采用 ——
     * 渲染中发现协程已被取消（ensureActive 抛异常），就直接丢弃，不切歌。
     */
    fun renderPreview(playWhenReady: Boolean) {
        val current = mutableState.value
        val track = current.track ?: return
        val path = track.localPath ?: return
        previewJob?.cancel()
        // 立刻标记「正在渲染」并把播放头归零：
        // 界面据此禁用播放键，避免用户对着旧预览以为增益没生效。
        mutableState.update { it.copy(isRenderingPreview = true) }
        audioPlayer.pause()
        audioPlayer.seekTo(0L)
        previewJob = viewModelScope.launch {
            val result = runCatching {
                previewRenderer.render(
                    sourcePath = path,
                    segments = listOf(EditTimeSegment(0L, current.durationUs, 0L, current.durationUs)),
                    gainDb = 0f,
                    fadeInUs = 0L,
                    fadeOutUs = 0L,
                    fadeCurve = FadeCurve.LINEAR,
                    denoiseMode = current.mode,
                    denoiseLowHz = current.lowHz,
                    denoiseHighHz = current.highHz,
                    denoiseStrength = current.strength,
                )
            }.getOrElse { error ->
                // 取消导致的异常不是错误：有更新的请求在排队，静默丢掉即可
                if (error is kotlinx.coroutines.CancellationException) null else {
                    notify("预览渲染失败：${error.message ?: "未知错误"}")
                    null
                }
            }
            // 渲染期间参数又变了：这份结果已过期，交给新协程，这里什么都不做
            val rendered = result?.getOrNull()
            if (rendered == null) {
                // 只有「没有更新的请求在排队」才复位，否则会误清掉新请求的状态
                if (previewJob === coroutineContext[Job]) {
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
        // 渲染中不接受播放：此刻是旧预览，播起来只会让人以为增益没生效
        if (mutableState.value.isRenderingPreview) return
        if (playback.value.isPlaying) audioPlayer.pause() else audioPlayer.play()
    }

    fun seekTo(ms: Long) = audioPlayer.seekTo(ms)

    // ---- 导出 ----

    fun suggestedFileName(): String? {
        val current = mutableState.value
        if (current.fileName.isBlank()) return null
        val base = current.fileName.substringBeforeLast('.', current.fileName).ifBlank { "音频" }
        return "$base-降噪.mp3"
    }

    fun export(confirmedFileName: String? = null) {
        val current = mutableState.value
        if (!current.canExport) {
            if (!current.hasTrack) notify("请先导入音频")
            return
        }
        if (!mutableExportState.value.isIdle) return
        val track = current.track ?: return
        viewModelScope.launch {
            val fileName = (confirmedFileName?.takeIf { it.isNotBlank() } ?: suggestedFileName())
                ?: "${track.title ?: "音频"}-降噪.mp3"
            val jobId = "denoise-${UUID.randomUUID()}"
            mutableState.update { it.copy(isWorking = true, message = null) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(jobId, fileName)) }
            val job = ExportJob(
                jobId = jobId,
                editProjectId = "denoise",
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
                denoiseMode = current.mode,
                denoiseLowHz = current.lowHz,
                denoiseHighHz = current.highHz,
                denoiseStrength = current.strength,
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
                        val reason = error.message ?: "未知错误"
                        mutableState.update { it.copy(publishedLocation = null) }
                        mutableExportState.update {
                            reduceExport(it, ExportEvent.Failed("保存到下载目录失败：$reason"))
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
        /** 淡入淡出时长的上限（秒）。超过 30 秒的渐变基本听不出区别。 */
        const val UNUSED = 0L

        /** 最短 0.1 秒 —— 再短就不是渐变而是「咔」一下。 */
        const val MIN_FADE_MS = 100L

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
                require(modelClass.isAssignableFrom(DenoiseViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return DenoiseViewModel(
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




