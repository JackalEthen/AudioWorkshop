package cn.music.audioworkshop.data.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 从分享文本里提取链接。
 *
 * 不做站点白名单 —— 解析源是用户自己配的，各家接口支持的平台差别很大
 * （网易云、B站、酷我、QQ音乐、快手、抖音、小红书、微博……），写死白名单
 * 会把大部分链接挡在门外。
 *
 * 下面这段本机/私网检查**不是 SSRF 防护**：这个 URL 从不被本 App 请求，
 * 它只作为 query 参数发给用户配置的远端解析接口，真正发起请求的是那个接口。
 * 本 App 唯一的任意 URL 请求出口是 lx 脚本的 HTTP 桥（`LxSourceEngine.handleScriptHttp`）。
 * 这里保留检查只是挡住「把 localhost 当分享链接粘进来」这种手滑。
 */
class ShareLinkExtractor {
    fun extract(shareInput: String): String {
        val rawUrl = UrlPattern.find(shareInput)
            ?.value
            ?.trimEnd(*TrailingCharacters)
            ?: throw ShareLinkException("未找到链接")
        val url = rawUrl.toHttpUrlOrNull()
            ?: throw ShareLinkException("链接格式无效")
        val host = url.host.lowercase()
        if (host in BlockedHosts || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) {
            throw ShareLinkException("不支持的链接地址")
        }
        return rawUrl
    }

    companion object {
        private val UrlPattern = Regex(
            """https?://[^\s<>"'，。！？、）】》]+""",
            RegexOption.IGNORE_CASE,
        )

        private val BlockedHosts = setOf(
            "localhost",
            "127.0.0.1",
            "0.0.0.0",
            "::1",
            "[::1]",
            "10.0.2.2",
        )

        private val TrailingCharacters = charArrayOf(
            '。', '，', '！', '？', '、', '；', '：', '"', '\'', '）', '】', '》',
            '.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'',
        )
    }
}

class ShareLinkException(message: String) : Exception(message)
