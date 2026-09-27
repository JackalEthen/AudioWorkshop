package cn.qishui.tool.feature.edit

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.qishui.tool.data.encoder.EncoderClient
import cn.qishui.tool.data.encoder.EncoderState
import cn.qishui.tool.domain.AudioPlayer
import cn.qishui.tool.domain.EditProjectRepository
import cn.qishui.tool.domain.ExportPackageRepository
import cn.qishui.tool.domain.PlaybackSnapshot
import cn.qishui.tool.domain.SourceTrackRepository
import cn.qishui.tool.domain.edit.EditTimelineCalculator
import cn.qishui.tool.domain.lyrics.LyricsParser
import cn.qishui.tool.data.media.LrcCodec
import cn.qishui.tool.domain.media.ExportJob
import cn.qishui.tool.domain.media.ExportResult
import cn.qishui.tool.domain.media.ExportSegment
import cn.qishui.tool.domain.media.ExportSource
import cn.qishui.tool.domain.model.EditMode
import cn.qishui.tool.domain.model.EditOperation
import cn.qishui.tool.domain.model.EditProject
import cn.qishui.tool.domain.model.JoinTransition
import cn.qishui.tool.domain.model.EditTimeRange
import cn.qishui.tool.domain.model.EditTimeSegment
import cn.qishui.tool.domain.model.ExportPackage
import cn.qishui.tool.domain.model.ExportValidationStatus
import cn.qishui.tool.domain.model.LyricsTrack
import cn.qishui.tool.domain.model.SourceTrack
import cn.qishui.tool.domain.model.WaveformPeaks
import cn.qishui.tool.domain.waveform.WaveformSource
import cn.qishui.tool.feature.edit.export.ExportEvent
import cn.qishui.tool.feature.edit.export.ExportTargetWriter
import cn.qishui.tool.feature.edit.export.ExportUiState
import cn.qishui.tool.feature.edit.export.JoinSourceOrder
import cn.qishui.tool.feature.edit.export.MAX_JOIN_SOURCES
import cn.qishui.tool.feature.edit.export.reduceExport
import cn.qishui.tool.feature.edit.waveform.WaveformSelection
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
    val splitMs: Long = 0L,
    val splitPointsMs: List<Long> = emptyList(),
    val mode: EditMode = EditMode.KEEP_SELECTED,
    val gainDb: Float = 0f,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val lyricOffsetMs: Long = 0L,
    val joinedTrackIds: List<String> = emptyList(),
    val joinedTracks: List<SourceTrack> = emptyList(),
    val joinTransition: JoinTransition = JoinTransition.NORMAL,
    val transitionMs: Long = 2000L,
    val previewRealEffect: Boolean = true,
    val segments: List<EditTimeSegment> = emptyList(),
    val outputDurationUs: Long = 0L,
    val lyrics: LyricsTrack = LyricsTrack.EMPTY,
    val waveform: WaveformUiState = WaveformUiState.Loading,
    val savedProjectId: String? = null,
    val isSaving: Boolean = false,
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
            return base.replace(UNSAFE_FILE_NAME, "_") + ".mp3"
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
    private val exportTargetWriter: ExportTargetWriter,
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
            val track = sourceTrackRepository.importLocalAudio(uri).getOrElse { error ->
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
        mutableUiState.update { it.copy(lyrics = parsed) }
    }

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
                splitMs = durationMs / 2L,
                mode = EditMode.KEEP_SELECTED,
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

    fun setSplitMs(value: Long) = mutate { it.copy(splitMs = value.coerceAtLeast(0L)) }
    fun setMode(mode: EditMode) = mutate { it.copy(mode = mode) }

    fun addSplitPoint() = mutate { current ->
        val durationMs = current.track?.durationMs ?: 0L
        val point = current.splitMs.coerceIn(0L, durationMs)
        if (point <= 0L || point >= durationMs) {
            current
        } else {
            current.copy(splitPointsMs = (current.splitPointsMs + point).distinct().sorted())
        }
    }

    fun removeSplitPoint(value: Long) = mutate { current ->
        current.copy(splitPointsMs = current.splitPointsMs - value)
    }
    fun setGainDb(value: Float) = mutate { it.copy(gainDb = value) }
    fun setFadeInMs(value: Long) = mutate { it.copy(fadeInMs = value.coerceAtLeast(0L)) }
    fun setFadeOutMs(value: Long) = mutate { it.copy(fadeOutMs = value.coerceAtLeast(0L)) }
    fun setLyricOffsetMs(value: Long) = mutate { it.copy(lyricOffsetMs = value) }

    fun setJoinTransition(value: JoinTransition) =
        mutableUiState.update { it.copy(joinTransition = value) }

    fun setTransitionMs(value: Long) =
        mutableUiState.update { it.copy(transitionMs = value.coerceIn(0L, 10_000L)) }

    fun togglePreviewRealEffect() {
        val current = mutableUiState.value
        mutableUiState.update { current.copy(previewRealEffect = !current.previewRealEffect) }
    }

    fun setStartMs(value: Long) = mutate { it.copy(startMs = value.coerceIn(0L, it.endMs)) }

    fun setEndMs(value: Long) = mutate { it.copy(endMs = value.coerceIn(it.startMs, it.endMs)) }

    /** 重置参数：只回数值参数，保留用户选中的区间和分割点。 */
    fun resetParams() = mutate {
        it.copy(
            mode = EditMode.KEEP_SELECTED,
            splitMs = 0L,
            fadeInMs = 0L,
            fadeOutMs = 0L,
            gainDb = 0f,
            lyricOffsetMs = 0L,
        )
    }

    fun toggleJoinedTrack(trackId: String) {
        if (trackId !in mutableUiState.value.joinedTrackIds &&
            mutableUiState.value.joinedTrackIds.size >= MAX_JOIN_SOURCES
        ) {
            notify("最多只能拼接 $MAX_JOIN_SOURCES 首歌曲")
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

    fun save() {
        viewModelScope.launch {
            if (ensureProjectSaved() != null) notify("已保存编辑项目")
        }
    }

    fun startExport(targetUri: String) {
        val current = mutableExportState.value
        if (!current.isIdle) return
        viewModelScope.launch {
            val projectId = ensureProjectSaved() ?: return@launch
            val job = buildExportJob(projectId) ?: return@launch
            mutableExportState.update { reduceExport(it, ExportEvent.Started(job.jobId, targetUri)) }
            encoderClient.export(job) { result -> onExportResult(job, targetUri, result) }
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

    /** 勾了才删音频；不勾只清记录，编辑项目本身仍按源曲谱系保留可查。 */
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
        mutableUiState.update { it.copy(joinedTracks = JoinSourceOrder.order(resolved)) }
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
        val sources = tracks.map { track -> track.toExportSource(current.segmentsOf(track)) }
        if (sources.any { it.localPath.isBlank() }) {
            notify("存在不可用的源音频，无法导出")
            return null
        }
        if (operation == EditOperation.JOIN && sources.size < 2) {
            notify("拼接至少需要两首可用歌曲")
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
            outputTempPath = File(exportTempDirectory, "${UUID.randomUUID()}.mp3").path,
            sources = sources,
            gainDb = current.gainDb,
            fadeInMs = current.fadeInMs,
            fadeOutMs = current.fadeOutMs,
            lyricOffsetMs = current.lyricOffsetMs,
        )
    }

    private fun onExportResult(job: ExportJob, targetUri: String, result: ExportResult) {
        when (result) {
            is ExportResult.Failed ->
                mutableExportState.update { reduceExport(it, ExportEvent.Failed(result.reason)) }

            is ExportResult.Completed -> viewModelScope.launch {
                val copied = runCatching {
                    exportTargetWriter.copy(result.outputPath, Uri.parse(targetUri))
                }
                copied.fold(
                    onSuccess = { bytes ->
                        recordExport(job, result, targetUri)
                        mutableExportState.update { reduceExport(it, ExportEvent.Succeeded(bytes)) }
                    },
                    onFailure = { failure ->
                        val reason = failure.message ?: "未知错误"
                        mutableExportState.update { reduceExport(it, ExportEvent.Failed("写入所选位置失败：$reason")) }
                    },
                )
            }
        }
    }

    private suspend fun recordExport(job: ExportJob, result: ExportResult.Completed, targetUri: String) {
        val recorded = awaitRecord(job.editProjectId, result.outputPath)
        val pkg = recorded ?: ExportPackage(
            sourceEditProjectId = job.editProjectId,
            outputPath = result.outputPath,
            format = MP3_FORMAT,
            durationMs = result.durationMs,
            sizeBytes = result.sizeBytes,
            createdAt = System.currentTimeMillis(),
            validationStatus = ExportValidationStatus.PASSED,
        )
        exportPackageRepository.upsert(pkg.copy(outputPath = targetUri))
        if (recorded != null) {
            exportPackageRepository.delete(job.editProjectId, result.outputPath)
        }
    }

    private suspend fun awaitRecord(editProjectId: String, outputPath: String): ExportPackage? {
        val packages = withTimeoutOrNull(RECORD_WAIT_MS) {
            exportPackageRepository.observeAll().first { list ->
                list.any { it.sourceEditProjectId == editProjectId && it.outputPath == outputPath }
            }
        } ?: return null
        return packages.firstOrNull { it.sourceEditProjectId == editProjectId && it.outputPath == outputPath }
    }

    private fun mutate(transform: (EditWorkspaceUiState) -> EditWorkspaceUiState) {
        mutableUiState.update { transform(it).recalculated() }
    }

    private fun EditWorkspaceUiState.recalculated(): EditWorkspaceUiState {
        val durationUs = (track?.durationMs ?: 0L) * 1000L
        val ranges = EditTimelineCalculator.segments(
            durationUs = durationUs,
            selections = selections(),
            mode = if (operation == EditOperation.SPLIT) mode else EditMode.KEEP_SELECTED,
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

    private fun EditWorkspaceUiState.selections(): List<EditTimeRange> = when (operation) {
        EditOperation.TRIM -> listOf(EditTimeRange(startMs * 1000L, endMs * 1000L))
        EditOperation.SPLIT -> when (mode) {
            EditMode.KEEP_SELECTED -> EditTimelineCalculator.splitIntervals(
                durationUs = (track?.durationMs ?: 0L) * 1000L,
                cutPointsUs = splitPointsMs.map { it * 1000L },
            )
            EditMode.REMOVE_SELECTED -> emptyList()
        }
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
        joinedTrackIds = joinedTrackIds.takeIf { operation == EditOperation.JOIN }.orEmpty(),
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
    )

    private fun EditWorkspaceUiState.recordType(): EditOperation = when {
        operation != EditOperation.FADE_IN -> operation
        fadeInMs > 0L -> EditOperation.FADE_IN
        else -> EditOperation.FADE_OUT
    }

    private fun SourceTrack.toExportSource(segments: List<ExportSegment>): ExportSource = ExportSource(
        id = id,
        localPath = localPath.orEmpty(),
        title = title,
        artist = artist,
        album = album,
        lyrics = lyrics,
        segments = segments,
    )

    companion object {
        private const val MP3_FORMAT = "mp3"
        private const val RECORD_WAIT_MS = 2_000L
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
            exportTargetWriter: ExportTargetWriter,
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
                    exportTargetWriter = exportTargetWriter,
                ) as T
            }
        }
    }
}
