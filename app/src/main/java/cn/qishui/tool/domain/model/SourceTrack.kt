package cn.qishui.tool.domain.model

enum class SourceOrigin {
    API,
    LOCAL_IMPORT,
}

data class SourceTrack(
    val id: String,
    val origin: SourceOrigin,
    val sourceShareUrl: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val localPath: String?,
    val format: String?,
    val durationMs: Long?,
    val bitrateBps: Long?,
    val sizeBytes: Long?,
    val sampleRateHz: Long?,
    val lyrics: String?,
    val fileHash: String?,
    val coverUri: String?,
)
