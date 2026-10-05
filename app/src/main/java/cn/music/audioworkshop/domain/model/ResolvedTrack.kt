package cn.music.audioworkshop.domain.model

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
    /**
     * 媒体类型。解析源不只返回音乐 —— 视频和图片源也走同一条链路。
     *
     * 命名保留 `audioUrl` 是历史原因，实际可能是视频或图片地址。
     */
    val mediaKind: MediaKind = MediaKind.AUDIO,
)
