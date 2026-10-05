package cn.music.audioworkshop.data.lyrics

import cn.music.audioworkshop.data.search.encryptLinuxApi
import cn.music.audioworkshop.data.search.getText
import cn.music.audioworkshop.data.search.postFormJson
import cn.music.audioworkshop.util.SourceLog
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * 网易云歌词。
 *
 * 两条路，先官方后社区库：
 *
 * 1. **官方** `song/lyric`，经 `api/linux/forward` 代理。
 *    官方接口本身在 `weapi` 下，要 RSA + 双层 AES；但 `linux/forward` 这层
 *    只要 linuxapi 的 AES-128-ECB，密钥固定，能直接代理到 `song/lyric`。
 *    实测返回 `lrc`（原文）、`tlyric`（译文）、`klyric`（逐字）、`romalrc`（罗马音）。
 *    这里只取 `lrc` —— 译文要改解析器合并时间轴，逐字是 YRC 格式，两样都不划算。
 *
 *    参考 `jitwxs/163MusicLyrics` 的 `NetEaseMusicNativeApi.GetLyric`
 *    （Apache-2.0，仅参考用哪些 lv/tv/kv 参数拿全歌词）。
 *
 * 2. **社区库** `amll-ttml-db`，**CC0-1.0**（公共领域，无需署名）。
 *    官方没有收录的冷门歌这里可能有。命名 `ncm-lyrics/{id}.lrc`。
 *
 * 刻意**不取 `.yrc`**：逐字歌词是 YRC 格式，现有解析器只吃 LRC，
 * 喂进去只会解析出空文本，不如老实没有。
 */
internal class NeteaseLyricsFetcher(private val client: OkHttpClient) {

    suspend fun fetch(songId: String): String? {
        if (songId.isBlank()) return null
        return official(songId) ?: community(songId)
    }

    private suspend fun official(songId: String): String? = runCatching {
        val params = JSONObject()
            .put("id", songId)
            .put("os", "pc")
            .put("lv", -1)
            .put("kv", -1)
            .put("tv", -1)
            .put("rv", -1)
            .toString()
        val payload = JSONObject()
            .put("method", "POST")
            .put("url", LYRIC_URL)
            .put("params", params)
            .toString()

        client.postFormJson(
            url = FORWARD_URL,
            form = mapOf("eparams" to encryptLinuxApi(payload)),
            headers = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://music.163.com/",
            ),
        ).optJSONObject("lrc")
            ?.optString("lyric")
            ?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("<?xml") }
    }.onFailure { SourceLog.e("Lyrics", "网易官方歌词失败 $songId", it) }
        .getOrNull()

    private suspend fun community(songId: String): String? =
        client.getText("$RAW_BASE/ncm-lyrics/${enc(songId)}.lrc")
            .takeIf { it.isNotBlank() && !it.trimStart().startsWith("404") }

    private companion object {
        const val FORWARD_URL = "https://music.163.com/api/linux/forward"
        const val LYRIC_URL = "https://music.163.com/api/song/lyric"
        const val RAW_BASE = "https://raw.githubusercontent.com/Steve-XMH/amll-ttml-db/main"

        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36"
    }
}

/**
 * QQ 音乐歌词：官方接口，匿名可用。
 *
 * **必须带 `Referer`，否则返回 `retcode=-1310` 且没有 `lyric` 字段。**
 * 这不是 403也不是空歌词，是 HTTP 200 + 错误码，很容易被当成「这首歌没歌词」
 * 而误判成覆盖率问题。`nobase64=1` 让它别做 base64。
 *
 * 路径里的 `fcgi-bin` 不能写成 `fcg-bin`，写错是 404。
 * `g_tk` 是 QQ 那套伪 csrf，匿名请求给任意值即可。
 */
internal class QqLyricsFetcher(private val client: OkHttpClient) {

    suspend fun fetch(songmid: String): String? {
        if (songmid.isBlank()) return null
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=${enc(songmid)}&format=json&nobase64=1&g_tk=5381"
        return client.getText(url, QQ_HEADERS).extractQqLyric()
    }

    private companion object {
        val QQ_HEADERS = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://y.qq.com/",
        )

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

/**
 * 从 QQ 响应里取出歌词正文。
 *
 * `retcode` 非 0 就是没拿到 —— `-1310` 是缺 `Referer`，
 * `-1901` 之类是这首歌真没歌词。两种都返回 null，不往下猜。
 *
 * 响应通常是纯 JSON，但偶尔会被包成 JSONP，所以先剥一层壳。
 */
internal fun String.extractQqLyric(): String? {
    val trimmed = trim().removeSuffix(");").removeSuffix(")")
    // 没有 `(` 就是纯 JSON，只有被包成 JSONP 时才剥壳。
    val body = trimmed.substringAfter("(", trimmed)
    val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
    if (obj.optInt("retcode", 0) != 0) return null
    return obj.optString("lyric")
        .takeIf { it.isNotBlank() && !it.trimStart().startsWith("<?xml") }
}

internal fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
