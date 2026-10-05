package cn.music.audioworkshop.domain.model

data class ParseRecord(
    val id: String,
    val sourceShareUrl: String,
    val title: String?,
    val artist: String?,
    val format: String?,
    val bitrateBps: Long?,
    val sizeBytes: Long?,
    val fileHash: String?,
    val lyrics: String?,
    val cacheExpiresAtEpochSeconds: Long?,
    val createdAtEpochMillis: Long,
)
