package cn.music.audioworkshop.feature.join

import cn.music.audioworkshop.domain.model.JoinTransition
import cn.music.audioworkshop.domain.model.SourceOrigin
import cn.music.audioworkshop.domain.model.SourceTrack
import cn.music.audioworkshop.domain.media.ExportJob
import cn.music.audioworkshop.media.export.ExportPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 合成列表的状态规则。 */
class JoinUiStateTest {

    private fun item(name: String, seconds: Long, gapMs: Long = 0L) = JoinItem(
        track = SourceTrack(
            id = name,
            origin = SourceOrigin.LOCAL_IMPORT,
            sourceShareUrl = null,
            title = name,
            artist = null,
            album = null,
            localPath = "/tmp/$name.mp3",
            format = "mp3",
            durationMs = seconds * 1000L,
            bitrateBps = null,
            sizeBytes = null,
            sampleRateHz = null,
            lyrics = null,
            fileHash = null,
            coverUri = null,
        ),
        fileName = "$name.mp3",
        durationUs = seconds * 1_000_000L,
        gapAfterMs = gapMs,
    )

    private fun state(
        items: List<JoinItem> = listOf(item("a", 100), item("b", 200)),
        mode: JoinMode = JoinMode.FADE,
        transitionMs: Long = 800L,
        trailingSilenceMs: Long = 0L,
    ) = JoinUiState(
        items = items,
        mode = mode,
        transitionMs = transitionMs,
        trailingSilenceMs = trailingSilenceMs,
    )

    @Test
    fun `没有音频时不能导出`() {
        assertFalse(JoinUiState().canExport)
        assertTrue(state().canExport)
    }

    @Test
    fun `四种模式映射到引擎已有的四种接缝`() {
        // 引擎层 JoinTransition 早就定义好了，这里只做映射，不重新发明
        assertEquals(JoinTransition.NORMAL, JoinMode.NORMAL.transition)
        assertEquals(JoinTransition.FADE, JoinMode.FADE.transition)
        assertEquals(JoinTransition.STABLE, JoinMode.STABLE.transition)
        assertEquals(JoinTransition.PRESERVE, JoinMode.PRESERVE.transition)
    }

    @Test
    fun `总时长等于各首加上所有空白`() {
        val s = state(
            items = listOf(item("a", 100, gapMs = 1_000L), item("b", 200, gapMs = 500L)),
            trailingSilenceMs = 250L,
        )
        // 100 + 1 + 200 + 0.5 + 0.25 = 301.75 秒
        assertEquals(301_750L, s.durationMs)
    }

    @Test
    fun `接缝时长不超过最短那首的一半`() {
        // 两首拼接时前一首淡出和后一首淡入会重叠，重叠处增益相乘，听感忽然一沉
        val s = state(
            items = listOf(item("a", 4), item("b", 200)),
            transitionMs = 10_000L,
        )
        assertEquals(2_000L, s.clampDurations().transitionMs)
    }

    @Test
    fun `最短那首只有一秒时接缝被夹到半秒`() {
        val s = state(
            items = listOf(item("a", 1), item("b", 200)),
            transitionMs = 5_000L,
        )
        assertEquals(500L, s.clampDurations().transitionMs)
    }

    @Test
    fun `负的末尾空白被夹到零`() {
        assertEquals(0L, state(trailingSilenceMs = -100L).clampDurations().trailingSilenceMs)
    }

    @Test
    fun `列表为空时接缝上限为零`() {
        assertEquals(0L, JoinUiState().maxTransitionMs())
    }

    // ---- 计划器：逐项空白真的插进去了 ----

    private fun job(
        items: List<JoinItem>,
        mode: JoinMode = JoinMode.NORMAL,
        transitionMs: Long = 0L,
        trailingMs: Long = 0L,
    ) = ExportJob(
        jobId = "t",
        editProjectId = "join",
        outputTempPath = "/tmp/out.mp3",
        sources = items.map {
            cn.music.audioworkshop.domain.media.ExportSource(
                id = it.track.id,
                localPath = it.track.localPath!!,
                title = it.track.title,
                artist = null,
                album = null,
                lyrics = null,
                segments = listOf(cn.music.audioworkshop.domain.media.ExportSegment(0L, it.durationUs)),
            )
        },
        gainDb = 0f,
        fadeInMs = 0L,
        fadeOutMs = 0L,
        lyricOffsetMs = 0L,
        joinTransition = mode.transition,
        transitionMs = transitionMs,
        trailingSilenceMs = trailingMs,
        perSourceGapMs = items.map { it.gapAfterMs },
    )

