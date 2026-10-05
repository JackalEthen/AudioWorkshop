package cn.music.audioworkshop.domain.naming

import cn.music.audioworkshop.domain.model.DEFAULT_AUTO_NAME_PATTERN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoNamerTest {

    private val input = NamingInput(
        title = "一昭成名",
        artist = "xyz",
        album = "示范专辑",
        date = "20260926",
    )

    @Test
    fun defaultPatternRendersTokens() {
        val result = AutoNamer.render(DEFAULT_AUTO_NAME_PATTERN, input)
        assertTrue(result is NamingResult.Success)
        assertEquals("一昭成名-xyz", result.baseName)
    }

    @Test
    fun illegalCharactersAreReplaced() {
        val result = AutoNamer.render("{歌名}/{歌手}", input)
        assertTrue(result is NamingResult.Success)
        assertEquals("一昭成名_xyz", result.baseName)
    }

    @Test
    fun unknownTokenIsReported() {
        val result = AutoNamer.render("{歌名}-{未知}", input)
        assertTrue(result is NamingResult.Failure)
        assertTrue((result as NamingResult.Failure).message.contains("未知"))
    }

    @Test
    fun missingTokensFallBackToLabels() {
        val blank = NamingInput(title = null, artist = null, album = null, date = "")
        val result = AutoNamer.render("{歌名}-{歌手}-{专辑}-{日期}", blank)
        assertTrue(result is NamingResult.Success)
        assertEquals("未知歌曲-未知歌手-未知专辑-未知日期", result.baseName)
    }

    @Test
    fun regexModeExtractsGroups() {
        val result = AutoNamer.render("""re:^(.+?)-(.+?)$ {2}-{1}""", input)
        assertTrue(result is NamingResult.Success)
        assertEquals("xyz-一昭成名", result.baseName)
    }

    @Test
    fun brokenRegexIsReportedNotThrown() {
        val result = AutoNamer.render("re:[unclosed", input)
        assertTrue(result is NamingResult.Failure)
    }
}
