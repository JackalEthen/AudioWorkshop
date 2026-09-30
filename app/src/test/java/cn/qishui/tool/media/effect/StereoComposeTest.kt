package cn.qishui.tool.media.effect

import org.junit.Assert.assertEquals
import org.junit.Test

class StereoComposeTest {

    @Test
    fun `left track goes to left channel and right track to right channel`() {
        val left = PcmBuffer(48_000, 1, shortArrayOf(1000, 2000, 3000))
        val right = PcmBuffer(48_000, 1, shortArrayOf(-1000, -2000, -3000))

        val out = PcmEffects.stereoCompose(left, right)

        assertEquals(2, out.channels)
        assertEquals(3, out.frames)
        assertEquals((1000).toShort(), out.samples[0])
        assertEquals((-1000).toShort(), out.samples[1])
        assertEquals((2000).toShort(), out.samples[2])
        assertEquals((-2000).toShort(), out.samples[3])
        assertEquals((3000).toShort(), out.samples[4])
        assertEquals((-3000).toShort(), out.samples[5])
    }

    @Test
    fun `shorter track is padded with silence`() {
        val left = PcmBuffer(48_000, 1, shortArrayOf(111))
        val right = PcmBuffer(48_000, 1, shortArrayOf(222, 333))

        val out = PcmEffects.stereoCompose(left, right)

        assertEquals(2, out.frames)
        assertEquals((111).toShort(), out.samples[0])
        assertEquals((222).toShort(), out.samples[1])
        assertEquals((0).toShort(), out.samples[2])
        assertEquals((333).toShort(), out.samples[3])
    }

    @Test
    fun `stereo source keeps its own side and mono source stays on its slot`() {
        val stereoLeft = PcmBuffer(
            48_000, 2,
            shortArrayOf(100, 101, 200, 201),
        )
        val monoRight = PcmBuffer(48_000, 1, shortArrayOf(900, 901))

        val out = PcmEffects.stereoCompose(stereoLeft, monoRight)

        assertEquals((100).toShort(), out.samples[0])
        assertEquals((900).toShort(), out.samples[1])
        assertEquals((200).toShort(), out.samples[2])
        assertEquals((901).toShort(), out.samples[3])
    }

    @Test
    fun `single track plays in both channels when the other slot reuses it`() {
        val source = PcmBuffer(48_000, 2, shortArrayOf(500, 600))

        val out = PcmEffects.stereoCompose(source, source)

        assertEquals((500).toShort(), out.samples[0])
        assertEquals((600).toShort(), out.samples[1])
    }
}
