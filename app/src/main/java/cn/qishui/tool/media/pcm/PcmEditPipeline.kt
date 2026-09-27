package cn.qishui.tool.media.pcm

import cn.qishui.tool.domain.model.EditTimeSegment
import kotlin.math.pow
import kotlin.math.roundToInt

class PcmEditPipeline(
    private val segments: List<EditTimeSegment>,
    gainDb: Float = 0f,
    private val fadeInUs: Long = 0L,
    private val fadeOutUs: Long = 0L,
) {
    private val gain = 10.0.pow(gainDb / 20.0).toFloat()
    private val outputDurationUs = segments.maxOfOrNull { it.outputEndUs } ?: 0L

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
                target[pendingSize + channel] = (samples[base + channel] * factor)
                    .coerceIn(SHORT_MIN, SHORT_MAX)
                    .roundToInt()
                    .toShort()
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
            return (positionUs.toFloat() / fadeInUs.toFloat()).coerceIn(0f, 1f)
        }
        if (fadeOutUs > 0L && outputDurationUs > 0L) {
            val remaining = outputDurationUs - positionUs
            if (remaining < fadeOutUs) {
                return (remaining.toFloat() / fadeOutUs.toFloat()).coerceIn(0f, 1f)
            }
        }
        return 1f
    }

    private fun flush(sink: (ShortArray) -> Unit) {
        val block = pending
        val size = pendingSize
        pendingSize = 0
        if (block != null && size > 0) sink(block.copyOf(size))
    }

    private companion object {
        const val BLOCK_FRAMES = 2048
        const val MICROS_PER_SECOND = 1_000_000L
        const val SHORT_MAX = 32767f
        const val SHORT_MIN = -32768f
    }
}
