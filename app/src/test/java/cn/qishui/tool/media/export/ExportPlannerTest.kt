package cn.qishui.tool.media.export

import cn.qishui.tool.domain.media.ExportJob
import cn.qishui.tool.domain.media.ExportSegment
import cn.qishui.tool.domain.media.ExportSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportPlannerTest {

    @Test
    fun singleSourceCarriesBothFades() {
        val base = job(fadeInMs = 500L, fadeOutMs = 1_000L)
        val plan = ExportPlanner.plan(base.copy(sources = listOf(base.sources.first())))

        val step = plan.steps.single()
        assertEquals(500_000L, step.fadeInUs)
        assertEquals(1_000_000L, step.fadeOutUs)
        assertEquals(6_000L, plan.totalOutputUs)
    }

    @Test
    fun joinedSourcesAccumulateOutputTimeInOrder() {
        val plan = ExportPlanner.plan(job(fadeInMs = 500L, fadeOutMs = 1_000L))

        assertEquals(2, plan.steps.size)
        val first = plan.steps[0]
        val second = plan.steps[1]
        assertEquals("track-1", first.source.id)
        assertEquals(0L, first.outputStartUs)
        assertEquals(6_000L, first.outputEndUs)
        assertEquals(6_000L, second.outputStartUs)
        assertEquals(8_000L, second.outputEndUs)
        assertEquals(8_000L, plan.totalOutputUs)
        assertEquals(8L, plan.durationMs)
    }

    @Test
    fun onlyTheOuterEdgesFade() {
        val plan = ExportPlanner.plan(job(fadeInMs = 500L, fadeOutMs = 1_000L))

        assertEquals(500_000L, plan.steps[0].fadeInUs)
        assertEquals(0L, plan.steps[0].fadeOutUs)
        assertEquals(0L, plan.steps[1].fadeInUs)
        assertEquals(1_000_000L, plan.steps[1].fadeOutUs)
    }

    @Test
    fun segmentsKeepSourceBoundsAndRebaseOutputAxis() {
        val plan = ExportPlanner.plan(job())

        val second = plan.steps[1].segments.single()
        assertEquals(1_000L, second.sourceStartUs)
        assertEquals(3_000L, second.sourceEndUs)
        assertEquals(6_000L, second.outputStartUs)
        assertEquals(8_000L, second.outputEndUs)
    }

    @Test
    fun gapsInsideOneSourceAreRemovedFromTheOutputAxis() {
        val plan = ExportPlanner.plan(job())

        val first = plan.steps[0].segments
        assertEquals(2, first.size)
        assertEquals(0L, first[0].outputStartUs)
        assertEquals(3_000L, first[0].outputEndUs)
        assertEquals(3_000L, first[1].outputStartUs)
        assertEquals(6_000L, first[1].outputEndUs)
    }

    @Test
    fun threeSourceJoinAccumulatesEveryTrack() {
        val base = job()
        val plan = ExportPlanner.plan(
            base.copy(
                sources = base.sources + base.sources[1].copy(id = "track-3"),
            ),
        )

        assertEquals(listOf(0L, 6_000L, 8_000L), plan.steps.map { it.outputStartUs })
        assertEquals(listOf(6_000L, 8_000L, 10_000L), plan.steps.map { it.outputEndUs })
        assertEquals(10_000L, plan.totalOutputUs)
    }

    private fun job(fadeInMs: Long = 0L, fadeOutMs: Long = 0L): ExportJob = ExportJob(
        jobId = "job-1",
        editProjectId = "edit-1",
        outputTempPath = "/cache/export.mp3",
        sources = listOf(
            ExportSource(
                id = "track-1",
                localPath = "/files/a.m4a",
                title = "A",
                artist = "B",
                album = "C",
                lyrics = null,
                segments = listOf(ExportSegment(0L, 3_000L), ExportSegment(8_000L, 11_000L)),
            ),
            ExportSource(
                id = "track-2",
                localPath = "/files/b.m4a",
                title = "D",
                artist = "E",
                album = "F",
                lyrics = null,
                segments = listOf(ExportSegment(1_000L, 3_000L)),
            ),
        ),
        gainDb = 0f,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        lyricOffsetMs = 0L,
    )
}
