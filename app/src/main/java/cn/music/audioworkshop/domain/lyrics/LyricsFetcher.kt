package cn.music.audioworkshop.domain.lyrics

/**
 * 取歌词原文。
 *
 * **lx 音源协议帮不上忙** —— 它的 `lyric` action 只对 `local` 平台开放，
 * 远端平台的歌音源脚本给不了歌词。所以歌词是独立的一层实现。
 *
 * 返回 LRC 原文（不是 [cn.music.audioworkshop.domain.model.LyricsTrack]），
 * 解析交给已有的 [LyricsParser] 实现，各 fetcher 只管取。
 */
interface LyricsFetcher {
    val platform: String

    /**
     * @param platformSongId 平台歌曲 id（网易歌名 id / QQ songmid …）
     * @param title 歌名，用于按元信息匹配的兜底源
     * @param artist 歌手，同上
     * @param durationMs 时长，用于在多个候选里挑最准的那个
     * @return LRC 原文；没有歌词返回 null，**不抛**（没歌词是常态不是错误）
     */
    suspend fun fetch(
        platformSongId: String,
        title: String,
        artist: String?,
        durationMs: Long?,
    ): String?
}
