package cn.qishui.tool.feature.metadata

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.qishui.tool.data.media.AudioFileProbe
import cn.qishui.tool.domain.SourceTrackRepository
import cn.qishui.tool.domain.model.SourceTrack
import cn.qishui.tool.domain.model.TrackMetadata
import cn.qishui.tool.feature.edit.export.ExportTargetWriter
import cn.qishui.tool.media.metadata.Id3v2Codec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

    /** 音频的技术信息，只读展示用。 */
    data class AudioFacts(
        val fileName: String = "",
        val format: String = "",
        val sampleRateHz: Long = 0L,
        val bitrateBps: Long = 0L,
        val channels: Int = 0,
        val durationMs: Long = 0L,
        val sizeBytes: Long = 0L,
        val path: String = "",
    )

data class MetadataUiState(
    val track: SourceTrack? = null,
    val fileName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: String = "",
    val comment: String = "",
    val coverUri: String? = null,
    val facts: AudioFacts = AudioFacts(),
    val isWorking: Boolean = false,
    val message: String? = null,
)

/**
 * 修改音乐信息：只重写 ID3 标签，不重新编码音频，所以是无损的。
 * 直接改原文件风险太大，这里统一走"写到用户选择的位置"。
 */
class MetadataViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val probe: AudioFileProbe,
    private val exportTargetWriter: ExportTargetWriter,
) : ViewModel() {

    private val mutableState = MutableStateFlow(MetadataUiState())
    val uiState: StateFlow<MetadataUiState> = mutableState.asStateFlow()

    val tracks: StateFlow<List<SourceTrack>> = sourceTrackRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { tracks.collect(::onTracksChanged) }
    }

    private fun onTracksChanged(list: List<SourceTrack>) {
        val current = mutableState.value
        if (current.track != null) return
        val first = list.firstOrNull() ?: return
        mutableState.update { it.copy(track = first) }
        viewModelScope.launch { load(first) }
    }

    fun select(track: SourceTrack) {
        mutableState.update { it.copy(track = track) }
        viewModelScope.launch { load(track) }
    }

    fun importLocalAudio(uri: String) {
        viewModelScope.launch {
            sourceTrackRepository.importLocalAudio(uri).fold(
                onSuccess = { select(it) },
                onFailure = { notify("导入失败：${it.message ?: "未知错误"}") },
            )
        }
    }

    fun setFileName(value: String) = mutableState.update { it.copy(fileName = value) }
    fun setTitle(value: String) = mutableState.update { it.copy(title = value) }
    fun setArtist(value: String) = mutableState.update { it.copy(artist = value) }
    fun setAlbum(value: String) = mutableState.update { it.copy(album = value) }
    fun setYear(value: String) = mutableState.update { it.copy(year = value.take(4).filter(Char::isDigit)) }
    fun setComment(value: String) = mutableState.update { it.copy(comment = value) }
    fun setCover(uri: String?) = mutableState.update { it.copy(coverUri = uri) }
    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    fun writeTo(targetUri: String) {
        val current = mutableState.value
        val track = current.track ?: return
        if (current.isWorking) return
        mutableState.update { it.copy(isWorking = true, message = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { write(track, current, targetUri) }
            mutableState.update { it.copy(isWorking = false) }
            result.fold(
                onSuccess = { notify("已写入所选位置") },
                onFailure = { notify("写入失败：${it.message ?: "未知错误"}") },
            )
        }
    }

    private suspend fun load(track: SourceTrack) {
        val file = File(track.localPath)
        val facts = withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext AudioFacts(path = track.localPath.orEmpty())
            val probed = runCatching { probe.probeFile(file) }.getOrNull()
            AudioFacts(
                fileName = file.name,
                format = probed?.format ?: track.format.orEmpty(),
                sampleRateHz = probed?.sampleRateHz ?: 0L,
                bitrateBps = probed?.bitrateBps ?: 0L,
                durationMs = probed?.durationMs ?: 0L,
                sizeBytes = file.length(),
                channels = probed?.channels ?: 0,
                path = track.localPath.orEmpty(),
            )
        }
        val existing = withContext(Dispatchers.IO) {
            runCatching { Id3v2Codec.read(file) }.getOrNull()
        }
        mutableState.update {
            it.copy(
                fileName = facts.fileName,
                title = existing?.title ?: track.title.orEmpty(),
                artist = existing?.artist ?: track.artist.orEmpty(),
                album = existing?.album ?: track.album.orEmpty(),
                year = existing?.date.orEmpty(),
                comment = existing?.comment.orEmpty(),
                facts = facts,
            )
        }
    }

    private fun write(track: SourceTrack, state: MetadataUiState, targetUri: String): Result<Unit> =
        runCatching {
            val source = File(track.localPath)
            require(source.isFile) { "源文件不可用" }
            val artwork = state.coverUri?.let { readCover(it) }
            // 先把源文件原样搬过去，再在前面拼上新的 ID3 头，音频数据一个字节都不动
            val stage = File(source.parentFile, ".meta-${System.currentTimeMillis()}.tmp")
            Id3v2Codec.write(
                audioFile = source,
                outputFile = stage,
                metadata = TrackMetadata(
                    title = state.title.ifBlank { null },
                    artist = state.artist.ifBlank { null },
                    album = state.album.ifBlank { null },
                    year = state.year.ifBlank { null },
                    artworkBytes = artwork?.first,
                    artworkMimeType = artwork?.second,
                    comment = state.comment.ifBlank { null },
                ),
                lyricsTrack = null,
            )
            try {
                // CreateDocument 返回的是 content:// URI，不能当文件路径用，必须走 ContentResolver。
                val resolver = currentContext?.contentResolver
                    ?: error("缺少写入上下文")
                val output = resolver.openOutputStream(Uri.parse(targetUri))
                    ?: error("无法打开所选位置")
                output.use { sink ->
                    stage.inputStream().use { source -> source.copyTo(sink) }
                }
            } finally {
                stage.delete()
            }
        }

    private fun readCover(uri: String): Pair<ByteArray, String>? = runCatching {
        val parsed = Uri.parse(uri)
        val mime = contextMime(uri)
        val resolver = currentContext?.contentResolver ?: return null
        resolver.openInputStream(parsed)?.use { it.readBytes() }?.let { bytes ->
            if (bytes.isEmpty()) null else bytes to (mime ?: sniffImage(bytes))
        }
    }.getOrNull()

    @Volatile
    private var currentContext: Context? = null

    fun attachContext(context: Context) {
        currentContext = context.applicationContext
    }

    private fun contextMime(uri: String): String? = runCatching {
        currentContext?.contentResolver?.getType(Uri.parse(uri))
    }.getOrNull()

    private fun sniffImage(bytes: ByteArray): String {
        val png = bytes.size > 3 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        return if (png) "image/png" else "image/jpeg"
    }

    private fun notify(message: String) = mutableState.update { it.copy(message = message) }

    companion object {
        fun factory(
            sourceTrackRepository: SourceTrackRepository,
            probe: AudioFileProbe,
            exportTargetWriter: ExportTargetWriter,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(MetadataViewModel::class.java)) {
                    throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
                }
                @Suppress("UNCHECKED_CAST")
                return MetadataViewModel(sourceTrackRepository, probe, exportTargetWriter) as T
            }
        }
    }
}
