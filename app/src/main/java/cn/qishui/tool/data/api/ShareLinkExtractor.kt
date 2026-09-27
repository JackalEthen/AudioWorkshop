package cn.qishui.tool.data.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ShareLinkExtractor {
    fun extract(shareInput: String): String {
        val rawUrl = UrlPattern.find(shareInput)
            ?.value
            ?.trimEnd(*TrailingCharacters)
            ?: throw ShareLinkException("未找到分享链接")
        val host = rawUrl.toHttpUrlOrNull()?.host?.lowercase()
            ?: throw ShareLinkException("分享链接格式无效")
        if (AllowedHosts.none { host == it || host.endsWith(".$it") }) {
            throw ShareLinkException("仅支持抖音分享链接")
        }
        return rawUrl
    }

    companion object {
        private val UrlPattern = Regex("""https?://[^\s<>"'，。！？、；：）】《》]+""", RegexOption.IGNORE_CASE)
        private val AllowedHosts = setOf(
            "qishui.douyin.com",
            "music.douyin.com",
            "douyin.com",
            "www.douyin.com",
        )
        private val TrailingCharacters = charArrayOf(
            '，', '。', '！', '？', '、', '；', '：', '）', '】', '》',
            '.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'',
        )
    }
}

class ShareLinkException(message: String) : Exception(message)
