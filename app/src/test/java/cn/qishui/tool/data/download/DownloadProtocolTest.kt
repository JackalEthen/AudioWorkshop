package cn.qishui.tool.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProtocolTest {
    @Test
    fun appendsOnlyMatchingPartialResponse() {
        assertTrue(canAppendPartialResponse(206, 128L, "bytes 128-255/256"))
        assertFalse(canAppendPartialResponse(200, 128L, null))
        assertFalse(canAppendPartialResponse(206, 127L, "bytes 128-255/256"))
        assertFalse(canAppendPartialResponse(206, 128L, "bytes */256"))
    }

    @Test
    fun parsesContentRange() {
        assertEquals(
            ContentRange(start = 128L, end = 255L, totalLength = 256L),
            parseContentRange("bytes 128-255/256"),
        )
        assertEquals(
            ContentRange(start = 0L, end = 10L, totalLength = null),
            parseContentRange("bytes 0-10/*"),
        )
        assertEquals(null, parseContentRange("bytes 10-9/20"))
        assertEquals(256L, parseUnsatisfiedContentLength("bytes */256"))
    }

    @Test
    fun buildsSafeDownloadFileName() {
        assertEquals(
            "歌名-歌手.m4a",
            safeDownloadFileName("歌名", "歌手", "m4a"),
        )
        assertEquals(
            "a_b-c_d.flac",
            safeDownloadFileName("a/b", "c:d", "flac"),
        )
        assertEquals(
            "下载-未知歌手.mp3",
            safeDownloadFileName(null, null, "mp3"),
        )
    }

    @Test
    fun validatesMd5HexIgnoringCase() {
        assertTrue(md5HexMatches("0123456789ABCDEF0123456789abcdef", "0123456789abcdef0123456789abcdef"))
        assertFalse(md5HexMatches("0123456789abcdef0123456789abcdef", "0123456789abcdef0123456789abcdee"))
        assertFalse(md5HexMatches(null, "0123456789abcdef0123456789abcdef"))
    }
}
