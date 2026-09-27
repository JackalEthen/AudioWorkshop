package cn.qishui.tool.media.pcm

import cn.qishui.tool.domain.model.EditTimeSegment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmEditPipelineTest {

    @Test
    fun keepSingleSegmentTrimsHeadAndTail() {
        val collected = runPipeline(
            segments = listOf(EditTimeSegment(2_000L, 6_000L, 0L, 4_000L)),
            chunks = listOf(mono(0L, RATE, 10) { 1_000 }),
        )

        assertArrayEquals(shorts(1_000, 1_000, 1_000, 1_000), collected)
    }

    @Test
    fun gapsBetweenRetainedSegmentsAreDropped() {
        val collected = runPipeline(
            segments = listOf(
                EditTimeSegment(0L, 3_000L, 0L, 3_000L),
                EditTimeSegment(5_000L, 10_000L, 3_000L, 8_000L),
            ),
            chunks = listOf(mono(0L, RATE, 10) { 1_000 }),
        )

        assertEquals(8, collected.size)
        assertArrayEquals(shorts(1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000), collected)
    }

    @Test
    fun multipleSegmentsJoinInSourceOrder() {
        val collected = runPipeline(
            segments = listOf(
                EditTimeSegment(0L, 2_000L, 0L, 2_000L),
                EditTimeSegment(5_000L, 7_000L, 2_000L, 4_000L),
            ),
            chunks = listOf(mono(0L, RATE, 10) { frame -> 100 + frame }),
        )

        assertArrayEquals(shorts(100, 101, 105, 106), collected)
    }

    @Test
    fun gainScalesSamplesInDecibels() {
        val collected = runPipeline(
            segments = listOf(EditTimeSegment(0L, 10_000L, 0L, 10_000L)),
            gainDb = 6f,
            chunks = listOf(mono(0L, RATE, 3) { 1_000 }),
        )

        assertArrayEquals(shorts(1_995, 1_995, 1_995), collected)
    }

    @Test
    fun fadeInRampsFromSilenceToUnity() {
        val collected = runPipeline(
            segments = listOf(EditTimeSegment(0L, 10_000L, 0L, 10_000L)),
            fadeInUs = 4_000L,
            chunks = listOf(mono(0L, RATE, 10) { 1_000 }),
        )

        assertArrayEquals(shorts(0, 250, 500, 750, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000), collected)
    }

    @Test
    fun fadeOutRampsFromUnityToSilence() {
        val collected = runPipeline(
            segments = listOf(EditTimeSegment(0L, 10_000L, 0L, 10_000L)),
            fadeOutUs = 4_000L,
            chunks = listOf(mono(0L, RATE, 10) { 1_000 }),
        )

        assertArrayEquals(shorts(1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 750, 500, 250), collected)
    }

    @Test
    fun segmentBoundarySpanningChunksKeepsExactlyTheRetainedFrames() {
        val collected = runPipeline(
            segments = listOf(EditTimeSegment(4_000L, 7_000L, 0L, 3_000L)),
            chunks = listOf(
                mono(0L, RATE, 3) { 1 },
                mono(3_000L, RATE, 3) { 2 },
                mono(6_000L, RATE, 3) { 3 },
                mono(9_000L, RATE, 1) { 4 },
            ),
        )

        assertArrayEquals(shorts(2, 2, 3), collected)
    }

    @Test
    fun noSegmentsProducesNoOutput() {
        val pipeline = PcmEditPipeline(emptyList())
        val collected = mutableListOf<ShortArray>()

        pipeline.process(source(mono(0L, RATE, 10) { 1_000 })) { collected += it }

        assertTrue(collected.isEmpty())
        assertEquals(0L, pipeline.outputFrameCount)
        assertEquals(0L, pipeline.outputTimeUs)
    }

    @Test
    fun gainBeyondFullScaleClampsBothPolarities() {
        val collected = runPipeline(
            segments = listOf(EditTimeSegment(0L, 4_000L, 0L, 4_000L)),
            gainDb = 40f,
            chunks = listOf(mono(0L, RATE, 4) { frame -> if (frame % 2 == 0) 30_000 else -30_000 }),
        )

        assertArrayEquals(shorts(32_767, -32_768, 32_767, -32_768), collected)
    }

    @Test
    fun fortyFourOneKiloHertzMapsOneHundredMillisecondsToFourThousandFourHundredTenFrames() {
        val pipeline = PcmEditPipeline(listOf(EditTimeSegment(0L, 100_000L, 0L, 100_000L)))
        val frames = 5_000

        pipeline.process(source(mono(0L, 44_100, frames) { 1_000 })) { }

        assertEquals(4_410L, pipeline.outputFrameCount)
        assertEquals(100_000L, pipeline.outputTimeUs)
    }

    @Test
    fun fortyEightKiloHertzMapsOneHundredMillisecondsToFourThousandEightHundredFrames() {
        val pipeline = PcmEditPipeline(listOf(EditTimeSegment(0L, 100_000L, 0L, 100_000L)))

        pipeline.process(source(mono(0L, 48_000, 6_000) { 1_000 })) { }

        assertEquals(4_800L, pipeline.outputFrameCount)
    }

    @Test
    fun outputIsStreamedInBoundedBlocks() {
        val pipeline = PcmEditPipeline(listOf(EditTimeSegment(0L, 10_000_000L, 0L, 10_000_000L)))
        val blockSizes = mutableListOf<Int>()

        pipeline.process(source(mono(0L, 1_000, 10_000) { 1_000 })) { blockSizes += it.size }

        assertTrue(blockSizes.isNotEmpty())
        assertTrue(blockSizes.all { it <= 2_048 })
        assertEquals(10_000, blockSizes.sum())
    }

    @Test
    fun stereoKeepsInterleavedChannelOrder() {
        val pipeline = PcmEditPipeline(listOf(EditTimeSegment(0L, 4_000L, 0L, 4_000L)))
        val collected = mutableListOf<ShortArray>()

        pipeline.process(source(stereo(0L, RATE, 4) { frame -> listOf(100 + frame, 900 + frame) })) {
            collected += it
        }

        assertArrayEquals(
            shorts(100, 900, 101, 901, 102, 902, 103, 903),
            collected.fold(ShortArray(0)) { acc, block -> acc + block },
        )
    }

    private fun runPipeline(
        segments: List<EditTimeSegment>,
        chunks: List<PcmChunk>,
        gainDb: Float = 0f,
        fadeInUs: Long = 0L,
        fadeOutUs: Long = 0L,
    ): ShortArray {
        val collected = mutableListOf<ShortArray>()
        PcmEditPipeline(segments, gainDb, fadeInUs, fadeOutUs).process(source(*chunks.toTypedArray())) {
            collected += it
        }
        return collected.fold(ShortArray(0)) { acc, block -> acc + block }
    }

    private fun source(vararg chunks: PcmChunk): PcmFrameSource = PcmFrameSource { sink ->
        val format = chunks.firstOrNull()
        chunks.forEach(sink)
        DecodedAudioFormat(
            sampleRateHz = format?.sampleRateHz ?: 0,
            channels = format?.channels ?: 0,
        )
    }

    private fun mono(startUs: Long, sampleRate: Int, frames: Int, value: (Int) -> Int): PcmChunk =
        PcmChunk(
            presentationTimeUs = startUs,
            sampleRateHz = sampleRate,
            channels = 1,
            samples = ShortArray(frames) { value(it).toShort() },
        )

    private fun stereo(startUs: Long, sampleRate: Int, frames: Int, value: (Int) -> List<Int>): PcmChunk {
        val samples = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            val pair = value(frame)
            samples[frame * 2] = pair[0].toShort()
            samples[frame * 2 + 1] = pair[1].toShort()
        }
        return PcmChunk(startUs, sampleRate, 2, samples)
    }

    private fun shorts(vararg values: Int): ShortArray = ShortArray(values.size) { values[it].toShort() }

    private companion object {
        const val RATE = 1_000
    }
}
