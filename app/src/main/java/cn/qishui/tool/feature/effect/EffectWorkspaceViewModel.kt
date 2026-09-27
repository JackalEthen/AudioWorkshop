package cn.qishui.tool.feature.effect

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.qishui.tool.data.encoder.EncoderClient
import cn.qishui.tool.data.media.AudioFileProbe
import cn.qishui.tool.domain.SourceTrackRepository
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
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val message: String? = null,
)

class EffectWorkspaceViewModel(
    private val effectId: String,
    private val sourceTrackRepository: SourceTrackRepository,
    private val effectProcessor: EffectProcessor,
    private val encoderClient: EncoderClient,
    private val exportTargetWriter: ExportTargetWriter,
    private val probe: AudioFileProbe,
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

    init {
        viewModelScope.launch {
            tracks.collect { list ->
                mutableState.update { current ->
                    current.copy(
                        tracks = list,
                        selectedTrackIds = current.selectedTrackIds.filter { id -> list.any { it.id == id } },
                    )
                }
            }
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
    }

    fun importLocalAudio(uri: String) {
        viewModelScope.launch {
            sourceTrackRepository.importLocalAudio(uri).fold(
                onSuccess = { track ->
                    mutableState.update { it.copy(selectedTrackIds = listOf(track.id), message = "已导入 ${track.title ?: "本地音频"}") }
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
        viewModelScope.launch {
            val sources = current.selectedTrackIds.mapNotNull { sourceTrackRepository.getById(it) }
            if (sources.size != current.selectedTrackIds.size) {
                notify("选中的歌曲已失效，请重新选择")
                return@launch
            }
            mutableState.update { it.copy(isProcessing = true, progress = 0f, message = null) }
            val processed = withContext(Dispatchers.IO) {
                effectProcessor.process(
                    effectId = effectId,
                    sources = sources.map { File(it.localPath) },
                    values = current.values,
                    onProgress = { fraction -> mutableState.update { state -> state.copy(progress = fraction) } },
                )
            }
            val intermediate = processed.getOrElse { error ->
                mutableState.update { it.copy(isProcessing = false) }
                notify("处理失败：${error.message ?: "未知错误"}")
                return@launch
            }

            val job = buildJob(intermediate, sources.first())
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

    fun cancelExport() {
        mutableExportState.value.jobId?.let(encoderClient::cancel)
    }

    private fun buildJob(intermediate: File, origin: SourceTrack): ExportJob? {
        val durationUs = runCatching { probe.probeFile(intermediate).durationMs?.times(1000L) }.getOrNull()
        if (durationUs == null || durationUs <= 0L) {
            notify("处理结果无法读取时长")
            return null
        }
        if (!exportTempDirectory.exists()) exportTempDirectory.mkdirs()
        return ExportJob(
            jobId = UUID.randomUUID().toString(),
            editProjectId = "effect:$effectId",
            outputTempPath = File(exportTempDirectory, "${UUID.randomUUID()}.mp3").path,
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
        )
    }

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
        effectProcessor.clearOutput()
    }

    companion object {
        fun factory(
            context: Context,
            effectId: String,
            sourceTrackRepository: SourceTrackRepository,
            encoderClient: EncoderClient,
            exportTargetWriter: ExportTargetWriter,
            probe: AudioFileProbe,
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
                    probe = probe,
                    exportTempDirectory = exportTempDirectory,
                ) as T
            }
        }
    }
}
