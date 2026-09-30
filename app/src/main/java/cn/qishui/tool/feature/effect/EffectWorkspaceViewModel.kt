package cn.qishui.tool.feature.effect

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.qishui.tool.data.encoder.EncoderClient
import cn.qishui.tool.data.media.AudioFileProbe
import cn.qishui.tool.domain.AudioPlayer
import cn.qishui.tool.domain.PlaybackSnapshot
import cn.qishui.tool.domain.SourceTrackRepository
import cn.qishui.tool.domain.media.ExportFormat
import cn.qishui.tool.domain.download.DownloadTargetResolver
import cn.qishui.tool.domain.media.ExportJob
import cn.qishui.tool.domain.media.ExportResult
import cn.qishui.tool.domain.media.ExportSegment
import cn.qishui.tool.domain.media.ExportSource
import cn.qishui.tool.domain.model.SourceTrack
import cn.qishui.tool.feature.edit.export.ExportEvent
import cn.qishui.tool.feature.edit.export.ExportTargetWriter
import cn.qishui.tool.feature.edit.export.ExportUiState
import cn.qishui.tool.feature.edit.export.reduceExport
import cn.qishui.tool.media.effect.EffectDefinition
import cn.qishui.tool.media.effect.EffectProcessor
import cn.qishui.tool.media.effect.EffectRegistry
import cn.qishui.tool.media.effect.PcmBuffer
import cn.qishui.tool.media.effect.SegmentRange
import cn.qishui.tool.media.pcm.PcmChunkReader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EffectUiState(
    val definition: EffectDefinition,
    val tracks: List<SourceTrack> = emptyList(),
    val selectedTrackIds: List<String> = emptyList(),
    val values: Map<String, Float> = emptyMap(),
    /** 分段变调 / 分段修改音量 的片段列表，每段带自己的参数值。 */
    val segments: List<SegmentRange> = emptyList(),
    val isProcessing: Boolean = false,
    val isPreviewing: Boolean = false,
    /** 选中曲目的声道数，反转相位要按声道分别处理，先探一次。 */
    val channels: Int = 0,
    val exportFormat: ExportFormat = ExportFormat.MP3,
    val presetName: String = "",
    /** 多输出效果（立体声分离）已渲染的两个文件，供各自的试听按钮使用。 */
    val splitFiles: List<File> = emptyList(),
    val playingSplitIndex: Int = -1,
    val progress: Float = 0f,
    val message: String? = null,
)

/** 合成器里「插入空白音乐」的占位 id，不对应任何真实歌曲。 */
const val SILENCE_TRACK_ID = "__silence__"
private const val SILENCE_SECONDS = 2

/** 新建片段的默认长度：够听出效果，又不至于默认就吃掉整首。 */
private const val DEFAULT_SEGMENT_MS = 5_000L

/** 立体声合成要求左右两槽都选好歌，空槽是空串。 */
private fun EffectUiState.slotsFilled(): Boolean =
    selectedTrackIds.size == 2 && selectedTrackIds.all { it.isNotEmpty() }

