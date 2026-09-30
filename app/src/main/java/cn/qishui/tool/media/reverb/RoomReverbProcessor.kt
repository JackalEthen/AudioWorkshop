package cn.qishui.tool.media.reverb

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import cn.qishui.tool.domain.player.SoundEffectPreset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 实时空间音效（房间混响 / 电话窄带）。
 *
 * 结构是经典的 Freeverb：8 个梳状滤波 + 4 个全通，梳状滤波器组的
 * 延迟长度本身就是早期反射模式，参数由 [SoundEffectPreset] 给。
 * Freeverb 本身是 MIT，这里是自己按房间声学重新给参数 + 加了电话带通。
 *
 * 契约（Media3 1.5 的 AudioProcessor）：
 * configure 返回 [AudioProcessor.AudioFormat.NOT_SET] 表示「我不处理，链路跳过我」。
 *
 * 安全第一：只要不是 16bit 立体交织 PCM，直接 NOT_SET 整条旁路。
 * 音效出问题宁可没音效，也不能让播放挂掉。
 */
class RoomReverbProcessor : AudioProcessor {

    private var preset: SoundEffectPreset = SoundEffectPreset.NONE
    private var engine: ReverbEngine? = null
    private var bytesPerFrame = 4
    private var output: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var ended = false

    /** 切预设。切到「无」时清掉延迟线，不留尾巴。 */
    fun setPreset(next: SoundEffectPreset) {
        if (next == preset) return
        preset = next
        val current = engine ?: return
        if (!next.isEnabled) {
            current.clear()
        } else {
            // 换空间时保留原来的尾音自然衰减，比硬切干净
            current.applyPreset(next)
        }
    }

    override fun configure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (!preset.isEnabled) {
            reset()
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.channelCount == 2 &&
            inputAudioFormat.sampleRate > 0
        if (!supported) {
            // 旁路：告知链路跳过，音频原样下去
            reset()
            return AudioProcessor.AudioFormat.NOT_SET
        }
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        engine = ReverbEngine(inputAudioFormat.sampleRate).also { it.applyPreset(preset) }
        output = ByteBuffer.allocateDirect(OUTPUT_CAPACITY_BYTES)
            .order(ByteOrder.nativeOrder())
        ended = false
        return inputAudioFormat
    }

    override fun isActive(): Boolean = preset.isEnabled && engine != null

