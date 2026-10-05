package cn.music.audioworkshop.domain.model

/**
 * 用户配置的解析源。
 *
 * 不内置任何默认源 —— 没有可用源时解析页就是空壳，由用户自行添加。
 *
 * @param typeParam 部分接口要求 `?type=json` 才会返回结构化数据（不传会返回
 *   「缺少type参数」的提示）。填 `json` 即自动附加。
 * @param apiKey 需要鉴权的接口用。会同时以 `key` 和 `apikey` 两个参数名附加，
 *   覆盖两种常见约定。
 * @param fields 字段名映射。留空的语义用 [FieldCandidates] 的内置候选列表。
 */
data class ParseApiSource(
    val id: String,
    val name: String,
    val url: String,
    val typeParam: String = "json",
    val apiKey: String = "",
    val enabled: Boolean = true,
    val fields: FieldMapping = FieldMapping(),
    /** 备注，仅用于设置页展示，方便用户区分同名源。 */
    val note: String = "",
)

/**
 * 字段名映射。
 *
 * 每个值是逗号分隔的候选字段名，按顺序尝试，命中第一个非空即用。
 * 例如 `title = "name,songName,music_name"` 表示依次找这三个键。
 *
 * 留空表示「用内置候选列表」。这样绝大多数接口只需填 URL 就能用，
 * 只有字段名特别离奇的才需要手动指定。
 */
data class FieldMapping(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val cover: String = "",
    val audioUrl: String = "",
    val lyrics: String = "",
    val bitrate: String = "",
    val size: String = "",
    val quality: String = "",
    val sampleRate: String = "",
    val format: String = "",
    val codec: String = "",
)

/** 语义字段。用于把「要什么」和「键叫什么」解耦。 */
enum class FieldSlot(val mappingKey: String, val candidates: List<String>) {
    TITLE("title", listOf("name", "title", "songname", "song_name", "songName", "musicname", "music_name", "musicName", "music", "track", "trackName", "song")),
    ARTIST("artist", listOf("ar_name", "artist", "artistname", "artist_name", "artistName", "artistsname", "singer", "singername", "singer_name", "author", "authorname", "author_name", "authorName", "nickname", "username", "user_name", "ar")),
    ALBUM("album", listOf("al_name", "album", "albumname", "album_name", "albumName", "collection")),
    COVER("cover", listOf("pic", "pic_url", "picUrl", "cover", "cover_url", "coverUrl", "img", "image", "img_url", "album_img", "thumbnail", "poster")),
    AUDIO_URL("audioUrl", listOf("url", "play_url", "playUrl", "music_url", "musicUrl", "audio", "audio_url", "audioUrl", "mp3", "mp3_url", "src", "link", "song_url", "songUrl", "voice", "stream_url", "baseUrl", "base_url", "backupUrl", "backup_url")),
    LYRICS("lyrics", listOf("lyric", "lyrics", "lrc", "lrc_content", "lrcContent", "text")),
    BITRATE("bitrate", listOf("real_bitrate", "bitrate", "bit_rate", "bitRate", "br", "bitrate_bps", "bitrateBps")),
    SIZE("size", listOf("size", "filesize", "file_size", "fileSize", "size_bytes", "sizeBytes", "filesize_bytes")),
    QUALITY("quality", listOf("level", "quality", "br_name", "brName", "bitrate_name", "quality_name")),
    SAMPLE_RATE("sampleRate", listOf("audio_sample_rate", "audioSampleRate", "sample_rate", "sampleRate", "samplerate", "sr")),
    FORMAT("format", listOf("vtype", "format", "ext", "extension", "file_type", "fileType", "type")),
    CODEC("codec", listOf("codec_type", "codecType", "codec", "encode", "encoder")),
    ;

    /** 用户在设置页填了自定义候选就用用户的，没填才用内置的。 */
    fun candidatesFrom(mapping: String): List<String> =
        mapping.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { candidates }
}

/** 读取某个语义字段当前的自定义映射值。 */
fun FieldMapping.slotValue(slot: FieldSlot): String = when (slot) {
    FieldSlot.TITLE -> title
    FieldSlot.ARTIST -> artist
    FieldSlot.ALBUM -> album
    FieldSlot.COVER -> cover
    FieldSlot.AUDIO_URL -> audioUrl
    FieldSlot.LYRICS -> lyrics
    FieldSlot.BITRATE -> bitrate
    FieldSlot.SIZE -> size
    FieldSlot.QUALITY -> quality
    FieldSlot.SAMPLE_RATE -> sampleRate
    FieldSlot.FORMAT -> format
    FieldSlot.CODEC -> codec
}

/** 写入某个语义字段的自定义映射值。 */
fun FieldMapping.withSlot(slot: FieldSlot, value: String): FieldMapping = when (slot) {
    FieldSlot.TITLE -> copy(title = value)
    FieldSlot.ARTIST -> copy(artist = value)
    FieldSlot.ALBUM -> copy(album = value)
    FieldSlot.COVER -> copy(cover = value)
    FieldSlot.AUDIO_URL -> copy(audioUrl = value)
    FieldSlot.LYRICS -> copy(lyrics = value)
    FieldSlot.BITRATE -> copy(bitrate = value)
    FieldSlot.SIZE -> copy(size = value)
    FieldSlot.QUALITY -> copy(quality = value)
    FieldSlot.SAMPLE_RATE -> copy(sampleRate = value)
    FieldSlot.FORMAT -> copy(format = value)
    FieldSlot.CODEC -> copy(codec = value)
}