class EffectWorkspaceViewModel(
    private val effectId: String,
    private val sourceTrackRepository: SourceTrackRepository,
    private val effectProcessor: EffectProcessor,
    private val encoderClient: EncoderClient,
    private val exportTargetWriter: ExportTargetWriter,
    private val targetResolver: DownloadTargetResolver,
    private val probe: AudioFileProbe,
    private val audioPlayer: AudioPlayer,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val definition = EffectRegistry.find(effectId)
        ?: error("未知效果: $effectId")

    private val mutableState = MutableStateFlow(
        EffectUiState(definition = definition, values = definition.defaults()),
    )
    val uiState: StateFlow<EffectUiState> = mutableState.asStateFlow()
    private val mutableExportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = mutableExportState.asStateFlow()

    val tracks: StateFlow<List<SourceTrack>> = sourceTrackRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playback: StateFlow<PlaybackSnapshot> = audioPlayer.snapshot

    init {
        viewModelScope.launch {
            tracks.collect { list ->
                mutableState.update { current ->
                    current.copy(
                        tracks = list,
                        selectedTrackIds = current.selectedTrackIds.filter { id ->
                            id == SILENCE_TRACK_ID || list.any { it.id == id }
                        },
                    )
                }
                probeChannels()
            }
        }
    }

    private fun probeChannels() {
        val path = mutableState.value.tracks
            .firstOrNull { it.id == mutableState.value.selectedTrackIds.firstOrNull() }
            ?.localPath
            ?: return
        val probed = runCatching { probe.probeFile(File(path)).channels }.getOrDefault(0)
        mutableState.update { it.copy(channels = probed) }
    }

    /**
     * 加一个片段。默认落在已有片段之后、默认 5 秒、参数值 0（不变调 / 不改音量）。
     * 值在片段行里点开再调，参考示例里这一页本来就没有全局滑杆。
     */
    fun addSegment() {
        val current = mutableState.value
        val durationMs = current.trackDurationMs()
        val previousEnd = current.segments.maxOfOrNull { it.endMs } ?: 0L
        val start = previousEnd.coerceAtMost(durationMs)
        val end = (start + DEFAULT_SEGMENT_MS).coerceAtMost(durationMs)
        if (end <= start) {
            notify("剩余时长不足 ${DEFAULT_SEGMENT_MS / 1000} 秒，放不下新片段了")
            return
        }
        mutableState.update {
            it.copy(segments = it.segments + SegmentRange(start, end, 0f), message = null)
        }
    }

    fun removeSegment(index: Int) = mutableState.update { current ->
        if (index !in current.segments.indices) return@update current
        current.copy(segments = current.segments.filterIndexed { position, _ -> position != index })
    }

    fun updateSegment(index: Int, startMs: Long, endMs: Long, value: Float?) = mutableState.update { current ->
        if (index !in current.segments.indices) return@update current
        val old = current.segments[index]
        val durationMs = current.trackDurationMs()
        val from = startMs.coerceIn(0L, durationMs)
        val to = endMs.coerceIn(from, durationMs)
        if (to <= from) return@update current
        val next = old.copy(
            startMs = from,
            endMs = to,
            value = value ?: old.value,
        )
        current.copy(segments = current.segments.toMutableList().also { it[index] = next })
    }

    /** 当前选中那首的时长，用来夹住片段的起止。 */
    private fun EffectUiState.trackDurationMs(): Long =
        tracks.firstOrNull { it.id == selectedTrackIds.firstOrNull() }?.durationMs ?: 0L

    /** 混音时间轴：点掉某一首，保持其余顺序不变。 */
    fun removeTrackAt(index: Int) {
        mutableState.update { current ->
            current.copy(
                selectedTrackIds = current.selectedTrackIds.filterIndexed { position, _ -> position != index },
                message = null,
            )
        }
    }

    /** 合成：关掉「试听真实效果」时直接播第一首原文件，不走渲染。 */
    fun previewFirstTrack() {
        val first = mutableState.value.selectedTrackIds.firstOrNull { it != SILENCE_TRACK_ID } ?: return
        viewModelScope.launch {
            sourceTrackRepository.getById(first)?.localPath?.let(audioPlayer::loadFile)
        }
    }

    /** 合成：在列表末尾插一段空白音乐，重复点击可叠加多段。 */
    fun insertSilence() {
        val written = runCatching {
            silenceSource.parentFile?.mkdirs()
            PcmBuffer(44_100, 2, ShortArray(44_100 * SILENCE_SECONDS * 2)).writeWav(silenceSource)
        }.isSuccess
        mutableState.update { current ->
            if (!written) {
                current.copy(message = "空白音乐生成失败")
            } else {
                current.copy(
                    selectedTrackIds = current.selectedTrackIds + SILENCE_TRACK_ID,
                    message = "已插入 $SILENCE_SECONDS 秒空白音乐",
                )
            }
        }
    }

    /**
     * 把选中的 id 翻成可读文件。SILENCE_TRACK_ID 指向临时生成的静音 WAV，
     * 任何一首查不到就返回 null，让调用方提示重新选择。
     */
    private suspend fun resolveSources(ids: List<String>): List<File>? {
        val files = ArrayList<File>(ids.size)
        for (id in ids) {
            if (id == SILENCE_TRACK_ID) {
                if (!silenceSource.isFile) return null
                files += silenceSource
            } else {
                val track = sourceTrackRepository.getById(id) ?: return null
                files += File(track.localPath)
            }
        }
        return files
    }

    private val silenceSource: File
        get() = File(exportTempDirectory, "effect-silence.wav")

    /** 立体声合成：左槽 / 右槽各一个音轨，空槽用空串占位以保住声道顺序。 */
    fun pickSlotTrack(slot: Int, trackId: String) {
        mutableState.update { current ->
            val slots = MutableList(2) { "" }
            current.selectedTrackIds.take(2).forEachIndexed { index, id -> slots[index] = id }
            if (slot in 0..1) slots[slot] = trackId
            current.copy(selectedTrackIds = slots, message = null)
        }
        viewModelScope.launch {
            sourceTrackRepository.getById(trackId)?.localPath?.let(audioPlayer::loadFile)
        }
    }

    fun toggleTrack(trackId: String) {
        mutableState.update { current ->
            val selected = current.selectedTrackIds
            val next = when {
                trackId in selected && !definition.multiTrack -> emptyList()
                trackId in selected -> selected - trackId
                definition.multiTrack -> selected + trackId
                else -> listOf(trackId)
            }
            current.copy(selectedTrackIds = next, message = null)
        }
        // 多选时不预载，只有单选的试听对象才明确
        if (!definition.multiTrack) {
            viewModelScope.launch {
                sourceTrackRepository.getById(trackId)?.localPath?.let(audioPlayer::loadFile)
            }
            probeChannels()
        }
    }

    fun togglePlay() {
        if (playback.value.isPlaying) audioPlayer.pause() else audioPlayer.play()
    }

    fun seekTo(positionMs: Long) = audioPlayer.seekTo(positionMs)

    /**
     * 导入本地音频。slot 为 null 时按普通单选导入；
     * 立体声合成传 0/1 表示把结果填进对应声道。
     */
    fun importLocalAudio(uri: String, slot: Int? = null) {
        viewModelScope.launch {
            sourceTrackRepository.importLocalAudio(uri).fold(
                onSuccess = { track ->
                    mutableState.update { current ->
                        val next = if (slot == null) {
                            listOf(track.id)
                        } else {
                            val slots = MutableList(2) { "" }
                            current.selectedTrackIds.take(2).forEachIndexed { index, id -> slots[index] = id }
                            slots[slot] = track.id
                            slots
                        }
                        current.copy(
                            selectedTrackIds = next,
                            message = "已导入 ${track.title ?: "本地音频"}",
                        )
                    }
                    if (slot == null) {
                        sourceTrackRepository.getById(track.id)?.localPath?.let(audioPlayer::loadFile)
                    }
                },
                onFailure = { error -> notify("导入失败：${error.message ?: "未知错误"}") },
            )
        }
    }

    fun setValue(paramId: String, value: Float) {
        mutableState.update { it.copy(values = it.values + (paramId to value)) }
    }

    fun resetParams() {
        mutableState.update { it.copy(values = definition.defaults()) }
    }

    fun consumeMessage() {
        mutableState.update { it.copy(message = null) }
    }

    fun resetExportFeedback() {
        if (mutableExportState.value.isIdle) {
            mutableExportState.update { reduceExport(it, ExportEvent.Reset) }
        }
    }

    fun notify(message: String) {
        mutableState.update { it.copy(message = message) }
    }

    /** 先出中间件，再交给现有导出链路落盘，全程只有一次有损编码。 */
    fun processAndExport(targetUri: String) {
        val current = mutableState.value
        if (current.isProcessing) return
        if (!mutableExportState.value.isIdle) return
        if (current.selectedTrackIds.isEmpty()) {
            notify("请先选择歌曲")
            return
        }
        if (definition.leftRightSlots && !current.slotsFilled()) {
            notify("请为左右声道各选一首歌")
            return
        }
        viewModelScope.launch {
            val sources = resolveSources(current.selectedTrackIds)
            if (sources == null) {
                notify("选中的歌曲已失效，请重新选择")
                return@launch
            }
            mutableState.update { it.copy(isProcessing = true, progress = 0f, message = null) }
            val processed = withContext(Dispatchers.IO) {
                effectProcessor.process(
                    effectId = effectId,
                    sources = sources,
                    values = current.values,
                    segments = current.segments,
                    onProgress = { fraction -> mutableState.update { state -> state.copy(progress = fraction) } },
                )
            }
            val intermediate = processed.getOrElse { error ->
                mutableState.update { it.copy(isProcessing = false) }
                notify("处理失败：${error.message ?: "未知错误"}")
                return@launch
            }

            val metaTrack = current.tracks.firstOrNull { it.id in current.selectedTrackIds }
                ?: sourceTrackRepository.getById(current.selectedTrackIds.first())
            val job = metaTrack?.let { buildJob(intermediate, it) }
            if (job == null) {
                mutableState.update { it.copy(isProcessing = false) }
                intermediate.delete()
                return@launch
            }
            mutableState.update { it.copy(isProcessing = false) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(job.jobId, targetUri)) }
            encoderClient.export(job) { result -> onExportResult(job, targetUri, result, intermediate) }
        }
    }

    fun setExportFormat(format: ExportFormat) = mutableState.update { it.copy(exportFormat = format) }

    fun applyPreset(name: String) = mutableState.update { current ->
        val values = definition.preset.valuesOf(name) ?: return@update current
        current.copy(
            values = values + definition.defaults().filterKeys { it !in values },
            presetName = name,
        )
    }

    /** 清空 = 全部频段归零，保留当前选中的预设名。 */
    fun clearParams() = mutableState.update { current ->
        current.copy(values = definition.defaults())
    }

    fun exportFileName(): String = "$effectId.${mutableState.value.exportFormat.extension}"

    fun cancelExport() {
        mutableExportState.value.jobId?.let(encoderClient::cancel)
    }

    /**
     * 试听：EffectProcessor 本来就输出 WAV，直接拿来播。
     * 播原文件听不出任何效果变化，这个按钮就没意义了。
     */
    fun previewEffect() {
        val current = mutableState.value
        if (current.isPreviewing || current.isProcessing) return
        if (current.selectedTrackIds.isEmpty()) {
            notify("请先选择歌曲")
            return
        }
        if (definition.leftRightSlots && !current.slotsFilled()) {
            notify("请为左右声道各选一首歌")
            return
        }
        viewModelScope.launch {
            val sources = resolveSources(current.selectedTrackIds)
            if (sources == null) {
                notify("选中的歌曲已失效，请重新选择")
                return@launch
            }
            mutableState.update { it.copy(isPreviewing = true) }
            previewFile?.delete()
            if (definition.isMultiOutput) {
                val files = effectProcessor.processMulti(effectId, sources, current.values)
                    .getOrElse { error ->
                        mutableState.update { it.copy(isPreviewing = false) }
                        notify("试听渲染失败：${error.message ?: "未知错误"}")
                        return@launch
                    }
                current.splitFiles.forEach { it.delete() }
                previewFile = files.firstOrNull()
                mutableState.update { it.copy(splitFiles = files) }
            } else {
                previewFile = effectProcessor.process(
                    effectId = effectId,
                    sources = sources,
                    values = current.values,
                    segments = current.segments,
                    onProgress = { },
                ).getOrNull()
            }
            val rendered = previewFile
            if (rendered == null) {
                mutableState.update { it.copy(isPreviewing = false) }
                notify("试听渲染失败")
                return@launch
            }
            audioPlayer.loadFile(rendered.absolutePath)
            audioPlayer.play()
            mutableState.update { it.copy(isPreviewing = false) }
        }
    }

    /** 播放第 index 条拆分结果（0=左声道 1=右声道）。 */
    fun playSplit(index: Int) {
        if (mutableState.value.isPreviewing) return
        if (mutableState.value.playingSplitIndex == index) {
            audioPlayer.pause()
            mutableState.update { it.copy(playingSplitIndex = -1) }
            return
        }
        val file = mutableState.value.splitFiles.getOrNull(index) ?: run {
            notify("先点试听生成拆分结果")
            return
        }
        audioPlayer.loadFile(file.absolutePath)
        audioPlayer.play()
        mutableState.update { it.copy(playingSplitIndex = index) }
    }

    /**
     * 多输出效果直接落盘到设置里指定的下载目录，不弹 SAF 选择器 ——
     * 下载目录在设置页已经选过了。
     */
    fun exportSplit() {
        val current = mutableState.value
        if (current.isProcessing) return
        val files = current.splitFiles
        if (files.isEmpty()) {
            notify("先点试听生成拆分结果")
            return
        }
        val base = current.tracks.firstOrNull { it.id == current.selectedTrackIds.firstOrNull() }
            ?.title?.trim()?.takeIf(String::isNotEmpty) ?: "分离"
        viewModelScope.launch {
            mutableState.update { it.copy(isProcessing = true) }
            val written = files.mapIndexed { index, file ->
                val suffix = if (index == 0) "左声道" else "右声道"
                val fileName = "${base}_$suffix.${current.exportFormat.extension}"
                val final = targetResolver.finalTarget(fileName)
                if (!targetResolver.exists(final)) {
                    val part = File(exportTempDirectory, "split-$index.part")
                    part.writeBytes(file.readBytes())
                    targetResolver.publish(
                        part = targetResolver.partTarget(part.name.removePrefix(".").removeSuffix(".part")),
                        final = final,
                    ).also { part.delete() }
                } else {
                    true
                }
            }
            mutableState.update { it.copy(isProcessing = false) }
            if (written.all { it }) {
                notify("已写入下载目录：${targetResolver.describe(targetResolver.finalTarget(""))}")
            } else {
                notify("部分文件写入失败，请检查下载目录权限")
            }
        }
    }

    private var splitFilesOnDisk: List<File> = emptyList()

    private var previewFile: File? = null

    private fun buildJob(intermediate: File, origin: SourceTrack): ExportJob? {
        val durationUs = runCatching { probe.probeFile(intermediate).durationMs?.times(1000L) }.getOrNull()
        if (durationUs == null || durationUs <= 0L) {
            notify("处理结果无法读取时长")
            return null
        }
        if (!exportTempDirectory.exists()) exportTempDirectory.mkdirs()
        val current = mutableState.value
        return ExportJob(
            jobId = UUID.randomUUID().toString(),
            editProjectId = "effect:$effectId",
            outputTempPath = File(
                exportTempDirectory,
                "${UUID.randomUUID()}.${current.exportFormat.extension}",
            ).path,
            format = current.exportFormat,
            sources = listOf(
                ExportSource(
                    id = origin.id,
                    localPath = intermediate.absolutePath,
                    title = origin.title,
                    artist = origin.artist,
                    album = origin.album,
                    lyrics = origin.lyrics,
                    segments = listOf(ExportSegment(0L, durationUs)),
                ),
            ),
            gainDb = 0f,
            fadeInMs = 0L,
            fadeOutMs = 0L,
            lyricOffsetMs = 0L,
            bitrateKbps = current.bitrateKbps(),
            sampleRateHz = current.sampleRateHz(),
            channelMode = current.channelMode(),
        )
    }

    // 格式转换的参数下标即选项位置，0 是「跟随源 / 自动 / 不变」
    private fun EffectUiState.sampleRateHz(): Int =
        indexOf("sampleRate").let { EffectRegistry.CONVERT_RATES.getOrNull(it - 1) ?: 0 }

    private fun EffectUiState.bitrateKbps(): Int =
        if (!exportFormat.supportsBitrate) {
            0
        } else {
            val index = indexOf("bitrate")
            if (index == 0) 0 else EffectRegistry.CONVERT_BITRATES.getOrNull(index - 1) ?: 0
        }

    private fun EffectUiState.channelMode(): Int = indexOf("channels").coerceIn(0, 2)

    private fun EffectUiState.indexOf(id: String): Int =
        (values[id] ?: definition.params.firstOrNull { it.id == id }?.default ?: 0f).toInt()

    private fun onExportResult(job: ExportJob, targetUri: String, result: ExportResult, intermediate: File) {
        when (result) {
            is ExportResult.Failed ->
                mutableExportState.update { reduceExport(it, ExportEvent.Failed(result.reason)) }

            is ExportResult.Completed -> viewModelScope.launch {
                val copied = runCatching { exportTargetWriter.copy(result.outputPath, Uri.parse(targetUri)) }
                copied.fold(
                    onSuccess = { bytes ->
                        mutableExportState.update { reduceExport(it, ExportEvent.Succeeded(bytes)) }
                    },
                    onFailure = { error ->
                        val reason = error.message ?: "未知错误"
                        mutableExportState.update {
                            reduceExport(it, ExportEvent.Failed("写入所选位置失败：$reason"))
                        }
                    },
                )
            }
        }
        intermediate.delete()
    }

    override fun onCleared() {
        super.onCleared()
        previewFile?.delete()
        mutableState.value.splitFiles.forEach { it.delete() }
        effectProcessor.clearOutput()
    }

    companion object {
        fun factory(
            context: Context,
            effectId: String,
            sourceTrackRepository: SourceTrackRepository,
            encoderClient: EncoderClient,
            exportTargetWriter: ExportTargetWriter,
            targetResolver: DownloadTargetResolver,
            probe: AudioFileProbe,
            audioPlayer: AudioPlayer,
            pcmChunkReader: PcmChunkReader,
            exportTempDirectory: File,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(EffectWorkspaceViewModel::class.java)) {
                    throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
                }
                @Suppress("UNCHECKED_CAST")
                return EffectWorkspaceViewModel(
                    effectId = effectId,
                    sourceTrackRepository = sourceTrackRepository,
                    effectProcessor = EffectProcessor(context, pcmChunkReader),
                    encoderClient = encoderClient,
                    exportTargetWriter = exportTargetWriter,
                    targetResolver = targetResolver,
                    probe = probe,
                    audioPlayer = audioPlayer,
                    exportTempDirectory = exportTempDirectory,
                ) as T
            }
        }
    }
}
