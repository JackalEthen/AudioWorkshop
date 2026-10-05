package cn.music.audioworkshop.domain.player

import android.net.Uri

/** 音质档位。值和 lx 音源协议里的一致，便于直接映射。 */
enum class PlaybackQuality(val lxType: String, val label: String) {
    K128("128k", "128k"),
    K320("320k", "320k"),
    FLAC("flac", "FLAC"),
    FLAC24("flac24bit", "FLAC 24bit");

    companion object {
        val DEFAULT = K320
    }
}

/**
 * 播放地址解析器。
 *
 * 本地文件直接就能播，远程音源（lx 自定义源）要把歌名歌手发给 JS 脚本
 * 才能拿到网盘直链，所以这一步必须留在播放器之外。
 *
 * 本地走 [LocalResolver]，远程走 `cn.music.audioworkshop.media.source.LxSourceResolver`
 * （在 `media` 层，因为它依赖 QuickJS 引擎），播放层不感知区别。
 */
interface UrlResolver {

    /** 来源标识，和 source_tracks.source_code 对应。 */
    val sourceCode: String

    /**
     * 解析出实际可播的地址。
     *
     * @param song 要播的那一项
     * @param quality 音质档位，只有远程源用得上
     * @return 可直接喂给 ExoPlayer 的地址
     */
    suspend fun resolve(song: QueueItem, quality: PlaybackQuality): Uri
}

/** 解析失败时抛出，消息会直接显示给用户。 */
class UrlResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)
