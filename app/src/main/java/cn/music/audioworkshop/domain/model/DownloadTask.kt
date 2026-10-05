package cn.music.audioworkshop.domain.model

data class DownloadTask(
    val id: String,
    val title: String?,
    val artist: String?,
    val sourceShareUrl: String,
    val temporaryUrl: String?,
    val format: String?,
    val sampleRateHz: Long?,
    val expectedSizeBytes: Long?,
    val expectedMd5: String?,
    val downloadedBytes: Long,
    val eTag: String?,
    val lastModified: String?,
    val partPath: String?,
    val finalPath: String?,
    val status: DownloadStatus,
    val errorMessage: String?,
    val lyrics: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
