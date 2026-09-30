package cn.qishui.tool.media.effect

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 分段处理的一段：时间范围 + 这一段自己的参数值。
 * 「分段变调」里 value 是半音，「分段修改音量」里是 dB。
 */
data class SegmentRange(
    val startMs: Long,
    val endMs: Long,
    val value: Float,
)

/** 效果参数描述，UI 照着它自动生成滑杆，不用给每个功能单独写界面。 */data class EffectParam(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val step: Float = 0.01f,
    val unit: String = "",
    /**
     * 离散选项。非空时值就是选项下标，UI 渲染成分段选择器而不是滑杆 ——
     * 「左/右/全声道」这种三选一用滑杆没法用。
     */
    val choices: List<String> = emptyList(),
    /** choices 下方的一行说明，来自参考示例的文案。 */
    val caption: String = "",
    /**
     * 这一项渲染成「左标题 + 右当前值 + 整行可点」的下拉行，而不是分段选择器。
     * 「模式」这种两三个选项、但每项是整套参数的说法，铺开成一排很占地方。
     */
    val asRow: Boolean = false,
)

/**
 * 全部音效运算。约定：能原地改就原地改，返回 null 表示没变；
 * 需要新缓冲的返回新对象。
 */
object PcmEffects {

    fun reverse(buffer: PcmBuffer): PcmBuffer {
        val channels = buffer.channels
        val samples = buffer.samples
        var left = 0
        var right = buffer.frames - 1
        while (left < right) {
            for (channel in 0 until channels) {
                val a = left * channels + channel
                val b = right * channels + channel
                val tmp = samples[a]
                samples[a] = samples[b]
                samples[b] = tmp
            }
            left++
            right--
        }
        return buffer
    }

    fun invertPhase(buffer: PcmBuffer, channel: Int = 0): PcmBuffer {
        // channel: 0=全声道 1=左声道 2=右声道；单声道文件没有左右之分，按全声道处理
        val perFrame = buffer.channels
        val invertAll = channel == 0 || perFrame < 2
        for (frame in 0 until buffer.frames) {
            for (lane in 0 until perFrame) {
                if (!invertAll && lane != channel - 1) continue
                val index = frame * perFrame + lane
                buffer.samples[index] = (-buffer.samples[index].toInt())
                    .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
        }
        return buffer
    }

    /** 声道分离： Karaoke 去掉中置，或只保留中置。 */
    fun stereoSplit(buffer: PcmBuffer, keep: Int): PcmBuffer {
        if (buffer.channels != 2) return buffer
        val samples = buffer.samples
        for (frame in 0 until buffer.frames) {
            val base = frame * 2
            val left = samples[base].toInt()
            val right = samples[base + 1].toInt()
            val mid = (left + right) / 2
            val side = (left - right) / 2
            val outLeft: Int
            val outRight: Int
            when (keep) {
                KEEP_SIDE -> {
                    outLeft = side
                    outRight = -side
                }

                KEEP_MID -> {
                    outLeft = mid
                    outRight = mid
                }

                else -> {
                    outLeft = left
                    outRight = right
                }
            }
            samples[base] = outLeft.coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            samples[base + 1] = outRight.coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
        }
        return buffer
    }

    /** 立体声环绕：M/S 矩阵拉宽声场，width 越大越宽。 */
    fun stereoWiden(buffer: PcmBuffer, width: Float): PcmBuffer {
        if (buffer.channels != 2) return buffer
        val factor = width.coerceIn(0f, 1f) * MAX_WIDTH
        val samples = buffer.samples
        for (frame in 0 until buffer.frames) {
            val base = frame * 2
            val mid = (samples[base].toInt() + samples[base + 1].toInt()) / 2f
            val side = (samples[base].toInt() - samples[base + 1].toInt()) / 2f * factor
            samples[base] = (mid + side).toInt().coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            samples[base + 1] = (mid - side).toInt().coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
        }
        return buffer
    }

    /** 立体声合成：把左右按比例混回中间，pan 决定偏向。 */
    /**
     * 立体声合成：第一首进左声道，第二首进右声道，左右耳听不同音乐。
     * 长度不一致按较长的一条补静音；单声道源原样放进对应那一侧。
     */
    fun stereoCompose(left: PcmBuffer, right: PcmBuffer): PcmBuffer {
        val rate = if (left.frames > 0) left.sampleRateHz else right.sampleRateHz
        val frames = maxOf(left.frames, right.frames)
        val out = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            out[frame * 2] = left.sampleAt(frame, 0)
            out[frame * 2 + 1] = right.sampleAt(frame, 1)
        }
        return PcmBuffer(rate, 2, out)
    }

    /** 取第 frame 帧的第 channel 声道；单声道时忽略 channel，越界返回静音。 */
    private fun PcmBuffer.sampleAt(frame: Int, channel: Int): Short {
        val sourceChannel = if (channels >= 2) channel else 0
        val index = frame * channels + sourceChannel
        return if (frame < frames && index < samples.size) samples[index] else 0
    }

