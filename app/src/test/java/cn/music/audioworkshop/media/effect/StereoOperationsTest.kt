package cn.music.audioworkshop.media.effect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 立体声分离 / 合成 / 环绕。
 *
 * 这三个功能都靠声道语义正确，错了听感上是「完全变了一首歌」而不是「有点不对」，
 * 所以把声道取对取错都钉死。
 */
class StereoOperationsTest {

    /** 左右不同的立体声帧，方便确认取的是哪一路。 */
    private fun stereoLr(frames: Int, left: (Int) -> Short, right: (Int) -> Short) =
        PcmBuffer(44_100, 2, ShortArray(frames * 2) { i ->
            if (i % 2 == 0) left(i / 2) else right(i / 2)
        })

    @Test
    fun `提取左声道只留下左边的内容`() {
        val buffer = stereoLr(100, { index -> (index + 1).toShort() }, { 0 })
        val left = PcmEffects.extractChannel(buffer, 0)
        assertEquals(1, left.channels)
        assertEquals(100, left.frames)
        for (i in 0 until 100) {
            assertEquals("第 $i 帧", (i + 1).toShort(), left.samples[i])
        }
    }

    @Test
    fun `提取右声道只留下右边的内容`() {
        val buffer = stereoLr(100, { 0 }, { index -> (index + 1).toShort() })
        val right = PcmEffects.extractChannel(buffer, 1)
        assertEquals(1, right.channels)
        for (i in 0 until 100) {
            assertEquals("第 $i 帧", (i + 1).toShort(), right.samples[i])
        }
    }

    @Test
    fun `单声道输入分离出两个相同的声道`() {
        // 用户明确要求：原曲是单声道的话，两个输出是同一份声音。
        val mono = PcmBuffer(44_100, 1, ShortArray(200) { (it * 3).toShort() })
        val left = PcmEffects.extractChannel(mono, 0)
        val right = PcmEffects.extractChannel(mono, 1)
        assertEquals("单声道的左右声道必须完全一致",
            left.samples.toList(),
            right.samples.toList(),
        )
        assertEquals("单声道提取后内容不变", mono.samples.toList(), left.samples.toList())
    }

@Test
    fun `声道提取和 M-S 分离不是一回事`() {
        // stereoSplit 给的是中间/侧信道；extractChannel 给的是真正的左右。
        // 左右相同（等价单声道）的输入下，侧信道应该是静音，
        // 而声道提取必须原样保留内容 —— 这是用户能听出来的差别。
        //
        // stereoSplit 是**原地修改**，所以先算好声道提取再动原 buffer，
        // 否则读到的就是被静音化之后的数据（两边都是 0，断言失去意义）。
        val flat = PcmBuffer(44_100, 2, ShortArray(200) { (1000 + it).toShort() })
        val rightChannel = PcmEffects.extractChannel(flat, 1)
        val sideChannel = PcmEffects.stereoSplit(flat, PcmEffects.KEEP_SIDE)
        assertTrue("声道提取不应把内容变成静音", rms(rightChannel) > 1.0)
        assertTrue(
            "M/S 侧声道应接近静音，实际能量 ${rms(sideChannel)}",
            rms(sideChannel) < rms(rightChannel) / 10f,
        )
    }

    @Test
    fun `合成把两个单声道放回左右`() {
        val left = PcmBuffer(44_100, 1, ShortArray(50) { (it + 1).toShort() })
        val right = PcmBuffer(44_100, 1, ShortArray(50) { (-(it + 1)).toShort() })
        val composed = PcmEffects.stereoCompose(left, right)
        assertEquals(2, composed.channels)
        for (i in 0 until 50) {
            assertEquals("左声道第 $i 帧", (i + 1).toShort(), composed.samples[i * 2])
            assertEquals("右声道第 $i 帧", (-(i + 1)).toShort(), composed.samples[i * 2 + 1])
        }
    }

    @Test
    fun `合成的往返是恒等的`() {
        // 分离 → 合成 应当回到原样。这是两个功能能互逆的前提。
        val original = stereoLr(120, { index -> (index * 2 % 900).toShort() }, { index -> (index * 3 % 700).toShort() })
        val left = PcmEffects.extractChannel(original, 0)
        val right = PcmEffects.extractChannel(original, 1)
        val roundTrip = PcmEffects.stereoCompose(left, right)
        assertEquals("分离再合成应当还原原始立体声",
            original.samples.toList(),
            roundTrip.samples.toList(),
        )
    }

@Test
    fun `时长不等时短的那路补静音`() {
        val left = PcmBuffer(44_100, 1, ShortArray(50) { (it + 1).toShort() })
        val right = PcmBuffer(44_100, 1, ShortArray(20) { (it + 1).toShort() })
        val composed = PcmEffects.stereoCompose(left, right)
        assertEquals("总帧数取较长的一路", 50, composed.frames)
        // 右声道只有 20 帧，从第 20 帧起补静音
        assertEquals(0, composed.samples[30 * 2 + 1].toInt())
        assertEquals(0, composed.samples[49 * 2 + 1].toInt())
        // 左路是长路，不该被截断
        assertTrue("长路的内容不该被截断", composed.samples[49 * 2] != 0.toShort())
        assertEquals(31, composed.samples[30 * 2].toInt())
    }

    /** 均方根能量。不依赖索引，所以不关心返回 buffer 的确切帧数。 */
    private fun rms(buffer: PcmBuffer): Double {
        var sum = 0.0
        for (sample in buffer.samples) {
            val v = sample.toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / buffer.samples.size)
    }

    @Test
    fun `单声道输入时环绕原样返回`() {
        // 只有两声道才能环绕，单声道没什么可绕的
        val mono = PcmBuffer(44_100, 1, ShortArray(500) { (it % 100).toShort() })
        val out = PcmEffects.stereoOrbit(mono, halfCircleSec = 8f, degrees = 60f)
        assertEquals(mono.samples.toList(), out.samples.toList())
    }

    @Test
    fun `环绕幅度为零时不改动信号`() {
        val stereo = stereoLr(500, { index -> (index % 200).toShort() }, { index -> (-index % 150).toShort() })
        val out = PcmEffects.stereoOrbit(stereo, halfCircleSec = 8f, degrees = 0f)
        assertEquals(stereo.samples.toList(), out.samples.toList())
    }

    @Test
    fun `环绕幅度越大改动越多`() {
        val stereo = stereoLr(44_100, { index -> (index % 300).toShort() }, { index -> (-index % 200).toShort() })
        fun changed(degrees: Float): Long {
            val copy = PcmBuffer(stereo.sampleRateHz, stereo.channels, stereo.samples.copyOf())
            val out = PcmEffects.stereoOrbit(copy, halfCircleSec = 4f, degrees = degrees)
            var sum = 0L
            for (i in stereo.samples.indices) {
                val d = out.samples[i].toLong() - stereo.samples[i].toLong()
                sum += d * d
            }
            return sum
        }
        val small = changed(15f)
        val large = changed(80f)
        assertTrue("幅度 15° 应有效果: $small", small > 0)
        assertTrue("幅度 80° 应比 15° 改动更多: 15°=$small 80°=$large", large > small)
    }
}



