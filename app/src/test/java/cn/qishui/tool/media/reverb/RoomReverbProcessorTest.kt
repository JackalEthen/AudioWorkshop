package cn.qishui.tool.media.reverb

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import cn.qishui.tool.domain.player.SoundEffectPreset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音效处理器的回归网。
 *
 * 这里只测**能确定性验证**的东西：增益公式、旁路安全、样本守恒、数值稳定、电话窄带。
 *
 * 刻意不测的：尾音长短、高频衰减快慢这些**听感**属性。
 * 它们要靠包络测量，而测量窗口、激励方式、绝对阈值都会左右结果，
 * 写进测试只会变成一个天天假失败的断言。这两个维度靠装机试听验收。
 */
class RoomReverbProcessorTest {

    private val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    private fun newProcessor(preset: SoundEffectPreset): RoomReverbProcessor =
        RoomReverbProcessor().apply { setPreset(preset) }

    /** 喂一段 16bit 立体声，取回同长度的输出。 */
    private fun render(processor: RoomReverbProcessor, samples: ShortArray): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        val out = processor.getOutput()
        val result = ShortArray(out.remaining() / 2)
        for (i in result.indices) result[i] = out.getShort(i * 2)
        return result
    }

    private fun sine(
        hz: Float,
        frames: Int,
        amplitude: Float = 0.4f,
    ): ShortArray = ShortArray(frames) { i ->
        (sin(2 * PI * hz * i / 44100f) * amplitude * 32767f).toInt().toShort()
    }

    /** 只量某个频段的稳态能量。[above] 为 true 取截止频率以上。 */
    private fun bandEnergy(
        preset: SoundEffectPreset,
        hz: Float,
        above: Boolean,
        cutoffHz: Float,
    ): Double {
        val processor = newProcessor(preset)
        processor.configure(format)
        val out = render(processor, sine(hz, 44100 * 2))
        var sum = 0.0
        for (v in filter(out, cutoffHz, above)) sum += (v.toDouble() * v)
        return sqrt(sum / out.size)
    }

    /** 一阶高低通，用来在测试里把被测频段单独摘出来。 */
    private fun filter(input: ShortArray, cutoffHz: Float, highPass: Boolean): ShortArray {
        val alpha = 1.0 - Math.exp(-2.0 * PI.toDouble() * cutoffHz / 44100.0)
        val out = ShortArray(input.size)
        var prevX = 0.0
        var prevY = 0.0
        for (i in input.indices) {
            val x = input[i] / 32768.0
            val y = if (highPass) {
                alpha * (prevY + x - prevX)
            } else {
                prevY + alpha * (x - prevY)
            }
            out[i] = (y * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            prevX = x
            prevY = y
        }
        return out
    }

    // ---- 增益公式（这是唯一需要「算」的地方，也是最容易改错的地方） ----

    /**
     * RT-60 是不是真的被实现了。
     *
     * 梳状滤波器每 looptime 秒重复一次，所以「rt60 秒后掉到 -60dB」
     * 等价于 `coef^(rt60/looptime) == 0.001`。
     * 纯数学断言，不依赖包络测量，最不容易假失败。
     */
    @Test
    fun 梳状增益严格对应RT60() {
        for (rt60 in SoundEffectPreset.selectable.map { it.rt60Seconds }.filter { it > 0f }) {
            val looptime = 1356f / 44100f
            val comb = CombFilter(looptime, 44100)
            comb.setRt60(rt60)
            val afterRt60 = Math.pow(comb.coef().toDouble(), (rt60 / looptime).toDouble())
            assertEquals(
                "RT60=$rt60 s 时应该在 $rt60 秒后掉到 -60dB，实际是 $afterRt60",
                0.001,
                afterRt60,
                0.0005,
            )
        }
    }

    /** 房间越大，反馈越接近 1，尾音越长。 */
    @Test
    fun 房间越大尾音越长() {
        fun coefOf(rt60: Float): Float =
            CombFilter(1356f / 44100f, 44100).also { it.setRt60(rt60) }.coef()

        val sorted = SoundEffectPreset.selectable.map { it.rt60Seconds }.filter { it > 0f }.sorted()
        for (i in 0 until sorted.size - 1) {
            assertTrue(
                "RT60 ${sorted[i + 1]}s 的增益应该大于 ${sorted[i]}s",
                coefOf(sorted[i + 1]) > coefOf(sorted[i]),
            )
        }
    }

    // ---- 旁路与稳定性 ----

    @Test
    fun 关闭预设时不参与处理() {
        val processor = newProcessor(SoundEffectPreset.NONE)
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            processor.configure(format),
        )
        assertTrue("没开音效时必须让链路跳过自己", !processor.isActive)
    }

    @Test
    fun 非十六位立体声直接旁路() {
        val processor = newProcessor(SoundEffectPreset.CINEMA)
        val mono = AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)
        assertEquals(
            "处理不了就整条旁路，绝不能让播放挂掉",
            AudioProcessor.AudioFormat.NOT_SET,
            processor.configure(mono),
        )
    }

    @Test
    fun 样本数进多少出多少() {
        val processor = newProcessor(SoundEffectPreset.HALL)
        processor.configure(format)
        val frames = 4096
        assertEquals(frames, render(processor, sine(440f, frames)).size)
    }

    @Test
    fun 引擎输出一直是有限数() {
        val engine = ReverbEngine(44100)
        engine.applyPreset(SoundEffectPreset.CINEMA)
        repeat(44100) { i ->
            val v = sin(i * 0.01).toFloat() * 0.4f
            engine.processStereo(v, 0.3f)
            assertTrue("outLeft 出现 NaN/Inf", engine.outLeft.isFinite())
            assertTrue("outRight 出现 NaN/Inf", engine.outRight.isFinite())
        }
    }

    @Test
    fun 满量程输入靠软限幅压住() {
        val processor = newProcessor(SoundEffectPreset.HALL)
        processor.configure(format)
        val square = ShortArray(44100) { if (it % 2 == 0) 32767 else -32768 }
        val out = render(processor, square)
        assertTrue("输出不能为空", out.isNotEmpty())
        val loud = out.count { abs(it.toInt()) > 1000 }
        assertTrue("满量程输入应该还有输出，实际只有 $loud 个采样超过 1000", loud > 1000)
    }

    // ---- 预设的具体行为 ----

    @Test
    fun 电话滤掉带外高频() {
        val inBand = bandEnergy(SoundEffectPreset.TELEPHONE, 1000f, above = false, cutoffHz = 3400f)
        val outOfBand = bandEnergy(SoundEffectPreset.TELEPHONE, 6000f, above = false, cutoffHz = 3400f)
        assertTrue(
            "听筒只有 300–3400Hz，6kHz（$outOfBand）必须远弱于 1kHz（$inBand）",
            outOfBand < inBand * 0.5,
        )
    }
}
