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
 * 网易云（wy）。
 *
 * 走 `linux/forward` + linuxapi 加密：`AES-128-ECB/PKCS5`，
 * 密钥是固定的 `rFgB&h#%2?^eDg:Q`。
 *
 * **不要用 lx 的 EAPI 那条路。** lx 打的是 `eapi/batch` + EAPI，
 * 而且它的 Java `AES.encrypt` 用 `AES/ECB/NoPadding` —— base64 之后长度
 * 不是 16 的倍数时 `doFinal` 抛异常，被 `catch` 吞掉后返回空字符串，
 * 于是 params 是空的、服务端返回空响应。这条路目前是坏的。
 *
 * 密钥与 lx 的 `linuxapiKey` 相同（同一个 key 两种写法：ASCII 与 hex）。
 * 参考 `guohuiyuan/music-lib` 的 `netease/crypto.go`（AGPL-3.0，仅参考算法步骤）。
 */
internal class WySearchClient(private val client: OkHttpClient) : MusicSearchClient {

    override val platform: String = SourceCode.NETEASE

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        val inner = JSONObject()
            .put("s", keyword)
            .put("type", 1)
            .put("offset", limit * (page - 1))
            .put("limit", limit)
            .toString()

        val payload = JSONObject()
            .put("method", "POST")
            .put("url", CLOUD_SEARCH_URL)
            .put("params", JSONObject(inner))
            .toString()

        val root = client.postFormJson(
            url = FORWARD_URL,
            form = mapOf("eparams" to encryptLinuxApi(payload)),
            headers = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://music.163.com/",
            ),
        )

        val result = root.optJSONObject("result")
            ?: throw MusicSearchException("网易云返回里没有 result")
        val songs: JSONArray = result.optJSONArray("songs")
            ?: throw MusicSearchException("网易云返回里没有 result.songs")

        val hits = buildList {
            for (i in 0 until songs.length()) {
                val item = songs.optJSONObject(i) ?: continue
                val id = item.optLong("id").takeIf { it > 0L }?.toString() ?: continue
                val album = item.optJSONObject("al")
                add(
                    SearchHit(
                        platform = platform,
                        platformSongId = id,
                        title = item.optString("name").orNullIfBlank() ?: "未命名",
                        artist = item.optJSONArray("ar").toArtistNames(),
                        album = album?.optString("name").orNullIfBlank(),
                        coverUrl = album?.optString("picUrl").orNullIfBlank(),
                        // 网易云这个字段是毫秒，不用换算。
                        durationMs = item.optLong("dt").takeIf { it > 0L },
                    )
                )
            }
        }

        val total = result.optInt("songCount", hits.size)
        return SearchResult(
            platform = platform,
            hits = hits,
            total = total,
            allPage = if (limit > 0) (total + limit - 1) / limit else 1,
        )
    }

    private companion object {
        const val FORWARD_URL = "https://music.163.com/api/linux/forward"
        const val CLOUD_SEARCH_URL = "https://music.163.com/api/cloudsearch/pc"

        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36"
    }
}

private fun JSONArray?.toArtistNames(): String? {
    if (this == null) return null
    val names = buildList {
        for (i in 0 until length()) {
            optJSONObject(i)?.optString("name")?.let(::add)
        }
    }
    return names.joinSingers()
}
