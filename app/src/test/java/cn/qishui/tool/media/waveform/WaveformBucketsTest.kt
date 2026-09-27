package cn.qishui.tool.media.waveform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformBucketsTest {

    @Test
    fun nonPositiveDurationFallsBackToMinimumBucketCount() {
        assertEquals(WaveformBuckets.MIN_BUCKETS, WaveformBuckets.countFor(0L))
        assertEquals(WaveformBuckets.MIN_BUCKETS, WaveformBuckets.countFor(-1L))
    }

    @Test
    fun shortDurationIsClampedUpToMinimumBucketCount() {
        assertEquals(WaveformBuckets.MIN_BUCKETS, WaveformBuckets.countFor(1_000_000L))
        assertEquals(WaveformBuckets.MIN_BUCKETS, WaveformBuckets.countFor(2_560_000L))
    }

    @Test
    fun bucketCountScalesWithDurationAndClampsToMaximum() {
        assertEquals(3_000, WaveformBuckets.countFor(30_000_000L))
        assertEquals(WaveformBuckets.MAX_BUCKETS, WaveformBuckets.countFor(600_000_000L))
        assertEquals(WaveformBuckets.MAX_BUCKETS, WaveformBuckets.countFor(Long.MAX_VALUE / 2))
    }

    @Test
    fun emptyFileProducesAllZeroBuckets() {
        val durationUs = 0L
        val accumulator = WaveformBucketAccumulator(WaveformBuckets.countFor(durationUs), durationUs)

        accumulator.accept(presentationTimeUs = 0L, sampleRateHz = 44_100, channels = 2, samples = shorts(1, -1))

        val peaks = accumulator.build(0L, durationUs)
        assertEquals(WaveformBuckets.MIN_BUCKETS, peaks.min.size)
        assertArrayEquals(FloatArray(WaveformBuckets.MIN_BUCKETS), peaks.min, 0f)
        assertArrayEquals(FloatArray(WaveformBuckets.MIN_BUCKETS), peaks.max, 0f)
    }

    @Test
    fun unfilledBucketsStayZeroWhileFilledBucketCarriesPeaks() {
        val durationUs = 10_000_000L
        val buckets = WaveformBuckets.countFor(durationUs)
        val accumulator = WaveformBucketAccumulator(buckets, durationUs)

        accumulator.accept(presentationTimeUs = 0L, sampleRateHz = 1_000, channels = 1, samples = shorts(1_000))

        val peaks = accumulator.build(0L, durationUs)
        assertEquals(buckets, peaks.min.size)
        assertEquals(1_000f / 32_768f, peaks.max[0], 1e-6f)
        assertEquals(0f, peaks.min[1], 1e-6f)
        assertTrue(peaks.max.drop(1).all { it == 0f })
    }

    @Test
    fun monoBucketTracksMinimumAndMaximumSample() {
        val durationUs = 4_000_000L
        val accumulator = WaveformBucketAccumulator(WaveformBuckets.countFor(durationUs), durationUs)

        accumulator.accept(
            presentationTimeUs = 0L,
            sampleRateHz = 4_000,
            channels = 1,
            samples = shorts(8_192, -16_384, 4_096),
        )

        val peaks = accumulator.build(0L, durationUs)
        assertEquals(-16_384f / 32_768f, peaks.min[0], 1e-6f)
        assertEquals(8_192f / 32_768f, peaks.max[0], 1e-6f)
    }

    @Test
    fun stereoChannelsMergeIntoASingleBucketRange() {
        val durationUs = 4_000_000L
        val accumulator = WaveformBucketAccumulator(WaveformBuckets.countFor(durationUs), durationUs)

        accumulator.accept(
            presentationTimeUs = 0L,
            sampleRateHz = 4_000,
            channels = 2,
            samples = shorts(-8_192, 8_192, 2_048, -2_048),
        )

        val peaks = accumulator.build(0L, durationUs)
        assertEquals(-8_192f / 32_768f, peaks.min[0], 1e-6f)
        assertEquals(8_192f / 32_768f, peaks.max[0], 1e-6f)
    }

    @Test
    fun samplesBeyondDurationAreDiscarded() {
        val durationUs = 1_000_000L
        val accumulator = WaveformBucketAccumulator(4, durationUs)

        accumulator.accept(
            presentationTimeUs = durationUs,
            sampleRateHz = 1_000,
            channels = 1,
            samples = shorts(16_384, 16_384),
        )

        val peaks = accumulator.build(0L, durationUs)
        assertEquals(4, peaks.min.size)
        assertTrue(peaks.max.all { it == 0f })
    }

    @Test
    fun samplesAreDistributedAcrossBucketsByTime() {
        val durationUs = 4_000L
        val accumulator = WaveformBucketAccumulator(4, durationUs)

        accumulator.accept(
            presentationTimeUs = 0L,
            sampleRateHz = 1_000,
            channels = 1,
            samples = shorts(1_000, 2_000, 3_000, 4_000),
        )

        val peaks = accumulator.build(0L, durationUs)
        assertEquals(1_000f / 32_768f, peaks.max[0], 1e-6f)
        assertEquals(2_000f / 32_768f, peaks.max[1], 1e-6f)
        assertEquals(3_000f / 32_768f, peaks.max[2], 1e-6f)
        assertEquals(4_000f / 32_768f, peaks.max[3], 1e-6f)
    }

    @Test
    fun buildCopiesAccumulatorStateSoLaterSamplesDoNotMutatePeaks() {
        val durationUs = 4_000_000L
        val accumulator = WaveformBucketAccumulator(4, durationUs)
        accumulator.accept(0L, 4_000, 1, shorts(1_000, 1_000, 1_000, 1_000))

        val peaks = accumulator.build(0L, durationUs)
        accumulator.accept(0L, 4_000, 1, shorts(32_767, 32_767, 32_767, 32_767))

        assertEquals(1_000f / 32_768f, peaks.max[0], 1e-6f)
    }

    private fun shorts(vararg values: Int): ShortArray = ShortArray(values.size) { values[it].toShort() }
}