    fun stereoMix(buffer: PcmBuffer, pan: Float): PcmBuffer {
        if (buffer.channels != 2) return buffer
        val shift = (pan.coerceIn(-1f, 1f) * MAX_PAN)
        val samples = buffer.samples
        for (frame in 0 until buffer.frames) {
            val base = frame * 2
            val left = samples[base].toFloat()
            val right = samples[base + 1].toFloat()
            samples[base] = (left * (0.5f + shift)).toInt()
                .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            samples[base + 1] = (right * (0.5f - shift)).toInt()
                .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
        }
        return buffer
    }

    /** 回声：延迟 + 反馈，原声和回声按 mix 混合。 */
    /** 回声。kind: 0=山谷回响（多抽头递减） 1=两倍叠加（单抽头）。 */
    fun echo(buffer: PcmBuffer, delayMs: Float, feedback: Float, mix: Float, kind: Int = 1): PcmBuffer {
        val rate = buffer.sampleRateHz
        val delayFrames = ((delayMs.coerceIn(20f, 2000f) / 1000f) * rate).toInt().coerceAtLeast(1)
        val fb = feedback.coerceIn(0f, 0.95f)
        val wet = mix.coerceIn(0f, 1f)
        val channels = buffer.channels
        val samples = buffer.samples

        if (kind == 0) {
            // 山谷回响：两路不同延时的抽头叠加，听起来像远处山谷的一次回声
            val near = delayFrames
            val far = (delayFrames * 2).toInt().coerceAtLeast(near + 1)
            val nearLine = FloatArray(near * channels)
            val farLine = FloatArray(far * channels)
            var nearIndex = 0
            var farIndex = 0
            for (frame in 0 until buffer.frames) {
                for (channel in 0 until channels) {
                    val dry = samples[frame * channels + channel].toFloat()
                    val nearDelayed = nearLine[nearIndex + channel]
                    val farDelayed = farLine[farIndex + channel]
                    val value = dry * (1f - wet) + (nearDelayed * 0.65f + farDelayed * 0.35f) * wet
                    samples[frame * channels + channel] =
                        value.toInt().coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
                    // 干声要同时灌进两条延迟线，漏掉的话 wet 拉大时回声会整个消失
                    val injected = value * fb + dry * (1f - fb) * 0.35f
                    nearLine[nearIndex + channel] = injected
                    farLine[farIndex + channel] = injected
                }
                nearIndex = (nearIndex + channels) % nearLine.size
                farIndex = (farIndex + channels) % farLine.size
            }
            return buffer
        }

        val line = FloatArray(delayFrames * channels)
        var index = 0
        for (frame in 0 until buffer.frames) {
            for (channel in 0 until channels) {
                val slot = index + channel
                val delayed = line[slot]
                val dry = samples[frame * channels + channel]
                val value = dry * (1f - wet) + delayed * wet
                samples[frame * channels + channel] =
                    value.toInt().coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
                line[slot] = value * fb + dry * (1f - fb) * 0.35f
            }
            index = (index + channels) % line.size
        }
        return buffer
    }

    /** 合唱：几路不同延时的干声叠加制造厚度。 */
    /**
     * 左右声道各拆成一个单声道文件。
     * 单声道源拆出来两边一样，按参考示例的提示这就是预期行为。
     */
    fun splitChannels(buffer: PcmBuffer): List<PcmBuffer> {
        if (buffer.channels < 2) {
            val mono = PcmBuffer(buffer.sampleRateHz, 1, buffer.samples.copyOf())
            return listOf(mono, mono)
        }
        val frames = buffer.frames
        val left = ShortArray(frames)
        val right = ShortArray(frames)
        for (frame in 0 until frames) {
            left[frame] = buffer.samples[frame * 2]
            right[frame] = buffer.samples[frame * 2 + 1]
        }
        return listOf(
            PcmBuffer(buffer.sampleRateHz, 1, left),
            PcmBuffer(buffer.sampleRateHz, 1, right),
        )
    }

    /**
     * 立体声环绕：把立体声像绕听者持续旋转。
     *
     * halfCircleSec 是转半圈的时间（整圈 = 2 倍），degrees 是最大旋转角度。
     * 单声道没有左右之分，原样返回。
     */
    fun stereoOrbit(buffer: PcmBuffer, halfCircleSec: Float, degrees: Float): PcmBuffer {
        val channels = buffer.channels
        if (channels < 2) return buffer
        val periodSec = (halfCircleSec.coerceIn(0.2f, 120f) * 2f).toDouble()
        val maxAngle = (degrees.coerceIn(0f, 90f) * Math.PI / 180.0).toFloat()
        if (maxAngle <= 0.0001f) return buffer
        val rate = buffer.sampleRateHz.toDouble()
        val samples = buffer.samples
        for (frame in 0 until buffer.frames) {
            val phase = (frame / rate / periodSec * 2.0 * Math.PI).toFloat()
            val angle = kotlin.math.sin(phase) * maxAngle
            val cosA = kotlin.math.cos(angle)
            val sinA = kotlin.math.sin(angle)
            val base = frame * channels
            val left = samples[base].toFloat()
            val right = samples[base + 1].toFloat()
            samples[base] = (left * cosA + right * sinA)
                .coerceIn(PcmBuffer.SHORT_MIN.toFloat(), PcmBuffer.SHORT_MAX.toFloat()).toInt().toShort()
            samples[base + 1] = (right * cosA - left * sinA)
                .coerceIn(PcmBuffer.SHORT_MIN.toFloat(), PcmBuffer.SHORT_MAX.toFloat()).toInt().toShort()
        }
        return buffer
    }

