package cn.qishui.tool.media.effect

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** 效果参数描述，UI 照着它自动生成滑杆，不用给每个功能单独写界面。 */
data class EffectParam(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val step: Float = 0.01f,
    val unit: String = "",
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

    fun invertPhase(buffer: PcmBuffer): PcmBuffer {
        for (index in buffer.samples.indices) {
            buffer.samples[index] = (-buffer.samples[index].toInt())
                .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
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
    fun echo(buffer: PcmBuffer, delayMs: Float, feedback: Float, mix: Float): PcmBuffer {
        val delayFrames = ((delayMs.coerceIn(20f, 2000f) / 1000f) * buffer.sampleRateHz).toInt()
            .coerceAtLeast(1)
        val fb = feedback.coerceIn(0f, 0.95f)
        val wet = mix.coerceIn(0f, 1f)
        val channels = buffer.channels
        val line = FloatArray(delayFrames * channels)
        val samples = buffer.samples
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
    fun choir(buffer: PcmBuffer, spreadMs: Float, mix: Float): PcmBuffer {
        val rate = buffer.sampleRateHz
        val channels = buffer.channels
        val wet = mix.coerceIn(0f, 1f)
        val base = (spreadMs.coerceIn(5f, 80f) / 1000f) * rate
        val voices = listOf(base.toInt(), (base * 1.5f).toInt(), (base * 2f).toInt())
            .map { it.coerceAtLeast(1) }
        val gains = floatArrayOf(0.7f, 0.5f, 0.35f)
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
                val value = dry * (1f - wet) + (sum / voices.size) * wet
                out[frame * channels + channel] =
                    value.toInt().coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
        }
        return PcmBuffer(buffer.sampleRateHz, buffer.channels, out)
    }

    /** 混响：4 组梳状滤波 + 2 组全通，Schroeder 结构。 */
    fun reverb(buffer: PcmBuffer, roomSize: Float, mix: Float): PcmBuffer {
        val size = roomSize.coerceIn(0f, 1f)
        val wet = mix.coerceIn(0f, 1f)
        val rate = buffer.sampleRateHz
        val channels = buffer.channels
        val combDelays = intArrayOf(
            (COMB_BASE * (1f + size)).toInt().coerceAtLeast(1),
            (COMB_BASE * 1.37f * (1f + size)).toInt().coerceAtLeast(1),
            (COMB_BASE * 1.73f * (1f + size)).toInt().coerceAtLeast(1),
            (COMB_BASE * 2.11f * (1f + size)).toInt().coerceAtLeast(1),
        )
        val feedback = 0.72f + size * 0.24f
        val combs = Array(combDelays.size * channels) { FloatArray(combDelays[it / channels]) }
        val allpassA = FloatArray((rate * 0.005f).toInt().coerceAtLeast(1) * channels)
        val allpassB = FloatArray((rate * 0.0017f).toInt().coerceAtLeast(1) * channels)
        val out = ShortArray(buffer.samples.size)
        val samples = buffer.samples
        val combIndex = IntArray(channels)
        for (frame in 0 until buffer.frames) {
            for (channel in 0 until channels) {
                val dry = samples[frame * channels + channel].toFloat()
                var acc = 0f
                for (voice in combDelays.indices) {
                    val line = combs[voice * channels + channel]
                    val slot = combIndex[channel] % line.size
                    acc += line[slot]
                    line[slot] = dry + acc * feedback
                    combIndex[channel] = (combIndex[channel] + 1) % line.size
                }
                acc /= combDelays.size
                acc = allpass(acc, allpassA, channel, 0.5f + size * 0.3f)
                acc = allpass(acc, allpassB, channel, 0.5f)
                val value = dry * (1f - wet) + acc * wet
                out[frame * channels + channel] =
                    value.toInt().coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
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
    fun equalizer(buffer: PcmBuffer, lowDb: Float, midDb: Float, highDb: Float): PcmBuffer {
        val rate = buffer.sampleRateHz.toFloat()
        val channels = buffer.channels
        val lowGain = dbToGain(lowDb)
        val midGain = dbToGain(midDb)
        val highGain = dbToGain(highDb)
        val lowPass = Biquad.lowPass(rate, BAND_LOW_HZ, Q_FACTOR)
        val highPass = Biquad.highPass(rate, BAND_HIGH_HZ, Q_FACTOR)
        val states = Array(channels * 2) { Biquad.State() }
        val samples = buffer.samples
        for (frame in 0 until buffer.frames) {
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                val lowState = states[channel * 2]
                val highState = states[channel * 2 + 1]
                val input = samples[index].toFloat() / SAMPLE_SCALE
                val low = Biquad.process(input, lowPass, lowState)
                val high = Biquad.process(input, highPass, highState)
                val mid = input - low - high
                val output = (low * lowGain + mid * midGain + high * highGain) * SAMPLE_SCALE
                samples[index] = output.toInt()
                    .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
        }
        return buffer
    }

    private fun dbToGain(db: Float): Float =
        Math.pow(10.0, (db.coerceIn(-24f, 24f) / 20.0)).toFloat()

    /** 收音机音效：带通 300-3400Hz + 轻微削波。 */
    fun radioFx(buffer: PcmBuffer, amount: Float): PcmBuffer {
        val rate = buffer.sampleRateHz.toFloat()
        val strength = amount.coerceIn(0f, 1f)
        val highPass = Biquad.highPass(rate, RADIO_LOW_HZ, Q_FACTOR)
        val lowPass = Biquad.lowPass(rate, RADIO_HIGH_HZ, Q_FACTOR)
        val states = Array(buffer.channels) { Biquad.State() }
        val samples = buffer.samples
        for (frame in 0 until buffer.frames) {
            for (channel in 0 until buffer.channels) {
                val index = frame * buffer.channels + channel
                val state = states[channel]
                var value = samples[index].toFloat() / SAMPLE_SCALE
                value = Biquad.process(value, highPass, state)
                value = Biquad.process(value, lowPass, state)
                value = value * (1f + strength * 0.6f)
                value = kotlin.math.tanh(value * (1f + strength * 2.5f))
                samples[index] = (value * SAMPLE_SCALE).toInt()
                    .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
        }
        return buffer
    }

    /** 音频修复：去直流 + 削波点插值。 */
    fun repair(buffer: PcmBuffer, strength: Float): PcmBuffer {
        val amount = strength.coerceIn(0f, 1f)
        val channels = buffer.channels
        val samples = buffer.samples
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
        // 削波点用前后样本线性插值抹平
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

    private fun frameIsSilent(buffer: PcmBuffer, frame: Int, channels: Int, threshold: Float): Boolean {
        val base = frame * channels
        for (channel in 0 until channels) {
            if (abs(buffer.samples[base + channel].toInt()) > threshold) return false
        }
        return true
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
     * 变速变调：WSOLA 做时间拉伸，再用重采样移调。
     * ponytail: WSOLA 对 0.5x~2x 稳定，超出会有明显金属味；真要高质量得换 rubberband 之类的相位声码器。
     */
    fun speedPitch(
        buffer: PcmBuffer,
        speed: Float,
        semitones: Float,
    ): PcmBuffer {
        val factor = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val stretched = if (kotlin.math.abs(factor - 1f) < 0.001f) buffer else wsola(buffer, factor)
        if (kotlin.math.abs(semitones) < 0.01f) return stretched
        val ratio = Math.pow(2.0, (semitones / SEMITONES_PER_OCTAVE)).toFloat()
        return resampleLinear(stretched, ratio)
    }

    /** WSOLA：用 32ms 窗、16ms 跳步，按互相关找最对齐的拼接点。 */
    private fun wsola(buffer: PcmBuffer, factor: Float): PcmBuffer {
        val channels = buffer.channels
        val window = WSOLA_WINDOW * channels
        val synthesisHop = WSOLA_HOP * channels
        val analysisHop = (WSOLA_HOP * factor).toInt().coerceAtLeast(1) * channels
        val searchRadius = (WSOLA_SEARCH * channels).coerceAtLeast(1)
        val windowCurve = hann(window)
        val input = buffer.samples
        val frames = buffer.frames
        if (frames < WSOLA_WINDOW * 2) return buffer
        val outFrames = (frames / factor).toInt().coerceAtLeast(1)
        val out = ShortArray(outFrames * channels)
        val searchBuffer = FloatArray(searchRadius * 2 + 1)

        var analysisPosition = 0
        var previousTail = FloatArray(channels)
        var produced = 0
        while (produced + WSOLA_WINDOW <= outFrames) {
            var bestOffset = 0
            var bestScore = -Float.MAX_VALUE
            val natural = (produced.toFloat() / factor).toInt()
            val from = (natural - WSOLA_SEARCH).coerceAtLeast(0)
            val to = (natural + WSOLA_SEARCH).coerceAtMost((frames - WSOLA_WINDOW * 2).coerceAtLeast(0))
            for (offset in from..to) {
                var score = 0f
                for (channel in 0 until channels) {
                    score += (previousTail[channel] *
                        input[(offset * channels) + channel].toFloat())
                }
                if (score > bestScore) {
                    bestScore = score
                    bestOffset = offset
                }
            }
            analysisPosition = bestOffset * channels
            for (frame in 0 until WSOLA_WINDOW) {
                if (produced + frame >= outFrames) break
                for (channel in 0 until channels) {
                    val index = (produced + frame) * channels + channel
                    val source = analysisPosition + frame * channels + channel
                    val sample = if (source < input.size) input[source] else 0
                    val faded = previousTail[channel] * (1f - windowCurve[frame]) +
                        sample.toFloat() * windowCurve[frame]
                    out[index] = faded.toInt().coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
                }
            }
            // 记录本窗尾部，供下一窗做对齐
            for (channel in 0 until channels) {
                val source = analysisPosition + WSOLA_HOP * channels + channel
                previousTail[channel] = if (source < input.size) input[source].toFloat() else 0f
            }
            produced += WSOLA_HOP
            if (bestOffset + WSOLA_WINDOW * 2 >= frames) break
            analysisPosition = bestOffset * channels + analysisHop
            if (searchBuffer.isEmpty()) break
        }
        return PcmBuffer(buffer.sampleRateHz, buffer.channels, out)
    }

    private fun hann(size: Int): FloatArray =
        FloatArray(size) { index ->
            (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * index / size)).toFloat()
        }

    /** 线性重采样，ratio > 1 变慢变高，< 1 变快变低。 */
    private fun resampleLinear(buffer: PcmBuffer, ratio: Float): PcmBuffer {
        if (ratio <= 0f || kotlin.math.abs(ratio - 1f) < 0.0001f) return buffer
        val channels = buffer.channels
        val frames = buffer.frames
        val targetFrames = (frames / ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(targetFrames * channels)
        val input = buffer.samples
        for (frame in 0 until targetFrames) {
            val position = frame * ratio
            val index = position.toInt().coerceIn(0, (frames - 1).coerceAtLeast(0))
            val next = (index + 1).coerceAtMost((frames - 1).coerceAtLeast(0))
            val fraction = (position - index).coerceIn(0f, 1f)
            for (channel in 0 until channels) {
                val a = input[index * channels + channel].toFloat()
                val b = input[next * channels + channel].toFloat()
                out[frame * channels + channel] = (a + (b - a) * fraction)
                    .toInt().coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
        }
        return PcmBuffer(buffer.sampleRateHz, buffer.channels, out)
    }

    /** 只对 [startUs, endUs) 区间套效果，区间外原样保留。 */
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
    private const val RADIO_LOW_HZ = 300f
    private const val RADIO_HIGH_HZ = 3_400f
    private const val CLIP_MARGIN = 64
    private const val SAMPLE_SCALE = 32_768f
    private const val SEMITONES_PER_OCTAVE = 12.0
    private const val MIN_SPEED = 0.5f
    private const val MAX_SPEED = 2.0f
    private const val WSOLA_WINDOW = 1_024
    private const val WSOLA_HOP = 512
    private const val WSOLA_SEARCH = 256
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
