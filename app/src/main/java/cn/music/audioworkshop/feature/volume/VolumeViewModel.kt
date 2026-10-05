package cn.music.audioworkshop.feature.volume

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

/** 音量调整的两种标度方式。 */
enum class VolumeMode(val label: String) {
    PERCENT("百分比"),
    DECIBEL("分贝"),
}

data class VolumeUiState(
    val track: SourceTrack? = null,
    val fileName: String = "",
    val durationUs: Long = 0L,
    val mode: VolumeMode = VolumeMode.PERCENT,
    /** 百分比模式下的倍率，1.0 = 原音量。 */
    val percent: Float = 1f,
    /** 分贝模式下的增益，0 = 原音量。 */
    val decibel: Float = 0f,
    val preventClipping: Boolean = true,
    /**
     * 预览是否正在重新渲染。
     *
     * 渲染要重读整首歌，耗时几百毫秒到几秒。这个期间旧的预览还在播，
     * 用户会以为「调了没效果」—— 实际上新效果是渲染完才切歌的。
     * 界面上要把这段时间显出来：播放键变灰、进度条归零。
     */
    val isRenderingPreview: Boolean = false,
    val isWorking: Boolean = false,
    val message: String? = null,
    val publishedLocation: String? = null,
) {
    /**
     * 统一换算成分贝。两种模式只是标度不同，最终都落到导出引擎的 [ExportJob.gainDb]。
     *
     * 百分比是线性倍率（150% = 1.5 倍），分贝是对数（+6dB 约等于 2 倍）。
     */
    val gainDb: Float
        get() = when (mode) {
            VolumeMode.PERCENT -> (20.0 * kotlin.math.log10(percent.coerceAtLeast(MIN_PERCENT))).toFloat()
            VolumeMode.DECIBEL -> decibel
        }

    val hasTrack: Boolean
        get() = track != null

    val canExport: Boolean
        get() = hasTrack && !isWorking
    companion object {
        /** 低于 1% 的增益没有听感，且会让 log10 溢出，界面也不给这个值。 */
        const val MIN_PERCENT = 0.01f
    }
}

/**
 * 修改音量。
 *
 * 预览走 [EditPreviewRenderer] 渲染成临时 WAV 再播，所以听到的和导出的完全一致 ——
 * 增益与防炸音都在同一条 [PcmEditPipeline] 上算。
 */
class VolumeViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val probe: AudioFileProbe,
    private val audioPlayer: AudioPlayer,
    private val previewRenderer: EditPreviewRenderer,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(VolumeUiState())
    val uiState: StateFlow<VolumeUiState> = mutableState.asStateFlow()

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
            )
        }
        // 先播原声，用户一进来就能听到东西
        audioPlayer.loadFile(file.absolutePath)
        mutableState.update { it.copy(isRenderingPreview = false) }
        renderPreview(playWhenReady = false)
    }

    // ---- 参数 ----

    fun setMode(mode: VolumeMode) = mutableState.update { it.copy(mode = mode) }

    fun setPercent(value: Float) = mutableState.update {
        it.copy(percent = value.coerceIn(VolumeUiState.MIN_PERCENT, MAX_PERCENT))
    }

    fun setDecibel(value: Float) = mutableState.update {
        it.copy(decibel = value.coerceIn(MIN_DECIBEL, MAX_DECIBEL))
    }

    fun setPreventClipping(enabled: Boolean) = mutableState.update { it.copy(preventClipping = enabled) }

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
                    gainDb = current.gainDb,
                    fadeInUs = 0L,
                    fadeOutUs = 0L,
                    fadeCurve = FadeCurve.LINEAR,
                    preventClipping = current.preventClipping,
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
        return "$base-音量.mp3"
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
                ?: "${track.title ?: "音频"}-音量.mp3"
            val jobId = "volume-${UUID.randomUUID()}"
            mutableState.update { it.copy(isWorking = true, message = null) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(jobId, fileName)) }
            val job = ExportJob(
                jobId = jobId,
                editProjectId = "volume",
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
                gainDb = current.gainDb,
                preventClipping = current.preventClipping,
                fadeInMs = 0L,
                fadeOutMs = 0L,
                fadeCurve = FadeCurve.LINEAR,
                lyricOffsetMs = 0L,
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
                runCatching { exportPublisher.publish(result.outputPath, fileName) }.fold(
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
        // 只能暂停，不能 release：audioPlayer 是 AppContainer 里的单例，
        // 全应用共用。release 会 scope.cancel() + player.release()，
        // 之后进任何功能页都播不出声了。
        audioPlayer.pause()
    }

    companion object {
        const val MIN_PERCENT = 0.01f
        /**
         * 最大 800%（+18dB）。真实用途就是「原曲录得太轻，要推上去」，
         * 给到 400% 往往还不够用；配防炸音时大增益也只会变脏不会爆。
         */
        const val MAX_PERCENT = 8f
        const val MIN_DECIBEL = -24f
        const val MAX_DECIBEL = 18f

        /** 百分比换算成分贝，供测试与界面复用。 */
        fun percentToDb(percent: Float): Float =
            (20.0 * kotlin.math.log10(percent.coerceIn(MIN_PERCENT, MAX_PERCENT))).toFloat()

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
                require(modelClass.isAssignableFrom(VolumeViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return VolumeViewModel(
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

