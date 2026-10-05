package cn.music.audioworkshop.data.parse

import cn.music.audioworkshop.domain.model.ParseApiSource
import cn.music.audioworkshop.domain.model.ResolvedTrack
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 通用解析接口客户端。
 *
 * 请求 URL 按 [ParseApiSource] 拼装：
 *  - `url` 参数是被解析的分享链接
 *  - `type` 参数在 [ParseApiSource.typeParam] 非空时附加
 *    （探测到的 api.bugpk.com 不传 type 会返回「缺少type参数」）
 *  - `key` 与 `apikey` 同时附加，覆盖两种常见鉴权命名
 *
 * 响应结构交给 [ParseResponseMatcher] 处理，不做硬编码。
 */
class GenericParseApiClient(
    private val client: OkHttpClient,
    private val requestIntervalNanos: Long = MinRequestIntervalNanos,
) {
    private val lock = Any()
    private var lastRequestStartedAtNanos: Long? = null

    fun fetch(source: ParseApiSource, shareUrl: String): String {
        val builder = source.url.trim().toHttpUrl().newBuilder()
            .addQueryParameter("url", shareUrl)
        if (source.typeParam.isNotBlank()) {
            builder.addQueryParameter("type", source.typeParam.trim())
        }
        if (source.apiKey.isNotBlank()) {
            // 两个名字都带上：接口方用 key 或 apikey 的都有
            builder.addQueryParameter("key", source.apiKey.trim())
            builder.addQueryParameter("apikey", source.apiKey.trim())
        }

        waitForRateLimit()
        val request = Request.Builder().url(builder.build()).get().build()
        return client.newCall(request)
            .apply { timeout().timeout(TimeoutSeconds, TimeUnit.SECONDS) }
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    throw GenericParseException("接口请求失败，HTTP ${response.code}")
                }
                response.body?.string()
                    ?: throw GenericParseException("接口未返回内容")
            }
    }

    /** 同一源连续请求时留间隔，避免被限流。 */
    private fun waitForRateLimit() {
        if (requestIntervalNanos <= 0L) return
        synchronized(lock) {
            val wait = lastRequestStartedAtNanos?.let { started ->
                requestIntervalNanos - (System.nanoTime() - started)
            } ?: 0L
            if (wait > 0L) {
                try {
                    TimeUnit.NANOSECONDS.sleep(wait)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("请求被中断", error)
                }
            }
            lastRequestStartedAtNanos = System.nanoTime()
        }
    }

    companion object {
        private const val TimeoutSeconds = 15L
        private const val MinRequestIntervalNanos = 800_000_000L
    }
}

class GenericParseException(message: String, cause: Throwable? = null) : IOException(message, cause)
