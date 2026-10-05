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
 * QQ 音乐（tx）。**无签名**。
 *
 * 走的是老的 `search_for_qq_cp` 接口。
 *
 * 为什么不用 lx 那套：lx 打的是新接口 `DoSearchForQQMusicDesktop`，
 * 签名（zzcSign = SHA1 + 定索引取字节 + XOR 常量）算得出来、服务器也认
 * （`code=2000`，错签名才是 500001），但未登录时**返回 0 条结果**。
 * 老接口无签名且照常返回数据，所以用老接口。
 *
 * 参考 `guohuiyuan/music-lib` 的 `qq/song.go`（AGPL-3.0，仅参考请求格式）。
 */
internal class TxSearchClient(private val client: OkHttpClient) : MusicSearchClient {

    override val platform: String = SourceCode.TENCENT

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        val url = buildString {
            append("https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?w=").append(keyword.urlEncoded())
            append("&format=json&p=").append(page)
            append("&n=").append(limit)
        }
        val root = client.getJson(
            url,
            mapOf(
                "User-Agent" to MOBILE_UA,
                "Referer" to "https://m.y.qq.com/",
            ),
        )

        val list = root.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list")
            ?: throw MusicSearchException("QQ 音乐返回里没有 data.song.list")

        val hits = buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                // songmid 是音源换直链要的；老接口偶尔只给 songid。
                val mid = item.optString("songmid").orNullIfBlank()
                    ?: item.optLong("songid").takeIf { it > 0L }?.toString()
                    ?: continue
                val albumMid = item.optString("albummid").orNullIfBlank()
                add(
                    SearchHit(
                        platform = platform,
                        platformSongId = mid,
                        title = item.optString("songname").orNullIfBlank() ?: "未命名",
                        artist = item.optJSONArray("singer").toSingerNames(),
                        album = item.optString("albumname").orNullIfBlank(),
                        // 封面能从 albummid 直接拼出来，不用再发一次请求。
                        coverUrl = albumMid?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" },
                        durationMs = item.optInt("interval").takeIf { it > 0 }?.times(1000L),
                    )
                )
            }
        }

        val total = root.optInt("totalnum", hits.size)
        return SearchResult(
            platform = platform,
            hits = hits,
            total = total,
            allPage = if (limit > 0) (total + limit - 1) / limit else 1,
        )
    }
}

private fun JSONArray?.toSingerNames(): String? {
    if (this == null) return null
    val names = buildList {
        for (i in 0 until length()) {
            optJSONObject(i)?.optString("name")?.let(::add)
        }
    }
    return names.joinSingers()
}

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
