package cn.music.audioworkshop.feature.edit

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.music.audioworkshop.data.encoder.EncoderClient
import cn.music.audioworkshop.data.encoder.EncoderState
import cn.music.audioworkshop.domain.AudioPlayer
import cn.music.audioworkshop.domain.EditProjectRepository
import cn.music.audioworkshop.domain.ExportPackageRepository
import cn.music.audioworkshop.domain.PlaybackSnapshot
import cn.music.audioworkshop.domain.SourceTrackRepository
import cn.music.audioworkshop.domain.edit.EditTimelineCalculator
import cn.music.audioworkshop.domain.lyrics.LyricsParser
import cn.music.audioworkshop.data.media.LrcCodec
import cn.music.audioworkshop.domain.media.ExportFormat
import cn.music.audioworkshop.domain.media.ExportJob
import cn.music.audioworkshop.domain.media.ExportResult
import cn.music.audioworkshop.domain.media.ExportSegment
import cn.music.audioworkshop.domain.media.ExportSource
import cn.music.audioworkshop.domain.model.EditMode
import cn.music.audioworkshop.domain.model.EditOperation
import cn.music.audioworkshop.domain.model.EditProject
import cn.music.audioworkshop.domain.model.JoinTransition
import cn.music.audioworkshop.domain.model.EditTimeRange
import cn.music.audioworkshop.domain.model.EditTimeSegment
import cn.music.audioworkshop.domain.model.ExportPackage
import cn.music.audioworkshop.domain.model.ExportValidationStatus
import cn.music.audioworkshop.domain.model.FadeCurve
import cn.music.audioworkshop.domain.model.GainScale
import cn.music.audioworkshop.domain.model.LyricLine
import cn.music.audioworkshop.domain.model.LyricWord
import cn.music.audioworkshop.domain.model.LyricsTrack
import cn.music.audioworkshop.domain.model.SourceTrack
import cn.music.audioworkshop.domain.model.WaveformPeaks
import cn.music.audioworkshop.domain.waveform.WaveformSource
import cn.music.audioworkshop.feature.edit.export.ExportEvent
import cn.music.audioworkshop.feature.edit.export.ExportPublisher
import cn.music.audioworkshop.media.export.ExportPlanner
import cn.music.audioworkshop.media.pcm.EditPreviewRenderer
import cn.music.audioworkshop.feature.edit.export.ExportUiState
import cn.music.audioworkshop.feature.edit.export.JoinSourceOrder
import cn.music.audioworkshop.feature.edit.export.MAX_JOIN_SOURCES
import cn.music.audioworkshop.feature.edit.export.reduceExport
import cn.music.audioworkshop.feature.edit.waveform.WaveformSelection
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import cn.music.audioworkshop.feature.edit.export.historyOf

sealed interface WaveformUiState {
    data object Loading : WaveformUiState
    data class Ready(val peaks: WaveformPeaks) : WaveformUiState
    data class Error(val message: String) : WaveformUiState
}

data class EditWorkspaceUiState(
    val operation: EditOperation,
    val track: SourceTrack? = null,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val gainDb: Float = 0f,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val lyricOffsetMs: Long = 0L,
    val joinedTrackIds: List<String> = emptyList(),
    val joinedTracks: List<SourceTrack> = emptyList(),
    val joinTransition: JoinTransition = JoinTransition.NORMAL,
    val transitionMs: Long = 2000L,
    val normalizeSources: Boolean = false,
    val trailingSilenceMs: Long = 0L,
    val previewRealEffect: Boolean = true,
    val segments: List<EditTimeSegment> = emptyList(),
    val outputDurationUs: Long = 0L,
    val lyrics: LyricsTrack = LyricsTrack.EMPTY,
    /** 逐行编辑后的 LRC 原文覆盖层。null = 没改过，导出用源曲自带的歌词。 */
    val lyricsOverride: String? = null,
    val activeLineIndex: Int = -1,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val wordMode: Boolean = false,
    val activeWordIndex: Int = -1,
    val waveform: WaveformUiState = WaveformUiState.Loading,
    val savedProjectId: String? = null,
    val isSaving: Boolean = false,
    val fadeCurve: FadeCurve = FadeCurve.LINEAR,
    val gainScale: GainScale = GainScale.DECIBEL,
    val autoPlayOnChange: Boolean = true,
    val isPreviewing: Boolean = false,
    val exportFormat: ExportFormat = ExportFormat.MP3,
    val message: String? = null,
) {
    val canSave: Boolean
        get() = track != null && when (operation) {
            EditOperation.JOIN -> joinedTrackIds.size >= 2
            else -> segments.isNotEmpty()
        }

    val exportFileName: String
        get() {
            val base = track?.title?.trim()?.takeIf(String::isNotEmpty) ?: "export"
            return base.replace(UNSAFE_FILE_NAME, "_") + "." + exportFormat.extension
        }
}

private val UNSAFE_FILE_NAME = Regex("[\\\\/:*?\"<>|]")

