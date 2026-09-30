package cn.qishui.tool.media.pcm

import java.io.File
import java.io.RandomAccessFile

/**
 * 流式 16-bit PCM WAV 写出器。
 *
 * 为什么不直接用 `PcmBuffer.writeWav`：那个要求整段采样已在内存里，
 * 试听整轨会一次吃掉几十 MB。这里先写占位长度、close 时回填。
 */
class PcmStreamWriter(
    private val file: File,
    private val sampleRateHz: Int,
    private val channels: Int,
) {
    private val output: RandomAccessFile = RandomAccessFile(file, "rw").apply {
        setLength(0L)
        write(header())
    }

    private var dataBytes: Long = 0L

    fun write(samples: ShortArray, count: Int = samples.size) {
        if (count <= 0) return
        val buffer = ByteArray(count * 2)
        var index = 0
        for (i in 0 until count) {
            val value = samples[i].toInt()
            buffer[index++] = (value and 0xFF).toByte()
            buffer[index++] = ((value shr 8) and 0xFF).toByte()
        }
        output.write(buffer)
        dataBytes += buffer.size
    }

    /** 回填 RIFF 头里的长度字段。ExoPlayer 认这个，填错直接拒播。 */
    fun close() {
        runCatching {
            output.seek(4L)
            output.write(intLe((36L + dataBytes).toInt()))
            output.seek(40L)
            output.write(intLe(dataBytes.toInt()))
        }
        output.close()
    }

    private fun header(): ByteArray {
        val byteRate = sampleRateHz * channels * 2
        val blockAlign = channels * 2
        val out = ByteArray(HEADER_BYTES)
        var at = 0
        fun ascii(text: String) {
            text.forEach { out[at++] = it.code.toByte() }
        }

        fun le(value: Int) {
            out[at++] = (value and 0xFF).toByte()
            out[at++] = ((value shr 8) and 0xFF).toByte()
            out[at++] = ((value shr 16) and 0xFF).toByte()
            out[at++] = ((value shr 24) and 0xFF).toByte()
        }

        fun le16(value: Int) {
            out[at++] = (value and 0xFF).toByte()
            out[at++] = ((value shr 8) and 0xFF).toByte()
        }

        ascii("RIFF")
        le(36) // 占位，close() 回填
        ascii("WAVE")
        ascii("fmt ")
        le(16) // fmt 块长度
        le16(1) // PCM，未压缩
        le16(channels)
        le(sampleRateHz)
        le(byteRate)
        le16(blockAlign)
        le16(16) // 每采样 16 bit
        ascii("data")
        le(0) // 占位，close() 回填
        return out
    }

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private companion object {
        const val HEADER_BYTES = 44
    }
}

/**
 * 写一段静音。拼接的「无损接缝」和「插入空白音乐」都靠它，
 * 分块写避免一次分配几秒的 ShortArray。
 */
fun PcmStreamWriter.writeSilenceUs(durationUs: Long, sampleRateHz: Int, channels: Int) {
    if (durationUs <= 0L || sampleRateHz <= 0 || channels <= 0) return
    val totalFrames = durationUs * sampleRateHz / 1_000_000L
    val block = ShortArray(SILENCE_BLOCK_FRAMES * channels)
    var written = 0L
    while (written < totalFrames) {
        val frames = minOf(SILENCE_BLOCK_FRAMES.toLong(), totalFrames - written).toInt()
        write(block, frames * channels)
        written += frames
    }
}

private const val SILENCE_BLOCK_FRAMES = 2_048
