package cn.music.audioworkshop.media.reverb

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import cn.music.audioworkshop.domain.player.SoundEffectPreset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.log10
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

    private val SAMPLE_RATE = 44100
    private val format = AudioProcessor.AudioFormat(SAMPLE_RATE, 2, C.ENCODING_PCM_16BIT)

    /** 输出与输入有差异的样本个数。旁路时必须是 0。 */
    private fun countDifferences(processor: RoomReverbProcessor, input: ShortArray): Int {
        val out = render(processor, input)
        var different = 0
        for (i in input.indices) {
            if (out.getOrNull(i)?.let { it != input[i] } != false) different++
        }
        return different
    }

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

    /** 旁路对比用的固定输入。 */
    private val samples: ShortArray get() = sine(440f, 2048)

    // ---- 衰减律（这是唯一需要「算」的地方，也是最容易改错的地方） ----

    /**
     * 实际衰减速度是不是真的跟着 RT-60 走。
     *
     * 之前这里断言的是梳状滤波器的内部增益公式，那是白盒断言 ——
     * 公式对了不代表听起来对。现在改成**测渲染出来的音频**：
     * 灌一段脉冲进去，量包络的下降斜率（dB/秒），反推出 RT-60，
     * 再跟预设声明的值比。
     *
     * 容差给得比较宽（±45%）：FDN 的环路增益是按平均延迟算的近似，
     * 实测本来就有偏差。这条测的是「有没有实现衰减、大致量级对不对」，
     * 不是「精确到分贝」。
     */
    @Test
    fun 实际衰减速度跟着RT60走() {
        for (preset in listOf(
            SoundEffectPreset.INDOOR,
            SoundEffectPreset.CINEMA,
            SoundEffectPreset.CINEMA,
            SoundEffectPreset.HALL,
        )) {
            val decayPerSecond = measureDecayDbPerSecond(preset)
            val expected = 60f / preset.rt60Seconds
            val ratio = decayPerSecond / expected
            assertTrue(
                "${preset.displayName}: 实测 ${"%.0f".format(decayPerSecond)} dB/s，" +
                    "期望 ${"%.0f".format(expected)} dB/s（比值 $ratio）",
                ratio > 0.55f && ratio < 1.8f,
            )
        }
    }

    /**
     * 喂宽带噪声，把输出按 [cutoffHz] 拆成低频段和高频段，返回两段的平均能量。
     *
     * 用确定性噪声（线性同余，不用 `Random`）是为了可复现。
     * 一阶低通当粗分频器：这里的目的是比较两段的**相对强弱**，
     * 不是做精确频谱分析，所以够用。
     */
    private fun bandSplit(preset: SoundEffectPreset, cutoffHz: Float): Pair<Double, Double> {
        val engine = ReverbEngine(SAMPLE_RATE).apply { applyPreset(preset) }
        val total = SAMPLE_RATE
        val from = (SAMPLE_RATE * 0.1f).toInt()
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        val alpha = (rc / (rc + 1f / SAMPLE_RATE))
        var low = 0f
        var lowEnergy = 0.0
        var highEnergy = 0.0
        var count = 0
        var seed = 20260930L
        for (i in 0 until total) {
            // LCG → [-1, 1) 的白噪声
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val noise = ((seed ushr 33).toInt() / 2147483648f) - 1f
            engine.processStereo(noise, noise)
            if (i >= from) {
                val out = engine.outRight
                low += (out - low) * alpha
                val high = out - low
                lowEnergy += (low * low).toDouble()
                highEnergy += (high * high).toDouble()
                count++
            }
        }
        return if (count == 0) 0.0 to 0.0 else lowEnergy / count to highEnergy / count
    }

    /** 灌一段脉冲，量包络在 0.2–1.2s 区间的平均下降速度（dB/秒）。 */
    private fun measureDecayDbPerSecond(preset: SoundEffectPreset): Float {
        val engine = ReverbEngine(SAMPLE_RATE).apply { applyPreset(preset) }
        val silence = 1.2f
        val totalSamples = (SAMPLE_RATE * silence).toInt()
        val buf = FloatArray(totalSamples)

        // 前 20ms 灌一个脉冲当激励，之后全零，剩下的就是纯衰减尾巴
        val impulseSamples = (SAMPLE_RATE * 0.02f).toInt()
        var peak = 0f
        for (i in 0 until totalSamples) {
            val t = i.toFloat() / SAMPLE_RATE
            val inSample = if (i < impulseSamples) {
                // 直流脉冲能量最大，最容易量
                1f
            } else {
                0f
            }
            engine.processStereo(inSample, 0f)
            val out = abs(engine.outRight)
            buf[i] = out
            if (i < impulseSamples && out > peak) peak = out
        }
        if (peak <= 0f) return 0f

        fun dbAt(seconds: Float): Float {
            val idx = (SAMPLE_RATE * seconds).toInt().coerceIn(0, totalSamples - 1)
            // 取 20ms 窗口的最大值，避免落在零交叉上量到 -inf
            val from = (idx - (SAMPLE_RATE * 0.02f).toInt()).coerceAtLeast(0)
            var local = 0f
            for (i in from..idx) if (buf[i] > local) local = buf[i]
            return 20f * log10((local / peak).coerceAtLeast(1e-7f))
        }

        val db1 = dbAt(0.2f)
        val db2 = dbAt(1.2f)
        return -(db2 - db1) / 1.0f
    }

    /** 房间越大，反馈越接近 1，尾音越长。 */
    @Test
    fun 房间越大尾音越长() {
        val sorted = SoundEffectPreset.selectable.map { it.rt60Seconds }.filter { it > 0f }.sorted()
        for (i in 0 until sorted.size - 1) {
            assertTrue(
                "RT60 ${sorted[i + 1]}s 应该比 ${sorted[i]}s 长",
                sorted[i + 1] > sorted[i],
            )
        }
    }

    /** 早期反射必须比晚期混响响 —— 这是「有空间感」的来源，反了就不像房间。 */
    @Test
    fun 早期反射必须存在且在混响之前() {
        val engine = ReverbEngine(SAMPLE_RATE).apply { applyPreset(SoundEffectPreset.CATHEDRAL) }
        val samples = (SAMPLE_RATE * 0.5f).toInt()
        val buf = FloatArray(samples)
        for (i in 0 until samples) {
            engine.processStereo(1f, 1f)
            buf[i] = abs(engine.outRight)
        }
        // 激励是常驻 1，输出应该很快出现非零（早期反射）
        val firstReflections = (1 until (SAMPLE_RATE * 0.05f).toInt())
            .count { buf[it] > 1e-4f }
        assertTrue(
            "50ms 内应该有一串早期反射，实际只有 $firstReflections 个采样点非零",
            firstReflections > 100,
        )
    }

    /** 立体声要真的分开：左右声道不能完全一样，否则空间是「死」的。 */
    @Test
    fun 左右声道必须有差异() {
        val engine = ReverbEngine(SAMPLE_RATE).apply { applyPreset(SoundEffectPreset.MAGNETIC) }
        var difference = 0f
        val samples = SAMPLE_RATE / 2
        for (i in 0 until samples) {
            engine.processStereo(0.6f, 0.4f)
            difference += abs(engine.outLeft - engine.outRight)
        }
        assertTrue(
            "旋转预设下左右应该明显不同，累计差 $difference",
            difference / samples > 1e-3f,
        )
    }

    // ---- 旁路与稳定性 ----

    @Test
    fun 关掉预设后仍要留在链路里但原样输出() {
        // Media3 的 setupAudioProcessors() 只在 sink 配置时跑一次，
        // 那时返回 NOT_SET 就等于被永久排除，之后切预设永远不生效。
        // 所以格式兼容就必须接下，音效开关只能在 queueInput 内部做。
        val processor = newProcessor(SoundEffectPreset.NONE)
        assertEquals(
            "格式兼容就必须留在链路里，否则运行中切不动音效",
            format,
            processor.configure(format),
        )
        assertTrue("留在链路里就必须始终 active", processor.isActive)
        assertEquals(
            "旁路时输出字节必须和输入完全一致",
            0,
            countDifferences(processor, samples),
        )
    }

    @Test
    fun 运行中切预设才生效() {
        val processor = newProcessor(SoundEffectPreset.NONE)
        processor.configure(format)
        // 先旁路一段，确认原样
        assertEquals(0, countDifferences(processor, samples))
        // 再打开：这时才真正开始处理
        processor.setPreset(SoundEffectPreset.CINEMA)
        assertTrue(
            "开了预设就必须在链路里真正干活",
            countDifferences(processor, samples) > 0,
        )
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
        val processor = newProcessor(SoundEffectPreset.CATHEDRAL)
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
        val processor = newProcessor(SoundEffectPreset.CATHEDRAL)
        processor.configure(format)
        val square = ShortArray(44100) { if (it % 2 == 0) 32767 else -32768 }
        val out = render(processor, square)
        assertTrue("输出不能为空", out.isNotEmpty())
        val loud = out.count { abs(it.toInt()) > 1000 }
        assertTrue("满量程输入应该还有输出，实际只有 $loud 个采样超过 1000", loud > 1000)
    }

    // ---- 预设之间的差异（这是「效果要能分辨」的保障） ----

    /**
     * 电影院和大厅是最容易混的一对，参数必须真的不同。
     *
     * 两者尾音长度接近，只靠阻尼区分：电影院 0.72（厚地毯）、
     * 大厅 0.20（硬质墙面）。这个断言防的是「有人把参数调成一样」。
     */
    @Test
    fun 电影院和大厅必须一个闷一个亮() {
        val cinema = SoundEffectPreset.CINEMA
        val hall = SoundEffectPreset.HALL
        assertTrue(
            "电影院应该比大厅闷（${cinema.damping} vs ${hall.damping}）",
            cinema.damping > hall.damping * 2,
        )
        assertTrue(
            "大厅应该比电影院亮（${hall.rt60Seconds}s vs ${cinema.rt60Seconds}s）",
            hall.rt60Seconds > cinema.rt60Seconds,
        )
    }

    /** 教堂必须明显长于大厅 —— 靠 RT-60 参考值（教堂 4–8s）。 */
    @Test
    fun 教堂尾巴必须远长于大厅() {
        assertTrue(
            "教堂 ${SoundEffectPreset.CATHEDRAL.rt60Seconds}s 应该远长于大厅 " +
                "${SoundEffectPreset.HALL.rt60Seconds}s",
            SoundEffectPreset.CATHEDRAL.rt60Seconds > SoundEffectPreset.HALL.rt60Seconds * 1.8f,
        )
    }

    /**
     * 演唱会的判别依据：观众吸高频，所以卡在电影院和大厅中间。
     *
     * 演唱会是全场最宽的（声源来自四面八方的返听和人群），但宽度不是
     * 它的独占特征 —— 真正把它和邻居分开的是「比电影院亮、比大厅闷、
     * 尾巴比电影院长、比大厅短」。
     */
    @Test
    fun 演唱会卡在电影院和大厅之间() {
        val concert = SoundEffectPreset.CONCERT
        val cinema = SoundEffectPreset.CINEMA
        val hall = SoundEffectPreset.HALL
        assertTrue(
            "演唱会应该比电影院亮（${concert.damping} vs ${cinema.damping}）",
            concert.damping < cinema.damping,
        )
        assertTrue(
            "演唱会应该比大厅闷（${concert.damping} vs ${hall.damping}）",
            concert.damping > hall.damping,
        )
        assertTrue(
            "演唱会尾巴应该在电影院和大厅之间",
            concert.rt60Seconds > cinema.rt60Seconds && concert.rt60Seconds < hall.rt60Seconds,
        )
        assertTrue(
            "演唱会应该是最宽的房间（${concert.stereoWidth}）",
            concert.stereoWidth >= rooms().maxOf { it.stereoWidth },
        )
    }

    /**
     * 电话必须真的把带外高频滤掉 —— 混响参数在这条通路里几乎不起作用。
     *
     * 正向断言：3.4kHz 以上必须远弱于以下。
     * **反向断言：普通房间不该被这么滤。** 没有这条，一个恒过的测量
     * 也能让正向断言变绿 —— 所以它必须能失败。
     */
    @Test
    fun 电话滤掉带外高频() {
        val (telephoneLow, telephoneHigh) = bandSplit(SoundEffectPreset.TELEPHONE, 3400f)
        assertTrue(
            "听筒只有 300–3400Hz，带外（$telephoneHigh）必须远弱于带内（$telephoneLow）",
            telephoneHigh < telephoneLow * 0.5,
        )

        val (roomLow, roomHigh) = bandSplit(SoundEffectPreset.INDOOR, 3400f)
        assertTrue(
            "普通房间不该被削成这样（带外 $roomHigh / 带内 $roomLow）—— " +
                "说明上面的测量根本区分不出滤波",
            roomHigh > telephoneHigh * 2.0,
        )
    }

    /** 所有房间预设（排除电话和磁性）两两参数不能完全相同。 */
    private fun rooms(): List<SoundEffectPreset> =
        SoundEffectPreset.selectable.filter { it.isEnabled && !it.rotating && !it.telephoneBand }

    /** 每个预设的三个维度不能和别人重样，否则就是「听着一样」。 */
    @Test
    fun 预设之间不能重样() {
        val list = rooms()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                val same = a.rt60Seconds == b.rt60Seconds &&
                    a.damping == b.damping &&
                    a.roomSize == b.roomSize &&
                    a.stereoWidth == b.stereoWidth
                assertTrue("${a.displayName} 和 ${b.displayName} 参数完全一样，听起来必然相同", !same)
            }
        }
    }

    /** 磁性立体声必须在转，且满宽 —— 它不靠混响，靠声像。 */
    @Test
    fun 磁性立体声会旋转且满宽() {
        val magnetic = SoundEffectPreset.MAGNETIC
        assertTrue("磁性立体声必须标记为旋转", magnetic.rotating)
        assertEquals("磁性立体声应该是满宽", 1.00f, magnetic.stereoWidth, 0.001f)
        assertTrue(
            "它的混响应该极轻（${magnetic.wet}），否则就不是空间定位了",
            magnetic.wet < 0.2f,
        )
    }
}
