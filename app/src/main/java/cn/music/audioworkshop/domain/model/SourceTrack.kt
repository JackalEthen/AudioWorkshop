package cn.music.audioworkshop.domain.model

enum class SourceOrigin {
    API,
    LOCAL_IMPORT,

    /** lx 音源解析出来的曲目，没有本地文件，播放前要先向脚本求直链。 */
    REMOTE_SOURCE,
}

/** 来源平台标识。和 lx 音源协议里的源 key 对齐。 */
object SourceCode {
    const val LOCAL = "local"
    const val KUWO = "kw"
    const val KUGOU = "kg"
    const val TENCENT = "tx"
    const val NETEASE = "wy"
    const val MIGU = "mg"
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
    /** 来源平台。本地导入是 [SourceCode.LOCAL]，见 [SourceCode] 其余取值。 */
    val sourceCode: String? = null,
    /** 平台歌曲 id（songmid / hash），音源求播放地址必需。 */
    val platformSongId: String? = null,
)
