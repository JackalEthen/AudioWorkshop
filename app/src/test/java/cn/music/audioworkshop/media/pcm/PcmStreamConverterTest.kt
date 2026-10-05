package cn.music.audioworkshop.media.pcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmStreamConverterTest {

    private fun run(
        input: ShortArray,
        sourceRate: Int,
        sourceChannels: Int,
        targetRate: Int,
        targetChannels: Int,
        chunkFrames: Int,
    ): ShortArray {
        val converter = PcmStreamConverter(sourceRate, sourceChannels, targetRate, targetChannels)
        val collected = ArrayList<Short>()
        val frameSize = chunkFrames * sourceChannels
        var offset = 0
        while (offset < input.size) {
            val count = minOf(frameSize, input.size - offset)
            val block = converter.convert(input.copyOfRange(offset, offset + count), count)
            for (value in block) collected.add(value)
            offset += count
        }
        for (value in converter.flush()) collected.add(value)
        return collected.toShortArray()
    }

    private fun sine(rate: Int, hz: Float, seconds: Float): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(frames) { frame ->
            (Math.sin(2.0 * Math.PI * hz * frame / rate) * 20_000).toInt().toShort()
        }
    }

    @Test
    fun `same rate and channels passes through`() {
        val input = sine(44_100, 440f, 0.1f)

        val out = run(input, 44_100, 1, 44_100, 1, chunkFrames = 1024)

        assertEquals(input.size, out.size)
        assertTrue("内容应该基本一致", out.contentEquals(input))
    }

    @Test
    fun `halving the sample rate halves the frame count`() {
        val input = sine(44_100, 440f, 0.5f)

        val out = run(input, 44_100, 1, 22_050, 1, chunkFrames = 1024)

        val expected = input.size / 2
        assertTrue("目标帧数应该约等于一半：${out.size} vs $expected", out.size in (expected - 4)..(expected + 4))
    }

    @Test
    fun `doubling the sample rate doubles the frame count`() {
        val input = sine(22_050, 440f, 0.5f)

        val out = run(input, 22_050, 1, 44_100, 1, chunkFrames = 512)

        val expected = input.size * 2
        assertTrue("目标帧数应该约等于两倍：${out.size} vs $expected", out.size in (expected - 4)..(expected + 4))
    }

    @Test
    fun `resampling keeps the tone frequency roughly intact`() {
        // 频率不变，所以每周期的秒数不变；但采样率减半，每周期的帧数也减半
        val input = sine(44_100, 440f, 0.5f)

        val out = run(input, 44_100, 1, 22_050, 1, chunkFrames = 777)

        var firstPositive = -1
        var crossings = 0
        for (index in 100 until out.size) {
            if (out[index - 1] <= 0 && out[index] > 0) {
                if (firstPositive < 0) firstPositive = index else crossings++
            }
        }
        val framesPerPeriod = 22_050 / 440
        val expectedCrossings = (out.size - 100) / framesPerPeriod
        assertTrue("周期应该还是 $framesPerPeriod 帧，实际 $crossings 次过零（预期约 $expectedCrossings）", kotlin.math.abs(crossings - expectedCrossings) <= 2)
    }

    @Test
    fun `chunk size does not change the result`() {
        val input = sine(48_000, 300f, 0.2f)

        val small = run(input, 48_000, 1, 32_000, 1, chunkFrames = 64)
        val large = run(input, 48_000, 1, 32_000, 1, chunkFrames = 4096)

        assertTrue("两种分块方式长度应一致：${small.size} vs ${large.size}", small.size == large.size)
        assertTrue("内容应该一致", small.contentEquals(large))
    }

    @Test
    fun `stereo to mono averages both sides`() {
        val stereo = shortArrayOf(1000, 3000, -2000, 2000, 500, -500)

        val out = run(stereo, 44_100, 2, 44_100, 1, chunkFrames = 1024)

        assertEquals(3, out.size)
        assertEquals(2000.toShort(), out[0])
        assertEquals(0.toShort(), out[1])
        assertEquals(0.toShort(), out[2])
    }

    @Test
    fun `mono to stereo duplicates the same sample on both sides`() {
        val mono = shortArrayOf(1000, -2000, 500)

        val out = run(mono, 44_100, 1, 44_100, 2, chunkFrames = 1024)

        assertEquals(6, out.size)
        assertEquals(1000.toShort(), out[0])
        assertEquals(1000.toShort(), out[1])
        assertEquals((-2000).toShort(), out[2])
        assertEquals((-2000).toShort(), out[3])
    }

    @Test
    fun `rate and channel conversion combined`() {
        val input = sine(44_100, 440f, 0.3f)

        val out = run(input, 44_100, 1, 8_000, 2, chunkFrames = 512)

        // 8k/44.1k ≈ 0.1814，且每帧两个声道
        val expected = (input.size * 0.1814).toInt()
        assertTrue("长度应该约等于比例换算：${out.size / 2} vs $expected", out.size / 2 in (expected - 3)..(expected + 3))
    }

    @Test
    fun `output never exceeds 16 bit range`() {
        val loud = ShortArray(4_800) { if (it % 2 == 0) 32_767 else -32_768 }

        val out = run(loud, 44_100, 1, 48_000, 1, chunkFrames = 333)

        assertTrue(out.all { it >= Short.MIN_VALUE && it <= Short.MAX_VALUE })
    }
}
