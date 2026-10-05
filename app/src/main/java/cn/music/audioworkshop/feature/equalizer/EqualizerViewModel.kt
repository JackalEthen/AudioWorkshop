package cn.music.audioworkshop.feature.equalizer

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

/**
 * 淡入淡出模式。
 *
 * [NORMAL] 等功率（sin）曲线：渐变过程中听感音量更均匀，是绝大多数场景该用的。
 * [LINEAR] 线性：幅度直线上升下降，实现最简单，中段听感偏轻。
 */
/**
 * 常用均衡曲线。
 *
 * 只有一个预设：没有「平直」这个选项。用户想要平直就点「全部归零」——
 * 把「什么都不做」摆成一个按钮，会让人以为选它等于开启均衡。
 */
enum class EqPreset(val label: String, val gains: List<Float>) {
    CUSTOM("自定义", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BASS("重低音", listOf(6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f)),
    VOCAL("人声", listOf(-2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f)),
    BRIGHT("去刺耳", listOf(0f, 0f, 0f, 0f, 0f, -1f, -3f, -5f)),
    ;

    companion object {
        val LABELS: List<String> = entries.map { it.label }
    }
}

data class EqualizerUiState(
    val track: SourceTrack? = null,
    val fileName: String = "",
    val durationUs: Long = 0L,
    /** 8 段增益，分贝。全 0 = 旁路。 */
    val gainsDb: List<Float> = List(BAND_COUNT) { 0f },
    /** 用户手动调过任一频段就为 true，界面据此显示「自定义」。 */
    val touchedByUser: Boolean = false,
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

    /** 有没有偏离平直。全 0 时整条 biquad 链都不用跑。 */
    val isModified: Boolean
        get() = gainsDb.any { kotlin.math.abs(it) > 0.01f }

    /**
     * 当前应该高亮哪个预设。
     *
     * 手动调过就是「自定义」，除非用户调回了某个预设的数值 —— 那时显示那个预设
     * 更准确（参数完全一致，叫「自定义」反而丢信息）。
     */
    val matchedPreset: EqPreset
        get() = if (!touchedByUser && !isModified) {
            EqPreset.CUSTOM
        } else {
            EqPreset.entries.firstOrNull { it.gains.take(BAND_COUNT) == gainsDb } ?: EqPreset.CUSTOM
        }

    companion object {
        /** 8 段，对应 [cn.music.audioworkshop.media.effect.PcmEffects.EQ_BAND_LABELS]。 */
        const val BAND_COUNT = 8
        /**
         * 单段最大增益 ±12 dB。
         *
         * 再大就必须削波才能达到，明显失真；而 12 dB 已经能听出
         * 「这段被抬起来了」到「这段很突出」的全部差别。
         */
        const val MAX_GAIN_DB = 12f
    }
}

/**
 * 均衡器：8 段图形均衡。
 *
 * 预览和导出都走 [cn.music.audioworkshop.media.effect.PcmEffects.equalizer]，
 * 增益逐位一致。
 */
class EqualizerViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val probe: AudioFileProbe,
    private val audioPlayer: AudioPlayer,
    private val previewRenderer: EditPreviewRenderer,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(EqualizerUiState())
    val uiState: StateFlow<EqualizerUiState> = mutableState.asStateFlow()

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

    fun setGain(index: Int, db: Float) = mutableState.update { current ->
        if (index !in current.gainsDb.indices) return@update current
        val clamped = db.coerceIn(-EqualizerUiState.MAX_GAIN_DB, EqualizerUiState.MAX_GAIN_DB)
        if (current.gainsDb[index] == clamped) return@update current
        current.copy(
            gainsDb = current.gainsDb.mapIndexed { i, g -> if (i == index) clamped else g },
            // 手动碰过就不再算「预设」，除非调回了某个预设的数值（matchedPreferences 会兜住）
            touchedByUser = true,
        )
    }

    /** 一键回到平直（全 0），整条 biquad 链都不跑。 */
    fun reset() = mutableState.update {
        it.copy(gainsDb = List(EqualizerUiState.BAND_COUNT) { 0f }, touchedByUser = false)
    }

    /** 常用曲线，省得用户一根根拖。 */
    fun applyPreset(preset: EqPreset) = mutableState.update {
        it.copy(gainsDb = preset.gains.take(EqualizerUiState.BAND_COUNT).toList(), touchedByUser = false)
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
                    eqGainsDb = current.gainsDb,
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
        return "$base-均衡.mp3"
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
                ?: "${track.title ?: "音频"}-均衡.mp3"
            val jobId = "eq-${UUID.randomUUID()}"
            mutableState.update { it.copy(isWorking = true, message = null) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(jobId, fileName)) }
            val job = ExportJob(
                jobId = jobId,
                editProjectId = "equalizer",
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
                eqGainsDb = current.gainsDb,
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
                require(modelClass.isAssignableFrom(EqualizerViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return EqualizerViewModel(
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




