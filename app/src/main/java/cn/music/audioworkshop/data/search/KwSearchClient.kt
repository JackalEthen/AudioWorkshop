package cn.music.audioworkshop.data.search

import cn.music.audioworkshop.domain.model.SourceCode
import cn.music.audioworkshop.domain.search.MusicSearchClient
import cn.music.audioworkshop.domain.search.MusicSearchException
import cn.music.audioworkshop.domain.search.SearchHit
import cn.music.audioworkshop.domain.search.SearchResult
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * 酷我（kw）。一条无签名 GET，没有加密。
 *
 * 端点和字段映射对着 lx-music-mobile 的 `musicSdk/kw/musicSearch.js` 抄的，
 * 但 lx 那份音质判断有 bug（按 `format` 匹配 `'4000'/'2000'`，
 * 而真实返回的 format 是 `flac`/`mgg`/`mp3`，一条都匹配不上），
 * 所以我们只取歌曲基本信息，不解析音质。
 */
internal class KwSearchClient(private val client: OkHttpClient) : MusicSearchClient {

    override val platform: String = SourceCode.KUWO

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        val url = buildString {
            append("https://search.kuwo.cn/r.s?client=kt")
            append("&all=").append(keyword.urlEncoded())
            append("&pn=").append(page - 1)
            append("&rn=").append(limit)
            append("&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1")
            append("&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012")
            append("&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1")
        }
        val root = client.getJson(url)

        val list = root.optJSONArray("abslist")
            ?: throw MusicSearchException("酷我返回里没有 abslist")

        val hits = buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val rid = item.optString("MUSICRID").removePrefix("MUSIC_")
                if (rid.isBlank()) continue
                add(
                    SearchHit(
                        platform = platform,
                        platformSongId = rid,
                        title = item.optString("SONGNAME").orNullIfBlank() ?: "未命名",
                        artist = item.optString("ARTIST").orNullIfBlank(),
                        album = item.optString("ALBUM").orNullIfBlank(),
                        coverUrl = null,
                        durationMs = item.optLong("DURATION").takeIf { it > 0L }?.times(1000L),
                    )
                )
            }
        }

        val total = root.optString("TOTAL").toIntOrNull() ?: hits.size
        return SearchResult(
            platform = platform,
            hits = hits,
            total = total,
            allPage = if (limit > 0) (total + limit - 1) / limit else 1,
        )
    }
}
