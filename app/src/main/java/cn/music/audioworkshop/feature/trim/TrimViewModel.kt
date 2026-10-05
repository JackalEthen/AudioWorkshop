package cn.music.audioworkshop.feature.trim

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
import cn.music.audioworkshop.domain.model.WaveformPeaks
import cn.music.audioworkshop.domain.waveform.WaveformSource
import cn.music.audioworkshop.feature.edit.export.ExportEvent
import cn.music.audioworkshop.feature.edit.export.ExportPublisher
import cn.music.audioworkshop.feature.edit.export.ExportUiState
import cn.music.audioworkshop.feature.edit.export.reduceExport
import cn.music.audioworkshop.feature.edit.waveform.WaveformGeometry
import cn.music.audioworkshop.feature.edit.waveform.WaveformSelection
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 剪切模式。 */
enum class TrimMode(val label: String, val caption: String) {
    /** 硬切：保留选区，无任何淡入淡出处理。 */
    FAST("快速", "直接保留选区，硬切无过渡"),

    /** 正常：按填写的淡入淡出时长处理接缝。 */
    NORMAL("正常", "带淡入淡出，接缝更平滑"),
}

data class TrimUiState(
    val track: SourceTrack? = null,
    val fileName: String = "",
    val durationUs: Long = 0L,
    val peaks: WaveformPeaks? = null,
    val selection: WaveformSelection = WaveformSelection(0L, 0L),
    val mode: TrimMode = TrimMode.FAST,
    /**
     * 调整时长后是否**自动开始播放**。
     *
     * 开着：改完起止时间立刻从头播一遍，改动效果马上能听到。
     * 关着：只改数值，播放头回到开头但不自动播 —— 反复微调时不被反复打断。
     *
     * 注意这和「播放头是否回到开头」是两件事：**回开头始终会发生**，
     * 这个开关只决定要不要自动开始播。
     */
    val replayAfterAdjust: Boolean = true,
    /** 淡入时长，毫秒。只在 [TrimMode.NORMAL] 下生效。 */
    val fadeInMs: Long = 0L,
    /** 淡出时长，毫秒。只在 [TrimMode.NORMAL] 下生效。 */
    val fadeOutMs: Long = 0L,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val message: String? = null,
    val publishedLocation: String? = null,
) {
    val selectedUs: Long
        get() = (selection.endUs - selection.startUs).coerceAtLeast(0L)

    val selectedMs: Long
        get() = selectedUs / 1000L

    val hasTrack: Boolean
        get() = track != null

    /** 选区太短时导出没有意义。1 毫秒以下直接拦掉。 */
    val canExport: Boolean
        get() = hasTrack && selectedMs >= MIN_SELECTION_MS && !isExporting && !isLoading

    /** 淡入淡出不能超过选区本身的一半，否则会互相吃掉。 */
    fun clampFades() = copy(
        fadeInMs = fadeInMs.coerceIn(0L, selectedMs / 2),
        fadeOutMs = fadeOutMs.coerceIn(0L, selectedMs / 2),
    )

    companion object {
        const val MIN_SELECTION_MS = 50L
    }
}

/**
 * 剪切。保留波形选区内的音频，导出成一个新文件。
 *
 * 与播放器、与其它功能页没有数据耦合：音频一次性导入，不写库。
 */
class TrimViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val probe: AudioFileProbe,
    private val audioPlayer: AudioPlayer,
    private val waveformExtractor: WaveformSource,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(TrimUiState())
    val uiState: StateFlow<TrimUiState> = mutableState.asStateFlow()

    private val mutableExportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = mutableExportState.asStateFlow()

    val playback: StateFlow<PlaybackSnapshot> = audioPlayer.snapshot

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
            mutableState.update { it.copy(isLoading = true, message = null) }
            // 编辑页导入是一次性的，不写库 —— 否则会出现在播放器的歌曲列表里
            sourceTrackRepository.importLocalAudioEphemeral(uri).fold(
                onSuccess = { track ->
                    load(track)
                },
                onFailure = { error ->
                    mutableState.update { it.copy(isLoading = false) }
                    notify("导入失败：${error.message ?: "未知错误"}")
                },
            )
        }
    }

    private suspend fun load(track: SourceTrack) {
        val path = track.localPath
        if (path.isNullOrBlank()) {
            mutableState.update { it.copy(isLoading = false) }
            notify("这个来源没有本地文件，无法剪切")
            return
        }
        val file = File(path)
        if (!file.isFile) {
            mutableState.update { it.copy(isLoading = false) }
            notify("源文件不可用，请重新导入")
            return
        }
        val durationUs = withContext(Dispatchers.IO) {
            runCatching { probe.probeFile(file).durationMs?.times(1000L) }.getOrNull() ?: 0L
        }
        if (durationUs <= 0L) {
            mutableState.update { it.copy(isLoading = false) }
            notify("读不出音频时长，文件可能已损坏")
            return
        }
        // 波形必须放到后台单独跑，不能 await。
        // peaks() 是流式的（每 250ms 回调一次部分结果），但它本身要读完整个文件
        // 才返回；之前在这里 withContext 等待它，结果导入后界面卡住不动 ——
        //
        // 现在：先把轨道/时长/选区提交上去让界面立刻能用，波形随后自己填进来。
        audioPlayer.loadFile(file.absolutePath)
        mutableState.update {
            it.copy(
                track = track,
                fileName = file.name,
                durationUs = durationUs,
                // 默认全选：先让用户看到完整时间轴，再自己缩
                selection = WaveformGeometry.fullSelection(durationUs),
                isLoading = false,
                message = null,
            )
        }

        loadWaveformInBackground(track, file)
    }

    /** 波形单独一条协程：出错也不能影响已经显示出来的界面。 */
    private fun loadWaveformInBackground(track: SourceTrack, file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                waveformExtractor.peaks(track.id, file) { partial ->
                    // 部分结果直接推给界面，大文件也能边读边看
                    mutableState.update { it.copy(peaks = partial) }
                }
            }.onSuccess { full ->
                mutableState.update { it.copy(peaks = full) }
            }.onFailure { error ->
                // 波形读不出来不影响剪切，只是画不出图；记日志不打扰用户
            }
        }
    }

    fun setSelection(selection: WaveformSelection) {
        mutableState.update { it.copy(selection = selection).clampFades() }
        onTimingChanged()
    }

    fun setReplayAfterAdjust(enabled: Boolean) = mutableState.update { it.copy(replayAfterAdjust = enabled) }

    /**
     * 时间改动后的统一处理。
     *
     * 播放头回到选区开头是**无条件**的 —— 选区变了之后，原先那个位置已经在
     * 新选区之外，继续停在那里会让人以为没生效。
     *
     * 「要不要自动开始播」才由开关决定：反复微调时长时从头播会很吵，
     * 所以把自动播放拆出来单独关掉。
     */
    private fun onTimingChanged() {
        val startMs = mutableState.value.selection.startUs / 1000L
        audioPlayer.seekTo(startMs)
        if (mutableState.value.replayAfterAdjust) audioPlayer.play()
    }

    /** 时间输入框改出来的值要重新夹回选区，start 不得晚于 end。 */
    fun setStartMs(ms: Long) {
        mutableState.update { current ->
            val limit = current.durationUs
            val start = (ms * 1000L).coerceIn(0L, (limit - WaveformGeometry.MIN_SELECTION_US).coerceAtLeast(0L))
            current.copy(
                selection = WaveformGeometry.normalize(
                    start,
                    current.selection.endUs,
                    limit,
                ),
            ).clampFades()
        }
        onTimingChanged()
    }

    fun setEndMs(ms: Long) {
        mutableState.update { current ->
            val limit = current.durationUs
            val end = (ms * 1000L).coerceIn(
                WaveformGeometry.MIN_SELECTION_US,
                limit,
            )
            current.copy(
                selection = WaveformGeometry.normalize(current.selection.startUs, end, limit),
            ).clampFades()
        }
        onTimingChanged()
    }

    fun setMode(mode: TrimMode) = mutableState.update { it.copy(mode = mode) }

    fun setFadeIn(ms: Long) = mutableState.update { it.copy(fadeInMs = ms.coerceAtLeast(0L)) }

    fun setFadeOut(ms: Long) = mutableState.update { it.copy(fadeOutMs = ms.coerceAtLeast(0L)) }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    fun resetExportFeedback() {
        if (mutableExportState.value.isIdle || mutableExportState.value.error != null) {
            mutableExportState.update { reduceExport(it, ExportEvent.Reset) }
        }
    }

    // ---- 预览 ----

    /**
     * 试听范围 = 波形选区。
     *
     * 用户选了一分钟，播放条就只在这一分钟里走；播放到选区末尾自动跳回选区开头，
     * 可以反复对比调整后的效果。播放头停在选区外时（刚导入、刚拖过手柄）
     * 先拉回选区开头。
     */
    fun onPlaybackTick(snapshot: PlaybackSnapshot) {
        val selection = mutableState.value.selection
        val startMs = selection.startUs / 1000L
        val endMs = selection.endUs / 1000L
        if (endMs <= startMs) return
        // 只管「播到选区末尾往回循环」这一件事。
        // 播放头回到开头由 onTimingChanged 在时间改动时统一处理，
        // 放这里判断会和拖动选区打架 —— 拖动过程中播放头会被反复拽回去。
        if (snapshot.positionMs >= endMs) audioPlayer.seekTo(startMs)
    }

    fun togglePlay() {
        if (playback.value.isPlaying) {
            audioPlayer.pause()
        } else {
            // 起播时若播放头在选区外，先拉回选区开头，否则用户听到的是「按了没反应」
            val selection = mutableState.value.selection
            val startMs = selection.startUs / 1000L
            val endMs = selection.endUs / 1000L
            if (playback.value.positionMs < startMs || playback.value.positionMs >= endMs) {
                audioPlayer.seekTo(startMs)
            }
            audioPlayer.play()
        }
    }

    /** 拖进度条时被夹回选区内，避免拖到没选中的地方去。 */
    fun seekTo(ms: Long) {
        val selection = mutableState.value.selection
        val startMs = selection.startUs / 1000L
        val endMs = (selection.endUs / 1000L).coerceAtLeast(startMs + 1L)
        audioPlayer.seekTo(ms.coerceIn(startMs, endMs - 1L))
    }

    /** 点波形定位播放头，同样夹在选区内。 */
    fun seekToUs(us: Long) = seekTo(us / 1000L)

    // ---- 导出 ----

    fun suggestedFileName(): String? {
        val current = mutableState.value
        if (current.fileName.isBlank()) return null
        val base = current.fileName.substringBeforeLast('.', current.fileName).ifBlank { "音频" }
        return "$base-剪切.mp3"
    }

    fun export(confirmedFileName: String? = null) {
        val current = mutableState.value
        if (!current.canExport) {
            if (!current.hasTrack) notify("请先导入音频") else notify("选区太短")
            return
        }
        if (!mutableExportState.value.isIdle) return
        val track = current.track ?: return
        viewModelScope.launch {
            val fileName = (confirmedFileName?.takeIf { it.isNotBlank() } ?: suggestedFileName())
                ?: "${track.title ?: "音频"}-剪切.mp3"
            val jobId = "trim-${UUID.randomUUID()}"
            mutableState.update { it.copy(isExporting = true, message = null) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(jobId, fileName)) }
            val job = ExportJob(
                jobId = jobId,
                editProjectId = "trim",
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
                        segments = listOf(ExportSegment(current.selection.startUs, current.selection.endUs)),
                    ),
                ),
                // 剪切不改动响度与歌词偏移
                gainDb = 0f,
                lyricOffsetMs = 0L,
                // 快速模式就是硬切：不填淡入淡出，导出引擎原样保留选区
                fadeInMs = if (current.mode == TrimMode.NORMAL) current.fadeInMs else 0L,
                fadeOutMs = if (current.mode == TrimMode.NORMAL) current.fadeOutMs else 0L,
                fadeCurve = FadeCurve.LINEAR,
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
                mutableState.update { it.copy(isExporting = false) }
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
        // 只能暂停，不能 release：audioPlayer 是 AppContainer 里的单例，
        // 全应用共用。release 会 scope.cancel() + player.release()，
        // 之后进任何功能页都播不出声了。
        audioPlayer.pause()
    }

    companion object {
        fun factory(
            sourceTrackRepository: SourceTrackRepository,
            probe: AudioFileProbe,
            audioPlayer: AudioPlayer,
            waveformExtractor: WaveformSource,
            encoderClient: EncoderClient,
            exportPublisher: ExportPublisher,
            exportTempDirectory: File,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(TrimViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return TrimViewModel(
                    sourceTrackRepository = sourceTrackRepository,
                    probe = probe,
                    audioPlayer = audioPlayer,
                    waveformExtractor = waveformExtractor,
                    encoderClient = encoderClient,
                    exportPublisher = exportPublisher,
                    exportTempDirectory = exportTempDirectory,
                ) as T
            }
        }
    }
}

