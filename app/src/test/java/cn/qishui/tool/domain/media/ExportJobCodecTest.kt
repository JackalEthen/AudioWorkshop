package cn.qishui.tool.domain.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExportJobCodecTest {

    @Test
    fun roundTripPreservesEveryField() {
        val job = job()

        val decoded = ExportJobCodec.decode(ExportJobCodec.encode(job))

        assertEquals(job, decoded)
    }

    @Test
    fun nullOptionalFieldsSurviveRoundTrip() {
        val job = job().copy(
            sources = listOf(
                job().sources.first().copy(title = null, artist = null, album = null, lyrics = null),
            ),
        )

        val decoded = ExportJobCodec.decode(ExportJobCodec.encode(job))

        assertEquals(job, decoded)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val raw = JSONObject(ExportJobCodec.encode(job())).apply {
            put("futureField", JSONObject().put("nested", 1))
            getJSONArray("sources").getJSONObject(0).put("futureSourceField", 42)
        }

        val decoded = ExportJobCodec.decode(raw.toString())

        assertEquals(job(), decoded)
    }

    @Test
    fun relativeOutputTempPathIsRejected() {
        val raw = JSONObject(ExportJobCodec.encode(job()))
            .put("outputTempPath", "cache/export.mp3")

        assertThrows(ExportJobFormatException::class.java) { ExportJobCodec.decode(raw.toString()) }
    }

    @Test
    fun relativeSourcePathIsRejected() {
        val raw = JSONObject(ExportJobCodec.encode(job()))
        raw.getJSONArray("sources").getJSONObject(0).put("localPath", "../secret.m4a")

        assertThrows(ExportJobFormatException::class.java) { ExportJobCodec.decode(raw.toString()) }
    }

    @Test
    fun missingRequiredFieldIsRejected() {
        val raw = JSONObject(ExportJobCodec.encode(job()))
        raw.put("editProjectId", JSONObject.NULL)

        assertThrows(ExportJobFormatException::class.java) { ExportJobCodec.decode(raw.toString()) }
    }

    @Test
    fun wrongFieldTypeIsRejected() {
        val raw = JSONObject(ExportJobCodec.encode(job())).put("gainDb", "loud")

        assertThrows(ExportJobFormatException::class.java) { ExportJobCodec.decode(raw.toString()) }
    }

    @Test
    fun negativeFadeIsRejected() {
        val raw = JSONObject(ExportJobCodec.encode(job())).put("fadeInMs", -1)

        assertThrows(ExportJobFormatException::class.java) { ExportJobCodec.decode(raw.toString()) }
    }

    @Test
    fun emptySourceListIsRejected() {
        val raw = JSONObject(ExportJobCodec.encode(job())).put("sources", emptyList<Any>())

        assertThrows(ExportJobFormatException::class.java) { ExportJobCodec.decode(raw.toString()) }
    }

    @Test
    fun emptySegmentListIsRejected() {
        val raw = JSONObject(ExportJobCodec.encode(job()))
        raw.getJSONArray("sources").getJSONObject(0).put("segments", emptyList<Any>())

        assertThrows(ExportJobFormatException::class.java) { ExportJobCodec.decode(raw.toString()) }
    }

    @Test
    fun invertedSegmentIsRejected() {
        val raw = JSONObject(ExportJobCodec.encode(job()))
        raw.getJSONArray("sources").getJSONObject(0).getJSONArray("segments").getJSONObject(0)
            .put("sourceStartUs", 9_000L)

        assertThrows(ExportJobFormatException::class.java) { ExportJobCodec.decode(raw.toString()) }
    }

    @Test
    fun malformedJsonIsRejected() {
        assertThrows(ExportJobFormatException::class.java) { ExportJobCodec.decode("not-json") }
    }

    private fun job(): ExportJob = ExportJob(
        jobId = "job-1",
        editProjectId = "edit-1",
        outputTempPath = "/data/user/0/cn.qishui.tool/cache/export-1.mp3",
        sources = listOf(
            ExportSource(
                id = "track-1",
                localPath = "/data/user/0/cn.qishui.tool/files/downloads/song.m4a",
                title = "一昭成蹊",
                artist = "洛天依",
                album = "专辑",
                lyrics = "[0,1000]<0,500,0>词",
                segments = listOf(
                    ExportSegment(0L, 4_000L),
                    ExportSegment(10_000L, 15_000L),
                ),
            ),
            ExportSource(
                id = "track-2",
                localPath = "/data/user/0/cn.qishui.tool/files/downloads/song2.m4a",
                title = null,
                artist = null,
                album = null,
                lyrics = null,
                segments = listOf(ExportSegment(2_000L, 6_000L)),
            ),
        ),
        gainDb = -3.5f,
        fadeInMs = 500L,
        fadeOutMs = 1_200L,
        lyricOffsetMs = -250L,
    )
}
