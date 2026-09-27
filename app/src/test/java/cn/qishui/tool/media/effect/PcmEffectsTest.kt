package cn.qishui.tool.media.effect

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmEffectsTest {

    private fun stereo(frames: Int, left: (Int) -> Short, right: (Int) -> Short = left) =
        PcmBuffer(
            sampleRateHz = 44_100,
            channels = 2,
            samples = ShortArray(frames * 2) { index ->
                if (index % 2 == 0) left(index / 2) else right(index / 2)
            },
        )

    @Test
    fun `倒放后首尾对调`() {
        val buffer = stereo(4, left = { frame -> (frame * 1000).toShort() })
        PcmEffects.reverse(buffer)
        assertEquals(3000, buffer.samples[0].toInt())
        assertEquals(2000, buffer.samples[2].toInt())
        assertEquals(1000, buffer.samples[4].toInt())
        assertEquals(0, buffer.samples[6].toInt())
    }

    @Test
    fun `反转相位逐采样取负`() {
        val buffer = PcmBuffer(44_100, 1, shortArrayOf(100, -200, 0, -32_768))
        PcmEffects.invertPhase(buffer)
        assertEquals(listOf(-100, 200, 0, 32_767), buffer.samples.map { it.toInt() })
    }

    @Test
    fun `立体声分离去掉中置只留侧向`() {
        // 左右相同 -> 纯中置；分离侧向后应接近静音
        val buffer = stereo(3, left = { 1000 })
        PcmEffects.stereoSplit(buffer, PcmEffects.KEEP_SIDE)
        buffer.samples.forEach { assertEquals(0, it.toInt()) }
    }

    @Test
    fun `立体声分离保留中置时左右一致`() {
        val buffer = PcmBuffer(44_100, 2, shortArrayOf(1000, 1000, -500, -500))
        PcmEffects.stereoSplit(buffer, PcmEffects.KEEP_MID)
        assertEquals(buffer.samples[0], buffer.samples[1])
        assertEquals(buffer.samples[2], buffer.samples[3])
    }

    @Test
    fun `环绕加宽不改变中置信号`() {
        val buffer = stereo(2, left = { 1000 })
        PcmEffects.stereoWiden(buffer, 1f)
        assertTrue(abs(buffer.samples[0].toInt() - 1000) <= 2)
    }

    @Test
    fun `回声在延迟之后产生非零信号`() {
        val frames = 44_100
        val buffer = PcmBuffer(44_100, 1, ShortArray(frames) { if (it < 100) 8000 else 0 })
        val delayFrames = 4410
        PcmEffects.echo(buffer, delayMs = delayFrames * 1000f / 44_100f, feedback = 0.5f, mix = 0.5f)
        assertTrue("延迟点之后应有回声", abs(buffer.samples[delayFrames + 50].toInt()) > 0)
    }

    @Test
    fun `合唱在展开后产生不同内容`() {
        val frames = 4000
        val buffer = PcmBuffer(44_100, 1, ShortArray(frames) { (it % 100).toShort() })
        val original = buffer.samples.copyOf()
        val processed = PcmEffects.choir(buffer, spreadMs = 20f, mix = 0.5f)
        assertTrue("合唱后应与原信号不同", original.contentEquals(processed.samples).not())
    }

    @Test
    fun `混响输出不削波且长度不变`() {
        val frames = 8000
        val buffer = PcmBuffer(44_100, 1, ShortArray(frames) { if (it % 50 == 0) 20_000 else 0 })
        val processed = PcmEffects.reverb(buffer, roomSize = 0.5f, mix = 0.4f)
        assertEquals(frames, processed.frames)
        processed.samples.forEach { assertTrue(abs(it.toInt()) <= 32_768) }
    }

    @Test
    fun `均衡器提升低频后低频幅度变大`() {
        val frames = 8_000
        val rate = 44_100
        val low = 100f
        val buffer = PcmBuffer(rate, 1, sine(frames, rate, low, 0.5f))
        val before = rms(buffer.samples)
        PcmEffects.equalizer(buffer, lowDb = 9f, midDb = 0f, highDb = 0f)
        val after = rms(buffer.samples)
        assertTrue("低频提升后能量应上升: $before -> $after", after > before * 1.2f)
    }

    @Test
    fun `收音机音效滤掉低频高频只留中频`() {
        val frames = 8_000
        val rate = 44_100
        val buffer = PcmBuffer(rate, 1, sine(frames, rate, 120f, 0.8f))
        val before = rms(buffer.samples)
        PcmEffects.radioFx(buffer, 0.6f)
        val after = rms(buffer.samples)
        assertTrue("带通后能量应下降: $before -> $after", after < before)
    }

    @Test
    fun `修复去掉直流偏移`() {
        val buffer = PcmBuffer(44_100, 1, ShortArray(500) { 1000 })
        PcmEffects.repair(buffer, 1f)
        assertTrue("直流应被抹平", abs(buffer.samples[250].toInt()) <= 2)
    }

    @Test
    fun `去除头尾裁掉静音但保留主体`() {
        val rate = 1_000
        val samples = ShortArray(1_000)
        for (index in 200 until 400) samples[index] = 9_000
        val buffer = PcmBuffer(rate, 1, samples)
        val trimmed = PcmEffects.trimSilence(buffer, thresholdDb = -40f, minSilenceMs = 0f)
        assertTrue("应裁短: ${trimmed.frames}", trimmed.frames < 1_000)
        assertTrue("主体要留下: ${trimmed.frames}", trimmed.frames > 100)
        assertTrue(trimmed.frames <= 400)
    }

    @Test
    fun `重采样按时长比例变化`() {
        val buffer = PcmBuffer(44_100, 1, sine(44_100, 44_100, 440f, 0.5f))
        val resampled = buffer.resampled(22_050)
        assertEquals(22_050, resampled.sampleRateHz)
        assertTrue("帧数应减半左右: ${resampled.frames}", abs(resampled.frames - 22_050) < 100)
    }

    @Test
    fun `转单声道后取左右平均`() {
        val buffer = PcmBuffer(44_100, 2, shortArrayOf(100, 300, -200, 200))
        val mono = buffer.toMono()
        assertEquals(1, mono.channels)
        assertEquals(200, mono.samples[0].toInt())
        assertEquals(0, mono.samples[1].toInt())
    }

    @Test
    fun `混音按最长轨补零并限幅`() {
        val a = PcmBuffer(44_100, 1, shortArrayOf(20_000, 20_000))
        val b = PcmBuffer(44_100, 1, shortArrayOf(20_000, 20_000, 20_000, 20_000))
        val mixed = a.mixedWith(b)
        assertEquals(4, mixed.frames)
        assertEquals(32_767, mixed.samples[0].toInt())
        assertEquals(20_000, mixed.samples[2].toInt())
    }

    @Test
    fun `写出 wav 后能被读回同样的采样`() {
        val original = PcmBuffer(44_100, 2, sine(1_000, 44_100, 440f, 0.5f))
        val file = File.createTempFile("qishui-effect", ".wav")
        try {
            original.writeWav(file)
            val read = readWav(file)
            assertEquals(44_100, read.first)
            assertEquals(2, read.second)
            assertTrue(original.samples.contentEquals(read.third))
        } finally {
            file.delete()
        }
    }

    private fun sine(frames: Int, rate: Int, hz: Float, amplitude: Float): ShortArray =
        ShortArray(frames) { index ->
            val value = kotlin.math.sin(2.0 * Math.PI * hz * index / rate).toFloat() * amplitude * Short.MAX_VALUE
            value.toInt().toShort()
        }

    private fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        samples.forEach { sum += it.toDouble() * it.toDouble() }
        return kotlin.math.sqrt(sum / samples.size).toFloat()
    }

    private fun readWav(file: File): Triple<Int, Int, ShortArray> =
        RandomAccessFile(file, "r").use { handle ->
            val header = ByteArray(44)
            handle.readFully(header)
            val channels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
            val rate = (header[24].toInt() and 0xFF) or
                ((header[25].toInt() and 0xFF) shl 8) or
                ((header[26].toInt() and 0xFF) shl 16) or
                ((header[27].toInt() and 0xFF) shl 24)
            val dataBytes = handle.length() - 44L
            val body = ByteArray(dataBytes.toInt())
            handle.readFully(body)
            val samples = ShortArray(dataBytes.toInt() / 2) { index ->
                val low = body[index * 2].toInt() and 0xFF
                val high = body[index * 2 + 1].toInt()
                ((high shl 8) or low).toShort()
            }
            Triple(rate, channels, samples)
        }
}
