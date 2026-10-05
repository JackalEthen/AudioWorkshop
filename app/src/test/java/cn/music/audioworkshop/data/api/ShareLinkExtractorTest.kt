package cn.music.audioworkshop.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 分享链接提取。
 *
 * 不再有域名白名单 —— 解析源由用户在设置页自行配置，各家接口支持的平台
 * 差异极大（网易云、B站、酷我、QQ音乐、快手、抖音、小红书、微博……），
 * 写死白名单会把大部分链接挡掉。所以这里只挡本机与私有网段。
 */
class ShareLinkExtractorTest {
    private val extractor = ShareLinkExtractor()

    @Test
    fun extractsFirstUrl() {
        val shareInput = "【抖音】快来看这个 https://music.douyin.com/song/123/?from=share 复制打开"
        assertEquals(
            "https://music.douyin.com/song/123/?from=share",
            extractor.extract(shareInput),
        )
    }

    @Test
    fun rejectsEmptyInput() {
        assertThrows(ShareLinkException::class.java) {
            extractor.extract("没有任何链接")
        }
    }

    @Test
    fun acceptsAnyPublicHost() {
        // 各平台链接都应能提取出来，交给解析源自己判断支不支持
        val hosts = listOf(
            "https://music.163.com/song?id=186016",
            "https://www.bilibili.com/video/BV1xx411c7mD",
            "https://www.kuwo.cn/play_detail/single/1636114614",
            "https://y.qq.com/n/ryqq/songDetail/001Qu4I30eVFYb",
            "https://v.douyin.com/iRNBho6G/",
            "https://www.xiaohongshu.com/explore/123",
            "https://weibo.com/tv/show/1033060822",
            "https://v.kuaishou.com/abcdef",
        )
        hosts.forEach { url ->
            assertEquals(url, extractor.extract("分享链接 $url 复制打开"))
        }
    }

    @Test
    fun rejectsLocalAndPrivateAddresses() {
        // 只挡「把 localhost 当分享链接粘进来」这种手滑。
        // 这不是 SSRF 防护：这个 URL 从不被本 App 请求，只作为 query 参数
        // 发给用户配置的远端解析接口。
        val blocked = listOf(
            "https://localhost/api/parse",
            "https://127.0.0.1:8080/parse",
            "https://10.0.2.2/parse",
            "https://192.168.1.1/parse",
        )
        blocked.forEach { url ->
            assertThrows("应拒绝 $url", ShareLinkException::class.java) {
                extractor.extract("链接 $url")
            }
        }
    }

    @Test
    fun rejectsMalformedUrl() {
        assertThrows(ShareLinkException::class.java) {
            extractor.extract("https://")
        }
    }
}
