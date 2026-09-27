package cn.qishui.tool.domain.model

data class TrackMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val year: String?,
    val artworkBytes: ByteArray?,
    val artworkMimeType: String?,
)