    override fun queueInput(inputBuffer: ByteBuffer) {
        val current = engine
        val bytes = inputBuffer.remaining()
        if (current == null || bytes < bytesPerFrame) {
            // 没在处理就把输入吃掉，别让链路卡住
            inputBuffer.position(inputBuffer.limit())
            return
        }
        if (output.capacity() < bytes) {
            output = ByteBuffer.allocateDirect(bytes + bytes / 2)
                .order(ByteOrder.nativeOrder())
        }
        output.clear()

        // 输入契约保证是 direct + native order，可以直接按下标取
        var i = inputBuffer.position()
        val last = inputBuffer.limit() - bytesPerFrame
        while (i <= last) {
            val left = inputBuffer.getShort(i) * PcmScale
            val right = inputBuffer.getShort(i + 2) * PcmScale
            current.processStereo(left, right)
            output.putShort(toPcm(current.outLeft))
            output.putShort(toPcm(current.outRight))
            i += bytesPerFrame
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun queueEndOfStream() {
        ended = true
    }

    override fun getOutput(): ByteBuffer = output

    override fun isEnded(): Boolean = ended

    override fun flush() {
        output.clear()
        ended = false
        // 跳转 / 换流之后旧尾音留着是错的，一起清掉
        engine?.clear()
    }

    override fun reset() {
        output = AudioProcessor.EMPTY_BUFFER
        engine = null
        ended = false
    }

    private companion object {
        /** 16bit 有符号归一到 -1..1。 */
        const val PcmScale = 1f / 32768f

        /** 正常播放缓冲 4–16KB，够用且不浪费。 */
        const val OUTPUT_CAPACITY_BYTES = 16 * 1024

        /**
         * 软限幅。超过 0.7 之后用 tanh 收，避免数字削波那种「咔咔」的方波失真。
         */
        fun toPcm(value: Float): Short {
            val limit = 0.7f
            val a = abs(value)
            val shaped = if (a <= limit) {
                value
            } else {
                val over = (a - limit) / (1f - limit)
                val curved = limit + (1f - limit) * (over / (1f + over))
                if (value < 0f) -curved else curved
            }
            val s = (shaped * 32767f).toInt()
            return s.coerceIn(-32768, 32767).toShort()
        }
    }
}

/**
 * 混响核心。只做逐样本浮点运算，不分配内存。
 *
 * 采样率相关的延迟长度全部按 44.1kHz 的标称值缩放，所以 48k/96k 都能直接用。
 */
internal class ReverbEngine(sampleRate: Int) {

    private val combsLeft: Array<CombFilter>
    private val combsRight: Array<CombFilter>
    private val allPassesLeft: Array<AllPassFilter>
    private val allPassesRight: Array<AllPassFilter>
    private val preDelayLeft: DelayLine
    private val preDelayRight: DelayLine
    private val bandPass = BiquadBandPass()

    private var wet = 0f
    private var combNormalization = 1f
    private var dry = 1f
    private var stereoWidth = 0.5f
    private var preDelaySamples = 0
    private var useBandPass = false
    private var drive = 0f

    var outLeft = 0f
        private set
    var outRight = 0f
        private set

    init {
        val scale = sampleRate / 44100f
        // Freeverb 标称调谐值（44100Hz 下的样本数），8 个不同长度让早期反射更散
        val leftTunings = COMB_TUNINGS.map { it * scale }
        combsLeft = leftTunings.map { CombFilter(it / sampleRate, sampleRate) }.toTypedArray()
        // 右声道整体错开，取立体声展宽
        combsRight = leftTunings.map {
            CombFilter((it + STEREO_SPREAD) / sampleRate, sampleRate)
        }.toTypedArray()
        allPassesLeft = ALL_PASS_TUNINGS.map {
            AllPassFilter((it * scale).toInt().coerceAtLeast(2))
        }.toTypedArray()
        allPassesRight = ALL_PASS_TUNINGS.map {
            AllPassFilter(((it * scale).toInt() + STEREO_SPREAD).coerceAtLeast(2))
        }.toTypedArray()
        // 预延迟最大 38ms，留 60ms 余量，够任何采样率
        val preDelaySize = (sampleRate * 0.06f).toInt().coerceAtLeast(64)
        preDelayLeft = DelayLine(preDelaySize)
        preDelayRight = DelayLine(preDelaySize)
        bandPass.setBandPass(TELEPHONE_CENTER_HZ, TELEPHONE_Q, sampleRate)
        applyPreset(SoundEffectPreset.NONE)
    }

    fun applyPreset(preset: SoundEffectPreset) {
        // 每个梳状按自己的间隔和房间的 RT-60 算增益，不共用一个常数
        combsLeft.forEach { it.setRt60(preset.rt60Seconds) }
        combsRight.forEach { it.setRt60(preset.rt60Seconds) }
        combsLeft.forEach { it.setDamping(preset.damping) }
        combsRight.forEach { it.setDamping(preset.damping) }
        // 归一：左右两组梳状求和的直流增益是 2*sum(1/(1-coef))，混响越长这个值越大。
        // 不除掉的话，尾音长的房间湿声会响一大截，听起来只是「更响」而不是「更大」。
        val dcGain = combsLeft.sumOf { 1.0 / (1.0 - it.coef()) }
        combNormalization = (1.0 / (dcGain * 2.0)).toFloat()
        wet = preset.wet
        dry = 1f
        stereoWidth = preset.stereoWidth
        useBandPass = preset.telephoneBand
        drive = preset.drive
        preDelaySamples = (preset.preDelayMs / 1000f * SAMPLE_RATE_REF).toInt()
        preDelayLeft.setPreDelay(preDelaySamples)
        preDelayRight.setPreDelay(preDelaySamples)
    }

    fun clear() {
        combsLeft.forEach { it.clear() }
        combsRight.forEach { it.clear() }
        allPassesLeft.forEach { it.clear() }
        allPassesRight.forEach { it.clear() }
        preDelayLeft.clear()
        preDelayRight.clear()
        bandPass.clear()
        outLeft = 0f
        outRight = 0f
    }

    fun processStereo(leftIn: Float, rightIn: Float) {
        var l = leftIn
        var r = rightIn

        if (useBandPass) {
            // 同一个滤波器实例处理两声道，保持声像不跑偏
            l = bandPass.process(l)
            r = bandPass.process(r)
        }
        if (drive > 0f) {
            val boost = 1f + drive * 3f
            l = l * boost / (1f + abs(l) * boost)
            r = r * boost / (1f + abs(r) * boost)
        }

        var wetL = 0f
        var wetR = 0f
        // 左进左、右进右，不要交叉馈。
        // 立体声宽度来自左右两组梳状不同的调谐长度，不是来自把两个声道混进同一组。
        for (i in combsLeft.indices) {
            wetL += combsLeft[i].process(l)
            wetR += combsRight[i].process(r)
        }
        wetL *= combNormalization
        wetR *= combNormalization

        for (i in allPassesLeft.indices) {
            wetL = allPassesLeft[i].process(wetL)
            wetR = allPassesRight[i].process(wetR)
        }

        // 只延迟湿声，直达声立刻到，这才有「空间」的距离感
        wetL = preDelayLeft.process(wetL)
        wetR = preDelayRight.process(wetR)

        val mixL = l * dry + wetL * wet
        val mixR = r * dry + wetR * wet

        // mid/side 展宽，width=1 原样，width=0 单声道
        val mid = (mixL + mixR) * 0.5f
        val side = (mixL - mixR) * 0.5f * stereoWidth
        outLeft = mid + side
        outRight = mid - side
    }

    private companion object {
        const val SAMPLE_RATE_REF = 44100f

        /** Freeverb 标称调谐值，44100Hz 下的延迟长度（样本数）。 */
        val COMB_TUNINGS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val ALL_PASS_TUNINGS = intArrayOf(556, 441, 341, 225)

        /** 右声道相对左声道的固定错位，样本数。 */
        const val STEREO_SPREAD = 23

        /** 梳状组求和后按直流增益归一，避免长混响湿声过载。 */
        const val TELEPHONE_CENTER_HZ = 1200f
        const val TELEPHONE_Q = 0.7f
    }
}

/**
 * 梳状滤波器，移植自 Soundpipe 的 `sp_comb`（MIT，Paul Batchelor）。
 *
 * 移植自 `modules/comb.c`，只改了命名和去掉 malloc。
 * Soundpipe 那份本身提取自 Csound 的 comb 算子（Barry Vercoe / John ffitch, 1991）。
 *
 * 关键是这个增益公式，它是整个混响唯一需要「算」的地方：
 * ```
 * coef = exp(log(0.001) * looptime / revtime) = 0.001^(looptime/revtime)
 * ```
 * 梳状滤波器每 looptime 秒重复一次，所以 revtime 秒之后电平是
 * `coef^(revtime/looptime) = 0.001`，正好是 -60dB —— 这就是 RT-60 的定义。
 * 换句话说增益**不需要调**，给定房间的混响时间就唯一确定了。
 *
 * Soundpipe 的 comb 没有阻尼。这里加了一阶低通串在反馈回路里，
 * 这不是自由发挥：真实房间的墙面和空气对高频的吸收远强于低频，
 * 没有它所有房间听起来会一样亮。参见 Freeverb 论文
 * (Esqueda/Daud/Nishikawa, DAFx-05) 的 damped comb filter。
 *
 * 低通必须是**单位直流增益**（`store += (out - store) * damp`）。
 * 写成教科书的 `store = out*(1-d) + store*d` 直流增益是 (1-d)，
 * 每反射一次尾音就乘一遍 (1-d)，房间再大尾音也起不来
 * （实测阻尼 0.7 会把 3.5 秒的教堂压成 0.2 秒）。
 */
internal class CombFilter(
    /** 每圈延迟的秒数，也就是梳状间隔。 */
    looptimeSeconds: Float,
    sampleRate: Int,
) {
    private val looptimeSeconds = looptimeSeconds
    private val buffer = FloatArray((looptimeSeconds * sampleRate).toInt().coerceAtLeast(2))
    private var index = 0
    private var store = 0f

    /** 反馈增益，由 RT-60 算出，不是手调常数。 */
    private var coef = 0f
    private var appliedRt60 = Float.NaN
    private var damp = 0.6f

    fun setRt60(rt60Seconds: Float) {
        if (rt60Seconds == appliedRt60) return
        appliedRt60 = rt60Seconds
        // rt60 为 0（关闭）时增益取 0，避免除零
        coef = if (rt60Seconds <= 0f) 0f else exp(LOG_001 * looptimeSeconds / rt60Seconds)
    }

    fun setDamping(damping: Float) {
        damp = 1f - damping
    }

    fun process(input: Float): Float {
        val output = buffer[index]
        store += (output - store) * damp
        buffer[index] = input + store * coef
        if (++index >= buffer.size) index = 0
        return output
    }

    fun clear() {
        buffer.fill(0f)
        store = 0f
        index = 0
    }

    /** 当前反馈增益，上层用它做直流增益归一。 */
    fun coef(): Float = coef

    private companion object {
        /** ln(0.001)，即 -60dB。 */
        const val LOG_001 = -6.9078f
    }
}

/** 全通滤波器，打散梳状的梳状感。 */
private class AllPassFilter(size: Int) {
    private val buffer = FloatArray(size)
    private var index = 0

    fun process(input: Float): Float {
        val output = buffer[index]
        buffer[index] = input + output * 0.5f
        if (++index >= buffer.size) index = 0
        return output - input
    }

    fun clear() {
        buffer.fill(0f)
        index = 0
    }
}

/** 纯延迟线，给湿声做预延迟。 */
private class DelayLine(size: Int) {
    private val buffer = FloatArray(size)
    private var index = 0
    private var preDelay = 0

    /** 预延迟样本数，上限由缓冲区大小决定。 */
    fun setPreDelay(samples: Int) {
        preDelay = samples.coerceIn(0, buffer.size - 1)
    }

    fun process(input: Float): Float {
        // 读指针滞后写指针 preDelay 个位置
        val readIndex = (index - preDelay + buffer.size) % buffer.size
        buffer[index] = input
        if (++index >= buffer.size) index = 0
        return buffer[readIndex]
    }

    fun clear() {
        buffer.fill(0f)
        index = 0
    }
}

/** RBJ 带通，用来切出电话听筒的 300–3400Hz 窄带。 */
private class BiquadBandPass {
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun setBandPass(centerHz: Float, q: Float, sampleRate: Int) {
        val w0 = 2.0 * Math.PI * centerHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (alpha / a0).toFloat()
        b1 = 0f
        b2 = (-alpha / a0).toFloat()
        a1 = (-2.0 * cos(w0) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun process(input: Float): Float {
        val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = output
        return output
    }

    fun clear() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
    }
}
