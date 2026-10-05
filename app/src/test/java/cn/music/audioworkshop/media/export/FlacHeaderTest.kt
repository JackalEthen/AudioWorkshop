package cn.music.audioworkshop.media.export

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * FLAC STREAMINFO 回填。
 *
 * 回归背景：`c2.android.flac.encoder` 流式输出，结束时不回填总样本数，
 * 实测导出文件的 totalSamples = 0，播放器读不出总时长（只能一秒一秒跳）。
 */
class FlacHeaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** 造一个最小合法 FLAC 头：magic + STREAMINFO(34 字节) + 一点数据。 */
    private fun flacFile(name: String, totalSamplesInHeader: Long = 0L): File {
        val bytes = ByteArray(256)
        "fLaC".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        // 偏移 4：STREAMINFO 块头。0x00 = type 0，未标记为最后一块
        bytes[4] = 0x00
        // 偏移 5..7：块长度 34
        bytes[5] = 0x00
        bytes[6] = 0x00
        bytes[7] = 34
        val file = folder.newFile(name)
        file.writeBytes(bytes)
        if (totalSamplesInHeader > 0L) {
            FlacHeader.writeTotalSamples(file, totalSamplesInHeader)
        }
        return file
    }

    @Test
    fun `写入后能读回相同的样本数`() {
        val file = flacFile("a.flac")
        val samples = 7_266_406L

        assertTrue("回填应成功", FlacHeader.writeTotalSamples(file, samples))
        assertEquals(samples, FlacHeader.readTotalSamples(file))
    }

    @Test
    fun `从零回填后不再是零`() {
        // 这就是真机上的初始状态：编码器写出来的 totalSamples = 0
        val file = flacFile("zero.flac", totalSamplesInHeader = 0L)
        assertEquals(0L, FlacHeader.readTotalSamples(file))

        assertTrue(FlacHeader.writeTotalSamples(file, 7_266_406L))
        assertEquals(7_266_406L, FlacHeader.readTotalSamples(file))
    }

    @Test
    fun `短于一首歌的时长也能正确往返`() {
        val file = flacFile("short.flac")
        val samples = 44_100L

        assertTrue(FlacHeader.writeTotalSamples(file, samples))
        assertEquals(samples, FlacHeader.readTotalSamples(file))
    }

    @Test
    fun `非正值一律拒绝写入`() {
        val file = flacFile("guard.flac")

        assertFalse("0 不该写入", FlacHeader.writeTotalSamples(file, 0L))
        assertFalse("负数不该写入", FlacHeader.writeTotalSamples(file, -1L))
    }

    @Test
    fun `超过三十六位上限才拒绝`() {
        val file = flacFile("huge.flac")

        // 36 位上限是 2^36-1，2^32 完全合法
        assertTrue(FlacHeader.writeTotalSamples(file, 0x1_0000_0000L))
        // 再大一位就超了
        assertFalse(FlacHeader.writeTotalSamples(file, 0x10_0000_0000L))
    }

    @Test
    fun `文件太短时不写也不崩`() {
        val tiny = folder.newFile("tiny.flac").apply { writeBytes(ByteArray(8)) }

        assertFalse(FlacHeader.writeTotalSamples(tiny, 1000L))
        assertEquals(null, FlacHeader.readTotalSamples(tiny))
    }

    @Test
    fun `不存在的文件安全返回`() {
        val missing = File(folder.root, "nope.flac")

        assertFalse(FlacHeader.writeTotalSamples(missing, 1000L))
        assertEquals(null, FlacHeader.readTotalSamples(missing))
    }

    @Test
    fun `能识别 FLAC 魔数`() {
        assertTrue(FlacHeader.looksLikeFlac(flacFile("m.flac")))

        val notFlac = folder.newFile("x.mp3").apply { writeText("ID3 not a flac at all") }
        assertFalse(FlacHeader.looksLikeFlac(notFlac))
    }
}
