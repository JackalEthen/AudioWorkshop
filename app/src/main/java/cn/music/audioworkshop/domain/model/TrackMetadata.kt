package cn.music.audioworkshop.domain.model

data class TrackMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val year: String?,
    val artworkBytes: ByteArray?,
    val artworkMimeType: String?,
    val comment: String? = null,
)
