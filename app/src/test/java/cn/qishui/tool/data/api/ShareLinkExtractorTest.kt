package cn.qishui.tool.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShareLinkExtractorTest {
    private val extractor = ShareLinkExtractor()

    @Test
    fun extractsFirstAllowedUrl() {
        val shareInput = "复制打开抖音 https://music.douyin.com/song/123/?from=share，查看内容"

        assertEquals(
            "https://music.douyin.com/song/123/?from=share",
            extractor.extract(shareInput),
        )
    }

    @Test
    fun rejectsEmptyInput() {
        assertThrows(ShareLinkException::class.java) {
            extractor.extract("没有可解析的链接")
        }
    }

    @Test
    fun rejectsIllegalDomain() {
        assertThrows(ShareLinkException::class.java) {
            extractor.extract("https://music.douyin.com.evil.example/song/123")
        }
    }
}
