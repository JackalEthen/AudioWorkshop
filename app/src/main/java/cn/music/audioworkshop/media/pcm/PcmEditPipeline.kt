package cn.music.audioworkshop.media.pcm

import cn.music.audioworkshop.domain.model.EditTimeSegment
import cn.music.audioworkshop.domain.model.FadeCurve
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

class PcmEditPipeline(
    segments: List<EditTimeSegment>,
    gainDb: Float = 0f,
    fadeInUs: Long = 0L,
    fadeOutUs: Long = 0L,
    fadeCurve: FadeCurve = FadeCurve.LINEAR,
    /**
     * 防炸音：超过满幅时用软限制器压回去，而不是硬削波。
     *
     * 硬削波（coerceIn 到 ±32767）会把波峰砍成平台，听感是「啪」的一下爆音，
     * 高频尤其刺耳。软限制器是渐近曲线，过增益处平滑压缩，听感自然得多。
     * 关掉时退回原来的硬削波 —— 逐位严格不变的场合（比如逐样本比对）才需要。
     */
    preventClipping: Boolean = false,
) {
    // 这些都做成可变的：预览复用同一条流水线时要用新参数重新跑一遍，
    // 复制一份新实例反而比重置这些字段更贵（gain/outputDuration 都要重算）。
    private var segments = segments
    private var gainDb = gainDb
    private var fadeInUs = fadeInUs
    private var fadeOutUs = fadeOutUs
    private var fadeCurve = fadeCurve
    private var preventClipping = preventClipping

    private var gain = 10.0.pow(gainDb / 20.0).toFloat()
    private var outputDurationUs = segments.maxOfOrNull { it.outputEndUs } ?: 0L

    var outputFrameCount: Long = 0L
        private set
    var outputTimeUs: Long = 0L
        private set

    private var segmentIndex = 0
    private var pending: ShortArray? = null
    private var pendingSize = 0

    fun process(source: PcmFrameSource, sink: (ShortArray) -> Unit) {
        source.read { chunk -> consume(chunk, sink) }
        flush(sink)
    }

    /**
     * 直接喂一个已知格式的 PCM 块。
     *
     * 预览要变速时会先解一次码拿到整段，再走一遍同样的流水线 ——
     * 和 [process] 走的是同一条 [consume]，所以「重渲染」和「真导出」
     * 的增益/淡入淡出结果不会因为这里换了入口而对不上。
     */
    fun consume(
        chunk: PcmChunk,
        segments: List<EditTimeSegment> = this.segments,
        gainDb: Float = this.gainDb,
        fadeInUs: Long = this.fadeInUs,
        fadeOutUs: Long = this.fadeOutUs,
        fadeCurve: FadeCurve = this.fadeCurve,
        preventClipping: Boolean = this.preventClipping,
        sink: (ShortArray) -> Unit,
    ) {
        this.segments = segments
        this.gainDb = gainDb
        this.fadeInUs = fadeInUs
        this.fadeOutUs = fadeOutUs
        this.fadeCurve = fadeCurve
        this.preventClipping = preventClipping
        this.outputDurationUs = segments.maxOfOrNull { it.outputEndUs } ?: 0L
        this.gain = 10.0.pow(gainDb / 20.0).toFloat()
        this.segmentIndex = 0
        consume(chunk, sink)
        flush(sink)
    }

    private fun consume(chunk: PcmChunk, sink: (ShortArray) -> Unit) {
        val channels = chunk.channels
        val sampleRate = chunk.sampleRateHz
        val samples = chunk.samples
        if (channels <= 0 || sampleRate <= 0 || samples.isEmpty()) return
        var block = pending
        if (block == null || block.size != BLOCK_FRAMES * channels) {
            flush(sink)
            block = ShortArray(BLOCK_FRAMES * channels)
            pending = block
        }
        val frames = samples.size / channels
        for (frame in 0 until frames) {
            val sourceUs = chunk.presentationTimeUs + (frame.toLong() * MICROS_PER_SECOND) / sampleRate
            while (segmentIndex < segments.size && sourceUs >= segments[segmentIndex].sourceEndUs) {
                segmentIndex++
            }
            val segment = segments.getOrNull(segmentIndex)
            if (segment == null || sourceUs < segment.sourceStartUs) continue
            val target = pending ?: return
            val factor = gain * fadeFactor()
            val base = frame * channels
            for (channel in 0 until channels) {
                target[pendingSize + channel] = shapeSample(samples[base + channel] * factor)
            }
            pendingSize += channels
            outputFrameCount++
            outputTimeUs = (outputFrameCount * MICROS_PER_SECOND) / sampleRate
            if (pendingSize == target.size) flush(sink)
        }
    }

    private fun fadeFactor(): Float {
        val positionUs = outputTimeUs
        if (fadeInUs > 0L && positionUs < fadeInUs) {
            return fadeCurve.shape(positionUs.toFloat() / fadeInUs.toFloat())
        }
        if (fadeOutUs > 0L && outputDurationUs > 0L) {
            val remaining = outputDurationUs - positionUs
            if (remaining < fadeOutUs) {
                return fadeCurve.shape(remaining.toFloat() / fadeOutUs.toFloat())
            }
        }
        return 1f
    }

    /**
     * 把一个已乘增益的浮点采样落回 16-bit。
     *
     * 开了防炸音走软限制：满幅附近是渐近压缩而不是切平，峰值不会「啪」地爆掉。
     * 关掉就是原来的硬削波。
     */
    private fun shapeSample(value: Float): Short {
        if (!preventClipping) {
            return value.coerceIn(SHORT_MIN, SHORT_MAX).roundToInt().toShort()
        }
        val magnitude = abs(value)
        val ceiling = SHORT_MAX
        // 阈值以下原样通过 —— 大部分正常电平不受影响，只处理真正过增益的波峰
        if (magnitude <= SOFT_LIMIT_THRESHOLD) return value.roundToInt().toShort()
        val headroom = ceiling - SOFT_LIMIT_THRESHOLD
        val overflow = magnitude - SOFT_LIMIT_THRESHOLD
        // 纯 tanh 在大过增益时会收敛到 ceiling 不再变化（数值取整后全变成同一个值，
        // 反而在极高增益处退化成硬削波）。所以这里取 tanh 与线性衰减的较小值：
        // 拐点附近用 tanh 的平滑过渡，远处用线性把余量逐步吃掉，全程严格单调。
        val curved = SOFT_LIMIT_THRESHOLD + headroom * kotlin.math.tanh(overflow / headroom)
        val linear = ceiling - headroom * kotlin.math.exp(-overflow / headroom)
        val sign = if (value < 0f) -1f else 1f
        return (sign * minOf(curved, linear)).coerceIn(SHORT_MIN, ceiling).roundToInt().toShort()
    }

    private fun flush(sink: (ShortArray) -> Unit) {
        val block = pending
        val size = pendingSize
        pendingSize = 0
        if (block != null && size > 0) sink(block.copyOf(size))
    }
}