class EditWorkspaceViewModel(
    private val operation: EditOperation,
    private val sourceTrackId: String,
    private val initialJoinedTrackIds: List<String>,
    private val exportTempDirectory: File,
    private val sourceTrackRepository: SourceTrackRepository,
    private val editProjectRepository: EditProjectRepository,
    private val exportPackageRepository: ExportPackageRepository,
    private val audioPlayer: AudioPlayer,
    private val waveformSource: WaveformSource,
    private val lyricsParser: LyricsParser,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val previewRenderer: EditPreviewRenderer,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(EditWorkspaceUiState(operation = operation))
    val uiState: StateFlow<EditWorkspaceUiState> = mutableUiState.asStateFlow()
    val playback: StateFlow<PlaybackSnapshot> = audioPlayer.snapshot
    val availableTracks: StateFlow<List<SourceTrack>> = sourceTrackRepository.observeAll()
        // 文件已经被删掉的记录没法编辑，别再摆出来让人点
        .map { tracks -> tracks.filter { File(it.localPath).isFile } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutableExportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = mutableExportState.asStateFlow()

    private val undoStack = ArrayDeque<LyricSnapshot>()
    private val redoStack = ArrayDeque<LyricSnapshot>()

    init {
        viewModelScope.launch {
            if (sourceTrackId.isNotBlank()) {
                sourceTrackRepository.getById(sourceTrackId)?.let { loadTrack(it) }
            }
        }
        viewModelScope.launch {
            encoderClient.state.collect { state ->
                if (state is EncoderState.InProgress) {
                    mutableExportState.update { reduceExport(it, ExportEvent.Progressed(state.progress)) }
                }
            }
        }
    }

    fun importLocalAudio(uri: String) {
        viewModelScope.launch {
            val track = sourceTrackRepository.importLocalAudioEphemeral(uri).getOrElse { error ->
                mutableUiState.update { it.copy(message = "导入失败：${error.message ?: "未知错误"}") }
                return@launch
            }
            loadTrack(track)
        }
    }

    fun importLyrics(rawLrc: String) {
        val parsed = LrcCodec.parse(rawLrc)
        if (parsed.lines.isEmpty()) {
            mutableUiState.update { it.copy(message = "歌词文件里没有可识别的时间轴") }
            return
        }
        mutableUiState.update { it.copy(lyrics = parsed, activeLineIndex = -1) }
    }

    // ---- 逐行歌词编辑（打点） ----

    fun selectLyricLine(index: Int) = mutate { current ->
        current.copy(
            activeLineIndex = if (index in current.lyrics.lines.indices) index else -1,
            activeWordIndex = 0,
        )
    }

    fun selectActiveWord(index: Int) = mutate { current ->
        current.copy(
            activeWordIndex = index.takeIf { i ->
                i in 0 until (current.lyrics.lines.getOrNull(current.activeLineIndex)?.words?.size ?: 0)
            } ?: -1,
        )
    }

    /** 打点：把当前播放位置写进选中行；没选中行就先在播放位置插一行。 */
    fun stampActiveLine(positionUs: Long) {
        val current = mutableUiState.value
        val index = current.activeLineIndex
        ensureAudible()
        if (index !in current.lyrics.lines.indices) {
            insertLineAt(positionUs, current.activeLineIndex)
            return
        }
        val updated = current.lyrics.lines.toMutableList()
        val line = updated[index]
        val span = (line.endUs - line.startUs).coerceAtLeast(LINE_MIN_US)
        updated[index] = line.copy(startUs = positionUs, endUs = positionUs + span)
        commitLyrics(updated)
        mutableUiState.update { it.copy(activeLineIndex = index) }
    }

    /** 前/后 0.1 秒、前/后 1 秒。 */
    fun nudgeActiveLine(deltaUs: Long) {
        val current = mutableUiState.value
        val index = current.activeLineIndex
        val line = current.lyrics.lines.getOrNull(index) ?: return
        val updated = current.lyrics.lines.toMutableList()
        updated[index] = line.copy(
            startUs = (line.startUs + deltaUs).coerceAtLeast(0L),
            endUs = (line.endUs + deltaUs).coerceAtLeast(0L),
        )
        commitLyrics(updated.sortedBy { it.startUs })
        mutableUiState.update { it.copy(activeLineIndex = updated.indices.minByOrNull { updated[it].startUs } ?: -1) }
    }

    /** 添加一句：插到选中行后面，起点接着上一行。 */
    fun addLyricLine() {
        val current = mutableUiState.value
        val at = (current.activeLineIndex + 1).coerceIn(0, current.lyrics.lines.size)
        val startUs = current.lyrics.lines.getOrNull(at - 1)?.endUs ?: 0L
        insertLineAt(startUs, at - 1)
    }

    fun removeActiveLine() {
        val current = mutableUiState.value
        val index = current.activeLineIndex
        if (index !in current.lyrics.lines.indices) return
        val updated = current.lyrics.lines.toMutableList().apply { removeAt(index) }
        commitLyrics(updated)
        mutableUiState.update { it.copy(activeLineIndex = -1) }
    }

    /** 文本视图里直接改某行歌词。 */
    fun setLyricLineText(index: Int, text: String) {
        val current = mutableUiState.value
        val line = current.lyrics.lines.getOrNull(index) ?: return
        val updated = current.lyrics.lines.toMutableList()
        updated[index] = line.copy(text = text)
        commitLyrics(updated)
    }

    private fun insertLineAt(startUs: Long, afterIndex: Int) {
        val current = mutableUiState.value
        val updated = current.lyrics.lines.toMutableList()
        updated.add(
            (afterIndex + 1).coerceIn(0, updated.size),
            LyricLine(text = "", startUs = startUs.coerceAtLeast(0L), endUs = startUs + LINE_MIN_US, words = emptyList()),
        )
        commitLyrics(updated.sortedBy { it.startUs })
        mutableUiState.update { state ->
            state.copy(activeLineIndex = updated.indices.minByOrNull { updated[it].startUs } ?: -1)
        }
    }

    /** 歌词是源曲的共享数据，编辑结果只存覆盖层，绝不写回 SourceTrack。 */
    private fun commitLyrics(lines: List<LyricLine>) {
        pushHistory()
        mutate { current ->
            val ordered = lines.sortedBy { it.startUs }
            val track = LyricsTrack(ordered, current.lyrics.offsetMs)
            current.copy(lyrics = track, lyricsOverride = LrcCodec.toLrc(track))
        }
    }

    // ---- 撤销 / 重做 ----

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(snapshot())
        restore(undoStack.removeLast())
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(snapshot())
        restore(redoStack.removeLast())
    }

    /**
     * LyricsTrack / LyricLine 都是不可变数据类，快照只是一次引用拷贝，很便宜。
     * 存 50 步足够，多的从头丢。
     */
    private fun pushHistory() {
        undoStack.addLast(snapshot())
        if (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        syncHistoryFlags()
    }

    // ---- 逐字（打轴）编辑 ----

    fun setWordMode(enabled: Boolean) {
        if (!enabled) {
            closeWordMode()
            return
        }
        val current = mutableUiState.value
        val index = current.activeLineIndex
        if (index !in current.lyrics.lines.indices) {
            notify("先点一行歌词再进逐字模式")
            return
        }
        val line = current.lyrics.lines[index]
        if (line.words.isNotEmpty()) {
            mutate { it.copy(wordMode = true, activeWordIndex = 0) }
            return
        }
        // 没字级时间戳就从文本切一份，先按等分铺满，再靠打点修正
        val tokens = splitIntoTokens(line.text)
        if (tokens.isEmpty()) {
            notify("这一行没有可打点的字")
            return
        }
        pushHistory()
        val span = (line.endUs - line.startUs).coerceAtLeast(LINE_MIN_US)
        val words = tokens.mapIndexed { i: Int, token: String ->
            val start = line.startUs + (span * i / tokens.size)
            LyricWord(token, start, line.startUs + (span * (i + 1) / tokens.size))
        }
        val updated = current.lyrics.lines.toMutableList()
        updated[index] = line.copy(words = words)
        mutate {
            it.copy(
                lyrics = LyricsTrack(updated.sortedBy { line -> line.startUs }, current.lyrics.offsetMs),
                lyricsOverride = null,
                wordMode = true,
                activeWordIndex = 0,
            ).withOverride()
        }
    }

    /** 逐字打点：把当前字的时间戳打到当前播放位置，然后跳到下一个字。 */
    fun stampActiveWord(positionUs: Long) {
        val current = mutableUiState.value
        val lineIndex = current.activeLineIndex
        val line = current.lyrics.lines.getOrNull(lineIndex) ?: return
        if (line.words.isEmpty()) {
            notify("先切到逐字模式")
            return
        }
        ensureAudible()
        val wordIndex = current.activeWordIndex.coerceIn(0, line.words.lastIndex)
        pushHistory()
        val words = line.words.toMutableList()
        val previousEnd = words.getOrNull(wordIndex - 1)?.endUs ?: line.startUs
        val nextStart = words.getOrNull(wordIndex + 1)?.startUs ?: line.endUs
        // 不允许和相邻字交叉，否则逐字高亮会乱。
        // 末字时 nextStart-1 可能小于 previousEnd，coerceIn 遇到空区间会直接抛，
        // 所以先各自夹一次，不用 coerceIn。
        val start = positionUs
            .coerceAtLeast(previousEnd)
            .coerceAtMost(maxOf(previousEnd, nextStart - 1L))
        words[wordIndex] = words[wordIndex].copy(
            startUs = start,
            endUs = words[wordIndex + 1]?.startUs ?: (start + WORD_MIN_US),
        )
        if (wordIndex > 0) {
            words[wordIndex - 1] = words[wordIndex - 1].copy(endUs = start)
        }
        val updated = current.lyrics.lines.toMutableList()
        updated[lineIndex] = line.copy(
            words = words,
            startUs = words.first().startUs,
            endUs = words.last().endUs,
        )
        mutate {
            it.copy(
                lyrics = LyricsTrack(updated.sortedBy { it.startUs }, current.lyrics.offsetMs),
                activeWordIndex = (wordIndex + 1).coerceAtMost(words.lastIndex),
            ).withOverride()
        }
    }

    fun nudgeActiveWord(deltaUs: Long) {
        val current = mutableUiState.value
        val line = current.lyrics.lines.getOrNull(current.activeLineIndex) ?: return
        if (line.words.isEmpty()) return
        val wordIndex = current.activeWordIndex.coerceIn(0, line.words.lastIndex)
        pushHistory()
        val words = line.words.toMutableList()
        val target = words[wordIndex]
        val moved = target.copy(
            startUs = (target.startUs + deltaUs).coerceAtLeast(0L),
            endUs = (target.endUs + deltaUs).coerceAtLeast(0L),
        )
        words[wordIndex] = moved
        if (wordIndex > 0) {
            val previous = words[wordIndex - 1]
            words[wordIndex - 1] = previous.copy(endUs = moved.startUs.coerceAtLeast(previous.startUs))
        }
        if (wordIndex < words.lastIndex) {
            val next = words[wordIndex + 1]
            words[wordIndex + 1] = next.copy(startUs = moved.endUs.coerceAtMost(next.endUs))
        }
        val updated = current.lyrics.lines.toMutableList()
        updated[current.activeLineIndex] = line.copy(
            words = words,
            startUs = words.first().startUs,
            endUs = words.last().endUs,
        )
        mutate {
            it.copy(
                lyrics = LyricsTrack(updated.sortedBy { it.startUs }, current.lyrics.offsetMs),
            ).withOverride()
        }
    }

    /** 退出逐字模式：末尾没有封口的话补上，不然播放器永远停不到句末。 */
    private fun closeWordMode() = mutate { current ->
        val line = current.lyrics.lines.getOrNull(current.activeLineIndex)
        if (line == null || line.words.isEmpty()) {
            current.copy(wordMode = false, activeWordIndex = -1)
        } else {
            val updated = current.lyrics.lines.toMutableList()
            val words = line.words.toMutableList()
            if (words.last().endUs <= words.last().startUs) {
                words[words.lastIndex] = words.last().copy(endUs = words.last().startUs + WORD_MIN_US)
            }
            updated[current.activeLineIndex] = line.copy(words = words, endUs = words.last().endUs)
            current.copy(
                lyrics = LyricsTrack(updated.sortedBy { it.startUs }, current.lyrics.offsetMs),
                wordMode = false,
                activeWordIndex = -1,
            ).withOverride()
        }
    }

    /**
     * 中文逐字、英文按单词。整行连成一个字级时间戳没有意义，
     * 播放器高亮会一步跳完整句。
     */
    private fun splitIntoTokens(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        val latin = StringBuilder()
        fun flushLatin() {
            if (latin.isNotEmpty()) {
                tokens += latin.toString()
                latin.setLength(0)
            }
        }
        text.forEach { char ->
            when {
                char.isWhitespace() -> {
                    flushLatin()
                    tokens += char.toString()
                }

                char.code < 128 -> latin.append(char)
                else -> {
                    flushLatin()
                    tokens += char.toString()
                }
            }
        }
        flushLatin()
        return tokens
    }

    private fun EditWorkspaceUiState.withOverride(): EditWorkspaceUiState =
        copy(lyricsOverride = LrcCodec.toLrc(lyrics))

    private fun snapshot(): LyricSnapshot {
        val current = mutableUiState.value
        return LyricSnapshot(
            current.lyrics,
            current.lyricOffsetMs,
            current.activeLineIndex,
            current.activeWordIndex,
            current.wordMode,
        )
    }

    private fun restore(saved: LyricSnapshot) {
        mutate { current ->
            current.copy(
                lyrics = saved.lyrics,
                lyricOffsetMs = saved.lyricOffsetMs,
                activeLineIndex = saved.activeLineIndex.coerceIn(-1, saved.lyrics.lines.lastIndex),
                activeWordIndex = saved.activeWordIndex,
                wordMode = saved.wordMode,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
            ).withOverride()
        }
    }

    private fun syncHistoryFlags() = mutate { current ->
        current.copy(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
        )
    }

    private data class LyricSnapshot(
        val lyrics: LyricsTrack,
        val lyricOffsetMs: Long,
        val activeLineIndex: Int,
        val activeWordIndex: Int,
        val wordMode: Boolean,
    )

    fun notify(message: String) {
        mutableUiState.update { it.copy(message = message) }
    }
    fun selectTrack(trackId: String) {
        viewModelScope.launch {
            sourceTrackRepository.getById(trackId)?.let { loadTrack(it) }
        }
    }

    private suspend fun loadTrack(track: SourceTrack) {
        val durationMs = track.durationMs ?: 0L
        mutableUiState.update { current ->
            current.copy(
                track = track,
                startMs = 0L,
                endMs = durationMs,
                joinedTrackIds = if (operation == EditOperation.JOIN) {
                    (listOf(track.id) + initialJoinedTrackIds).distinct().take(MAX_JOIN_SOURCES)
                } else {
                    emptyList()
                },
                lyrics = lyricsParser.parse(track.lyrics),
            ).recalculated()
        }
        track.localPath?.let(audioPlayer::loadFile)
        loadWaveform(track)
        if (operation == EditOperation.JOIN) refreshJoinedTracks()
    }

    fun setSelection(selection: WaveformSelection) = mutate {
        it.copy(startMs = selection.startUs / 1000L, endMs = selection.endUs / 1000L)
    }

    fun setGainDb(value: Float) = mutate { it.copy(gainDb = value) }
    fun setFadeInMs(value: Long) = mutate { it.copy(fadeInMs = value.coerceAtLeast(0L)) }
    fun setFadeOutMs(value: Long) = mutate { it.copy(fadeOutMs = value.coerceAtLeast(0L)) }
    fun setLyricOffsetMs(value: Long) {
        pushHistory()
        mutate { it.copy(lyricOffsetMs = value) }
    }

    fun setJoinTransition(value: JoinTransition) =
        mutableUiState.update { it.copy(joinTransition = value) }

    fun setTransitionMs(value: Long) =
        mutableUiState.update { it.copy(transitionMs = value.coerceIn(0L, 10_000L)) }

    fun toggleNormalizeSources() {
        val current = mutableUiState.value
        mutableUiState.update { it.copy(normalizeSources = !current.normalizeSources) }
    }

    /** 插入空白音乐：末尾追加一段静音，可反复点击叠加。 */
    fun appendSilence() {
        val current = mutableUiState.value
        val added = (current.trailingSilenceMs / SILENCE_STEP_MS + 1L) * SILENCE_STEP_MS
        if (added > MAX_TRAILING_SILENCE_MS) {
            notify("末尾空白最多 ${MAX_TRAILING_SILENCE_MS / 1000} 秒")
            return
        }
        mutableUiState.update { it.copy(trailingSilenceMs = added, message = "末尾空白 ${added / 1000} 秒") }
    }

    fun togglePreviewRealEffect() {
        val current = mutableUiState.value
        mutableUiState.update { current.copy(previewRealEffect = !current.previewRealEffect) }
    }

    fun setStartMs(value: Long) {
        val clamped = value.coerceIn(0L, mutableUiState.value.endMs)
        mutate { it.copy(startMs = clamped) }
        previewFrom(clamped)
    }

    fun setEndMs(value: Long) {
        val clamped = value.coerceIn(mutableUiState.value.startMs, Long.MAX_VALUE)
        mutate { it.copy(endMs = clamped) }
        previewFrom(clamped)
    }

    fun toggleAutoPlayOnChange() = mutate { it.copy(autoPlayOnChange = !it.autoPlayOnChange) }

    /** 「改动时间自动播放」：拖完起止点直接从该处试听，省一次手点播放。 */
    private fun previewFrom(positionMs: Long) {
        if (!mutableUiState.value.autoPlayOnChange) return
        audioPlayer.seekTo(positionMs)
        audioPlayer.play()
    }

    fun setFadeCurve(curve: FadeCurve) = mutate { it.copy(fadeCurve = curve) }

    fun setExportFormat(format: ExportFormat) = mutate { it.copy(exportFormat = format) }

    fun setGainScale(scale: GainScale) = mutate { it.copy(gainScale = scale) }

    /** 步进按钮给的是当前显示单位的值，换算回 dB 再存。 */
    fun setGainDisplay(display: Float) = mutate {
        it.copy(gainDb = it.gainScale.toGainDb(display, it.gainDb))
    }

    /** 已经在播就当停止用，省得再渲染一遍。 */
    fun togglePreview() {
        if (mutableUiState.value.isPreviewing) return
        if (playback.value.isPlaying) audioPlayer.pause() else previewEditedResult()
    }

    /**
     * 合成页的试听：勾着「试听真实效果」就渲染整条合成链，
     * 取消勾选则直接播第一首原文件（不渲染）。
     */
    fun toggleJoinPreview() {
        if (mutableUiState.value.isPreviewing) return
        val current = mutableUiState.value
        if (!current.previewRealEffect) {
            val first = current.joinedTrackIds.firstOrNull() ?: return
            viewModelScope.launch {
                val track = sourceTrackRepository.getById(first)
                val path = track?.localPath
                if (path.isNullOrBlank()) {
                    notify("选中的歌曲已失效，请重新选择")
                } else {
                    audioPlayer.loadFile(path)
                    audioPlayer.play()
                }
            }
            return
        }
        togglePreview()
    }

    /**
     * 试听真实效果：把当前参数渲染成临时 WAV 再播，
     * 播的是原文件的话根本听不出淡入淡出。
     */
    fun previewEditedResult() {
        if (mutableUiState.value.isPreviewing) return
        val current = mutableUiState.value
        viewModelScope.launch {
            mutableUiState.update { it.copy(isPreviewing = true) }
            val result = if (operation == EditOperation.JOIN) previewJoinedResult(current) else previewSingleTrack(current)
            result.fold(
                onSuccess = { file ->
                    audioPlayer.loadFile(file.absolutePath)
                    audioPlayer.play()
                },
                onFailure = { error ->
                    notify("试听渲染失败：${error.message ?: "未知错误"}")
                },
            )
            mutableUiState.update { it.copy(isPreviewing = false) }
        }
    }

    private suspend fun previewSingleTrack(current: EditWorkspaceUiState): Result<File> {
        val path = current.track?.localPath
        if (path.isNullOrBlank() || current.segments.isEmpty()) {
            return Result.failure(IllegalStateException("当前参数没有产生任何区间"))
        }
        return previewRenderer.render(
            sourcePath = path,
            segments = current.segments,
            gainDb = current.gainDb,
            fadeInUs = current.fadeInMs * MICROS_PER_MILLI,
            fadeOutUs = current.fadeOutMs * MICROS_PER_MILLI,
            fadeCurve = current.fadeCurve,
        )
    }

    /** 「试听真实效果」：把整条合成链渲染出来，接缝斜坡和末尾空白都能听见。 */
    private suspend fun previewJoinedResult(current: EditWorkspaceUiState): Result<File> {
        val sources = joinExportSources(current)
            ?: return Result.failure(IllegalStateException("选中的歌曲已失效，请重新选择"))
        if (sources.size < 2) {
            return Result.failure(IllegalStateException("合成至少需要两首可用歌曲"))
        }
        val plan = ExportPlanner.plan(
            ExportJob(
                jobId = "preview",
                editProjectId = "preview",
                outputTempPath = "preview.wav",
                sources = sources,
                gainDb = current.gainDb,
                format = current.exportFormat,
                fadeInMs = current.fadeInMs,
                fadeOutMs = current.fadeOutMs,
                fadeCurve = current.fadeCurve,
                lyricOffsetMs = current.lyricOffsetMs,
                joinTransition = current.joinTransition,
                transitionMs = current.transitionMs,
                normalizeSources = false,
                trailingSilenceMs = current.trailingSilenceMs,
            ),
        )
        return previewRenderer.renderPlan(plan, current.gainDb)
    }

    /** 重置参数：只回数值参数，保留用户选中的区间。 */
    fun resetParams() = mutate {
        it.copy(
            fadeInMs = 0L,
            fadeOutMs = 0L,
            fadeCurve = FadeCurve.LINEAR,
            gainDb = 0f,
            gainScale = GainScale.DECIBEL,
            lyricOffsetMs = 0L,
        )
    }

    fun toggleJoinedTrack(trackId: String) {
        if (trackId !in mutableUiState.value.joinedTrackIds &&
            mutableUiState.value.joinedTrackIds.size >= MAX_JOIN_SOURCES
        ) {
            notify("最多只能合成 $MAX_JOIN_SOURCES 首歌曲")
            return
        }
        mutate { current ->
            val joined = if (trackId in current.joinedTrackIds) {
                current.joinedTrackIds - trackId
            } else {
                current.joinedTrackIds + trackId
            }
            current.copy(joinedTrackIds = joined)
        }
        viewModelScope.launch { refreshJoinedTracks() }
    }

    fun togglePlay() {
        if (playback.value.isPlaying) audioPlayer.pause() else audioPlayer.play()
    }

    fun seekTo(positionMs: Long) = audioPlayer.seekTo(positionMs)

    fun pausePlayback() = audioPlayer.pause()

    /**
     * 打点必须听得见：没在播就从本行开头起播。
     * 停在原地等用户先点播放再回来打点，节奏对不上，逐字就没法用。
     */
    private fun ensureAudible() {
        if (playback.value.isPlaying) return
        val line = mutableUiState.value.lyrics.lines
            .getOrNull(mutableUiState.value.activeLineIndex)
        val from = line?.startUs?.div(1000L) ?: playback.value.positionMs
        audioPlayer.seekTo(from)
        audioPlayer.play()
    }

    fun save() {
        viewModelScope.launch {
            if (ensureProjectSaved() != null) notify("已保存编辑项目")
        }
    }

    fun startExport() {
        val current = mutableExportState.value
        if (!current.isIdle) return
        viewModelScope.launch {
            val projectId = ensureProjectSaved() ?: return@launch
            val job = buildExportJob(projectId) ?: return@launch
            val fileName = mutableUiState.value.exportFileName
            mutableExportState.update { reduceExport(it, ExportEvent.Started(job.jobId, fileName)) }
            encoderClient.export(job) { result -> onExportResult(job, fileName, result) }
        }
    }

    fun cancelExport() {
        mutableExportState.value.jobId?.let(encoderClient::cancel)
    }

    fun resetExportFeedback() {
        if (mutableExportState.value.isIdle) {
            mutableExportState.update { reduceExport(it, ExportEvent.Reset) }
        }
    }

    fun consumeMessage() {
        mutableUiState.update { it.copy(message = null) }
    }

    // 删 SourceTrack 不动 edit_projects：历史记录要靠源曲谱系回溯。
    fun deleteTracks(ids: List<String>, deleteFiles: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            ids.forEach { id ->
                runCatching {
                    val track = sourceTrackRepository.getById(id) ?: return@runCatching
                    if (deleteFiles) {
                        File(track.localPath).takeIf { it.isFile }?.delete()
                    }
                    sourceTrackRepository.delete(id)
                }.onFailure { failed++ }
            }
            mutableUiState.update { current ->
                current.copy(
                    message = if (failed == 0) {
                        "已删除 ${ids.size} 首歌曲"
                    } else {
                        "已删除 ${ids.size - failed} 首，$failed 首失败"
                    },
                )
            }
        }
    }

    private fun loadWaveform(track: SourceTrack) {
        val path = track.localPath
        if (path.isNullOrBlank()) {
            mutableUiState.update { it.copy(waveform = WaveformUiState.Error("源音频不可用，无法生成波形")) }
            return
        }
        viewModelScope.launch {
            // 边解码边把已解出的部分推给界面，最后一次是完整波形。
            val result = runCatching {
                waveformSource.peaks(track.id, File(path)) { partial ->
                    mutableUiState.update { current ->
                        if (current.waveform is WaveformUiState.Error) current
                        else current.copy(waveform = WaveformUiState.Ready(partial))
                    }
                }
            }
            mutableUiState.update { current ->
                val waveform = result.fold(
                    onSuccess = { peaks -> WaveformUiState.Ready(peaks) },
                    onFailure = { WaveformUiState.Error(it.message ?: "波形生成失败") },
                )
                current.copy(waveform = waveform).recalculated()
            }
        }
    }

    private suspend fun refreshJoinedTracks() {
        val resolved = mutableListOf<SourceTrack>()
        for (id in mutableUiState.value.joinedTrackIds) {
            sourceTrackRepository.getById(id)?.let(resolved::add)
        }
        // 加歌减歌都要重算总时长，否则页面上还是旧长度
        mutableUiState.update { it.copy(joinedTracks = JoinSourceOrder.order(resolved)).recalculated() }
    }

    private suspend fun ensureProjectSaved(): String? {
        mutableUiState.value.savedProjectId?.let { return it }
        val current = mutableUiState.value
        if (!current.canSave) {
            notify("当前参数还不能生成可保存的编辑项目")
            return null
        }
        mutableUiState.update { it.copy(isSaving = true) }
        val projectId = UUID.randomUUID().toString()
        val result = runCatching {
            editProjectRepository.upsert(current.toProject(projectId, System.currentTimeMillis()))
        }
        return result.fold(
            onSuccess = {
                mutableUiState.update { it.copy(isSaving = false, savedProjectId = projectId) }
                projectId
            },
            onFailure = {
                mutableUiState.update { state -> state.copy(isSaving = false) }
                notify("保存编辑项目失败：${it.message ?: "未知错误"}")
                null
            },
        )
    }

    /**
     * 合成的源序列。导出和试听都走它，两边看到的顺序必然一致。
     *
     * 非 JOIN 操作返回 null，表示「这里不适用」，由调用方走单轨分支。
     * 早先这里返回空 list，而调用方用 `?: tracks.map { }` 兜底 ——
     * 空 list 不是 null，兜底不生效，剪切/淡入淡出全都拿到空源，
     * 最后报「没有可用的源音频」。
     */
    private suspend fun joinExportSources(current: EditWorkspaceUiState): List<ExportSource>? {
        if (current.operation != EditOperation.JOIN) return null
        val resolved = mutableListOf<SourceTrack>()
        for (id in current.joinedTrackIds) {
            sourceTrackRepository.getById(id)?.let(resolved::add) ?: return null
        }
        return JoinSourceOrder.order(resolved).map { track ->
            track.toExportSource(
                segments = current.segmentsOf(track),
                // 只有被编辑的那条轨才带覆盖层，合成时其余轨用自带歌词
                lyricsOverride = current.lyricsOverride.takeIf { track.id == current.track?.id },
            )
        }
    }

    private suspend fun buildExportJob(projectId: String): ExportJob? {
        val current = mutableUiState.value
        val tracks = if (operation == EditOperation.JOIN) {
            val resolved = mutableListOf<SourceTrack>()
            for (id in current.joinedTrackIds) {
                sourceTrackRepository.getById(id)?.let(resolved::add)
            }
            JoinSourceOrder.order(resolved)
        } else {
            listOfNotNull(current.track)
        }
        val sources = joinExportSources(current)
            ?: tracks.map { track ->
                track.toExportSource(
                    segments = current.segmentsOf(track),
                    lyricsOverride = current.lyricsOverride.takeIf { track.id == current.track?.id },
                )
            }
        if (sources.isEmpty() && operation != EditOperation.JOIN) {
            notify("没有可用的源音频，无法导出")
            return null
        }
        if (sources.any { it.localPath.isBlank() }) {
            notify("存在不可用的源音频，无法导出")
            return null
        }
        if (operation == EditOperation.JOIN && sources.size < 2) {
            notify("合成至少需要两首可用歌曲")
            return null
        }
        if (sources.any { it.segments.isEmpty() }) {
            notify("当前时间线为空，无法导出")
            return null
        }
        if (!exportTempDirectory.exists()) exportTempDirectory.mkdirs()
        return ExportJob(
            jobId = UUID.randomUUID().toString(),
            editProjectId = projectId,
            outputTempPath = File(
                exportTempDirectory,
                "${UUID.randomUUID()}.${current.exportFormat.extension}",
            ).path,
            sources = sources,
            gainDb = current.gainDb,
            format = current.exportFormat,
            fadeInMs = current.fadeInMs,
            fadeCurve = current.fadeCurve,
            fadeOutMs = current.fadeOutMs,
            lyricOffsetMs = current.lyricOffsetMs,
            joinTransition = current.joinTransition,
            transitionMs = current.transitionMs,
            normalizeSources = current.normalizeSources,
            trailingSilenceMs = current.trailingSilenceMs,
        )
    }

    private fun onExportResult(job: ExportJob, fileName: String, result: ExportResult) {
        when (result) {
            is ExportResult.Failed ->
                mutableExportState.update { reduceExport(it, ExportEvent.Failed(result.reason)) }

            is ExportResult.Completed -> viewModelScope.launch {
                // 落盘走设置里指定的下载目录，不再弹 SAF 选择框。
                // 历史记录由 publish() 用真实落点写，这里不用再管。
                runCatching { exportPublisher.publish(result.outputPath, fileName, historyOf(job, result)) }.fold(
                    onSuccess = { published ->
                        mutableExportState.update { reduceExport(it, ExportEvent.Succeeded(published.bytes)) }
                    },
                    onFailure = { failure ->
                        val reason = failure.message ?: "未知错误"
                        mutableExportState.update {
                            reduceExport(it, ExportEvent.Failed("保存到下载目录失败：$reason"))
                        }
                    },
                )
            }
        }
    }

    private fun mutate(transform: (EditWorkspaceUiState) -> EditWorkspaceUiState) {
        mutableUiState.update { transform(it).recalculated() }
    }

    private fun EditWorkspaceUiState.recalculated(): EditWorkspaceUiState {
        // 合成是多源的，时长必须把所有源累加。公式和 ExportPlanner.plan 完全一致，
        // 否则页面显示的时长和导出的文件对不上。
        if (operation == EditOperation.JOIN) {
            return copy(segments = emptyList(), outputDurationUs = joinedOutputDurationUs())
        }
        val durationUs = (track?.durationMs ?: 0L) * 1000L
        val ranges = EditTimelineCalculator.segments(
            durationUs = durationUs,
            selections = selections(),
            mode = EditMode.KEEP_SELECTED,
        )
        var cursorUs = 0L
        val segments = ranges.map { range ->
            EditTimeSegment(
                sourceStartUs = range.startUs,
                sourceEndUs = range.endUs,
                outputStartUs = cursorUs,
                outputEndUs = cursorUs + (range.endUs - range.startUs),
            ).also { cursorUs = it.outputEndUs }
        }
        return copy(segments = segments, outputDurationUs = cursorUs)
    }

    /**
     * 合成输出总时长：各源时长之和 + 无损衔接的间隙 + 末尾空白。
     * 斜坡/正常衔接是重叠淡化，不改总长，所以只有 PRESERVE 才加。
     */
    private fun EditWorkspaceUiState.joinedOutputDurationUs(): Long {
        val sources = if (joinedTracks.isNotEmpty()) joinedTracks else listOfNotNull(track)
        var totalUs = sources.sumOf { (it.durationMs ?: 0L) * 1000L }
        if (joinTransition == JoinTransition.PRESERVE && sources.size > 1) {
            totalUs += transitionMs.coerceAtLeast(0L) * 1000L * (sources.size - 1)
        }
        return totalUs + trailingSilenceMs.coerceAtLeast(0L) * 1000L
    }

    private fun EditWorkspaceUiState.selections(): List<EditTimeRange> = when (operation) {
        EditOperation.TRIM -> listOf(EditTimeRange(startMs * 1000L, endMs * 1000L))
        else -> listOf(EditTimeRange(0L, (track?.durationMs ?: 0L) * 1000L))
    }

    private fun EditWorkspaceUiState.segmentsOf(track: SourceTrack): List<ExportSegment> = when (operation) {
        EditOperation.JOIN -> listOf(ExportSegment(0L, (track.durationMs ?: 0L) * 1000L))
        else -> segments.map { ExportSegment(it.sourceStartUs, it.sourceEndUs) }
    }

    private fun EditWorkspaceUiState.toProject(id: String, now: Long): EditProject = EditProject(
        id = id,
        sourceTrackId = checkNotNull(track).id,
        type = recordType(),
        segments = segments.map { EditTimeRange(it.sourceStartUs, it.sourceEndUs) },
        gainDb = gainDb.takeIf { operation == EditOperation.GAIN },
        fadeInMs = fadeInMs.takeIf { operation == EditOperation.FADE_IN || operation == EditOperation.FADE_OUT },
        fadeOutMs = fadeOutMs.takeIf { operation == EditOperation.FADE_IN || operation == EditOperation.FADE_OUT },
        lyricOffsetMs = lyricOffsetMs.takeIf { operation == EditOperation.LYRIC_OFFSET },
        lyricsOverride = lyricsOverride.takeIf { operation == EditOperation.LYRIC_OFFSET },
        joinedTrackIds = joinedTrackIds.takeIf { operation == EditOperation.JOIN }.orEmpty(),
        joinTransition = if (operation == EditOperation.JOIN) joinTransition else JoinTransition.NORMAL,
        transitionMs = if (operation == EditOperation.JOIN) transitionMs else 0L,
        normalizeSources = if (operation == EditOperation.JOIN) normalizeSources else false,
        trailingSilenceMs = if (operation == EditOperation.JOIN) trailingSilenceMs else 0L,
        fadeCurve = fadeCurve,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
    )

    private fun EditWorkspaceUiState.recordType(): EditOperation = when {        operation != EditOperation.FADE_IN -> operation
        fadeInMs > 0L -> EditOperation.FADE_IN
        else -> EditOperation.FADE_OUT
    }

    private fun SourceTrack.toExportSource(
        segments: List<ExportSegment>,
        lyricsOverride: String?,
    ): ExportSource = ExportSource(
        id = id,
        localPath = localPath.orEmpty(),
        title = title,
        artist = artist,
        album = album,
        lyrics = lyrics,
        lyricsOverride = lyricsOverride,
        segments = segments,
    )

    companion object {
        private const val MP3_FORMAT = "mp3"
        private const val RECORD_WAIT_MS = 2_000L
        private const val MICROS_PER_MILLI = 1_000L
        const val LINE_MIN_US = 1_000_000L
        private const val WORD_MIN_US = 60_000L
        private const val HISTORY_LIMIT = 50
        private val UNSAFE_FILE_NAME = Regex("[\\\\/:*?\"<>|]")

        fun factory(
            operation: EditOperation,
            sourceTrackId: String,
            joinedTrackIds: List<String>,
            exportTempDirectory: File,
            sourceTrackRepository: SourceTrackRepository,
            editProjectRepository: EditProjectRepository,
            exportPackageRepository: ExportPackageRepository,
            audioPlayer: AudioPlayer,
            waveformSource: WaveformSource,
            lyricsParser: LyricsParser,
            encoderClient: EncoderClient,
            exportPublisher: ExportPublisher,
            previewRenderer: EditPreviewRenderer,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(EditWorkspaceViewModel::class.java)) {
                    throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
                }
                @Suppress("UNCHECKED_CAST")
                return EditWorkspaceViewModel(
                    operation = operation,
                    sourceTrackId = sourceTrackId,
                    initialJoinedTrackIds = joinedTrackIds,
                    exportTempDirectory = exportTempDirectory,
                    sourceTrackRepository = sourceTrackRepository,
                    editProjectRepository = editProjectRepository,
                    exportPackageRepository = exportPackageRepository,
                    audioPlayer = audioPlayer,
                    waveformSource = waveformSource,
                    lyricsParser = lyricsParser,
                    encoderClient = encoderClient,
                    exportPublisher = exportPublisher,
                    previewRenderer = previewRenderer,
                ) as T
            }
        }
    }
}

/** 每次「插入空白音乐」加 2 秒，够用又不至于把文件撑爆。 */
private const val SILENCE_STEP_MS = 2_000L
private const val MAX_TRAILING_SILENCE_MS = 60_000L
