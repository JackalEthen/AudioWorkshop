package cn.music.audioworkshop.data.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 从分享文本里提取链接。
 *
 * 不做站点白名单 —— 解析源是用户自己配的，各家接口支持的平台差别很大
 * （网易云、B站、酷我、QQ音乐、快手、抖音、小红书、微博……），写死白名单
 * 会把大部分链接挡在门外。只拒绝明显的本地地址，避免解析接口被指向内网。
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
        // 没有域名白名单 —— 解析源由用户配置，各家接口支持的平台差别太大。
        // 只挡本机与私有网段，避免解析接口被指向内网（SSRF）。
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

        /**
         * 本机和私有地址。解析接口是用户配置的，但不能让它去请求内网 ——
         * 那是 SSRF。虽然是本地应用、风险有限，但这一条检查几乎零成本。
         */
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
