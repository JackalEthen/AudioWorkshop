package cn.qishui.tool.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QsmusicResponseParserTest {
    private val parser = QsmusicResponseParser()

    @Test
    fun parsesCompleteResponse() {
        val response = """
            {
              "code": 200,
              "msg": "success",
              "data": {
                "url": "https://audio.example/song.m4a",
                "video_meta": {
                  "quality": "medium",
                  "vtype": "m4a",
                  "codec_type": "aac",
                  "bitrate": 64000,
                  "real_bitrate": 65441,
                  "size": 2101312,
                  "file_hash": "0123456789abcdef0123456789ABCDEF",
                  "audio_sample_rate": 44100
                },
                "lyric": "[0,1000]一昭成名",
                "albumname": "一昭成名",
                "artistsname": "一昭成名",
                "artistsmedium_avatar_url": [
                  "https://p11.douyinpic.example/avatar.jpeg",
                  "https://p3.douyinpic.example/avatar.jpeg"
                ]
              },
              "cache_expire_at": 1893456000
            }
        """.trimIndent()

        val track = parser.parse(response)

        assertEquals("一昭成名", track.title)
        assertEquals("一昭成名", track.artist)
        assertEquals("https://audio.example/song.m4a", track.audioUrl)
        assertEquals("m4a", track.format)
        assertEquals("aac", track.codec)
        assertEquals("medium", track.quality)
        assertEquals(65441L, track.bitrateBps)
        assertEquals(2101312L, track.sizeBytes)
        assertEquals("0123456789abcdef0123456789ABCDEF", track.fileHash)
        assertEquals(44100L, track.sampleRateHz)
        assertEquals("[0,1000]一昭成名", track.lyrics)
        assertEquals(
            listOf(
                "https://p11.douyinpic.example/avatar.jpeg",
                "https://p3.douyinpic.example/avatar.jpeg",
            ),
            track.artistAvatarUrls,
        )
        assertEquals(1893456000L, track.cacheExpiresAtEpochSeconds)
    }

    @Test
    fun rejectsNonSuccessfulCode() {
        val error = assertThrows(ApiFormatException::class.java) {
            parser.parse("""{"code":429,"msg":"请求过于频繁"}""")
        }

        assertTrue(error.message.orEmpty().contains("429"))
    }

    @Test
    fun rejectsMissingData() {
        val error = assertThrows(ApiFormatException::class.java) {
            parser.parse("""{"code":200,"msg":"success"}""")
        }

        assertTrue(error.message.orEmpty().contains("data"))
    }
}
