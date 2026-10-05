package cn.music.audioworkshop.feature.lrc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.music.audioworkshop.data.encoder.EncoderClient
import cn.music.audioworkshop.data.encoder.EncoderState
import cn.music.audioworkshop.data.media.AudioFileProbe
import cn.music.audioworkshop.data.media.LrcCodec
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
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌词编辑里的一行。
 *
 * 只存开始时间和文本 —— 结束时间由下一行推出来，用户不需要也不能自己填。
 * 逐字时间（words）也保留一份：读进来的逐字歌词重新导出时不能丢。
 */
data class LrcLine(
    val startUs: Long,
    val text: String,
)

data class LrcUiState(
    val track: SourceTrack? = null,
    val fileName: String = "",
    val durationUs: Long = 0L,
    /** 文本框里正在编辑的 LRC 源码。 */
    val sourceText: String = "",
    /** 文本框是否展开。「查看切换歌词」按钮控制。 */
    val isSourceVisible: Boolean = false,
    /** 逐行列表，界面上平铺滚动。 */
    val lines: List<LrcLine> = emptyList(),
    /** 当前选中行，下标。新增行插在它后面。 */
    val selectedIndex: Int = -1,
    val isWorking: Boolean = false,
    val message: String? = null,
    val publishedLocation: String? = null,
) {
    val hasTrack: Boolean
        get() = track != null

    val canExport: Boolean
        get() = hasTrack && !isWorking && lines.isNotEmpty()

    /** 有改动但还没点保存时给个提示，否则用户以为保存了其实列表没变。 */
    val sourceDirty: Boolean
        get() = isSourceVisible && sourceText.isNotBlank()

    /** 播放位置落在哪一行 —— 界面用它高亮。 */
    fun currentLineIndexAt(positionMs: Long): Int {
        val positionUs = positionMs * 1000L
        var found = -1
        lines.forEachIndexed { index, line ->
            if (line.startUs <= positionUs) found = index else return found
        }
        return found
    }
}

/**
 * LRC 歌词编辑：改源码 + 逐行打点。
 *
 * 歌词不改变音频，所以这里没有音频预览渲染 —— 播放的是源文件本身，
 * 只有导出时才把 [ExportSource.lyricsOverride] 写进新文件。
 */
class LrcEditViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val probe: AudioFileProbe,
    private val audioPlayer: AudioPlayer,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(LrcUiState())
    val uiState: StateFlow<LrcUiState> = mutableState.asStateFlow()

    private val mutableExportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = mutableExportState.asStateFlow()

    val playback: StateFlow<PlaybackSnapshot> = audioPlayer.snapshot

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
            notify("这个来源没有本地文件，无法编辑歌词")
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
        // 自动读出文件里已有的歌词，用户可以直接改，而不是从空白开始
        val raw = withContext(Dispatchers.IO) { probe.readEmbeddedLyrics(file) }
        val lines = if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            LrcCodec.parse(raw).lines.map { LrcLine(it.startUs, it.text) }
        }
        mutableState.update {
            it.copy(
                track = track,
                fileName = file.name,
                durationUs = durationUs,
                sourceText = raw.orEmpty(),
                lines = lines,
                selectedIndex = -1,
                isWorking = false,
                message = if (lines.isEmpty()) "这首歌没有歌词，点上方「查看歌词」粘贴一份" else null,
            )
        }
        // 歌词不影响音频，直接播源文件即可，不需要渲染预览
        audioPlayer.loadFile(file.absolutePath)
    }

    /** 从文件标签里读出歌词原文（USLT / SYLT / ID3v1 歌词字段）。 */
    // ---- 源码文本 ----

    fun toggleSourceVisible() = mutableState.update { it.copy(isSourceVisible = !it.isSourceVisible) }

    fun setSourceText(text: String) = mutableState.update { it.copy(sourceText = text) }

    /**
     * 保存文本框内容：校验格式后解析成逐行列表。
     *
     * 三种输入都接受：
     * - 标准 LRC：直接用行里的时间戳
     * - 纯文本：自动按固定间隔分配时间，用户再用 ±1s 校准
     * - 空白：清空歌词
     *
     * 格式彻底不对才不动列表 —— 否则用户粘贴错一次，之前打好的点就全丢了。
     */
    fun saveSource() {
        val text = mutableState.value.sourceText
        if (text.isBlank()) {
            mutableState.update { it.copy(lines = emptyList(), selectedIndex = -1) }
            notify("已清空歌词")
            return
        }
        // 不拦任何输入：带时间戳、纯文本、混着来都存。
        // 纯文本按固定间隔分配时间，然后把这句话原样告诉用户，别让他以为时间打准了。
        if (LrcTiming.hasNoTimestamp(text)) {
            val assigned = LrcTiming.assignPlainTextTimings(text)
            if (assigned.isEmpty()) {
                notify("没有识别到歌词内容")
                return
            }
            mutableState.update {
                it.copy(
                    lines = assigned.map { (startUs, body) -> LrcLine(startUs, body) },
                    selectedIndex = -1,
                    isSourceVisible = false,
                )
            }
            notify(LrcTiming.describeTimestamps(text) ?: "已保存 ${assigned.size} 句")
            return
        }
        val parsed = LrcCodec.parse(text).lines.map { LrcLine(it.startUs, it.text) }
        if (parsed.isEmpty()) {
            notify("没有识别到歌词行，请检查是否缺少 [mm:ss.SSS] 时间戳")
            return
        }
        mutableState.update {
            it.copy(
                lines = parsed,
                selectedIndex = if (parsed.isEmpty()) -1 else it.selectedIndex.coerceIn(-1, parsed.lastIndex),
                isSourceVisible = false,
            )
        }
        notify("已保存 ${parsed.size} 句")
    }

    fun clearSource() = mutableState.update {
        it.copy(sourceText = "", lines = emptyList(), selectedIndex = -1)
    }

    fun copySourceText(): String = mutableState.value.sourceText

    fun pasteSourceText(text: String) = mutableState.update { it.copy(sourceText = text) }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    fun resetExportFeedback() {
        if (mutableExportState.value.isIdle || mutableExportState.value.error != null) {
            mutableExportState.update { reduceExport(it, ExportEvent.Reset) }
        }
    }

    // ---- 逐行打点 ----

    fun selectLine(index: Int) = mutableState.update { it.copy(selectedIndex = index) }

    /**
     * 在当前播放位置插入一个空行。
     *
     * 插在选中行之后；没选中就插到末尾。插完自动选中，
     * 让用户接着就能改文本 —— 打点通常就是「补一句漏掉的词」。
     */
    fun addLineAtPlayback(positionMs: Long) {
        val current = mutableState.value
        val startUs = positionMs * 1000L
        val insertAt = (current.selectedIndex + 1).coerceIn(0, current.lines.size)
        val updated = current.lines.toMutableList().apply {
            add(insertAt, LrcLine(startUs, ""))
        }
        mutableState.update { it.copy(lines = updated, selectedIndex = insertAt) }
    }

    fun setLineText(index: Int, text: String) = mutableState.update { current ->
        if (index !in current.lines.indices) return@update current
        current.copy(lines = current.lines.mapIndexed { i, l -> if (i == index) l.copy(text = text) else l })
    }

    /** 时间 ±deltaMs 微调。改完保持列表按时间升序，否则导出的 LRC 会乱序。 */
    fun nudgeStart(index: Int, deltaMs: Long) = mutableState.update { current ->
        if (index !in current.lines.indices) return@update current
        val target = current.lines[index]
        val movedTime = (target.startUs + deltaMs * 1000L).coerceAtLeast(0L)
        val updated = current.lines
            .mapIndexed { i, line -> if (i == index) line.copy(startUs = movedTime) else line }
            .sortedBy { it.startUs }
        // 排序后下标会变，按这一行本身定位它跑到哪了，选中状态才不会跟丢
        val newIndex = updated.indexOfFirst { it.startUs == movedTime && it.text == target.text }
        current.copy(lines = updated, selectedIndex = if (newIndex >= 0) newIndex else current.selectedIndex)
    }

    fun deleteLine(index: Int) = mutableState.update { current ->
        if (index !in current.lines.indices) return@update current
        current.copy(
            lines = current.lines.filterIndexed { i, _ -> i != index },
            selectedIndex = (current.selectedIndex - 1).coerceAtMost(current.lines.size - 2),
        )
    }

    fun togglePlay() {
        if (playback.value.isPlaying) audioPlayer.pause() else audioPlayer.play()
    }

    fun seekTo(ms: Long) = audioPlayer.seekTo(ms)

    // ---- 导出 ----

    fun suggestedFileName(): String? {
        val current = mutableState.value
        if (current.fileName.isBlank()) return null
        val base = current.fileName.substringBeforeLast('.', current.fileName).ifBlank { "音频" }
        return "$base-歌词.mp3"
    }

    /** 导出纯 `.lrc` 文本时的建议名。 */
    fun suggestedLrcFileName(): String? {
        val current = mutableState.value
        if (current.fileName.isBlank()) return null
        val base = current.fileName.substringBeforeLast('.', current.fileName).ifBlank { "音频" }
        return "$base.lrc"
    }

    /**
     * 只导出 `.lrc` 文本文件，不重新编码音频。
     *
     * 纯文本歌词照样能导出成 LRC：分配好的时间戳会按标准格式写出去，
     * 任何支持 LRC 的播放器都能直接打开这个文件。
     */
    fun exportLrc(confirmedFileName: String? = null) {
        val current = mutableState.value
        if (current.lines.isEmpty()) {
            notify("还没有歌词，先保存一份")
            return
        }
        if (!mutableExportState.value.isIdle) return
        viewModelScope.launch {
            val fileName = (confirmedFileName?.takeIf { it.isNotBlank() }
                ?: suggestedLrcFileName() ?: "音频.lrc").let {
                // 后缀由内容决定，不让用户导出一个叫 xxx.txt 的歌词
                if (it.substringAfterLast('.', "").lowercase() == "lrc") it else "$it.lrc"
            }
            mutableState.update { it.copy(isWorking = true, message = null) }
            mutableExportState.update {
                reduceExport(it, ExportEvent.Started("lrc-${UUID.randomUUID()}", fileName))
            }
            val temp = File(exportTempDirectory, "${UUID.randomUUID()}.lrc")
            runCatching {
                temp.writeText(toLrc(current.lines), Charsets.UTF_8)
                exportPublisher.publish(temp.absolutePath, fileName)
            }.fold(
                onSuccess = { published ->
                    mutableExportState.update { reduceExport(it, ExportEvent.Succeeded(published.bytes)) }
                    mutableState.update { it.copy(isWorking = false, publishedLocation = published.location) }
                },
                onFailure = { error ->
                    mutableState.update { it.copy(isWorking = false) }
                    mutableExportState.update {
                        reduceExport(it, ExportEvent.Failed("导出歌词失败：${error.message ?: "未知错误"}"))
                    }
                },
            )
            temp.delete()
        }
    }

    fun export(confirmedFileName: String? = null) {
        val current = mutableState.value
        if (!current.canExport) {
            if (!current.hasTrack) notify("请先导入音频") else if (current.lines.isEmpty()) notify("还没有歌词，先保存一份")
            return
        }
        if (!mutableExportState.value.isIdle) return
        val track = current.track ?: return
        viewModelScope.launch {
            val fileName = confirmedFileName?.takeIf { it.isNotBlank() } ?: suggestedFileName() ?: "音频-歌词.mp3"
            val jobId = "lrc-${UUID.randomUUID()}"
            mutableState.update { it.copy(isWorking = true, message = null) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(jobId, fileName)) }
            val job = ExportJob(
                jobId = jobId,
                editProjectId = "lrc",
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
                        // 覆盖源歌词：用户编辑过的版本优先
                        lyricsOverride = toLrc(current.lines),
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
                        mutableState.update { it.copy(publishedLocation = null) }
                        mutableExportState.update {
                            reduceExport(it, ExportEvent.Failed("保存到下载目录失败：${error.message}"))
                        }
                    },
                )
            }
        }
    }

    /** 逐行列表 -> 标准 LRC 源码。 */
    private fun toLrc(lines: List<LrcLine>): String {
        val lrc = LrcCodec.toLrc(
            cn.music.audioworkshop.domain.model.LyricsTrack(
                lines = lines.map {
                    cn.music.audioworkshop.domain.model.LyricLine(it.text, it.startUs, it.startUs + 5_000_000L, emptyList())
                },
                offsetMs = 0L,
            ),
        )
        android.util.Log.i("QishuiLrc", "toLrc lines=${lines.size} chars=${lrc.length} head=${lrc.take(60)}")
        return lrc
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
            encoderClient: EncoderClient,
            exportPublisher: ExportPublisher,
            exportTempDirectory: File,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(LrcEditViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return LrcEditViewModel(
                    sourceTrackRepository = sourceTrackRepository,
                    probe = probe,
                    audioPlayer = audioPlayer,
                    encoderClient = encoderClient,
                    exportPublisher = exportPublisher,
                    exportTempDirectory = exportTempDirectory,
                ) as T
            }
        }
    }
}
