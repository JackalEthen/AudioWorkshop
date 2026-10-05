package cn.music.audioworkshop.data.search

import cn.music.audioworkshop.domain.search.MusicSearchException
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 搜索用的共用 HTTP 壳。
 *
 * 五个平台的差异全在 URL、请求头和解析里，网络部分没必要各写一遍。
 */
internal suspend fun OkHttpClient.getJson(
    url: String,
    headers: Map<String, String> = emptyMap(),
): JSONObject = executeJson(url, headers, form = null)

/** form 编码的 POST，只有网易云的 linux/forward 用。 */
internal suspend fun OkHttpClient.postFormJson(
    url: String,
    form: Map<String, String>,
    headers: Map<String, String> = emptyMap(),
): JSONObject = executeJson(url, headers, form = form)

private suspend fun OkHttpClient.executeJson(
    url: String,
    headers: Map<String, String>,
    form: Map<String, String>?,
): JSONObject = withContext(Dispatchers.IO) {
    val builder = Request.Builder().url(url)
    headers.forEach { (name, value) -> builder.header(name, value) }
    if (form == null) {
        builder.get()
    } else {
        val body = FormBody.Builder().apply {
            form.forEach { (name, value) -> add(name, value) }
        }.build()
        builder.post(body)
    }

    newCall(builder.build()).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw MusicSearchException("HTTP ${response.code}：${url.substringBefore('?')}")
        }
        if (text.isBlank()) throw MusicSearchException("平台返回了空内容")
        try {
            JSONObject(text)
        } catch (error: Exception) {
            throw MusicSearchException("平台返回的不是 JSON", error)
        }
    }
}

/**
 * 纯文本 GET。失败返回空串。
 *
 * 歌词那边用这个就够了，不必抛 MusicSearchException —— 那是搜索的异常类型。
 * **headers 很重要**：QQ 歌词接口不给 `Referer` 会返回 HTTP 200 + `retcode=-1310`，
 * 看起来像「这歌没歌词」，其实是请求没被受理。
 */
internal suspend fun OkHttpClient.getText(
    url: String,
    headers: Map<String, String> = emptyMap(),
): String = withContext(Dispatchers.IO) {
    runCatching {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        newCall(builder.get().build()).execute().use { it.body?.string().orEmpty() }
    }.getOrDefault("")
}

/**
 * 网易云 linuxapi 加密：`AES-128-ECB/PKCS5`，密钥固定，转大写 hex。
 *
 * 搜索和歌词都要用，所以放这里。密钥与 lx 的 `linuxapiKey` 相同
 * （同一个 key 两种写法：ASCII 与 hex）。
 */
internal fun encryptLinuxApi(text: String): String {
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(LINUX_API_KEY, "AES"))
    return cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { String.format(Locale.ROOT, "%02X", it) }
}

/** "rFgB&h#%2?^eDg:Q" 的 16 字节原始形式。 */
private val LINUX_API_KEY = "rFgB&h#%2?^eDg:Q".toByteArray(Charsets.US_ASCII)

/** 咪咕的请求签名。固定盐 + deviceId + 时间戳的 MD5。 */
internal fun md5Hex(text: String): String = MessageDigest.getInstance("MD5")
    .digest(text.toByteArray())
    .joinToString("") { "%02x".format(it) }

/** 各平台歌手字段拼起来的分隔符，lx 用「、」，这里跟着它。 */
internal fun List<String>.joinSingers(): String? =
    filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString("、")

/** 毫秒转毫秒，顺便把异常值和 0 一起归零。 */
internal fun Long?.toDurationMsOrNull(): Long? = this?.takeIf { it > 0L }

internal fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

/** URL 拼接用的安全编码，酷狗/咪咕/网易都要求。 */
internal fun String.urlEncoded(): String =
    java.net.URLEncoder.encode(this, "UTF-8")
