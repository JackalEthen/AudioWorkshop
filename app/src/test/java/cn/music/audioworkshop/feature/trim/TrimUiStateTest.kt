package cn.music.audioworkshop.feature.trim

import cn.music.audioworkshop.feature.edit.waveform.WaveformGeometry
import cn.music.audioworkshop.feature.edit.waveform.WaveformSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪切页的纯规则部分：选区约束、时间换算、淡入淡出夹紧、可导出判定。
 *
 * 这些都在 UiState 上，测它们不需要 Android 环境。
 */
class TrimUiStateTest {

    private fun state(
        startUs: Long = 0L,
        endUs: Long = 0L,
        durationUs: Long = 180_000_000L,
        fadeInMs: Long = 0L,
        fadeOutMs: Long = 0L,
        mode: TrimMode = TrimMode.FAST,
    ) = TrimUiState(
        durationUs = durationUs,
        selection = WaveformSelection(startUs, endUs),
        mode = mode,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
    )

    @Test
    fun `选区时长按微秒算再转毫秒`() {
        val s = state(startUs = 1_000_000L, endUs = 3_500_000L)
        assertEquals(2_500_000L, s.selectedUs)
        assertEquals(2500L, s.selectedMs)
    }

    @Test
    fun `没导入音频时不能导出`() {
        val s = TrimUiState(durationUs = 100_000_000L)
        assertFalse(s.hasTrack)
        assertFalse(s.canExport)
    }

    @Test
    fun `选区太短不能导出`() {
        // 1 毫秒的选区没有意义
        val s = state(startUs = 0L, endUs = 1_000L)
        assertFalse(s.canExport)
    }

    @Test
    fun `导出中不能重复触发`() {
        val s = state(startUs = 0L, endUs = 60_000_000L).copy(isExporting = true)
        assertFalse(s.canExport)
    }

    @Test
    fun `淡入淡出不得超过选区一半`() {
        val s = state(startUs = 0L, endUs = 10_000_000L, fadeInMs = 9_000L, fadeOutMs = 9_000L)
        val clamped = s.clampFades()
        assertEquals("淡入应收敛到选区一半", 5_000L, clamped.fadeInMs)
        assertEquals("淡出应收敛到选区一半", 5_000L, clamped.fadeOutMs)
    }

    @Test
    fun `选区变短时淡入淡出跟着收敛`() {
        val wide = state(startUs = 0L, endUs = 100_000_000L, fadeInMs = 5_000L, fadeOutMs = 5_000L)
        val narrowed = wide.copy(selection = WaveformSelection(0L, 2_000_000L)).clampFades()
        assertEquals("2 秒选区的一半是 1 秒", 1_000L, narrowed.fadeInMs)
        assertEquals(1_000L, narrowed.fadeOutMs)
    }

    @Test
    fun `负的淡入淡出被夹到零`() {
        val s = state(startUs = 0L, endUs = 10_000_000L, fadeInMs = -100L, fadeOutMs = -1L)
        val clamped = s.clampFades()
        assertEquals(0L, clamped.fadeInMs)
        assertEquals(0L, clamped.fadeOutMs)
    }

    @Test
    fun `快速模式是硬切不处理淡入淡出`() {
        assertEquals("快速", TrimMode.FAST.label)
        // 语义写在 caption 里，界面直接展示，避免用户以为快速模式也有淡化
        assertTrue(TrimMode.FAST.caption.contains("硬切"))
        assertTrue(TrimMode.NORMAL.caption.contains("淡入淡出"))
    }

    @Test
    fun `归一化保证选区非空且不越界`() {
        val normalized = WaveformGeometry.normalize(500_000L, 500_000L, 100_000_000L)
        assertTrue("空选区要被撑开到最小长度", normalized.endUs > normalized.startUs)

        val overEnd = WaveformGeometry.normalize(99_000_000L, 999_000_000L, 100_000_000L)
        assertTrue("结束点不能超过总长", overEnd.endUs <= 100_000_000L)
    }
}
