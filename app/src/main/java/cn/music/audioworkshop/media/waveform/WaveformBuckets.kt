package cn.music.audioworkshop.media.waveform

import cn.music.audioworkshop.domain.model.WaveformPeaks

object WaveformBuckets {

    const val MIN_BUCKETS = 256
    const val MAX_BUCKETS = 4000
    private const val BUCKETS_PER_SECOND = 100L
    private const val MICROS_PER_SECOND = 1_000_000L

    fun countFor(durationUs: Long): Int {
        if (durationUs <= 0L) return MIN_BUCKETS
        val seconds = durationUs / MICROS_PER_SECOND
        val remainder = durationUs % MICROS_PER_SECOND
        val raw = seconds * BUCKETS_PER_SECOND + (remainder * BUCKETS_PER_SECOND) / MICROS_PER_SECOND
        return raw.coerceIn(MIN_BUCKETS.toLong(), MAX_BUCKETS.toLong()).toInt()
    }
}

class WaveformBucketAccumulator(
    private val bucketCount: Int,
    private val durationUs: Long,
) {
    private val min = FloatArray(bucketCount)
    private val max = FloatArray(bucketCount)
    private val filled = BooleanArray(bucketCount)

    fun accept(presentationTimeUs: Long, sampleRateHz: Int, channels: Int, samples: ShortArray) {
        if (bucketCount <= 0 || durationUs <= 0L || sampleRateHz <= 0 || channels <= 0) return
        val frames = samples.size / channels
        for (frame in 0 until frames) {
            val timeUs = presentationTimeUs + (frame.toLong() * MICROS_PER_SECOND) / sampleRateHz
            if (timeUs < 0L || timeUs >= durationUs) continue
            val bucket = ((timeUs * bucketCount) / durationUs).toInt()
            if (bucket < 0 || bucket >= bucketCount) continue
            val base = frame * channels
            for (channel in 0 until channels) {
                val value = samples[base + channel] / SHORT_FULL_SCALE
                if (filled[bucket]) {
                    if (value < min[bucket]) min[bucket] = value
                    if (value > max[bucket]) max[bucket] = value
                } else {
                    min[bucket] = value
                    max[bucket] = value
                    filled[bucket] = true
                }
            }
        }
    }

    fun build(startUs: Long, endUs: Long): WaveformPeaks =
        WaveformPeaks(startUs, endUs, min.copyOf(), max.copyOf())

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val SHORT_FULL_SCALE = 32768f
    }
}
