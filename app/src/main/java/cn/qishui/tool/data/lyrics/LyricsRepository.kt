package cn.qishui.tool.data.lyrics

import cn.qishui.tool.data.search.MusicSearchRepository
import cn.qishui.tool.domain.lyrics.LyricsMatch
import cn.qishui.tool.domain.lyrics.LyricsParser
import cn.qishui.tool.domain.model.LyricsTrack
import cn.qishui.tool.domain.model.SourceCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 歌词仓库。
 *
 * **歌词和歌曲是两件事。** 歌从哪个平台来不影响歌词能不能拿到：只有网易和 QQ
 * 有能用的公开歌词接口，但它们俩的曲库几乎覆盖所有中文歌，所以拿别的平台的歌
 * 时，按歌名反查到这两个平台就行。
 *
 * 顺序：
 * 1. 本平台有官方歌词接口就直接用（网易、QQ），省掉一次搜索
 * 2. 按「歌名 + 歌手」到 QQ 搜，匹配上了取它的官方歌词
 * 3. 同样到网易搜一遍
 * 4. 都没有就是没有，不硬凑
 *
 * 为什么不用第三方歌词库（lrclib 那类）：实测中文歌零覆盖，社区库基本只有
 * 英文歌，兜底等于没有；而且它有时长容差和匹配猜测，配错了比空白更糟。
 *
 * 宁可没有歌词，也不能在唱 A 的时候放 B 的词 —— 匹配规则见 [LyricsMatch]。
 */
class LyricsRepository(
    private val client: OkHttpClient,
    private val parser: LyricsParser,
    private val searchRepository: MusicSearchRepository,
) {

    private val netease = NeteaseLyricsFetcher(client)
    private val tencent = QqLyricsFetcher(client)

    /** 反查顺序：QQ 匿名无签名、最快，先试它。 */
    private val lookupOrder = listOf(SourceCode.TENCENT, SourceCode.NETEASE)

    /**
     * 取歌词并解析成时间轴。任何一步失败都返回 [LyricsTrack.EMPTY] ——
     * 歌词缺失不该让播放出问题。
     */
    suspend fun load(
        platform: String?,
        platformSongId: String?,
        title: String,
        artist: String?,
        durationMs: Long?,
    ): LyricsTrack = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext LyricsTrack.EMPTY

        // 1. 本平台有官方接口就直接用
        direct(platform, platformSongId)?.let { raw ->
            parser.parse(raw).takeIf { it.lines.isNotEmpty() }?.let { return@withContext it }
        }

        // 2/3. 按歌名跨平台反查
        for (target in lookupOrder) {
            if (target == platform) continue
            val hit = findSameSong(target, title, artist, durationMs) ?: continue
            direct(target, hit.platformSongId)?.let { raw ->
                parser.parse(raw).takeIf { it.lines.isNotEmpty() }?.let { return@withContext it }
            }
        }

        LyricsTrack.EMPTY
    }

    /** 平台的官方歌词。wx/wy 之外的平台本来就没有歌词接口。 */
    private suspend fun direct(platform: String?, songId: String?): String? {
        if (songId.isNullOrBlank()) return null
        return when (platform) {
            SourceCode.NETEASE -> netease.fetch(songId)
            SourceCode.TENCENT -> tencent.fetch(songId)
            else -> null
        }
    }

    /** 在目标平台按歌名+歌手搜，命中同一首就返回它。 */
    private suspend fun findSameSong(
        platform: String,
        title: String,
        artist: String?,
        durationMs: Long?,
    ): cn.qishui.tool.domain.search.SearchHit? {
        val keyword = listOf(title, artist).filterNot { it.isNullOrBlank() }.joinToString(" ")
        val hits = runCatching { searchRepository.search(platform, keyword, 1, LOOKUP_LIMIT) }
            .getOrNull()
            ?.hits
            .orEmpty()

        return hits.firstOrNull {
            LyricsMatch.isSameSong(
                queryTitle = title,
                queryArtist = artist,
                queryDurationMs = durationMs,
                hitTitle = it.title,
                hitArtist = it.artist,
                hitDurationMs = it.durationMs,
            )
        }
    }

    private companion object {
        /** 反查时只看前几条 —— 同一首歌在结果里通常排第一，太多反而容易挑花眼。 */
        const val LOOKUP_LIMIT = 5
    }
}
