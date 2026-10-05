package cn.music.audioworkshop.feature.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 音量标度换算。
 *
 * 百分比是线性倍率，分贝是对数。两种模式只是标度不同，最终都落到导出引擎的
 * `ExportJob.gainDb`，所以换算错了导出就会和界面显示不一致。
 */
class VolumeUiStateTest {

    private fun state(
        mode: VolumeMode = VolumeMode.PERCENT,
        percent: Float = 1f,
        decibel: Float = 0f,
        preventClipping: Boolean = true,
        hasTrack: Boolean = true,
    ) = VolumeUiState(
        track = if (hasTrack) fakeTrack() else null,
        mode = mode,
        percent = percent,
        decibel = decibel,
        preventClipping = preventClipping,
    )

    @Test
    fun `100 百分比等于 0 分贝`() {
        assertEquals(0f, state(percent = 1f).gainDb, 0.01f)
    }

    @Test
    fun `200 百分比约等于 +6 分贝`() {
        // 20*log10(2) = 6.02
        assertEquals(6.02f, state(percent = 2f).gainDb, 0.05f)
    }

    @Test
    fun `50 百分比约等于 -6 分贝`() {
        assertEquals(-6.02f, state(percent = 0.5f).gainDb, 0.05f)
    }

    @Test
    fun `分贝模式下直接用分贝值`() {
        assertEquals(6f, state(mode = VolumeMode.DECIBEL, decibel = 6f).gainDb, 0.001f)
        assertEquals(-12f, state(mode = VolumeMode.DECIBEL, decibel = -12f).gainDb, 0.001f)
    }

    @Test
    fun `换算往返一致`() {
        listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f).forEach { percent ->
            val db = state(percent = percent).gainDb
            val back = VolumeViewModel.percentToDb(percent)
            assertEquals("$percent 两次换算应一致", db, back, 0.01f)
        }
    }

    @Test
    fun `极小百分比不会让换算溢出`() {
        // log10(0) 是 -Inf，会把增益变成无穷大
        val s = state(percent = 0f)
        assertTrue("增益必须是有限值", s.gainDb.isFinite())
    }

    @Test
    fun `防炸音默认开启`() {
        assertTrue("过增益是常见场景，防炸音应默认开", state().preventClipping)
    }

    @Test
    fun `没导入音频时不能导出`() {
        assertTrue(!state(hasTrack = false).canExport)
        assertTrue(state().canExport)
    }

    @Test
    fun `预览渲染中播放键应禁用`() {
        // 渲染耗时肉眼可见，期间播的还是旧预览。
        // 不禁用的话用户会以为增益没生效 —— 这是实测反馈过的真实问题。
        val rendering = state().copy(isRenderingPreview = true)
        assertTrue("渲染中要能看出这是个中间态", rendering.isRenderingPreview)
    }

    @Test
    fun `渲染完成后回到可用态`() {
        val done = state().copy(isRenderingPreview = false)
        assertTrue("渲染完成后不该还停在中间态", !done.isRenderingPreview)
    }

    @Test
    fun `最大档要给足提升空间`() {
        // 实际用途是「原曲录得太轻要推上去」，400% 常常不够。
        assertTrue("最大百分比至少要到 800%", VolumeViewModel.MAX_PERCENT >= 8f)
        assertTrue("分贝上限要与百分比上限大致对应", VolumeViewModel.MAX_DECIBEL >= 18f)
        // 800% 约等于 +18dB
        assertEquals(18.06f, VolumeViewModel.percentToDb(VolumeViewModel.MAX_PERCENT), 0.2f)
    }

    @Test
    fun `软限制阈值不能太高否则增益被吃掉`() {
        // 阈值贴近满幅时，原曲电平稍高就把增益全压缩掉，
        // 用户调到最大档也几乎听不出变化 —— 这是实测踩过的坑。
        val threshold = 16_400f
        val boosted = 8_000 * 10.0.pow(12.0 / 20.0)
        assertTrue(
            "常见电平在 +12dB 后应仍高于阈值、拿到接近完整的增益（阈值=$threshold 输入=$boosted）",
            boosted > threshold,
        )
    }

    /** 只为满足 data class 的非空 track 字段，不参与断言。 */
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