    @Test
    fun `逐项空白被插入到对应位置之后`() {
        val items = listOf(
            item("a", 10, gapMs = 2_000L),
            item("b", 20, gapMs = 3_000L),
            item("c", 30, gapMs = 0L),
        )
        val plan = ExportPlanner.plan(job(items))
        assertEquals(2_000_000L, plan.steps[0].gapAfterUs)
        assertEquals(3_000_000L, plan.steps[1].gapAfterUs)
        assertEquals(0L, plan.steps[2].gapAfterUs)
    }

    @Test
    fun `空白会往后推移后续各项的起始时间`() {
        val items = listOf(item("a", 10, gapMs = 2_000L), item("b", 20))
        val plan = ExportPlanner.plan(job(items))
        // 第一首 10 秒 + 2 秒空白 = 第二首从第 12 秒开始
        assertEquals(0L, plan.steps[0].outputStartUs)
        assertEquals(12_000_000L, plan.steps[1].outputStartUs)
        assertEquals(32_000_000L, plan.totalOutputUs)
    }

    @Test
    fun `无损模式额外补等长空白`() {
        // PRESERVE 除了用户手动插的空白，还会在接缝处补过渡时长的空白
        val items = listOf(item("a", 10, gapMs = 1_000L), item("b", 20))
        val plan = ExportPlanner.plan(job(items, mode = JoinMode.PRESERVE, transitionMs = 500L))
        // 手动 1 秒 + 无损自动 0.5 秒
        assertEquals(1_500_000L, plan.steps[0].gapAfterUs)
    }

    @Test
    fun `末项后面不补无损空白`() {
        // 最后一首后面没有接缝，补了就是结尾凭空多一段空白
        val items = listOf(item("a", 10), item("b", 20))
        val plan = ExportPlanner.plan(job(items, mode = JoinMode.PRESERVE, transitionMs = 500L))
        assertEquals(0L, plan.steps[1].gapAfterUs)
    }

    @Test
    fun `空白数量比音频少时按零处理`() {
        // 少填的项不能崩，也不能沿用上一项的值
        val items = listOf(item("a", 10, gapMs = 5_000L), item("b", 20))
        val j = job(items).copy(perSourceGapMs = listOf(5_000L))
        val plan = ExportPlanner.plan(j)
        assertEquals(0L, plan.steps[1].gapAfterUs)
    }

    @Test
    fun `淡入淡出模式给每个接缝补一对斜坡`() {
        val items = listOf(item("a", 30), item("b", 30), item("c", 30))
        val plan = ExportPlanner.plan(job(items, mode = JoinMode.FADE, transitionMs = 700L))
        // 首项的淡入取整条的 fadeInMs（本测试传 0），末项的淡出同理；
        // 真正由接缝决定的是：每个非末项的淡出、每个非首项的淡入
        assertEquals(0L, plan.steps[0].fadeInUs)
        assertEquals(700_000L, plan.steps[0].fadeOutUs)
        assertEquals(700_000L, plan.steps[1].fadeOutUs)
        assertEquals(700_000L, plan.steps[1].fadeInUs)
        assertEquals(700_000L, plan.steps[2].fadeInUs)
        assertEquals(0L, plan.steps[2].fadeOutUs)
    }

    @Test
    fun `正常模式不补斜坡也不补空白`() {
        val items = listOf(item("a", 30), item("b", 30))
        val plan = ExportPlanner.plan(job(items, mode = JoinMode.NORMAL, transitionMs = 700L))
        assertTrue(plan.steps.all { it.fadeInUs == 0L && it.fadeOutUs == 0L })
        assertTrue(plan.steps.all { it.gapAfterUs == 0L })
    }

    @Test
    fun `末尾空白算进总时长`() {
        val items = listOf(item("a", 10), item("b", 20))
        val plan = ExportPlanner.plan(job(items, trailingMs = 1_000L))
        assertEquals(31_000L, plan.durationMs)
    }

    @Test
    fun `改顺序会改变输出时间轴`() {
        val items = listOf(item("a", 10), item("b", 20))
        val plan = ExportPlanner.plan(job(items))
        assertEquals("a", plan.steps[0].source.id)
        val swapped = job(items.reversed())
        assertEquals("b", ExportPlanner.plan(swapped).steps[0].source.id)
    }
}