package cn.music.audioworkshop.feature.fade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 淡入淡出的状态规则。
 *
 * 淡入 = 开头 0% → 100%，淡出 = 结尾前 100% → 0%。
 * 两者都不改变总时长，只改包络。
 */
class FadeUiStateTest {

    private fun state(
        durationUs: Long = 180_000_000L,
        fadeInMs: Long = 2_000L,
        fadeOutMs: Long = 3_000L,
        mode: FadeMode = FadeMode.NORMAL,
        hasTrack: Boolean = true,
    ) = FadeUiState(
        track = if (hasTrack) fakeTrack() else null,
        durationUs = durationUs,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        mode = mode,
    )

    @Test
    fun `时长不会超过歌曲长度`() {
        // 3 秒的歌不可能有 5 秒淡入 —— 重叠处音量会忽大忽小
        val s = state(durationUs = 3_000_000L, fadeInMs = 5_000L, fadeOutMs = 5_000L).clampDurations()
        assertEquals(3_000L, s.fadeInMs)
        assertEquals(3_000L, s.fadeOutMs)
    }

    @Test
    fun `没导入音频时不能导出`() {
        assertFalse(state(hasTrack = false).canExport)
        assertTrue(state().canExport)
    }

    @Test
    fun `正常模式用等功率曲线`() {
        // 等功率是渐变听感最均匀的曲线，绝大多数场景该用它
        assertEquals(
            cn.music.audioworkshop.domain.model.FadeCurve.EQUAL_POWER,
            FadeMode.NORMAL.curve,
        )
    }

    @Test
    fun `线性模式用线性曲线`() {
        assertEquals(
            cn.music.audioworkshop.domain.model.FadeCurve.LINEAR,
            FadeMode.LINEAR.curve,
        )
    }

    @Test
    fun `两种模式默认都是正常模式`() {
        assertEquals(FadeMode.NORMAL, FadeUiState().mode)
    }

    @Test
    fun `曲线端点正确`() {
        // 开头必须是 0（完全无声），结尾满值 —— 否则会突兀地跳进去
        val linear = cn.music.audioworkshop.domain.model.FadeCurve.LINEAR
        assertEquals(0f, linear.shape(0f), 0.001f)
        assertEquals(1f, linear.shape(1f), 0.001f)

        val equalPower = cn.music.audioworkshop.domain.model.FadeCurve.EQUAL_POWER
        assertEquals(0f, equalPower.shape(0f), 0.001f)
        assertEquals(1f, equalPower.shape(1f), 0.001f)
    }

    @Test
    fun `等功率曲线中段比线性更响`() {
        // 这正是「正常模式」存在的意义：sin 曲线中段抬升，听感均匀
        val at = 0.5f
        val linear = cn.music.audioworkshop.domain.model.FadeCurve.LINEAR.shape(at)
        val equalPower = cn.music.audioworkshop.domain.model.FadeCurve.EQUAL_POWER.shape(at)
        assertTrue("等功率中段应比线性响（线性=$linear 等功率=$equalPower）", equalPower > linear)
    }

    @Test
    fun `负时长被夹到零`() {
        val s = state(fadeInMs = -100L, fadeOutMs = -50L).clampDurations()
        assertEquals(0L, s.fadeInMs)
        assertEquals(0L, s.fadeOutMs)
    }

    @Test
    fun `零时长是允许的等于不处理`() {
        val s = state(fadeInMs = 0L, fadeOutMs = 0L).clampDurations()
        assertEquals(0L, s.fadeInMs)
        assertEquals(0L, s.fadeOutMs)
    }

    private fun fakeTrack() = cn.music.audioworkshop.domain.model.SourceTrack(
        id = "t",
        origin = cn.music.audioworkshop.domain.model.SourceOrigin.LOCAL_IMPORT,
        sourceShareUrl = null,
        title = null,
        artist = null,
        album = null,
        localPath = null,
        format = null,
        durationMs = null,
        bitrateBps = null,
        sizeBytes = null,
        sampleRateHz = null,
        lyrics = null,
        fileHash = null,
        coverUri = null,
        sourceCode = null,
        platformSongId = null,
    )
}
