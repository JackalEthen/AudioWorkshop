package cn.music.audioworkshop.domain.player

import cn.music.audioworkshop.domain.model.LyricsTrack
import cn.music.audioworkshop.domain.model.SourceOrigin

/**
 * 播放队列里的一项。
 *
 * 和 [cn.music.audioworkshop.domain.AudioPlayer] 的区别：那个是「单文件预览试听」，
 * 这个是「正式播放队列」，两者语义不同，不要合并。
 *
 * [mediaId] 直接塞进 Media3 的 MediaItem.mediaId，播放层靠它回传当前播的是哪一项。
 */
data class QueueItem(
    val mediaId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val uri: String,
    val coverUri: String? = null,
    val durationMs: Long = 0L,
    /** 本地文件路径；远程音源为空，播放前要先过 [UrlResolver] 求直链。 */
    val localPath: String? = null,
    val origin: SourceOrigin = SourceOrigin.LOCAL_IMPORT,
    /**
     * 来源平台，见 [cn.music.audioworkshop.domain.model.SourceCode]。
     * 远程音源必须带，lx 音源脚本按它决定走哪个平台的解析分支。
     */
    val sourceCode: String? = null,
    /**
     * 平台歌曲 id。求播放直链必需，音源拿不到 id 就换不了地址。
     *
     * 平台特有字段由 `toLxMusicInfo` 映射：kg 用 `hash`、tx 用 `strMediaMid`、
     * mg 用 `copyrightId`，其余平台用 `songmid`。
     */
    val platformSongId: String? = null,
    /**
     * 已经抓好的歌词。播放页直接渲染它，不在这里现查 ——
     * 歌词要走网络，播放已经开始了，不能让界面等。
     *
     * 搜索结果不入库，只有播放路径知道平台和 id，所以歌词必须跟着队列项走，
     * 播放页回查曲库是查不到的（远端曲根本没入库）。
     */
    val lyrics: LyricsTrack = LyricsTrack.EMPTY,
) {
    /** 远程来源的播放地址要现求，本地的直接就能播。 */
    val isRemote: Boolean
        get() = localPath.isNullOrBlank()
}

/** 队列最多留这么多首，超了从最早的非当前项开始丢。 */
const val QueueMaxSize = 200

/**
 * 「下一位播放」要插入到的下标。
 *
 * - currentIndex 为 -1（还没播过任何一项）时返回 0，插到队首，冷启动点歌也能工作
 * - currentIndex 指向队尾时返回 itemCount，也就是追加，不会越界
 */
fun queueInsertIndex(currentIndex: Int, itemCount: Int): Int =
    if (currentIndex < 0) 0 else (currentIndex + 1).coerceAtMost(itemCount)

/** 播放模式。顺序播完就停，不循环。 */
enum class RepeatMode {
    /** 顺序播完停止 */
    OFF,

    /** 单曲循环 */
    ONE,

    /** 全部循环 */
    ALL;

    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}
