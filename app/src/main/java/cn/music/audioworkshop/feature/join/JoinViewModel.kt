package cn.music.audioworkshop.feature.join

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
import cn.music.audioworkshop.domain.model.JoinTransition
import cn.music.audioworkshop.domain.model.SourceTrack
import cn.music.audioworkshop.feature.edit.export.ExportEvent
import cn.music.audioworkshop.feature.edit.export.ExportPublisher
import cn.music.audioworkshop.feature.edit.export.ExportUiState
import cn.music.audioworkshop.feature.edit.export.reduceExport
import cn.music.audioworkshop.media.export.ExportPlanner
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

/** 拼接接缝的方式，四选一。名字沿用引擎层已有的 [JoinTransition]。 */
enum class JoinMode(val label: String, val caption: String) {
    NORMAL("正常", "直接首尾硬接，一秒过渡都不要"),
    FADE("淡入淡出", "前一首淡出接后一首淡入，消除接缝爆音"),
    STABLE("稳定", "淡入淡出但用等功率曲线，两首音量起伏更小"),
    PRESERVE("无损", "不裁任何原样本，接缝处插入等长空白"),
    ;

    val transition: JoinTransition
        get() = when (this) {
            NORMAL -> JoinTransition.NORMAL
            FADE -> JoinTransition.FADE
            STABLE -> JoinTransition.STABLE
            PRESERVE -> JoinTransition.PRESERVE
        }
}

/**
 * 列表里的一项。
 *
 * [gapAfterMs] 是这一项**后面**插多少毫秒空白 —— 用户要的「在具体时间点插入空白」
 * 就是这个东西：改哪一项的空白，就等于在那个时间点上开了个口子。
 */
data class JoinItem(
    val track: SourceTrack,
    val fileName: String,
    val durationUs: Long,
    /** 这一项后面插入的空白，毫秒。 */
    val gapAfterMs: Long = 0L,
)

data class JoinUiState(
    val items: List<JoinItem> = emptyList(),
    val mode: JoinMode = JoinMode.FADE,
    /** 接缝过渡时长，毫秒。只有 FADE / STABLE / PRESERVE 用得上。 */
    val transitionMs: Long = 800L,
    /** 最后一项后面追加的空白，毫秒。 */
    val trailingSilenceMs: Long = 0L,
    val isRenderingPreview: Boolean = false,
    val isWorking: Boolean = false,
    val message: String? = null,
    val publishedLocation: String? = null,
) {
    val hasTrack: Boolean
        get() = items.isNotEmpty()

    val canExport: Boolean
        get() = items.isNotEmpty() && !isWorking

    val durationMs: Long
        get() = items.sumOf { it.durationUs / 1000L } +
            items.sumOf { it.gapAfterMs } +
            trailingSilenceMs

    /**
     * 接缝时长不能超过最短那首的一半。
     *
     * 前一首淡出和后一首淡入会重叠 —— 重叠处两段增益相乘，听感是忽然一沉，
     * 比硬接还难听。半首是保守但安全的上限。
     */
    fun maxTransitionMs(): Long {
        // 没有音频就没有接缝可言。返回 0 而不是上限，
        // 否则空列表下会把 transitionMs 夹到 10 秒，界面凭空出现一条滑杆
        val shortestUs = items.minOfOrNull { it.durationUs } ?: return 0L
        return (shortestUs / 1000L / 2).coerceIn(0L, MAX_TRANSITION_MS)
    }

    fun clampDurations(): JoinUiState = copy(
        transitionMs = transitionMs.coerceIn(0L, maxTransitionMs()),
        trailingSilenceMs = trailingSilenceMs.coerceAtLeast(0L),
    )

    companion object {
        /** 接缝最长 10 秒。再长就不是「过渡」而是「两首歌中间停了 10 秒」。 */
        const val MAX_TRANSITION_MS = 10_000L
        /** 空白最长 60 秒。够用且防止手滑输错单位。 */
        const val MAX_GAP_MS = 60_000L
    }
}

/**
 * 合成：把多个音频按顺序拼成一条。
 *
 * 预览和导出共用同一份 [ExportPlanner] 计划，所以听到的和导出的逐样本对得上。
 */
class JoinViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val probe: AudioFileProbe,
    private val audioPlayer: AudioPlayer,
    private val previewRenderer: EditPreviewRenderer,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(JoinUiState())
    val uiState: StateFlow<JoinUiState> = mutableState.asStateFlow()

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

    // ---- 导入 ----

    /** 追加一条到列表末尾，不替换已有内容 —— 合成要的就是「多个」。 */
    fun addTracks(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            mutableState.update { it.copy(isWorking = true, message = null) }
            val loaded = mutableListOf<JoinItem>()
            uris.forEach { uri ->
                sourceTrackRepository.importLocalAudioEphemeral(uri).fold(
                    onSuccess = { track -> loadItem(track)?.let { loaded += it } },
                    onFailure = { notify("导入失败：${it.message ?: "未知错误"}") },
                )
            }
            if (loaded.isEmpty()) {
                mutableState.update { it.copy(isWorking = false) }
                return@launch
            }
            mutableState.update {
                it.copy(items = it.items + loaded, isWorking = false).clampDurations()
            }
            renderPreview(playWhenReady = false)
        }
    }

    private suspend fun loadItem(track: SourceTrack): JoinItem? {
        val path = track.localPath
        if (path.isNullOrBlank()) {
            notify("有一项没有本地文件，已跳过")
            return null
        }
        val file = File(path)
        if (!file.isFile) {
            notify("${file.name} 已不存在，已跳过")
            return null
        }
        val durationUs = withContext(Dispatchers.IO) {
            runCatching { probe.probeFile(file).durationMs?.times(1000L) }.getOrNull() ?: 0L
        }
        if (durationUs <= 0L) {
            notify("${file.name} 读不出时长，已跳过")
            return null
        }
        return JoinItem(track = track, fileName = file.name, durationUs = durationUs)
    }

    fun removeAt(index: Int) = mutableState.update {
        it.copy(items = it.items.filterIndexed { i, _ -> i != index }).clampDurations()
    }.let { renderPreview(playWhenReady = false) }

    /** 上移一项。第一项往上移是空操作。 */
    fun moveUp(index: Int) = move(index, index - 1)

    /** 下移一项。最后一项往下移是空操作。 */
    fun moveDown(index: Int) = move(index, index + 1)

    private fun move(from: Int, to: Int) {
        mutableState.update { current ->
            val list = current.items
            if (from !in list.indices || to !in list.indices) return@update current
            list.toMutableList().apply {
                add(to, removeAt(from))
            }.let { current.copy(items = it) }
        }
        renderPreview(playWhenReady = false)
    }

    // ---- 参数 ----

    fun setMode(mode: JoinMode) = mutableState.update { it.copy(mode = mode) }

    fun setTransitionMs(ms: Long) = mutableState.update { it.copy(transitionMs = ms).clampDurations() }

    fun setTrailingSilenceMs(ms: Long) = mutableState.update { it.copy(trailingSilenceMs = ms).clampDurations() }

    /** 改某一项后面的空白 —— 也就是「在这个时间点插入空白」。 */
    fun setGapAfterMs(index: Int, ms: Long) = mutableState.update { current ->
        current.copy(
            items = current.items.mapIndexed { i, item ->
                if (i == index) item.copy(gapAfterMs = ms.coerceIn(0L, JoinUiState.MAX_GAP_MS)) else item
            },
        )
    }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    fun resetExportFeedback() {
        if (mutableExportState.value.isIdle || mutableExportState.value.error != null) {
            mutableExportState.update { reduceExport(it, ExportEvent.Reset) }
        }
    }

    // ---- 预览 ----

    /**
     * 用 [ExportPlanner] 生成拼接计划。预览和导出走同一份计划，
     * 所以「听到的」就是「导出的」，不会两边不一致。
     */
    private fun buildJob(current: JoinUiState, outputTempPath: String): ExportJob {
        val gapByIndex = current.items.map { it.gapAfterMs }
        return ExportJob(
            jobId = "join-plan",
            editProjectId = "join",
            outputTempPath = outputTempPath,
            sources = current.items.map { item ->
                ExportSource(
                    id = item.track.id,
                    localPath = item.track.localPath.orEmpty(),
                    title = item.track.title,
                    artist = item.track.artist,
                    album = item.track.album,
                    lyrics = item.track.lyrics,
                    segments = listOf(ExportSegment(0L, item.durationUs)),
                )
            },
            gainDb = 0f,
            // 整条不做整体淡入淡出 —— 接缝的斜坡由 plan 按 transition 逐接缝算
            fadeInMs = 0L,
            fadeOutMs = 0L,
            lyricOffsetMs = 0L,
            joinTransition = current.mode.transition,
            transitionMs = current.transitionMs,
            trailingSilenceMs = current.trailingSilenceMs,
            format = ExportFormat.MP3,
            // 空白按项插入：把每项的 gapAfterMs 交给计划器，避免界面再算一遍时间轴
            perSourceGapMs = gapByIndex,
        )
    }

    fun renderPreview(playWhenReady: Boolean) {
        val current = mutableState.value
        if (current.items.isEmpty()) return
        previewJob?.cancel()
        mutableState.update { it.copy(isRenderingPreview = true) }
        audioPlayer.pause()
        audioPlayer.seekTo(0L)
        previewJob = viewModelScope.launch {
            val result = runCatching {
                val job = buildJob(current, File(exportTempDirectory, "join-preview.mp3").absolutePath)
                previewRenderer.renderPlan(ExportPlanner.plan(job), gainDb = 0f)
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) null else {
                    notify("预览渲染失败：${error.message ?: "未知错误"}")
                    null
                }
            }
            val rendered = result?.getOrNull()
            if (rendered == null) {
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
        if (current.items.isEmpty()) return null
        val first = current.items.first().fileName.substringBeforeLast('.', "").ifBlank { "音频" }
        return if (current.items.size > 1) "$first 等${current.items.size}首-合成.mp3" else "$first-合成.mp3"
    }

    fun export(confirmedFileName: String? = null) {
        val current = mutableState.value
        if (!current.canExport) {
            if (!current.hasTrack) notify("请先导入音频")
            return
        }
        if (!mutableExportState.value.isIdle) return
        viewModelScope.launch {
            val fileName = confirmedFileName?.takeIf { it.isNotBlank() } ?: suggestedFileName() ?: "合成.mp3"
            val jobId = "join-${UUID.randomUUID()}"
            mutableState.update { it.copy(isWorking = true, message = null) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(jobId, fileName)) }
            val job = buildJob(
                current,
                File(exportTempDirectory, "${UUID.randomUUID()}.${ExportFormat.MP3.extension}").absolutePath,
            ).copy(jobId = jobId)
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
                require(modelClass.isAssignableFrom(JoinViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return JoinViewModel(
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
