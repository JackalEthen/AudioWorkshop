package cn.music.audioworkshop.domain.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出格式贯通 job：老 JSON 没有 format 键必须按 MP3 处理，
 * 否则用户升级后历史任务会整批反序列化失败（EncoderService 里是静默兜底成 INVALID_JOB）。
 */
class ExportFormatCodecTest {

    private fun job(format: ExportFormat = ExportFormat.WAV) = ExportJob(
        jobId = "job-1",
        editProjectId = "edit-1",
        outputTempPath = "/tmp/out.${format.extension}",
        sources = listOf(
            ExportSource(
                id = "s1",
                localPath = "/tmp/source.mp3",
                title = "歌名",
                artist = "歌手",
                album = null,
                lyrics = null,
                segments = listOf(ExportSegment(0L, 1_000_000L)),
            ),
        ),
        gainDb = 0f,
        format = format,
        fadeInMs = 0L,
        fadeOutMs = 0L,
        lyricOffsetMs = 0L,
    )

    @Test
    fun roundTripPreservesTheChosenFormat() {
        ExportFormat.entries.forEach { format ->
            val decoded = ExportJobCodec.decode(ExportJobCodec.encode(job(format)))
            assertEquals(format, decoded.format)
        }
    }

    @Test
    fun missingFormatKeyFallsBackToMp3SoOldJobsStillLoad() {
        val raw = ExportJobCodec.encode(job(ExportFormat.MP3))
        val stripped = raw.replace(",\"format\":\"MP3\"", "").replace("\"format\":\"MP3\",", "")

        assertTrue(stripped.contains("gainDb"))
        assertEquals(ExportFormat.MP3, ExportJobCodec.decode(stripped).format)
    }

    @Test
    fun unknownFormatNameAlsoFallsBackInsteadOfThrowing() {
        val raw = ExportJobCodec.encode(job(ExportFormat.MP3))
            .replace("\"format\":\"MP3\"", "\"format\":\"WMA\"")

        assertEquals(ExportFormat.MP3, ExportJobCodec.decode(raw).format)
    }

    @Test
    fun onlyLosslessFormatsSkipTheBitrate() {
        assertTrue(ExportFormat.MP3.supportsBitrate)
        assertTrue(!ExportFormat.WAV.supportsBitrate)
        assertTrue(!ExportFormat.FLAC.supportsBitrate)
    }

    @Test
    fun losslessFormatsDeclareThemselves() {
        assertTrue(ExportFormat.WAV.lossless)
        assertTrue(ExportFormat.FLAC.lossless)
        assertTrue(!ExportFormat.MP3.lossless)
    }

    @Test
    fun mimeTypeAndExtensionAgreeForEachFormat() {
        assertEquals("audio/mpeg", ExportFormat.MP3.mimeType)
        assertEquals("mp3", ExportFormat.MP3.extension)
        assertEquals("audio/wav", ExportFormat.WAV.mimeType)
        assertEquals("wav", ExportFormat.WAV.extension)
    }
}
