package cn.music.audioworkshop.feature.speedpitch

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
import cn.music.audioworkshop.feature.edit.export.historyOf

data class SpeedPitchUiState(
    val track: SourceTrack? = null,
    val fileName: String = "",
    val durationUs: Long = 0L,
    /** 变速倍率，1.0 = 原速。大于 1 变快。只改时长不改音高。 */
    val speed: Float = 1f,
    /** 变调半音数，0 = 原调。正数升调。只改音高不改时长。 */
    val semitones: Float = 0f,
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

    /** 变速后的时长，毫秒。预览条要显示这个而不是原曲时长。 */
    val outputDurationMs: Long
        get() = if (durationUs <= 0L) 0L else (durationUs / 1000L / speed).toLong()

    /**
     * 预览条显示的时长，毫秒。
     *
     * 预览只渲染开头一段，所以这里也是那一段变速后的长度，
     * 而不是整曲。播放条按整曲长度走会走到一半就空白。
     */
    val previewDurationMs: Long
        get() {
            val previewUs = minOf(durationUs, PREVIEW_LENGTH_US)
            return if (previewUs <= 0L) 0L else (previewUs / 1000L / speed).toLong()
        }

    /** 是否有改动 —— 用来禁用「重置」和提示「未修改」。 */
    val isModified: Boolean
        get() = kotlin.math.abs(speed - 1f) > 0.001f || kotlin.math.abs(semitones) > 0.01f

    fun clampParams(): SpeedPitchUiState = copy(
        speed = speed.coerceIn(MIN_SPEED, MAX_SPEED),
        semitones = semitones.coerceIn(MIN_SEMITONES, MAX_SEMITONES),
    )

    companion object {
        /** 预览只渲染开头 40 秒（变速后），够判断效果又不至于等十几秒。 */
        const val PREVIEW_LENGTH_US = 40_000_000L

        /**
         * 速度下限 0.5x。
         *
         * 低于 0.5x 时 Signalsmith 的时间轴拉伸会开始出可闻的颤音，
         * 而且输出的帧数是输入的 2 倍以上，整轨渲染会明显变慢。
         * 要更慢请配合降半音，那才是听感正确的做法。
         */
        const val MIN_SPEED = 0.5f

        /** 上限 3x。native 侧支持到 4x，但 3 倍速的输出已经短到听不全词了。 */
        const val MAX_SPEED = 3f

        /** ±12 半音 = 一个八度。再多就是「换乐器」而不是「变调」了。 */
        const val MIN_SEMITONES = -12f
        const val MAX_SEMITONES = 12f
    }
}

/**
 * 变速变调：速度与音调是两个独立参数。
 *
 * 预览走 [EditPreviewRenderer] 渲染成临时 WAV 再播，所以听到的和导出的完全一致 ——
 * 两边都调同一套 [cn.music.audioworkshop.media.effect.PcmEffects.speedPitch]。
 */
class SpeedPitchViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val probe: AudioFileProbe,
    private val audioPlayer: AudioPlayer,
    private val previewRenderer: EditPreviewRenderer,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(SpeedPitchUiState())
    val uiState: StateFlow<SpeedPitchUiState> = mutableState.asStateFlow()

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
        // 换歌了就把上一首的解码缓存丢掉，别让它白占几十 MB
        previewRenderer.invalidateCache()
        mutableState.update {
            it.copy(
                track = track,
                fileName = file.name,
                durationUs = durationUs,
                isWorking = false,
                message = null,
            ).clampParams()
        }
        // 先播原声，用户一进来就能听到东西
        audioPlayer.loadFile(file.absolutePath)
        mutableState.update { it.copy(isRenderingPreview = false) }
        renderPreview(playWhenReady = false)
    }

    // ---- 参数 ----

    fun setSpeed(speed: Float) = mutableState.update { it.copy(speed = speed).clampParams() }

    fun setSemitones(semitones: Float) = mutableState.update { it.copy(semitones = semitones).clampParams() }

    /** 一键回到原速原调。 */
    fun reset() = mutableState.update { it.copy(speed = 1f, semitones = 0f) }

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
            // 预览只取开头一段：判断快慢和音高听 40 秒足够，
            // 而整轨渲染要对 700 多万帧跑一遍解码 + 时间轴拉伸，实测要十几秒。
            // 播放条也只显示这段，否则拖一次滑杆要等十几秒才出声。
            val previewUs = minOf(current.durationUs, SpeedPitchUiState.PREVIEW_LENGTH_US)
            val result = runCatching {
                previewRenderer.render(
                    sourcePath = path,
                    segments = listOf(EditTimeSegment(0L, previewUs, 0L, previewUs)),
                    gainDb = 0f,
                    fadeInUs = 0L,
                    fadeOutUs = 0L,
                    fadeCurve = FadeCurve.LINEAR,
                    speed = current.speed,
                    semitones = current.semitones,
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
        return "$base-变速变调.mp3"
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
                ?: "${track.title ?: "音频"}-变速变调.mp3"
            val jobId = "speedpitch-${UUID.randomUUID()}"
            mutableState.update { it.copy(isWorking = true, message = null) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(jobId, fileName)) }
            val job = ExportJob(
                jobId = jobId,
                editProjectId = "speedpitch",
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
                speed = current.speed,
                semitones = current.semitones,
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
                require(modelClass.isAssignableFrom(SpeedPitchViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return SpeedPitchViewModel(
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



