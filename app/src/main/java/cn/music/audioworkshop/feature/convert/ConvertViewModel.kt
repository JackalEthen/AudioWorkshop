package cn.music.audioworkshop.feature.convert

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.music.audioworkshop.data.encoder.EncoderClient
import cn.music.audioworkshop.data.encoder.EncoderState
import cn.music.audioworkshop.data.media.AudioFileProbe
import cn.music.audioworkshop.domain.SourceTrackRepository
import cn.music.audioworkshop.domain.media.ExportFormat
import cn.music.audioworkshop.domain.media.ExportJob
import cn.music.audioworkshop.domain.media.ExportResult
import cn.music.audioworkshop.domain.media.ExportSegment
import cn.music.audioworkshop.domain.media.ExportSource
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

/** 声道选项：0 不变 / 1 单声道 / 2 立体声 */
enum class ChannelOption(val label: String, val value: Int) {
    KEEP("跟随源", 0),
    MONO("单声道", 1),
    STEREO("立体声", 2),
}

data class ConvertUiState(
    val track: SourceTrack? = null,
    val fileName: String = "",
    val format: ExportFormat = ExportFormat.MP3,
    /** 0 = 跟随源，其余是对应 Hz 值 */
    val sampleRateHz: Int = 0,
    /** 0 = 自动，其余是对应 kbps 值 */
    val bitrateKbps: Int = 0,
    val channels: ChannelOption = ChannelOption.KEEP,
    val isExporting: Boolean = false,
    val message: String? = null,
    /** 导出成功后展示给用户看：文件落在哪、多大。 */
    val publishedLocation: String? = null,
)

/**
 * 格式转换。
 *
 * 音频数据一个字节都不动，只换容器 —— 重采样与有损编码都发生在导出链路的
 * 编码器里，所以这里只负责收集参数并组装 [ExportJob]。
 *
 * 与播放器、与旧的音效页都没有依赖。
 */
class ConvertViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val probe: AudioFileProbe,
    private val encoderClient: EncoderClient,
    private val exportPublisher: ExportPublisher,
    private val exportTempDirectory: File,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ConvertUiState())
    val uiState: StateFlow<ConvertUiState> = mutableState.asStateFlow()

    private val mutableExportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = mutableExportState.asStateFlow()

    init {
        viewModelScope.launch {
            encoderClient.state.collect { state ->
                if (state !is EncoderState.InProgress) return@collect
                val active = mutableExportState.value
                if (!active.isRunning) return@collect
                mutableExportState.update {
                    reduceExport(it, ExportEvent.Progressed(state.progress))
                }
            }
        }
    }

    fun importLocalAudio(uri: String) {
        viewModelScope.launch {
            sourceTrackRepository.importLocalAudioEphemeral(uri).fold(
                onSuccess = { track ->
                    mutableState.update { it.copy(track = track, fileName = fileNameOf(track)) }
                    notify("已导入 ${track.title ?: "本地音频"}")
                },
                onFailure = { error -> notify("导入失败：${error.message ?: "未知错误"}") },
            )
        }
    }

    fun setFormat(format: ExportFormat) = mutableState.update { current ->
        // 切格式后原有参数可能非法（无损格式没有比特率、MP3 不支持 96k），
        // 统一退回「跟随源 / 自动」，别让下拉显示一个编码器会拒绝的值
        val rate = current.sampleRateHz
        val bitrate = current.bitrateKbps
        current.copy(
            format = format,
            sampleRateHz = if (rate in format.supportedSampleRates) rate else 0,
            bitrateKbps = if (bitrate in format.supportedBitrates) bitrate else 0,
        )
    }

    fun setSampleRate(hz: Int) = mutableState.update { it.copy(sampleRateHz = hz) }
    fun setBitrate(kbps: Int) = mutableState.update { current ->
        // 无损格式没有比特率，界面那行虽然可点（为了统一行样式），这里直接拒掉
        if (!current.format.supportsBitrate) current
        else current.copy(bitrateKbps = kbps)
    }
    fun setChannels(option: ChannelOption) = mutableState.update { it.copy(channels = option) }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    /** 用户看过成功提示后清掉落点展示。导出状态单独用 resetExportFeedback 重置。 */
    fun consumePublished() = mutableState.update { it.copy(publishedLocation = null) }

    fun resetExportFeedback() {
        // 失败态也要能复位：出错后 isRunning/isCopying 都是 false，
        // 只按 isIdle 复位会跳过，错误就永远留在界面上。
        if (mutableExportState.value.isIdle || mutableExportState.value.error != null) {
            mutableExportState.update { reduceExport(it, ExportEvent.Reset) }
        }
    }

    fun cancelExport() {
        mutableExportState.value.jobId?.let(encoderClient::cancel)
    }

    /**
     * 建议的导出文件名，喂给重命名弹窗当默认值。
     * 用户没导入音频时返回空串 —— 此时不启用重命名流程。
     */
    fun suggestedFileName(): String? {
        val current = mutableState.value
        if (current.fileName.isBlank()) return null
        val base = current.fileName.substringBeforeLast('.', current.fileName).ifBlank { "音频" }
        return "$base-${current.format.label}.${current.format.extension}"
    }

    /** 用户确认文件名后开始转换。[fileName] 为空时用建议名兜底。 */
    fun convert(confirmedFileName: String? = null) {
        convertInternal(confirmedFileName)
    }

    private fun convertInternal(confirmedFileName: String?) {
        val current = mutableState.value
        if (current.isExporting) return
        if (!mutableExportState.value.isIdle) return
        val track = current.track
        if (track == null) {
            notify("请先导入音频")
            return
        }
        current.format.validate(current.sampleRateHz, current.bitrateKbps, 2)?.let { reason ->
            notify(reason)
            return
        }
        viewModelScope.launch {
            val source = File(track.localPath)
            if (!source.isFile) {
                notify("源文件不可用，请重新导入")
                return@launch
            }
            val durationUs = withContext(Dispatchers.IO) {
                runCatching { probe.probeFile(source).durationMs?.times(1000L) }.getOrNull() ?: 0L
            }
            if (durationUs <= 0L) {
                notify("读不出音频时长，文件可能已损坏")
                return@launch
            }
            // 用户给的文件名优先；留空则用建议名；再空则退回歌名。
            val fileName = (confirmedFileName?.takeIf { it.isNotBlank() } ?: suggestedFileName())
                ?: "${baseNameOf(current.fileName, track)}.${current.format.extension}"
            mutableState.update { it.copy(isExporting = true, message = null) }
            mutableExportState.update { reduceExport(it, ExportEvent.Started(newJobId(), fileName)) }
            val job = ExportJob(
                jobId = mutableExportState.value.jobId.orEmpty(),
                editProjectId = "convert",
                outputTempPath = File(
                    exportTempDirectory,
                    "${UUID.randomUUID()}.${current.format.extension}",
                ).absolutePath,
                sources = listOf(
                    ExportSource(
                        id = track.id,
                        localPath = source.absolutePath,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        lyrics = track.lyrics,
                        segments = listOf(ExportSegment(0L, durationUs)),
                    ),
                ),
                // 格式转换只换容器，不做增益 / 淡入淡出 / 歌词偏移，这些参数填中性值
                gainDb = 0f,
                fadeInMs = 0L,
                fadeOutMs = 0L,
                lyricOffsetMs = 0L,
                format = current.format,
                sampleRateHz = current.sampleRateHz,
                bitrateKbps = current.bitrateKbps,
                channelMode = current.channels.value,
            )
            encoderClient.export(job) { result -> onExportResult(job, fileName, result) }
        }
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
                        // 提示要带上真实落点，否则用户不知道文件去哪了
                        mutableState.update {
                            it.copy(publishedLocation = published.location, message = null)
                        }
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

    private fun fileNameOf(track: SourceTrack): String =
        File(track.localPath).name.ifBlank { track.title.orEmpty() }

    private fun baseNameOf(fileName: String, track: SourceTrack): String =
        fileName.substringBeforeLast('.', fileName).ifBlank {
            track.title?.takeIf { it.isNotBlank() } ?: "音频"
        }

    private fun newJobId(): String = "convert-${System.currentTimeMillis()}"

    companion object {
        fun factory(
            sourceTrackRepository: SourceTrackRepository,
            probe: AudioFileProbe,
            encoderClient: EncoderClient,
            exportPublisher: ExportPublisher,
            exportTempDirectory: File,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ConvertViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return ConvertViewModel(
                    sourceTrackRepository = sourceTrackRepository,
                    probe = probe,
                    encoderClient = encoderClient,
                    exportPublisher = exportPublisher,
                    exportTempDirectory = exportTempDirectory,
                ) as T
            }
        }
    }
}
