package cn.qishui.tool.media.export

class LameEncodeException(message: String) : Exception(message)

class LameNativeBridge : AutoCloseable {

    private var handle: Long = 0L
    private var sampleRate: Int = 0
    private var channels: Int = 0

    val isOpen: Boolean
        get() = handle > 0L

    fun init(sampleRateHz: Int, channelCount: Int, vbrQuality: Int) {
        require(sampleRateHz == 44100 || sampleRateHz == 48000) { "sampleRate 仅支持 44100 或 48000: $sampleRateHz" }
        require(channelCount == 1 || channelCount == 2) { "channels 仅支持 1 或 2: $channelCount" }
        require(vbrQuality in 0..9) { "vbrQuality 必须在 0..9: $vbrQuality" }
        check(handle == 0L) { "already initialized" }
        val created = nativeInit(sampleRateHz, channelCount, vbrQuality)
        if (created <= 0L) throw LameEncodeException("LAME 初始化失败, code=$created")
        handle = created
        sampleRate = sampleRateHz
        channels = channelCount
    }

    fun encode(samples: ShortArray, samplesPerChannel: Int): ByteArray {
        val active = requireOpen()
        require(samplesPerChannel > 0) { "samplesPerChannel 必须为正数: $samplesPerChannel" }
        require(samples.size >= samplesPerChannel * channels) {
            "PCM 长度不足: 需要 ${samplesPerChannel * channels}, 实际 ${samples.size}"
        }
        val out = ByteArray(samplesPerChannel * channels * 2 + OUTPUT_MARGIN_BYTES)
        val written = nativeEncode(active, samples, samplesPerChannel, out)
        if (written < 0) throw LameEncodeException("LAME 编码失败, code=$written")
        if (written == 0) return EMPTY
        return out.copyOf(written)
    }

    fun flush(): ByteArray {
        val active = requireOpen()
        val out = ByteArray(FLUSH_MARGIN_BYTES)
        val written = nativeFlush(active, out)
        if (written < 0) throw LameEncodeException("LAME flush 失败, code=$written")
        if (written == 0) return EMPTY
        return out.copyOf(written)
    }

    override fun close() {
        if (handle <= 0L) {
            handle = 0L
            return
        }
        val active = handle
        handle = 0L
        nativeClose(active)
    }

    private fun requireOpen(): Long {
        check(handle > 0L) { "not initialized" }
        return handle
    }

    private external fun nativeInit(sampleRate: Int, channels: Int, vbrQuality: Int): Long

    private external fun nativeEncode(owner: Long, pcm: ShortArray, samplesPerChannel: Int, out: ByteArray): Int

    private external fun nativeFlush(owner: Long, out: ByteArray): Int

    private external fun nativeClose(owner: Long)

    private companion object {
        const val OUTPUT_MARGIN_BYTES = 8192
        const val FLUSH_MARGIN_BYTES = 8192
        val EMPTY = ByteArray(0)

        init {
            System.loadLibrary("mp3lame_jni")
        }
    }
}
