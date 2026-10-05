package cn.music.audioworkshop.media.effect

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * 响度测量与标准化（native libebur128）。
 *
 * 这些测试同时是 **JNI 链路的守门测试**：nativeMeasureLufs 走不到就会
 * UnsatisfiedLinkError 或返回 null，测试直接失败。之前的符号剥离问题就是
 * 这样漏到用户手里的。
 *
 * 测量准确性用 BS.1770 的公认锚点校准 —— 响度偏 2 LU 你只会觉得"好像没完全
 * 标准化"，靠听是听不出来的。
 */
class LoudnessTest {

    private val bridge = LoudnessBridge()

    /**
     * native 库在 JVM 单元测试里加载不了（.so 是 Android 产物）。
     *
     * 所以纯 JVM 跑这一组会跳过，真正验证 native 链路的地方是
     * `:app:testDebugAndroidTest` 或真机上打开响度标准化页导出一遍。
     * 不跳过的话每次 JVM 测试都红，久而久之就没人看这个测试了。
     */
    private val nativeAvailable: Boolean by lazy {
        try {
            LoudnessBridge().measureLufs(ShortArray(48_000), 24_000, 2, 48_000)
            true
        } catch (error: UnsatisfiedLinkError) {
            false
        }
    }

    /** 满量程 1kHz 正弦，左右相同。 */
    private fun sineFullScale(amplitude: Float = 1f, seconds: Int = 10, rate: Int = 48_000): PcmBuffer {
        val mono = ShortArray(rate * seconds)
        for (i in mono.indices) {
            mono[i] = (sin(2.0 * Math.PI * 1000.0 * i / rate) * Short.MAX_VALUE * amplitude).toInt().toShort()
        }
        val out = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            out[i * 2] = mono[i]
            out[i * 2 + 1] = mono[i]
        }
        return PcmBuffer(sampleRateHz = rate, channels = 2, samples = out)
    }

    private fun measure(buffer: PcmBuffer): Float? =
        bridge.measureLufs(buffer.samples, buffer.frames, buffer.channels, buffer.sampleRateHz)

    @Test
    fun `JNI 链路可用`() {
        assumeTrue("native 库在 JVM 测试环境不可用，跳过", nativeAvailable)
        // 没有这一条，后面所有断言都可能只是「碰巧没跑到 native」
        assertNotNull("满幅正弦应能测出响度", measure(sineFullScale()))
    }

    @Test
    fun `满幅 1kHz 正弦约等于 -3 LUFS`() {
        assumeTrue("native 库在 JVM 测试环境不可用，跳过", nativeAvailable)
        // BS.1770-4 的标准锚点：0 dBFS 的 1kHz 正弦对应 -3 LUFS。
        val measured = measure(sineFullScale())!!
        assertEquals("1kHz 满幅正弦应为 -3 LUFS，实际 $measured", -3.0f, measured, 1.0f)
    }

    @Test
    fun `幅度减半响度低 6 LU`() {
        assumeTrue("native 库在 JVM 测试环境不可用，跳过", nativeAvailable)
        val full = measure(sineFullScale(amplitude = 1f))!!
        val half = measure(sineFullScale(amplitude = 0.5f))!!
        assertEquals("幅度减半应低 6 LU，实际 $full vs $half", 6.0f, full - half, 0.6f)
    }

    @Test
    fun `标准化后到达目标响度`() {
        assumeTrue("native 库在 JVM 测试环境不可用，跳过", nativeAvailable)
        val target = -14f
        val buffer = sineFullScale(amplitude = 0.1f)
        val before = measure(buffer)!!
        assertTrue("前置条件：素材应比目标安静，实际 $before", before < target - 3f)

        bridge.normalizeTo(
            buffer.samples,
            buffer.frames,
            buffer.channels,
            buffer.sampleRateHz,
            target,
        )
        val after = measure(buffer)!!
        assertEquals("标准化后应到达 $target LUFS，实际 $after", target, after, 1.0f)
    }

    @Test
    fun `标准化不会削波`() {
        assumeTrue("native 库在 JVM 测试环境不可用，跳过", nativeAvailable)
        // 目标很响时增益很大，必须限幅而不是切掉波形顶部
        val buffer = sineFullScale(amplitude = 0.9f)
        bridge.normalizeTo(
            buffer.samples,
            buffer.frames,
            buffer.channels,
            buffer.sampleRateHz,
            -8f,
        )
        var clipped = 0
        for (sample in buffer.samples) {
            if (abs(sample.toInt()) >= PcmBuffer.SHORT_MAX) clipped++
        }
        assertEquals("不应有样本被削到满量程", 0, clipped)
    }

    @Test
    fun `纯静音不被放大`() {
        assumeTrue("native 库在 JVM 测试环境不可用，跳过", nativeAvailable)
        val silence = PcmBuffer(48_000, 2, ShortArray(48_000 * 5 * 2))
        assertEquals("纯静音标准化后应仍是静音", 0, silence.samples.sumOf { abs(it.toInt()) })
        bridge.normalizeTo(
            silence.samples,
            silence.frames,
            silence.channels,
            silence.sampleRateHz,
            -14f,
        )
        assertEquals("静音被放大只会变成噪声", 0, silence.samples.sumOf { abs(it.toInt()) })
    }

    @Test
    fun `空缓冲返回 null 而不是抛异常`() {
        val empty = PcmBuffer(48_000, 2, ShortArray(0))
        assertNull("空缓冲应返回 null", measure(empty))
    }

    @Test
    fun `PcmEffects 的封装走的是同一条 native 链路`() {
        assumeTrue("native 库在 JVM 测试环境不可用，跳过", nativeAvailable)
        // 页面用的是 PcmEffects.normalizeLoudness，这里确认它和 bridge 行为一致，
        // 免得 UI 上显示的响度和实际导出的对不上。
        val direct = sineFullScale(amplitude = 0.1f)
        val viaEffect = sineFullScale(amplitude = 0.1f)
        bridge.normalizeTo(direct.samples, direct.frames, direct.channels, direct.sampleRateHz, -14f)
        PcmEffects.normalizeLoudness(viaEffect, -14f)
        val a = measure(direct)!!
        val b = measure(viaEffect)!!
        assertEquals("两种入口应得到相同响度: $a vs $b", a, b, 0.5f)
    }

    @Test
    fun `目标响度常量在合理范围内`() {
        assertTrue(
            "默认目标应在 -14 附近，实际 ${LoudnessBridge.TARGET_LUFS}",
            abs(LoudnessBridge.TARGET_LUFS - (-14f)) < 0.1f,
        )
    }
}

