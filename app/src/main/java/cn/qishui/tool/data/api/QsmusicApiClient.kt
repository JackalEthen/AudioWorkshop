package cn.qishui.tool.data.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class QsmusicApiClient(
    private val client: OkHttpClient,
) {
    private val requestLock = Any()
    private var lastRequestStartedAtNanos: Long? = null

    fun get(shareUrl: String): String {
        waitForRateLimit()
        val request = Request.Builder()
            .url(Endpoint.newBuilder().addQueryParameter("url", shareUrl).build())
            .get()
            .build()

        return client.newCall(request)
            .apply { timeout().timeout(TimeoutSeconds, TimeUnit.SECONDS) }
            .execute()
            .use { response ->
            if (!response.isSuccessful) {
                throw QsmusicRequestException("接口请求失败（HTTP ${response.code}）")
            }
            response.body?.string() ?: throw QsmusicRequestException("接口未返回内容")
        }
    }

    private fun waitForRateLimit() {
        synchronized(requestLock) {
            val waitNanos = lastRequestStartedAtNanos?.let { startedAt ->
                MinRequestIntervalNanos - (System.nanoTime() - startedAt)
            } ?: 0L
            if (waitNanos > 0L) {
                try {
                    TimeUnit.NANOSECONDS.sleep(waitNanos)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("请求已取消", error)
                }
            }
            lastRequestStartedAtNanos = System.nanoTime()
        }
    }

    companion object {
        private val Endpoint = "https://api.bugpk.com/api/qsmusic".toHttpUrl()
        private const val TimeoutSeconds = 15L
        private const val MinRequestIntervalNanos = 1_000_000_000L
    }
}

class QsmusicRequestException(message: String) : IOException(message)
