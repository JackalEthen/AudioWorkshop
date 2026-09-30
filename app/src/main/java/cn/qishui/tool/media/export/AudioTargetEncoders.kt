package cn.qishui.tool.media.export

import android.media.MediaCodec
import android.media.MediaMuxer
import cn.qishui.tool.domain.media.ExportFormat
import cn.qishui.tool.media.pcm.PcmStreamWriter
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * 导出时的目标编码器。导出链路是纯追加的，所以只暴露追加写和收尾。
 */
interface AudioTargetEncoder : AutoCloseable {
    fun write(samples: ShortArray, count: Int = samples.size)

    /** 冲刷编码器内部缓存。调用后不能再 write。 */
    fun finish()
}

/** 导出链路里对用户可见的失败。message 直接进 snackbar，所以必须是能看懂的中文。 */
internal class ExportFailureException(message: String) : RuntimeException(message)

/** 按目标格式建编码器。[output] 只有 MP3 会用到（写进已有的输出流）。 */
internal fun createTargetEncoder(
    format: ExportFormat,
    file: File,
    sampleRateHz: Int,
    channels: Int,
    bitrateKbps: Int,
    output: OutputStream? = null,
): AudioTargetEncoder = try {
    when (format) {
        ExportFormat.MP3 -> LameTargetEncoder(
            stream = output ?: file.outputStream(),
            sampleRateHz = sampleRateHz,
            channels = channels,
            bitrateKbps = bitrateKbps,
        )

        ExportFormat.WAV -> WavTargetEncoder(file, sampleRateHz, channels)
        ExportFormat.FLAC -> FlacTargetEncoder(file, sampleRateHz, channels)
        ExportFormat.AAC -> AacTargetEncoder(file, sampleRateHz, channels, bitrateKbps)
        ExportFormat.M4A -> M4aTargetEncoder(file, sampleRateHz, channels, bitrateKbps)
    }
} catch (error: Throwable) {
    // 不是所有设备都有 FLAC 编码器，缺了要给能看懂的话，别把 MediaCodec 的原始异常抛出去
    throw ExportFailureException("这台设备不支持 ${format.label} 编码：${error.message ?: "编码器不可用"}")
}

/** MP3：LAME CBR。 */
internal class LameTargetEncoder(
    private val stream: OutputStream,
    sampleRateHz: Int,
    private val channels: Int,
    bitrateKbps: Int,
) : AudioTargetEncoder {

    private val bridge = LameNativeBridge()

    init {
        bridge.init(sampleRateHz, channels, bitrateKbps)
    }

    override fun write(samples: ShortArray, count: Int) {
        if (count <= 0) return
        val encoded = bridge.encode(samples, count / channels)
        if (encoded.isNotEmpty()) stream.write(encoded)
    }

    override fun finish() {
        val tail = bridge.flush()
        if (tail.isNotEmpty()) stream.write(tail)
    }

    override fun close() = bridge.close()
}

/** WAV：PCM 直写，无损，不写任何标签。 */
internal class WavTargetEncoder(
    file: File,
    sampleRateHz: Int,
    channels: Int,
) : AudioTargetEncoder {

    private val writer = PcmStreamWriter(file, sampleRateHz, channels)

    override fun write(samples: ShortArray, count: Int) = writer.write(samples, count)

    override fun finish() = writer.close()

    override fun close() = writer.close()
}

/** AAC：裸 ADTS 流。MediaCodec 吐的是无头访问单元，这里补头。 */
internal class AacTargetEncoder(
    file: File,
    private val sampleRateHz: Int,
    private val channels: Int,
    bitrateKbps: Int,
) : AudioTargetEncoder {

    private val stream = file.outputStream().buffered()
    private val encoder = MediaCodecAudioEncoder(MimeTypes.AAC, sampleRateHz, channels, bitrateKbps)

    override fun write(samples: ShortArray, count: Int) {
        encoder.encode(samples, count) { bytes, size, _ ->
            stream.write(adtsHeader(size, sampleRateHz, channels))
            stream.write(bytes, 0, size)
        }
    }

    override fun finish() {
        encoder.finish { bytes, size, _ ->
            stream.write(adtsHeader(size, sampleRateHz, channels))
            stream.write(bytes, 0, size)
        }
    }

    override fun close() {
        runCatching { encoder.close() }
        runCatching { stream.close() }
    }
}

/** FLAC：MediaCodec 的 FLAC 输出本身就是完整 .flac 流，直接落盘。 */
internal class FlacTargetEncoder(
    file: File,
    sampleRateHz: Int,
    channels: Int,
) : AudioTargetEncoder {

    private val stream = file.outputStream().buffered()
    // FLAC 是无损的，KEY_BIT_RATE 设了也没意义，传 0
    private val encoder = MediaCodecAudioEncoder(MimeTypes.FLAC, sampleRateHz, channels, 0)

    override fun write(samples: ShortArray, count: Int) {
        encoder.encode(samples, count) { bytes, size, _ -> stream.write(bytes, 0, size) }
    }

    override fun finish() {
        encoder.finish { bytes, size, _ -> stream.write(bytes, 0, size) }
    }

    override fun close() {
        runCatching { encoder.close() }
        runCatching { stream.close() }
    }
}

/**
 * M4A：和 AAC 同一个编码器，但用 MediaMuxer 装进 MPEG-4 容器。
 * 轨道号要等编码器吐出输出格式之后才拿得到，所以首个块之后才开始写。
 */
internal class M4aTargetEncoder(
    file: File,
    sampleRateHz: Int,
    channels: Int,
    bitrateKbps: Int,
) : AudioTargetEncoder {

    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val encoder = MediaCodecAudioEncoder(MimeTypes.AAC, sampleRateHz, channels, bitrateKbps)
    private val muxerInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var started = false

    override fun write(samples: ShortArray, count: Int) {
        encoder.encode(samples, count, ::emit)
    }

    override fun finish() {
        encoder.finish(::emit)
    }

    private fun emit(bytes: ByteArray, size: Int, presentationTimeUs: Long) {
        if (size <= 0) return
        if (trackIndex < 0) {
            // 没有输出格式就没法建轨道。正常流程里 INFO_OUTPUT_FORMAT_CHANGED
            // 一定先于任何输出块，这里拿不到说明编码器状态不对，不能静默丢数据
            val format = encoder.outputFormat
                ?: throw ExportFailureException("M4A 编码器没有给出音频格式，无法写入轨道")
            trackIndex = muxer.addTrack(format)
            muxer.start()
            started = true
        }
        muxerInfo.offset = 0
        muxerInfo.size = size
        muxerInfo.presentationTimeUs = presentationTimeUs
        muxerInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
        muxer.writeSampleData(trackIndex, ByteBuffer.wrap(bytes), muxerInfo)
    }

    override fun close() {
        runCatching { encoder.close() }
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }
}
