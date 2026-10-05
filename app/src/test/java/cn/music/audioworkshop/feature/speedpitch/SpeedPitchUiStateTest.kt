package cn.music.audioworkshop.feature.speedpitch

import cn.music.audioworkshop.domain.media.ExportJob
import cn.music.audioworkshop.domain.media.ExportSegment
import cn.music.audioworkshop.domain.media.ExportSource
import cn.music.audioworkshop.media.export.ExportPlanner
import cn.music.audioworkshop.media.export.needsSpeedPitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 变速变调的状态与计划。
 *
 * 关键不变量：**变速只改时长，变调只改音高**。两者互相独立，
 * 而且变速后计划器算出的时长必须真的变了 —— 否则导出会被时长校验判失败。
 */
class SpeedPitchUiStateTest {

    private fun job(speed: Float = 1f, semitones: Float = 0f, durationUs: Long = 180_000_000L) = ExportJob(
        jobId = "t",
        editProjectId = "speedpitch",
        outputTempPath = "/tmp/out.mp3",
        sources = listOf(
            ExportSource(
                id = "a",
                localPath = "/tmp/a.mp3",
                title = "a",
                artist = null,
                album = null,
                lyrics = null,
                segments = listOf(ExportSegment(0L, durationUs)),
            ),
        ),
        gainDb = 0f,
        fadeInMs = 0L,
        fadeOutMs = 0L,
        lyricOffsetMs = 0L,
        speed = speed,
        semitones = semitones,
    )

    // ---- 范围 ----

    @Test
    fun `速度被夹在半点五到三倍之间`() {
        assertEquals(0.5f, SpeedPitchUiState(speed = 0.1f).clampParams().speed, 0.001f)
        assertEquals(3f, SpeedPitchUiState(speed = 9f).clampParams().speed, 0.001f)
    }

    @Test
    fun `音调被夹在正负十二个半音之间`() {
        assertEquals(12f, SpeedPitchUiState(semitones = 99f).clampParams().semitones, 0.001f)
        assertEquals(-12f, SpeedPitchUiState(semitones = -99f).clampParams().semitones, 0.001f)
    }

    // ---- 变速后的时长 ----

    @Test
    fun `两倍速时长减半`() {
        val s = SpeedPitchUiState(durationUs = 180_000_000L, speed = 2f)
        // 180 秒 / 2 = 90 秒
        assertEquals(90_000L, s.outputDurationMs)
    }

    @Test
    fun `半速时长翻倍`() {
        val s = SpeedPitchUiState(durationUs = 180_000_000L, speed = 0.5f)
        assertEquals(360_000L, s.outputDurationMs)
    }

    @Test
    fun `变调不影响时长`() {
        // 变调只改音高不改快慢，所以时长必须一模一样
        val original = SpeedPitchUiState(durationUs = 180_000_000L, semitones = 0f)
        val shifted = SpeedPitchUiState(durationUs = 180_000_000L, semitones = 7f)
        assertEquals(original.outputDurationMs, shifted.outputDurationMs)
    }

    @Test
    fun `没读出时长时输出时长是零而不是崩`() {
        assertEquals(0L, SpeedPitchUiState(durationUs = 0L, speed = 2f).outputDurationMs)
    }

    @Test
    fun `没有音频时不能导出`() {
        assertFalse(SpeedPitchUiState().canExport)
        assertTrue(SpeedPitchUiState(track = null).hasTrack.not())
    }

    // ---- 是否需要跑时间轴拉伸 ----

    @Test
    fun `原速原调不需要跑拉伸`() {
        // 原速原调跑一遍 Signalsmith 是白花几百毫秒 CPU
        assertFalse(1f.needsSpeedPitch(0f))
    }

    @Test
    fun `改了速度或音调就要跑拉伸`() {
        assertTrue(1.5f.needsSpeedPitch(0f))
        assertTrue(1f.needsSpeedPitch(3f))
        assertTrue(1f.needsSpeedPitch(-3f))
    }

    @Test
    fun `浮点误差范围内不算改动`() {
        // 滑杆回调可能给出 0.9999999 这种值，不该因此触发整条 native 链路
        assertFalse(1.0000001f.needsSpeedPitch(0.0001f))
    }

    // ---- 计划器：变速真的改了输出时长 ----

    @Test
    fun `计划时长按变速倍率缩放`() {
        val original = ExportPlanner.plan(job(speed = 1f))
        val double = ExportPlanner.plan(job(speed = 2f))
        assertEquals(180_000L, original.durationMs)
        assertEquals(90_000L, double.durationMs)
    }

    @Test
    fun `变速后每段的时间轴也跟着缩放`() {
        val plan = ExportPlanner.plan(job(speed = 2f, durationUs = 180_000_000L))
        val step = plan.steps.single()
        // 源 0~180 秒，输出只占 90 秒
        assertEquals(0L, step.outputStartUs)
        assertEquals(90_000_000L, step.outputEndUs)
    }

    @Test
    fun `变调不改变计划时长`() {
        assertEquals(
            ExportPlanner.plan(job(semitones = 0f)).durationMs,
            ExportPlanner.plan(job(semitones = -12f)).durationMs,
        )
    }

    @Test
    fun `变速标记只在真的需要时才为真`() {
        assertTrue(ExportPlanner.plan(job(speed = 1.25f)).steps.single().needsSpeedPitch)
        assertFalse(ExportPlanner.plan(job(speed = 1f)).steps.single().needsSpeedPitch)
    }

    @Test
    fun `变速倍率非法时退回原速而不是产生零长输出`() {
        // speed=0 会让整条时长计算除零，输出长度变 0，导出直接被时长校验拒掉
        val plan = ExportPlanner.plan(job(speed = 0f))
        assertEquals(180_000L, plan.durationMs)
    }

    @Test
    fun `无穷大速度退回原速`() {
        assertEquals(180_000L, ExportPlanner.plan(job(speed = Float.POSITIVE_INFINITY)).durationMs)
    }
}