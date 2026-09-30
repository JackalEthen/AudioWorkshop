package cn.qishui.tool.data.search

import cn.qishui.tool.domain.model.SourceCode
import cn.qishui.tool.domain.search.MusicSearchClient
import cn.qishui.tool.domain.search.MusicSearchException
import cn.qishui.tool.domain.search.SearchHit
import cn.qishui.tool.domain.search.SearchResult
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 咪咕（mg）。签名是固定盐 + deviceId + 时间戳的 MD5，几十行就能复刻。
 *
 * 两个坑：
 * - `resultList` 是**数组的数组**（`[[{...}]]`），不是对象数组
 * - 歌手字段叫 `singerList`，不是 `singers`
 *
 * 对应 lx 的 `musicSdk/mg/musicSearch.js`。
 */
internal class MgSearchClient(private val client: OkHttpClient) : MusicSearchClient {

    override val platform: String = SourceCode.MIGU

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        val timestamp = System.currentTimeMillis().toString()
        val sign = md5Hex("$keyword$SIGNATURE_SALT$SECRET$DEVICE_ID$timestamp")

        val url = buildString {
            append("https://jadeite.migu.cn/music_search/v3/search/searchAll")
            append("?isCorrect=0&isCopyright=1")
            append("&searchSwitch=").append(SEARCH_SWITCH)
            append("&pageSize=").append(limit)
            append("&text=").append(keyword.urlEncoded())
            append("&pageNo=").append(page)
            append("&sort=0&sid=USS")
        }

        val root = client.getJson(
            url = url,
            headers = mapOf(
                "uiVersion" to "A_music_3.6.1",
                "deviceId" to DEVICE_ID,
                "timestamp" to timestamp,
                "sign" to sign,
                "channel" to "0146921",
                "User-Agent" to MOBILE_UA,
            ),
        )

        // 咪咕的成功码是字符串 "000000"，不是 0。
        val code = root.optString("code")
        if (code.isNotBlank() && code != SUCCESS_CODE) {
            throw MusicSearchException("咪咕返回错误码 $code")
        }

        val songData = root.optJSONObject("songResultData")
            ?: throw MusicSearchException("咪咕返回里没有 songResultData")
        val rawList: JSONArray = songData.optJSONArray("resultList")
            ?: throw MusicSearchException("咪咕返回里没有 songResultData.resultList")

        val hits = buildList {
            for (i in 0 until rawList.length()) {
                // 外层是数组的数组，内层才是歌曲对象。
                val entry = rawList.opt(i) ?: continue
                val song = when (entry) {
                    is JSONObject -> entry
                    is JSONArray -> entry.optJSONObject(0)
                    else -> null
                } ?: continue
                val copyrightId = song.optString("copyrightId").orNullIfBlank() ?: continue
                add(
                    SearchHit(
                        platform = platform,
                        platformSongId = copyrightId,
                        title = song.optString("songName").orNullIfBlank() ?: "未命名",
                        artist = song.optJSONArray("singerList").toSingerNames(),
                        album = song.optString("album").orNullIfBlank(),
                        coverUrl = song.optString("img1").orNullIfBlank(),
                        // 咪咕这个字段是毫秒。
                        durationMs = song.optLong("duration").takeIf { it > 0L },
                    )
                )
            }
        }

        val total = songData.optString("totalCount").toIntOrNull()
            ?: root.optInt("resultNum", hits.size)
        return SearchResult(
            platform = platform,
            hits = hits,
            total = total,
            allPage = if (limit > 0) (total + limit - 1) / limit else 1,
        )
    }

    private companion object {
        const val SUCCESS_CODE = "000000"
        const val DEVICE_ID = "963B7AA0D21511ED807EE5846EC87D20"
        const val SIGNATURE_SALT = "6cdc72a439cef99a3418d2a78aa28c73"
        const val SECRET = "yyapp2d16148780a1dcc7408e06336b98cfd50"

        /** 只搜歌曲。 */
        const val SEARCH_SWITCH =
            "%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0%2C%22tagSong%22%3A1" +
                "%2C%22mvSong%22%3A0%2C%22bestShow%22%3A1%2C%22songlist%22%3A0%2C%22lyricSong%22%3A0%7D"

        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11) AppleWebKit/534.30 " +
                "(KHTML, like Gecko) Version/4.0 Mobile Safari/534.30"
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
