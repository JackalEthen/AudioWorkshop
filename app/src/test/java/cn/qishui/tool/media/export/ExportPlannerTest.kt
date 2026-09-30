package cn.qishui.tool.media.export

import cn.qishui.tool.domain.media.ExportJob
import cn.qishui.tool.domain.media.ExportSegment
import cn.qishui.tool.domain.media.ExportSource
import cn.qishui.tool.domain.model.FadeCurve
import cn.qishui.tool.domain.model.JoinTransition
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

    @Test
    fun fadeTransitionRampsBothSidesOfTheSeamWithoutChangingTotalLength() {
        val plan = ExportPlanner.plan(job(joinTransition = JoinTransition.FADE, transitionMs = 1_500L))

        assertEquals(1_500_000L, plan.steps[0].fadeOutUs)
        assertEquals(1_500_000L, plan.steps[1].fadeInUs)
        // 斜坡对接不重叠，总时长不变
        assertEquals(0L, plan.steps[0].gapAfterUs)
        assertEquals(8_000L, plan.totalOutputUs)
    }

    @Test
    fun stableTransitionUsesEqualPowerCurveEvenOnTheOuterSteps() {
        // 两首拼接时首尾两条同时承担接缝斜坡，必须也能拿到等功率曲线
        val plan = ExportPlanner.plan(job(joinTransition = JoinTransition.STABLE, transitionMs = 800L))

        assertEquals(FadeCurve.EQUAL_POWER, plan.steps[0].fadeCurve)
        assertEquals(FadeCurve.EQUAL_POWER, plan.steps[1].fadeCurve)
    }

    @Test
    fun normalTransitionLeavesEverySeamHard() {
        val plan = ExportPlanner.plan(job(joinTransition = JoinTransition.NORMAL, transitionMs = 1_500L))

        assertEquals(0L, plan.steps[0].fadeOutUs)
        assertEquals(0L, plan.steps[1].fadeInUs)
        assertEquals(0L, plan.steps[0].gapAfterUs)
        assertEquals(8_000L, plan.totalOutputUs)
    }

    @Test
    fun preserveTransitionInsertsSilenceAndKeepsEverySample() {
        val plan = ExportPlanner.plan(job(joinTransition = JoinTransition.PRESERVE, transitionMs = 2_000L))

        assertEquals(2_000_000L, plan.steps[0].gapAfterUs)
        assertEquals(0L, plan.steps[1].gapAfterUs)
        // 不淡出也不淡入，只加空白
        assertEquals(0L, plan.steps[0].fadeOutUs)
        assertEquals(0L, plan.steps[1].fadeInUs)
        // 原本 8ms，加 2000ms 空白
        assertEquals(2_008_000L, plan.totalOutputUs)
        // 后一首整体后移：前一首 6ms + 2000ms 空白
        assertEquals(2_006_000L, plan.steps[1].outputStartUs)
        assertEquals(2_008_000L, plan.steps[1].outputEndUs)
    }

    @Test
    fun trailingSilenceExtendsThePlanWithoutTouchingTheSteps() {
        val plan = ExportPlanner.plan(job(trailingSilenceMs = 3_000L))

        assertEquals(3_008_000L, plan.totalOutputUs)
        assertEquals(3_008L, plan.durationMs)
        assertEquals(3_000_000L, plan.trailingSilenceUs)
        assertEquals(8_000L, plan.steps.last().outputEndUs)
    }

    @Test
    fun trailingSilenceCombinesWithPreserveGaps() {
        val plan = ExportPlanner.plan(
            job(
                joinTransition = JoinTransition.PRESERVE,
                transitionMs = 1_000L,
                trailingSilenceMs = 2_000L,
            ),
        )

        assertEquals(1_000_000L, plan.steps[0].gapAfterUs)
        assertEquals(3_008_000L, plan.totalOutputUs)
    }

    private fun job(
        fadeInMs: Long = 0L,
        fadeOutMs: Long = 0L,
        joinTransition: JoinTransition = JoinTransition.NORMAL,
        transitionMs: Long = 0L,
        trailingSilenceMs: Long = 0L,
    ): ExportJob = ExportJob(
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
        joinTransition = joinTransition,
        transitionMs = transitionMs,
        trailingSilenceMs = trailingSilenceMs,
    )
}
