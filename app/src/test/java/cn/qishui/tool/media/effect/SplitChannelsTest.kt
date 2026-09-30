package cn.qishui.tool.media.effect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 立体声分离现在一次产出左右两个单声道文件。
 * 单声道源按参考提示的预期行为：拆出来两边一样。
 */
class SplitChannelsTest {

    @Test
    fun stereoSourceBecomesTwoMonoBuffers() {
        val stereo = PcmBuffer.of(
            shortArrayOf(100, -100, 200, -200, 300, -300),
            sampleRateHz = 44_100,
            channels = 2,
        )

        val (left, right) = PcmEffects.splitChannels(stereo)

        assertEquals(1, left.channels)
        assertEquals(1, right.channels)
        assertEquals(3, left.frames)
        assertArrayEquals(shortArrayOf(100, 200, 300), left.samples)
        assertArrayEquals(shortArrayOf(-100, -200, -300), right.samples)
    }

    @Test
    fun monoSourceProducesTwoIdenticalCopies() {
        val mono = PcmBuffer.of(shortArrayOf(10, 20, 30), sampleRateHz = 44_100, channels = 1)

        val outputs = PcmEffects.splitChannels(mono)

        assertEquals(2, outputs.size)
        assertArrayEquals(outputs[0].samples, outputs[1].samples)
        assertArrayEquals(shortArrayOf(10, 20, 30), outputs[0].samples)
    }

    @Test
    fun stereoSplitIsRegisteredAsAMultiOutputEffect() {
        val definition = EffectRegistry.find("stereo_split")

        assertEquals(true, definition?.isMultiOutput)
        // 其它效果不能被这个改动带成多输出
        listOf("reverse", "reverb", "equalizer", "denoise").forEach { id ->
            assertEquals("「$id」不该是多输出", false, EffectRegistry.find(id)?.isMultiOutput)
        }
    }

    @Test
    fun theTwoOutputsAreIndependentBuffersNotAliases() {
        val stereo = PcmBuffer.of(
            shortArrayOf(1, 2, 3, 4),
            sampleRateHz = 44_100,
            channels = 2,
        )

        val (left, right) = PcmEffects.splitChannels(stereo)
        left.samples[0] = 999

        // 改左边不能影响右边，否则播放时会串
        assertEquals(2, right.samples[0].toInt())
    }
}
