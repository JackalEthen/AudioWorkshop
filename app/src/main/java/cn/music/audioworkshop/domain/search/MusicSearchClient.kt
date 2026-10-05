package cn.music.audioworkshop.domain.search

import cn.music.audioworkshop.domain.model.SourceCode

/**
 * 一条搜索结果。
 *
 * 这不是 [cn.music.audioworkshop.domain.model.SourceTrack] —— 搜索结果还没被用户留下，
 * 只是候选。点「加入曲库」之后才变成 SourceTrack。
 */
data class SearchHit(
    /** 来源平台，见 [SourceCode]。 */
    val platform: String,
    /** 平台歌曲 id。求播放直链必需，音源拿不到 id 就换不了地址。 */
    val platformSongId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
) {
    /**
     * 稳定键。同平台同一首歌重复入库时 upsert 到同一条，不会刷出重复行。
     */
    val trackId: String get() = "$platform:$platformSongId"
}

data class SearchResult(
    val platform: String,
    val hits: List<SearchHit>,
    val total: Int,
    val allPage: Int,
)

/**
 * 单平台歌曲搜索。
 *
 * **注意 lx 音源协议里没有 `search` 这个 action** —— 脚本给不了搜索结果，
 * 所以每个平台必须自己实现一份。对应 lx-music-mobile 里
 * `src/utils/musicSdk/` 下各平台的 `musicSearch.js`。
 */
interface MusicSearchClient {
    val platform: String

    /** @param page 从 1 开始 */
    suspend fun search(keyword: String, page: Int, limit: Int): SearchResult
}

class MusicSearchException(message: String, cause: Throwable? = null) : Exception(message, cause)
