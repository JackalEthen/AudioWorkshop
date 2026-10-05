package cn.music.audioworkshop.media.pcm

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmStreamWriterTest {

    @Test
    fun writesAStandardPcmWavHeaderWithBackfilledLengths() {
        val file = write(shortArrayOf(1, 2, 3, 4), sampleRateHz = 8_000, channels = 1)
        val bytes = file.readBytes()

        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("fmt ", String(bytes, 12, 4))
        assertEquals("data", String(bytes, 36, 4))
        // 8 字节数据 + 36 字节固定头
        assertEquals(44, le32(bytes, 4))
        assertEquals(8, le32(bytes, 40))
        // fmt: PCM=1, 声道=1, 采样率=8000, 字节率=16000, 块对齐=2, 位深=16
        assertEquals(1, le16(bytes, 20))
        assertEquals(1, le16(bytes, 22))
        assertEquals(8_000, le32(bytes, 24))
        assertEquals(16_000, le32(bytes, 28))
        assertEquals(2, le16(bytes, 32))
        assertEquals(16, le16(bytes, 34))
    }

    @Test
    fun writesSamplesAsLittleEndianAndRoundTripsExactly() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 1_000)
        val file = write(samples, sampleRateHz = 44_100, channels = 2)

        val payload = file.readBytes().drop(44)
        val buffer = ByteBuffer.wrap(payload.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val restored = ShortArray(samples.size) { buffer.short }
        assertArrayEquals(samples, restored)
    }

    @Test
    fun honoursTheCountArgumentSoPartialBlocksCanBeFlushed() {
        val file = write(shortArrayOf(9, 9, 9, 9), sampleRateHz = 8_000, channels = 1, countPerWrite = 2)

        assertEquals(4, le32(file.readBytes(), 40))
    }

    private fun write(
        samples: ShortArray,
        sampleRateHz: Int,
        channels: Int,
        countPerWrite: Int = samples.size,
    ): File {
        val file = File.createTempFile("pcm-stream", ".wav")
        file.deleteOnExit()
        val writer = PcmStreamWriter(file, sampleRateHz, channels)
        writer.write(samples, countPerWrite)
        writer.close()
        return file
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun le32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
