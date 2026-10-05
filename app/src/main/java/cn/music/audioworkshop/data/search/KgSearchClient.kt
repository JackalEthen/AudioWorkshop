package cn.music.audioworkshop.data.search

import cn.music.audioworkshop.domain.model.SourceCode
import cn.music.audioworkshop.domain.search.MusicSearchClient
import cn.music.audioworkshop.domain.search.MusicSearchException
import cn.music.audioworkshop.domain.search.SearchHit
import cn.music.audioworkshop.domain.search.SearchResult
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 酷狗（kg）。一条无签名 GET。
 *
 * 搜索本身不要签名，但**求播放地址要**（`infSign`）—— 那部分交给音源脚本做，
 * 我们只负责把 `FileHash` 搜出来。对应 lx 的 `musicSdk/kg/musicSearch.js`。
 */
internal class KgSearchClient(private val client: OkHttpClient) : MusicSearchClient {

    override val platform: String = SourceCode.KUGOU

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        val url = buildString {
            append("https://songsearch.kugou.com/song_search_v2?keyword=").append(keyword.urlEncoded())
            append("&page=").append(page)
            append("&pagesize=").append(limit)
            append("&userid=0&clientver=&platform=WebFilter&filter=2")
            append("&iscorrection=1&privilege_filter=0&area_code=1")
        }
        val root = client.getJson(url, mapOf("User-Agent" to DESKTOP_UA))

        val data = root.optJSONObject("data")
            ?: throw MusicSearchException("酷狗返回里没有 data")
        val list: JSONArray = data.optJSONArray("lists")
            ?: throw MusicSearchException("酷狗返回里没有 lists")

        val hits = buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val hash = item.optString("FileHash")
                if (hash.isBlank()) continue
                add(
                    SearchHit(
                        platform = platform,
                        platformSongId = hash,
                        title = item.optString("SongName").orNullIfBlank() ?: "未命名",
                        artist = item.optString("SingerName").orNullIfBlank()
                            ?: item.optJSONArray("Singers").toSingerNames(),
                        album = item.optString("AlbumName").orNullIfBlank(),
                        coverUrl = item.optString("Image").orNullIfBlank(),
                        // 酷狗这个字段是秒，不是毫秒。
                        durationMs = item.optLong("Duration").takeIf { it > 0L }?.times(1000L),
                    )
                )
            }
        }

        val total = data.optInt("total", hits.size)
        return SearchResult(
            platform = platform,
            hits = hits,
            total = total,
            allPage = if (limit > 0) (total + limit - 1) / limit else 1,
        )
    }
}

/** 酷狗的歌手可能是逗号串也可能是对象数组，两种都认。 */
private fun JSONArray?.toSingerNames(): String? {
    if (this == null) return null
    val names = buildList {
        for (i in 0 until length()) {
            when (val entry = opt(i)) {
                is String -> add(entry)
                is JSONObject -> optJSONObject(i)?.optString("name")?.let(::add)
            }
        }
    }
    return names.joinSingers()
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
