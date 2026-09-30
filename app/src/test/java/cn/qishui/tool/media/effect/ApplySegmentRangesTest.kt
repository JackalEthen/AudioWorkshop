package cn.qishui.tool.media.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplySegmentRangesTest {

    /** 1kHz，1 毫秒 1 帧，方便把毫秒直接当帧下标用。 */
    private fun ramp(frames: Int): PcmBuffer =
        PcmBuffer(1_000, 1, ShortArray(frames) { (it + 1).toShort() })

    private fun PcmBuffer.at(frame: Int): Int = samples[frame].toInt()

    @Test
    fun `segments outside are left untouched`() {
        val buffer = ramp(100)

        val out = PcmEffects.applySegmentRanges(buffer, listOf(SegmentRange(20, 30, 0f))) { slice, _ ->
            PcmBuffer(1_000, 1, ShortArray(slice.samples.size) { 999 })
        }

        assertEquals(20, out.at(19))
        assertEquals(999, out.at(20))
        assertEquals(999, out.at(29))
        assertEquals(31, out.at(30))
    }

    @Test
    fun `no segments means no change`() {
        val buffer = ramp(10)

        val out = PcmEffects.applySegmentRanges(buffer, emptyList()) { slice, _ -> slice }

        assertTrue(out.samples.contentEquals(buffer.samples))
    }

    @Test
    fun `several segments each get their own value`() {
        val buffer = ramp(100)

        val out = PcmEffects.applySegmentRanges(
            buffer,
            listOf(SegmentRange(0, 10, 2f), SegmentRange(50, 60, 7f)),
        ) { slice, value -> PcmBuffer(1_000, 1, ShortArray(slice.samples.size) { value.toInt().toShort() }) }

        assertEquals(2, out.at(0))
        assertEquals(2, out.at(9))
        assertEquals(11, out.at(10))
        assertEquals(7, out.at(55))
    }

    @Test
    fun `segments are applied in time order regardless of input order`() {
        val buffer = ramp(100)

        val out = PcmEffects.applySegmentRanges(
            buffer,
            listOf(SegmentRange(50, 60, 1f), SegmentRange(0, 10, 2f)),
        ) { slice, value -> PcmBuffer(1_000, 1, ShortArray(slice.samples.size) { value.toInt().toShort() }) }

        assertEquals(2, out.at(0))
        assertEquals(1, out.at(55))
    }

    @Test
    fun `overlapping segments do not apply the effect twice`() {
        val buffer = ramp(100)

        val out = PcmEffects.applySegmentRanges(
            buffer,
            listOf(SegmentRange(0, 20, 1f), SegmentRange(10, 30, 5f)),
        ) { slice, value -> PcmBuffer(1_000, 1, ShortArray(slice.samples.size) { value.toInt().toShort() }) }

        // 0..10 归第一段，10..20 已经处理过所以跳过，20..30 归第二段
        assertEquals(1, out.at(5))
        assertEquals(1, out.at(15))
        assertEquals(5, out.at(25))
    }

    @Test
    fun `ranges beyond the audio are clamped not crashed`() {
        val buffer = ramp(50)

        val out = PcmEffects.applySegmentRanges(buffer, listOf(SegmentRange(40, 9_000, 3f))) { slice, value ->
            PcmBuffer(1_000, 1, ShortArray(slice.samples.size) { value.toInt().toShort() })
        }

        assertEquals(3, out.at(45))
        assertEquals(50, out.frames)
    }

    @Test
    fun `an empty or reversed range is dropped`() {
        val buffer = ramp(50)

        val out = PcmEffects.applySegmentRanges(buffer, listOf(SegmentRange(30, 30, 1f), SegmentRange(40, 20, 1f))) { slice, value ->
            PcmBuffer(1_000, 1, ShortArray(slice.samples.size) { value.toInt().toShort() })
        }

        assertTrue("无效区间应该被丢掉", out.samples.contentEquals(buffer.samples))
    }

    @Test
    fun `an effect that returns fewer samples cannot write past the end`() {
        val buffer = ramp(50)

        val out = PcmEffects.applySegmentRanges(buffer, listOf(SegmentRange(40, 50, 1f))) { slice, _ ->
            // 故意少给一半，模拟长度变化
            PcmBuffer(1_000, 1, ShortArray(slice.samples.size / 2) { 7 })
        }

        // 只写得下 5 个样本，40..44 被替换，45..49 保持原样
        assertEquals(7, out.at(40))
        assertEquals(7, out.at(44))
        assertEquals(50, out.at(49))
    }

    @Test
    fun `length is preserved`() {
        val buffer = ramp(100)

        val out = PcmEffects.applySegmentRanges(buffer, listOf(SegmentRange(10, 90, 4f))) { slice, _ -> slice }

        assertEquals(100, out.frames)
        assertEquals(1, out.channels)
    }

    @Test
    fun `stereo keeps both channels aligned`() {
        val stereo = PcmBuffer(1_000, 2, ShortArray(200) { (it + 1).toShort() })

        val out = PcmEffects.applySegmentRanges(stereo, listOf(SegmentRange(10, 20, 8f))) { slice, value ->
            PcmBuffer(1_000, 2, ShortArray(slice.samples.size) { value.toInt().toShort() })
        }

        assertEquals(2, out.channels)
        assertEquals(8.toShort(), out.samples[20])
        assertEquals(8.toShort(), out.samples[21])
        // 段外原样
        assertEquals(19.toShort(), out.samples[18])
    }
}