    /** 合唱：几路不同延时的干声叠加制造厚度。kind: 0=两人 1=三人 2=多人。 */
    fun choir(buffer: PcmBuffer, spreadMs: Float, mix: Float, kind: Int = 1): PcmBuffer {
        val rate = buffer.sampleRateHz
        val channels = buffer.channels
        val wet = mix.coerceIn(0f, 1f)
        val base = ((spreadMs.coerceIn(5f, 80f) / 1000f) * rate).toInt().coerceAtLeast(1)
        val voiceCount = when (kind) {
            0 -> 2
            2 -> 6
            else -> 3
        }
        // 声部越多间隔越密，最后一路落在 2 倍延迟上
        val voices = (0 until voiceCount).map { index ->
            (base * (1f + 0.5f * index)).toInt().coerceAtLeast(1)
        }
        // 靠前的声部更响，人数变多时整体才不会越叠越小
        val gains = FloatArray(voiceCount) { index ->
            1f - (0.45f * index / (voiceCount - 1).coerceAtLeast(1))
        }
        val samples = buffer.samples
        val out = ShortArray(samples.size)
        for (frame in 0 until buffer.frames) {
            for (channel in 0 until channels) {
                val dry = samples[frame * channels + channel].toFloat()
                var sum = 0f
                voices.forEachIndexed { index, delay ->
                    val from = frame - delay
                    if (from >= 0) sum += samples[from * channels + channel].toFloat() * gains[index]
                }
                val value = dry * (1f - wet) + (sum / voiceCount) * wet
                out[frame * channels + channel] =
                    value.toInt().coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
        }
        return PcmBuffer(buffer.sampleRateHz, buffer.channels, out)
    }

    /**
     * 混响：4 组梳状滤波 + 2 组全通，Schroeder 结构。
     * decay 控制尾巴长度，predelay 是进入混响前的延迟，damping 压反馈路径上的高频。
     */
    fun reverb(
        buffer: PcmBuffer,
        roomSize: Float,
        mix: Float,
        decay: Float = 0.6f,
        predelayMs: Float = 0f,
        damping: Float = 0.5f,
    ): PcmBuffer {
        val size = roomSize.coerceIn(0f, 1f)
        val wet = mix.coerceIn(0f, 1f)
        val feedback = 0.7f + decay.coerceIn(0f, 1f) * 0.27f
        val damp = damping.coerceIn(0f, 1f) * 0.9f
        val rate = buffer.sampleRateHz
        val channels = buffer.channels
        val combDelays = intArrayOf(
            (COMB_BASE * (1f + size)).toInt().coerceAtLeast(1),
            (COMB_BASE * 1.37f * (1f + size)).toInt().coerceAtLeast(1),
            (COMB_BASE * 1.73f * (1f + size)).toInt().coerceAtLeast(1),
            (COMB_BASE * 2.11f * (1f + size)).toInt().coerceAtLeast(1),
        )
        val combs = Array(combDelays.size * channels) { FloatArray(combDelays[it / channels]) }
        val dampState = FloatArray(combDelays.size * channels)
        val allpassA = FloatArray((rate * 0.005f).toInt().coerceAtLeast(1) * channels)
        val allpassB = FloatArray((rate * 0.0017f).toInt().coerceAtLeast(1) * channels)
        val preFrames = ((predelayMs.coerceIn(0f, 200f) / 1000f) * rate).toInt()
        val preLine = FloatArray((preFrames * channels).coerceAtLeast(channels))
        val out = ShortArray(buffer.samples.size)
        val samples = buffer.samples
        val combIndex = IntArray(channels)
        var preIndex = 0
        for (frame in 0 until buffer.frames) {
            for (channel in 0 until channels) {
                val dry = samples[frame * channels + channel].toFloat()
                val input: Float
                if (preFrames <= 0) {
                    input = dry
                } else {
                    val slot = (preIndex + channel) % preLine.size
                    input = preLine[slot]
                    preLine[slot] = dry
                }
                var acc = 0f
                for (voice in combDelays.indices) {
                    val line = combs[voice * channels + channel]
                    val slot = combIndex[channel] % line.size
                    val delayed = line[slot]
                    acc += delayed
                    // 高频阻尼：一阶低通只插在反馈路径上，
                    // 湿声用原始输出、反馈用滤波值，尾巴才会一边衰减一边变暗。
                    // 系数必须是 (1-a) 和 a，写成 x + a*y_prev 那是积分器，阻尼拉满直接发散
                    val dampSlot = voice * channels + channel
                    val filtered = delayed * (1f - damp) + dampState[dampSlot] * damp
                    dampState[dampSlot] = filtered
                    // 每条梳状线只反馈自己的输出；错用累加值会让四条线互相耦合，
                    // 听起来是尖锐的谐振而不是平滑的混响
                    line[slot] = input + filtered * feedback
                    combIndex[channel] = (combIndex[channel] + 1) % line.size
                }
                acc /= combDelays.size
                acc = allpass(acc, allpassA, channel, 0.5f + size * 0.3f)
                acc = allpass(acc, allpassB, channel, 0.5f)
                val value = dry * (1f - wet) + acc * wet
                out[frame * channels + channel] =
                    value.toInt().coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
            preIndex = (preIndex + channels) % preLine.size
        }
        return PcmBuffer(buffer.sampleRateHz, buffer.channels, out)
    }

    private fun allpass(input: Float, line: FloatArray, channel: Int, gain: Float): Float {
        if (line.size <= channel) return input
        val slot = channel % line.size
        val buffered = line[slot]
        val output = -input + buffered
        line[slot] = input + buffered * gain
        return output
    }

    /**
     * 三段均衡：低频 / 中频 / 高频各走一个稳定的二阶滤波器再叠加。
     * 不用 shelf 系数，那套公式在低频增益下会退化成近似临界稳定。
     */
    /** 8 段峰值均衡，频段中心按 2 倍频程排布，和参考示例的 0~125 … 8k~16k 对齐。 */
    fun equalizer(buffer: PcmBuffer, gainsDb: FloatArray): PcmBuffer {
        if (gainsDb.isEmpty()) return buffer
        val rate = buffer.sampleRateHz.toFloat()
        val channels = buffer.channels
        val bandCount = minOf(gainsDb.size, EQ_CENTER_HZ.size)
        // 全 0 就不用白跑一遍滤波器
        if (gainsDb.take(bandCount).all { kotlin.math.abs(it) < 0.01f }) return buffer
        val coefficients = Array(bandCount) { band ->
            Biquad.peaking(rate, EQ_CENTER_HZ[band], EQ_Q, gainsDb[band])
        }
        val states = Array(channels * bandCount) { Biquad.State() }
        val samples = buffer.samples
        for (frame in 0 until buffer.frames) {
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                var value = samples[index].toFloat() / SAMPLE_SCALE
                for (band in 0 until bandCount) {
                    value = Biquad.process(value, coefficients[band], states[channel * bandCount + band])
                }
                samples[index] = (value * SAMPLE_SCALE).toInt()
                    .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
        }
        return buffer
    }

    private fun dbToGain(db: Float): Float =
        Math.pow(10.0, (db.coerceIn(-24f, 24f) / 20.0)).toFloat()

    /** 收音机音效：带通 + 削波 + 可选底噪。lowHz/highHz 决定频响收窄程度。 */
    fun radioFx(
        buffer: PcmBuffer,
        amount: Float,
        lowHz: Float = RADIO_LOW_HZ,
        highHz: Float = RADIO_HIGH_HZ,
        noiseFloor: Float = 0f,
    ): PcmBuffer {
        val rate = buffer.sampleRateHz.toFloat()
        val strength = amount.coerceIn(0f, 1f)
        val highPass = Biquad.highPass(rate, lowHz.coerceIn(20f, 2000f), Q_FACTOR)
        val lowPass = Biquad.lowPass(rate, highHz.coerceIn(1000f, 18_000f), Q_FACTOR)
        // 两级各用各的状态，共用会让级联发散
        val highPassStates = Array(buffer.channels) { Biquad.State() }
        val lowPassStates = Array(buffer.channels) { Biquad.State() }
        val samples = buffer.samples
        // 底噪用固定种子，导出每次结果一致，不会每次都变
        var noiseState = 0x5F3A_1193
        for (frame in 0 until buffer.frames) {
            for (channel in 0 until buffer.channels) {
                val index = frame * buffer.channels + channel
                var value = samples[index].toFloat() / SAMPLE_SCALE
                value = Biquad.process(value, highPass, highPassStates[channel])
                value = Biquad.process(value, lowPass, lowPassStates[channel])
                value = value * (1f + strength * 0.6f)
                value = kotlin.math.tanh(value * (1f + strength * 2.5f))
                if (noiseFloor > 0f) {
                    noiseState = noiseState * 1_103_515_245 + 12_345
                    val noise = ((noiseState ushr 9) and 0xFFFF) / 32_768f - 1f
                    value += noise * noiseFloor.coerceIn(0f, 0.2f)
                }
                samples[index] = (value * SAMPLE_SCALE).toInt()
                    .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
        }
        return buffer
    }

    /** 音频修复：削波点插值 + 去直流偏移。 */
    fun repair(buffer: PcmBuffer, strength: Float): PcmBuffer {
        val amount = strength.coerceIn(0f, 1f)
        val channels = buffer.channels
        val samples = buffer.samples
        // 削波点用前后样本线性插值抹平。
        // 必须放在去直流之前：去直流会把顶在满幅的采样压到判定阈值以下，
        // 那样就再也认不出哪些点是削波的了。
        for (frame in 1 until buffer.frames - 1) {
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                val value = samples[index].toInt()
                val clipped = abs(value) >= PcmBuffer.SHORT_MAX - CLIP_MARGIN
                if (!clipped) continue
                val previous = samples[index - channels].toInt()
                val next = samples[index + channels].toInt()
                samples[index] = ((previous + next) / 2)
                    .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
        }
        for (channel in 0 until channels) {
            var mean = 0.0
            var count = 0
            for (frame in 0 until buffer.frames) {
                mean += samples[frame * channels + channel]
                count++
            }
            val offset = if (count == 0) 0 else (mean / count).toInt()
            for (frame in 0 until buffer.frames) {
                val index = frame * channels + channel
                samples[index] = (samples[index] - (offset * amount).toInt())
                    .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
        }
        return buffer
    }

    /** 去除头尾：按阈值找首尾有声位置，低于最小时长就整体保留。 */
    fun trimSilence(buffer: PcmBuffer, thresholdDb: Float, minSilenceMs: Float): PcmBuffer {
        val threshold = Math.pow(10.0, (thresholdDb.coerceIn(-60f, 0f) / 20.0)).toFloat() * Short.MAX_VALUE
        val minSilenceFrames = ((minSilenceMs.coerceIn(0f, 5000f) / 1000f) * buffer.sampleRateHz).toInt()
        val channels = buffer.channels
        var first = -1
        var last = -1
        for (frame in 0 until buffer.frames) {
            if (!frameIsSilent(buffer, frame, channels, threshold)) {
                if (first < 0) first = frame
                last = frame
            }
        }
        if (first < 0 || last <= first) return buffer
        val from = max(0, first - minSilenceFrames)
        val to = minOf(buffer.frames, last + minSilenceFrames + 1)
        if (from == 0 && to == buffer.frames) return buffer
        return buffer.slice(from, to)
    }

    const val JOIN_HARD = 0
    const val JOIN_LINEAR = 1
    const val JOIN_EQUAL_POWER = 2
    const val JOIN_PRESERVE = 3

    /**
     * 混音 = 按顺序首尾拼接，交界处做淡入淡出。normalizeRates = true 时
     * 先把所有轨道重采样到最高采样率，避免不同采样率拼完变速。
     */
    fun concatWithCrossfade(
        buffers: List<PcmBuffer>,
        crossfadeMs: Float,
        normalizeRates: Boolean,
        mode: Int = JOIN_LINEAR,
    ): PcmBuffer {
        require(buffers.isNotEmpty()) { "混音至少需要一首歌" }
        val targetRate = if (normalizeRates) buffers.maxOf { it.sampleRateHz } else buffers.first().sampleRateHz
        val tracks = buffers.map { if (it.sampleRateHz == targetRate) it else it.resampled(targetRate) }
        val requested = ((crossfadeMs.coerceIn(0f, 60_000f) / 1000f) * targetRate).toInt()
        return tracks.reduce { acc, next -> join(acc, next, mode, requested) }
    }

    private fun join(a: PcmBuffer, b: PcmBuffer, mode: Int, requestedOverlap: Int): PcmBuffer {
        val channels = a.channels
        if (mode == JOIN_HARD) return append(a, b)
        if (mode == JOIN_PRESERVE) return append(a, b, silenceFrames = requestedOverlap)
        val overlap = requestedOverlap.coerceAtMost(minOf(a.frames, b.frames))
        if (overlap <= 0) return append(a, b)
        val equalPower = mode == JOIN_EQUAL_POWER
        val outFrames = a.frames + b.frames - overlap
        val out = ShortArray(outFrames * channels)
        // 交叠区落在拼接点上：a 的最后 overlap 帧淡出，b 的头 overlap 帧淡入
        val joinStart = a.frames - overlap
        for (frame in 0 until outFrames) {
            val t = ((frame - joinStart).toFloat() / overlap).coerceIn(0f, 1f)
            val fadeIn = if (equalPower) kotlin.math.sin(t * Math.PI / 2).toFloat() else t
            val fadeOut = if (equalPower) kotlin.math.cos(t * Math.PI / 2).toFloat() else 1f - t
            val fromB = (frame - joinStart) * channels
            for (channel in 0 until channels) {
                val left = if (frame < a.frames) a.samples[frame * channels + channel] else 0
                val index = fromB + channel
                val right = if (index >= 0 && index < b.samples.size) b.samples[index] else 0
                out[frame * channels + channel] = (left * fadeOut + right * fadeIn).toInt()
                    .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
        }
        return PcmBuffer(a.sampleRateHz, channels, out)
    }

    /** 首尾相接。silenceFrames > 0 时在中间插入等长静音，一个原样本都不丢。 */
    private fun append(a: PcmBuffer, b: PcmBuffer, silenceFrames: Int = 0): PcmBuffer {
        val channels = a.channels
        val gap = silenceFrames.coerceAtLeast(0)
        val out = ShortArray((a.frames + gap + b.frames) * channels)
        a.samples.copyInto(out)
        b.samples.copyInto(out, (a.frames + gap) * channels)
        return PcmBuffer(a.sampleRateHz, channels, out)
    }

    /**
     * 手动去除头尾：头部砍掉 headMs、尾部砍掉 tailMs。
     * 砍过头就全静音，不做自动回退，用户给的值就是最终值。
     */
    fun trimEnds(buffer: PcmBuffer, headMs: Float, tailMs: Float): PcmBuffer {
        val headFrames = ((headMs.coerceIn(0f, 600_000f) / 1000f) * buffer.sampleRateHz).toInt()
        val tailFrames = ((tailMs.coerceIn(0f, 600_000f) / 1000f) * buffer.sampleRateHz).toInt()
        if (headFrames == 0 && tailFrames == 0) return buffer
        val from = headFrames.coerceIn(0, buffer.frames)
        val to = (buffer.frames - tailFrames).coerceIn(from, buffer.frames)
        if (from == 0 && to == buffer.frames) return buffer
        return buffer.slice(from, to)
    }

    private fun frameIsSilent(buffer: PcmBuffer, frame: Int, channels: Int, threshold: Float): Boolean {
        val base = frame * channels
        for (channel in 0 until channels) {
            if (abs(buffer.samples[base + channel].toInt()) > threshold) return false
        }
        return true
    }

    /**
     * 通用降噪：把 [lowHz] 以下和 [highHz] 以上滤掉，只保留中间那段。
     *
     * Q 固定 Butterworth：调 Q 会让拐点起振铃、输出削波，力度一大反而更糟。
     * amount 走干湿混合 —— 稳定档保留一部分原声，正常档整段替换。
     */
    fun bandLimit(buffer: PcmBuffer, lowHz: Float, highHz: Float, amount: Float): PcmBuffer {
        val rate = buffer.sampleRateHz.toFloat()
        val nyquist = rate / 2f
        val low = lowHz.coerceIn(20f, nyquist - 1_000f)
        val high = highHz.coerceIn(low + 500f, nyquist)
        val mix = 0.4f + 0.6f * amount.coerceIn(0f, 1f)
        val highPass = Biquad.highPass(rate, low, Q_FACTOR)
        val lowPass = Biquad.lowPass(rate, high, Q_FACTOR)
        // 两级必须各有各的状态：共用一个 State 时低通会把 y1/y2 覆盖掉，
        // 高通下一轮再拿自己的 a1/a2 去减那个值，等效分母变了，极点会跑出单位圆直接发散
        val highPassStates = Array(buffer.channels) { Biquad.State() }
        val lowPassStates = Array(buffer.channels) { Biquad.State() }
        val samples = buffer.samples
        for (frame in 0 until buffer.frames) {
            for (channel in 0 until buffer.channels) {
                val index = frame * buffer.channels + channel
                val dry = samples[index].toFloat() / SAMPLE_SCALE
                var value = Biquad.process(dry, highPass, highPassStates[channel])
                value = Biquad.process(value, lowPass, lowPassStates[channel])
                value = dry * (1f - mix) + value * mix
                samples[index] = (value * SAMPLE_SCALE).toInt()
                    .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
        }
        return buffer
    }

    /**
     * RNNoise 降噪。算法来自 Xiph 官方 RNNoise（BSD-3），本地静态编译，
     * 固定 48kHz 单声道，这里负责重采样和多声道复制。
     */
    fun denoise(buffer: PcmBuffer, bridge: RnnoiseBridge, strength: Float): PcmBuffer {
        if (!bridge.isReady) throw IllegalStateException("降噪模型未就绪")
        val mix = strength.coerceIn(0f, 1f)
        val mono = buffer.toMono().resampled(RnnoiseBridge.SAMPLE_RATE)
        val frames = mono.frames
        val floats = FloatArray(frames)
        for (index in 0 until frames) {
            floats[index] = mono.samples[index] / SAMPLE_SCALE
        }
        bridge.process(floats)
        // 干湿混合后再转回原采样率与声道数
        val processed = PcmBuffer(
            sampleRateHz = RnnoiseBridge.SAMPLE_RATE,
            channels = 1,
            samples = ShortArray(frames) { index ->
                val original = mono.samples[index] / SAMPLE_SCALE
                val wet = (floats[index] * mix + original * (1f - mix)) * SAMPLE_SCALE
                wet.toInt().coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            },
        )
        val restored = if (buffer.sampleRateHz == processed.sampleRateHz) {
            processed
        } else {
            processed.resampled(buffer.sampleRateHz)
        }
        return if (buffer.channels == 1) {
            restored
        } else {
            PcmBuffer(buffer.sampleRateHz, buffer.channels, spreadToChannels(restored.samples, buffer.channels))
        }
    }

    private fun spreadToChannels(mono: ShortArray, channels: Int): ShortArray {
        val out = ShortArray(mono.size * channels)
        for (index in mono.indices) {
            for (channel in 0 until channels) out[index * channels + channel] = mono[index]
        }
        return out
    }

    /**
     * 音调百分比换成半音：100% 是原调，+100% 高一个八度。
     * 单独提出来是因为它是有实际数学的那一步，值得单独测。
     */
    fun pitchPercentToSemitones(percent: Float): Float =
        (12.0 * Math.log(percent.coerceIn(25f, 400f) / 100.0) / Math.log(2.0)).toFloat()

    /**
     * 变速变调走 Signalsmith Stretch（MIT，源自 BBC R&D 的 ADC22 论文），
     * 一次处理同时改变时长和音高，带 tonality limit 保住音色。
     */
    fun speedPitch(
        buffer: PcmBuffer,
        speed: Float,
        semitones: Float,
    ): PcmBuffer {
        val factor = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        if (kotlin.math.abs(factor - 1f) < 0.001f && kotlin.math.abs(semitones) < 0.01f) return buffer
        val output = ShortArray(StretchBridge.maxOutputSamples(buffer.frames, buffer.channels))
        val written = StretchBridge.create().process(
            pcm = buffer.samples,
            frames = buffer.frames,
            channels = buffer.channels,
            sampleRateHz = buffer.sampleRateHz,
            speed = factor,
            semitones = semitones,
            output = output,
        )
        check(written > 0) { "变速变调失败" }
        return PcmBuffer(
            sampleRateHz = buffer.sampleRateHz,
            channels = buffer.channels,
            samples = output.copyOf(written * buffer.channels),
        )
    }

    /** 按目标响度（EBU R128 LUFS）标准化，比单纯峰值归一化更接近听感。 */
    fun normalizeLoudness(buffer: PcmBuffer, targetLufs: Float): PcmBuffer {
        val bridge = LoudnessBridge()
        val measured = bridge.measureLufs(buffer.samples, buffer.frames, buffer.channels, buffer.sampleRateHz)
        if (measured == null) return PcmBuffer.normalize(buffer)
        bridge.normalizeTo(buffer.samples, buffer.frames, buffer.channels, buffer.sampleRateHz, targetLufs)
        return PcmBuffer.normalize(buffer, targetPeak = 30_000)
    }

    /**
     * 分段处理：只对每段区间套用效果，区间外原样保留。
     *
     * 区间会先排序并裁到缓冲区内，重叠的按先出现的优先。
     * 效果必须保持长度（变调用 Signalsmith、音量是乘法，都满足），
     * 长度变了就按能放下的部分写入，绝不越界。
     */
    fun applySegmentRanges(
        buffer: PcmBuffer,
        segments: List<SegmentRange>,
        effect: (PcmBuffer, Float) -> PcmBuffer,
    ): PcmBuffer {
        if (segments.isEmpty()) return buffer
        val channels = buffer.channels
        val rate = buffer.sampleRateHz
        val out = buffer.samples.copyOf()
        val ordered = segments
            .map { it to frameBounds(it, buffer) }
            .filter { (_, bounds) -> bounds != null }
            .sortedBy { (_, bounds) -> bounds!!.first }
        val covered = BooleanArray(buffer.frames)
        for ((segment, bounds) in ordered) {
            val (fromFrame, toFrame) = bounds!!
            // 和已处理过的区间重叠时跳过，避免同一段被套两次效果
            var usable = fromFrame
            while (usable < toFrame && covered[usable]) usable++
            if (usable >= toFrame) continue
            for (index in usable until toFrame) covered[index] = true
            val slice = buffer.slice(usable, toFrame)
            val processed = effect(slice, segment.value)
            val writable = minOf(processed.samples.size, out.size - usable * channels)
            if (writable > 0) {
                System.arraycopy(processed.samples, 0, out, usable * channels, writable)
            }
        }
        return PcmBuffer(rate, channels, out)
    }

    private fun frameBounds(segment: SegmentRange, buffer: PcmBuffer): Pair<Int, Int>? {
        val from = ((segment.startMs.coerceAtLeast(0L) / 1000.0) * buffer.sampleRateHz).toInt()
            .coerceIn(0, buffer.frames)
        val to = ((segment.endMs.coerceAtLeast(0L) / 1000.0) * buffer.sampleRateHz).toInt()
            .coerceIn(from, buffer.frames)
        return if (to > from) from to to else null
    }

    fun applyRange(buffer: PcmBuffer, startUs: Long, endUs: Long, effect: (PcmBuffer) -> PcmBuffer): PcmBuffer {
        val rate = buffer.sampleRateHz
        val fromFrame = ((startUs.coerceAtLeast(0L) / 1_000_000.0) * rate).toInt().coerceIn(0, buffer.frames)
        val toFrame = if (endUs <= 0L) {
            buffer.frames
        } else {
            ((endUs / 1_000_000.0) * rate).toInt().coerceIn(fromFrame, buffer.frames)
        }
        if (fromFrame <= 0 && toFrame >= buffer.frames) return effect(buffer)
        val region = buffer.slice(fromFrame, toFrame)
        val processed = effect(region)
        val merged = ShortArray(buffer.samples.size)
        System.arraycopy(buffer.samples, 0, merged, 0, fromFrame * buffer.channels)
        val insertCount = minOf(processed.samples.size, merged.size - fromFrame * buffer.channels)
        if (insertCount > 0) {
            System.arraycopy(processed.samples, 0, merged, fromFrame * buffer.channels, insertCount)
        }
        return PcmBuffer(rate, buffer.channels, merged)
    }

    private data class Voice(val delayFrames: Long, val gain: Float)
    const val KEEP_ORIGINAL = 0
    const val KEEP_SIDE = 1
    const val KEEP_MID = 2
    private const val MAX_WIDTH = 1.6f
    private const val MAX_PAN = 0.5f
    private const val COMB_BASE = 1200
    private const val BAND_LOW_HZ = 250f
    private const val BAND_HIGH_HZ = 4_000f
    private const val Q_FACTOR = 0.7071f
    private const val EQ_Q = 1.2f

    /** 8 段均衡的中心频率，标签沿用参考示例的 0~125 / 125~250 / … / 8k~16k。 */
    private val EQ_CENTER_HZ = floatArrayOf(
        60f, 170f, 350f, 700f, 1_400f, 2_800f, 5_600f, 11_200f,
    )

    /** 8 段均衡的显示标签，顺序和 EQ_CENTER_HZ 一致。 */
    val EQ_BAND_LABELS = listOf(
        "0~125", "125~250", "250~500", "500~1.0k",
        "1.0k~2.0k", "2.0k~4.0k", "4.0k~8.0k", "8.0k~16.0k",
    )
    private const val RADIO_LOW_HZ = 300f
    private const val RADIO_HIGH_HZ = 3_400f
    private const val CLIP_MARGIN = 64
    private const val SAMPLE_SCALE = 32_768f
    private const val MIN_SPEED = 0.25f
    private const val MAX_SPEED = 4.0f
}

/** RBJ cookbook 双二阶滤波器。 */
object Biquad {
    class State {
        var x1 = 0f
        var x2 = 0f
        var y1 = 0f
        var y2 = 0f
    }

    class Coefficients(
        val b0: Float,
        val b1: Float,
        val b2: Float,
        val a1: Float,
        val a2: Float,
    )

    fun process(input: Float, coefficients: Coefficients, state: State): Float {
        val output = coefficients.b0 * input + coefficients.b1 * state.x1 + coefficients.b2 * state.x2 -
            coefficients.a1 * state.y1 - coefficients.a2 * state.y2
        state.x2 = state.x1
        state.x1 = input
        state.y2 = state.y1
        state.y1 = output
        return output
    }

    fun peaking(rate: Float, hz: Float, q: Float, gainDb: Float): Coefficients {
        val amplitude = Math.pow(10.0, (gainDb / 40.0)).toFloat()
        val omega = 2f * PI.toFloat() * hz / rate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2f * q)
        val a0 = 1f + alpha / amplitude
        return Coefficients(
            b0 = (1f + alpha * amplitude) / a0,
            b1 = (-2f * cosOmega) / a0,
            b2 = (1f - alpha * amplitude) / a0,
            a1 = (-2f * cosOmega) / a0,
            a2 = (1f - alpha / amplitude) / a0,
        )
    }

    fun highPass(rate: Float, hz: Float, q: Float): Coefficients {
        val omega = 2f * PI.toFloat() * hz / rate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2f * q)
        val a0 = 1f + alpha
        return Coefficients(
            b0 = ((1f + cosOmega) / 2f) / a0,
            b1 = (-(1f + cosOmega)) / a0,
            b2 = ((1f + cosOmega) / 2f) / a0,
            a1 = (-2f * cosOmega) / a0,
            a2 = (1f - alpha) / a0,
        )
    }

    fun lowPass(rate: Float, hz: Float, q: Float): Coefficients {
        val omega = 2f * PI.toFloat() * hz / rate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2f * q)
        val a0 = 1f + alpha
        return Coefficients(
            b0 = ((1f - cosOmega) / 2f) / a0,
            b1 = (1f - cosOmega) / a0,
            b2 = ((1f - cosOmega) / 2f) / a0,
            a1 = (-2f * cosOmega) / a0,
            a2 = (1f - alpha) / a0,
        )
    }
}
