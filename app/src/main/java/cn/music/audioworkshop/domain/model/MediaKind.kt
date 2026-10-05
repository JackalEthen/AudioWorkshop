package cn.music.audioworkshop.domain.model

/**
 * 解析结果的媒体类型。
 *
 * 解析源不只返回音乐 —— 抖音、B站、小红书这类返回视频，
 * 部分图文接口返回图片。字段名上区分不出来（视频和音频都叫 `url`），
 * 所以按扩展名判定。
 */
enum class MediaKind(val label: String) {
    AUDIO("音频"),
    VIDEO("视频"),
    IMAGE("图片"),
    ;

    /** 该类型能否在解析页内联预览。音频走播放器，视频和图片各有专用控件。 */
    val supportsPreview: Boolean
        get() = true

    companion object {
        /**
         * 按扩展名判定媒体类型。
         *
         * 先看 [format]，再看 URL 后缀 —— 很多接口 `format` 字段是空的
         * 或填的是内部标识（如 `vtype: "mp4"` 之外的 `aac`），
         * 而 URL 后缀往往才是真实类型。两者都没有时按音频处理，
         * 因为音乐是这个应用的主场景，猜错的代价最小。
         *
         * @param format 接口给的格式字段，可为空
         * @param url 媒体地址，可为空
         */
        fun detect(format: String?, url: String?): MediaKind {
            // format 优先：接口自己说的最准
            normalize(format)?.let { ext ->
                VIDEO_EXTENSIONS[ext]?.let { return VIDEO }
                IMAGE_EXTENSIONS[ext]?.let { return IMAGE }
                AUDIO_EXTENSIONS[ext]?.let { return AUDIO }
            }
            normalize(extensionOf(url))?.let { ext ->
                VIDEO_EXTENSIONS[ext]?.let { return VIDEO }
                IMAGE_EXTENSIONS[ext]?.let { return IMAGE }
                AUDIO_EXTENSIONS[ext]?.let { return AUDIO }
            }
            return AUDIO
        }

        /** URL 里的扩展名。查询参数和片段要剥掉，`?a=b.mp3` 不是 mp3。 */
        private fun extensionOf(url: String?): String? {
            val path = url?.substringBefore('?')?.substringBefore('#') ?: return null
            val dot = path.lastIndexOf('.')
            if (dot < 0 || dot == path.lastIndex) return null
            return path.substring(dot + 1)
        }

        private fun normalize(raw: String?): String? =
            raw?.trim()?.trimStart('.')?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 5 }

        /**
         * 视频扩展名。
         *
         * `m3u8` 是流媒体清单而非单个文件：能播但下载拿到的是播放列表，
         * 所以标记为不可下载。
         */
        val VIDEO_EXTENSIONS: Map<String, MediaKind> = mapOf(
            "mp4" to VIDEO,
            "m4v" to VIDEO,
            "webm" to VIDEO,
            "mkv" to VIDEO,
            "mov" to VIDEO,
            "avi" to VIDEO,
            "flv" to VIDEO,
            "ts" to VIDEO,
            "3gp" to VIDEO,
            "m3u8" to VIDEO,
            "mpd" to VIDEO,
        )

        val IMAGE_EXTENSIONS: Map<String, MediaKind> = mapOf(
            "jpg" to IMAGE,
            "jpeg" to IMAGE,
            "png" to IMAGE,
            "gif" to IMAGE,
            "webp" to IMAGE,
            "bmp" to IMAGE,
            "heic" to IMAGE,
            "heif" to IMAGE,
        )

        val AUDIO_EXTENSIONS: Map<String, MediaKind> = mapOf(
            "mp3" to AUDIO,
            "flac" to AUDIO,
            "wav" to AUDIO,
            "m4a" to AUDIO,
            "aac" to AUDIO,
            "ogg" to AUDIO,
            "opus" to AUDIO,
            "wma" to AUDIO,
            "ape" to AUDIO,
        )

        /**
         * 是否能下载。
         *
         * `m3u8`/`mpd` 是播放清单而非媒体文件，下载它们没有意义。
         */
        fun isDownloadable(format: String?, url: String?): Boolean {
            val kind = detect(format, url)
            val ext = normalize(format) ?: normalize(extensionOf(url))
            if (kind == AUDIO) return true
            return ext !in STREAM_ONLY_EXTENSIONS
        }

        private val STREAM_ONLY_EXTENSIONS = setOf("m3u8", "mpd")
    }
}
