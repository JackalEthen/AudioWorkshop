package cn.qishui.tool.media.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MediaCodec 音频编码泵：喂 16-bit PCM，吐编码后的字节。
 *
 * 同步用法：每个 [encode] 把一块 PCM 塞进输入队列，再把能拿到的输出块交给 [onOutput]。
 * FLAC 走它是因为 MediaCodec 的 FLAC 输出本身就是完整 .flac 流；
 * AAC 靠它产出裸 AAC 访问单元，再由外面补 ADTS 头。
 */
internal class MediaCodecAudioEncoder(
    mimeType: String,
    private val sampleRateHz: Int,
    private val channels: Int,
    bitrate: Int,
) : AutoCloseable {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(mimeType)
    private val info = MediaCodec.BufferInfo()
    private val endOfStream = MediaCodec.BUFFER_FLAG_END_OF_STREAM

    /** Muxer 要拿它来建轨道，所以得暴露出去。 */
    var outputFormat: MediaFormat? = null
        private set

    private var finished = false
    private var presentationTimeUs = 0L

    /** 每帧的时长，AAC 一帧固定 1024 个采样。 */
    private val frameDurationUs: Long
        get() = 1_000_000L * SAMPLES_PER_AAC_FRAME / sampleRateHz

    init {
        val format = MediaFormat.createAudioFormat(mimeType, sampleRateHz, channels).apply {
            if (bitrate > 0) setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            if (mimeType != MimeTypes.FLAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            }
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encode(samples: ShortArray, count: Int, onOutput: (ByteArray, Int, Long) -> Unit) {
        if (finished || count <= 0) return
        val index = dequeueInput()
        val buffer = codec.getInputBuffer(index)
            ?: throw ExportFailureException("编码器没有可写的输入缓冲区")
        buffer.clear()
        buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(samples, 0, count)
        codec.queueInputBuffer(index, 0, count * Short.SIZE_BYTES, presentationTimeUs, 0)
        presentationTimeUs += count.toLong() * 1_000_000L / (sampleRateHz * channels)
        drain(false, onOutput)
    }

    /** 收尾：喂一个 EOS 标记，把编码器里剩下的块全掏空。 */
    fun finish(onOutput: (ByteArray, Int, Long) -> Unit) {
        if (finished) return
        finished = true
        val index = dequeueInput()
        codec.queueInputBuffer(index, 0, 0, presentationTimeUs, endOfStream)
        drain(true, onOutput)
    }

    /**
     * 等一个可用的输入槽。
     *
     * 拿不到就抛异常，不能静默跳过：跳过意味着这一块 PCM 直接丢了，
     * 导出会缺一段而且没人会发现。deadline 给到 10 秒，真拿不到说明编码器卡死了。
     */
    private fun dequeueInput(): Int {
        val deadline = System.currentTimeMillis() + INPUT_TIMEOUT_MS
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) return index
            if (System.currentTimeMillis() > deadline) {
                throw ExportFailureException("编码器长时间不响应输入，已中止以免丢音频")
            }
        }
    }

    private fun drain(untilEos: Boolean, onOutput: (ByteArray, Int, Long) -> Unit) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEos) TIMEOUT_US else 0L)
            when {
                // TRY_AGAIN_LATER 时 info 不会被刷新，不能拿它的 flags 判断 EOS
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    if (System.currentTimeMillis() > deadline) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = codec.outputFormat
                }

                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(bytes)
                        onOutput(bytes, info.size, info.presentationTimeUs)
                    }
                    val eos = info.flags and endOfStream != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }

    override fun close() {
        runCatching {
            codec.stop()
            codec.release()
        }
    }

    private companion object {
        const val SAMPLES_PER_AAC_FRAME = 1024
        const val MAX_INPUT_SIZE = 64 * 1024
        const val TIMEOUT_US = 10_000L
        const val TIMEOUT_MS = 5_000L
        const val INPUT_TIMEOUT_MS = 10_000L
    }
}

/** 编码器用的 MIME 常量，MediaCodec 的字符串散在各处容易写错。 */
internal object MimeTypes {
    const val AAC = MediaFormat.MIMETYPE_AUDIO_AAC
    const val FLAC = "audio/flac"
}

/**
 * 给裸 AAC 访问单元补 ADTS 头，输出才是能直接播放的 .aac。
 *
 * header 7 字节：syncword(12) id(1) layer(2) protection(1) profile(2) samplingIdx(4) private(1)
 * channelCfg(3) originality(1) home(1) copyrightId(1) copyrightStart(1) frameLen(13) bufferFull(11) numBlocks(2)
 */
internal fun adtsHeader(payloadSize: Int, sampleRateHz: Int, channels: Int): ByteArray {
    val rateIndex = ADTS_RATES.indexOf(sampleRateHz).takeIf { it >= 0 } ?: ADTS_RATES.indexOf(44_100)
    val header = ByteArray(7)
    var at = 0
    fun put(value: Int, bits: Int) {
        for (bit in bits - 1 downTo 0) {
            val mask = 1 shl bit
            val byte = header[at]
            header[at] = if (value and mask != 0) (byte.toInt() or 0x01).toByte() else (byte.toInt() and 0xFE).toByte()
            at++
            if (at == header.size) at = 0
        }
    }
    put(0xFFF, 12) // syncword
    put(0, 1) // MPEG-4
    put(0, 2) // layer
    put(1, 1) // protection absent
    put(AAC_LC_PROFILE, 2) // profile = AAC-LC
    put(rateIndex, 4)
    put(0, 1) // private
    put(channels, 3) // channel configuration
    put(0, 1) // originality
    put(0, 1) // home
    put(0, 1) // copyright id
    put(0, 1) // copyright start
    put(payloadSize + header.size, 13) // frame length
    put(0x7FF, 11) // buffer fullness = VBR
    put(0, 2) // num raw data blocks - 1
    return header
}

private const val AAC_LC_PROFILE = 1
private val ADTS_RATES = listOf(
    96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000,
)
