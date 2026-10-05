package cn.music.audioworkshop.media.export

import cn.music.audioworkshop.domain.media.ExportFormat
import cn.music.audioworkshop.domain.media.ExportJobCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标编码器的参数校验与单位约定。
 *
 * 回归重点：`MediaFormat.KEY_BIT_RATE` 的单位是 **bps**，而我们对外一律用 kbps。
 * 曾经把 kbps 直接塞给 MediaCodec，128 被当成 128 bps，低于编码器下限，
 * configure 阶段就 BAD_VALUE。AAC / M4A 因此已从目标格式里移除，
 * 但 FLAC 仍走 MediaCodec，将来接别的 MediaCodec 格式时这个坑会再踩一次 ——
 * 所以换算规则留在这里钉住。
 */
class AudioTargetEncoderParamsTest {

    @Test
    fun `MediaCodec 收到的码率必须是 bps`() {
        assertEquals(128_000, 128 * 1000)
        assertEquals(320_000, 320 * 1000)
        assertEquals(64_000, 64 * 1000)
    }

    @Test
    fun `换算后不能出现零或负值`() {
        listOf(64, 96, 128, 160, 192, 224, 256, 320).forEach { kbps ->
            assertTrue("$kbps kbps 换算后应为正", kbps * 1000 > 0)
        }
    }

    @Test
    fun `目标格式只有 MP3 WAV FLAC`() {
        assertEquals(
            listOf(ExportFormat.MP3, ExportFormat.WAV, ExportFormat.FLAC),
            ExportFormat.entries,
        )
    }

    @Test
    fun `MP3 的采样率范围不能超出 LAME 支持集`() {
        val lameRates = listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)
        ExportFormat.MP3.supportedSampleRates.forEach { rate ->
            assertTrue("MP3 提供了 LAME 不支持的 $rate Hz", rate in lameRates)
        }
        assertEquals(32, ExportJobCodec.MIN_BITRATE_KBPS)
        assertEquals(320, ExportJobCodec.MAX_BITRATE_KBPS)
    }

    @Test
    fun `无损格式不设比特率`() {
        assertTrue(ExportFormat.WAV.supportedBitrates.isEmpty())
        assertTrue(ExportFormat.FLAC.supportedBitrates.isEmpty())
    }

    @Test
    fun `各格式的合法参数组合通过校验`() {
        assertNull(ExportFormat.MP3.validate(44100, 128, 2))
        assertNull(ExportFormat.WAV.validate(44100, 0, 2))
        assertNull(ExportFormat.FLAC.validate(96000, 0, 1))
    }

    @Test
    fun `无损格式拒绝比特率 MP3 拒绝低采样率`() {
        assertNotNull(ExportFormat.FLAC.validate(44100, 320, 2))
        assertNotNull(ExportFormat.WAV.validate(44100, 128, 2))
        assertNotNull("8000Hz 对 MP3 非法", ExportFormat.MP3.validate(8_000, 320, 2))
    }
}
