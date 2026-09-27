package cn.qishui.tool.domain.model

data class ResolvedTrack(
    val title: String?,
    val artist: String?,
    val audioUrl: String?,
    val format: String?,
    val codec: String?,
    val quality: String?,
    val bitrateBps: Long?,
    val sizeBytes: Long?,
    val sampleRateHz: Long?,
    val lyrics: String?,
    val artistAvatarUrls: List<String>,
    val cacheExpiresAtEpochSeconds: Long?,
    val sourceShareUrl: String? = null,
    val fileHash: String? = null,
)