/**
 * 把流水线分块产出的结果接成一个连续数组。
 *
 * 不能用 `fold { acc + b }`：那是反复复制整条数组，复杂度 O(n²)。
 * 一首四分钟的歌有几十万个 2048 帧的块，光这一步就要复制几百 MB，
 * 比渲染本身还慢。
 */
fun joinShortArrays(blocks: List<ShortArray>): ShortArray {
    if (blocks.isEmpty()) return ShortArray(0)
    if (blocks.size == 1) return blocks[0]
    var total = 0
    blocks.forEach { total += it.size }
    val out = ShortArray(total)
    var at = 0
    blocks.forEach { block ->
        block.copyInto(out, at)
        at += block.size
    }
    return out
}

/**
 * 流水线每次交给下游的帧数。
 *
 * 一首四分钟的歌按 2048 帧切会产生八万多个小数组，每个都要 new + copy 一次 ——
 * 光这两次复制就能吃掉导出的一大截时间。8192 帧（44.1kHz 下约 0.19 秒）
 * 仍然足够小，重采样转换器和编码器都能舒服地处理，但对象数降到四分之一。
 */
private const val BLOCK_FRAMES = 8192

private const val MICROS_PER_SECOND = 1_000_000L

private const val SHORT_MAX = 32767f

private const val SHORT_MIN = -32768f

/**
 * 软限制起作用的电平。取 -6 dBFS 而不是贴近满幅：
 *
 * 阈值太高（比如 -1dBFS）时，只要原曲本身电平偏高，增益就几乎全被吃掉 ——
 * 实测输入 29000 在 +6dB 下最终只涨了 1.1dB，用户会认为「调了没反应」。
 * 降到 -6dBFS 后，安静的段落能正常拿到全部增益，只有真正的波峰才被压。
 */
private const val SOFT_LIMIT_THRESHOLD = 16_400f

