package cn.qishui.tool.domain.media

/**
 * 导出容器格式。
 *
 * [WAV] / [FLAC] 走 PCM 直写，不经过有损编码，也没有 ID3 —— 封面和歌词会丢。
 * [MP3] 走 LAME CBR，标签完整。
 * [AAC] 是裸 ADTS 流，[M4A] 是同样的 AAC 编码结果装进 MPEG-4 容器。
 */
enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    val label: String,
    /** 无损格式没有「比特率」这个概念，界面上要置灰。 */
    val lossless: Boolean = false,
) {
    MP3("mp3", "audio/mpeg", "mp3"),
    WAV("wav", "audio/wav", "wav", lossless = true),
    FLAC("flac", "audio/flac", "flac", lossless = true),
    AAC("aac", "audio/aac", "aac"),
    M4A("m4a", "audio/mp4", "m4a"),
    ;

    val supportsBitrate: Boolean
        get() = !lossless

    /**
     * 有没有 ID3 可写。WAV / FLAC 是裸流，塞标签会破坏文件结构。
     * FLAC 的 Vorbis 注释本可以写，但那是另一套容器逻辑，目前没做。
     */
    val supportsTags: Boolean
        get() = this == MP3

    companion object {
        fun fromName(name: String?): ExportFormat =
            entries.firstOrNull { it.name == name } ?: MP3
    }
}
