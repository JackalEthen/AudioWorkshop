package cn.music.audioworkshop.media.export

import android.media.MediaCodec
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
    bitrateKbps: Int,
) : AutoCloseable {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(mimeType)
    private val info = MediaCodec.BufferInfo()
    private val endOfStream = MediaCodec.BUFFER_FLAG_END_OF_STREAM

    /** Muxer 要拿它来建轨道，所以得暴露出去。 */
    var outputFormat: MediaFormat? = null
        private set

    private var finished = false
    private var presentationTimeUs = 0L

    init {
        val format = MediaFormat.createAudioFormat(mimeType, sampleRateHz, channels).apply {
            // 单位换算：入参是 kbps，MediaFormat.KEY_BIT_RATE 要的是 **bps**。
            //
            // 以前直接传 kbps，128 被当成 128 bps 发给编码器，远低于设备下限
            // （c2.android.aac.encoder 是 8000-960000 bps），于是 configure 阶段
            // 报 BAD_VALUE，AAC 和 M4A 两个格式全部导不出。
            // MP3 走 LAME（lame_set_brate 收 kbps）所以一直是对的，掩盖了这个单位问题。
            if (bitrateKbps > 0) setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            // MAX_INPUT_SIZE 对所有格式都要设，包括 FLAC。
            // 不设的话部分设备的编码器输入缓冲区会小于调用方的块大小，
            // 直接 BufferOverflowException。
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            if (mimeType == MimeTypes.FLAC) {
                // FLAC 的 STREAMINFO 里要写位深。缺了它容器读不出时长，
                // 表现是导出了却「无法解析」。
                setInteger(KEY_BITS_PER_SAMPLE, BITS_PER_SAMPLE)
            }
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encode(samples: ShortArray, count: Int, onOutput: (ByteArray, Int, Long) -> Unit) {
        if (finished || count <= 0) return
        val index = dequeueInput()
        val buffer = codec.getInputBuffer(index)
            ?: throw ExportFailureException("编码器没有可写入的输入缓冲区")
        buffer.clear()
        val shortBuffer = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        // 容量不够要立刻报错，不能直接 put —— 那会 BufferOverflowException
        if (shortBuffer.remaining() < count) {
            throw ExportFailureException(
                "编码器输入缓冲区不足：需要 $count 帧，只有 ${shortBuffer.remaining()} 帧"
            )
        }
        shortBuffer.put(samples, 0, count)
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
        // EOS 之后是否还有输出产出。
        //
        // FLAC 的软件编码器（c2.android.flac.encoder）收到 EOS **只改输出格式，
        // 不吐数据块**，而且不给最后一个 buffer 打 BUFFER_FLAG_END_OF_STREAM。
        //
        // 之前这里用「EOS 后是否产出过数据」当成功判据（producedAfterEos），
        // 而纯软件 FLAC 编码器一个数据块都不给，该标记恒为 false ——
        // 于是每次转 FLAC 都必然抛「编码器在 5 秒内没有输出结束标记」。
        // 判据换成「拿到过输出格式」：格式变了说明编码器确实起来了，
        // 再加上整个 encode() 期间累计写入过字节，数据就是完整的。
        var producedAnyOutput = false
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEos) TIMEOUT_US else 0L)
            when {
                // TRY_AGAIN_LATER 时 info 不会被刷新，不能拿它的 flags 判断 EOS
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    // 超时不能静默 return —— 那会产出尾部被截断的文件。
                    // 只有「编码器确实工作过」才允许按成功收尾，否则必须报错。
                    if (System.currentTimeMillis() > deadline) {
                        if (producedAnyOutput || outputFormat != null) return
                        throw ExportFailureException(
                            "编码器在 ${TIMEOUT_MS / 1000} 秒内没有输出结束标记，导出中止"
                        )
                    }
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
                        producedAnyOutput = true
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
        const val MAX_INPUT_SIZE = 64 * 1024
        const val BITS_PER_SAMPLE = 16
        /** MediaFormat 没有这个常量，但 FLAC 编码器认这个 key。 */
        const val KEY_BITS_PER_SAMPLE = "bits-per-sample"
        const val TIMEOUT_US = 10_000L
        const val TIMEOUT_MS = 5_000L
        const val INPUT_TIMEOUT_MS = 10_000L
    }
}

/** 编码器用的 MIME 常量，MediaCodec 的字符串散在各处容易写错。 */
internal object MimeTypes {
    const val FLAC = "audio/flac"
}

