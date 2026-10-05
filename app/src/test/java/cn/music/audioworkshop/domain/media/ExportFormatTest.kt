package cn.music.audioworkshop.domain.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 格式能力矩阵：界面的下拉和编码器的闸门共用同一份数据，
 * 两边不一致就会漏出「界面能选、导出失败」的组合，所以这里把边界钉住。
 */
class ExportFormatTest {

    @Test
    fun `MP3 不提供 LAME 会退回的采样率`() {
        assertTrue(
            "MP3 不该给出 8000Hz，LAME 在部分版本上会静默退回 16000",
            ExportFormat.MP3.supportedSampleRates.none { it < 16_000 },
        )
        assertTrue(44_100 in ExportFormat.MP3.supportedSampleRates)
        assertTrue(48_000 in ExportFormat.MP3.supportedSampleRates)
    }

    @Test
    fun `无损格式没有比特率可设`() {
        assertEquals(emptyList<Int>(), ExportFormat.WAV.supportedBitrates)
        assertEquals(emptyList<Int>(), ExportFormat.FLAC.supportedBitrates)
        assertTrue(ExportFormat.MP3.supportedBitrates.isNotEmpty())
    }

    @Test
    fun `合法参数通过校验`() {
        assertNull(ExportFormat.MP3.validate(44_100, 320, 2))
        assertNull(ExportFormat.WAV.validate(44_100, 0, 2))
        assertNull(ExportFormat.FLAC.validate(96_000, 0, 1))
    }

    @Test
    fun `非法采样率被拦下且给出可读原因`() {
        val error = ExportFormat.MP3.validate(8_000, 320, 2)
        assertNotNull("8000Hz 对 MP3 非法，应该报错", error)
        assertTrue("原因里要带上格式名", error!!.contains("MP3"))
        assertTrue("原因里要说明可选值", error.contains("/"))
    }

    @Test
    fun `给无损格式设比特率被拦下`() {
        assertNotNull(ExportFormat.FLAC.validate(44_100, 320, 2))
        assertNotNull(ExportFormat.WAV.validate(44_100, 128, 2))
    }

    @Test
    fun `声道数越界被拦下`() {
        assertNotNull(ExportFormat.MP3.validate(44_100, 320, 0))
        assertNotNull(ExportFormat.MP3.validate(44_100, 320, 6))
        assertNull(ExportFormat.MP3.validate(44_100, 320, 1))
    }

    @Test
    fun `零值表示跟随源不算非法`() {
        assertNull(ExportFormat.MP3.validate(0, 0, 2))
    }

    @Test
    fun `未知格式名退回 MP3`() {
        assertEquals(ExportFormat.MP3, ExportFormat.fromName("不存在的格式"))
        assertEquals(ExportFormat.MP3, ExportFormat.fromName(null))
        assertEquals(ExportFormat.FLAC, ExportFormat.fromName("FLAC"))
    }
}
