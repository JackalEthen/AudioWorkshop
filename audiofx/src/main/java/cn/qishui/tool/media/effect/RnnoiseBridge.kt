package cn.qishui.tool.media.effect

/**
 * RNNoise（Xiph，BSD-3）降噪桥。模型已静态编进 native 库，不需要运行时加载权重。
 * RNNoise 固定吃 48kHz 单声道 float，重采样在调用方做。
 */
class RnnoiseBridge private constructor(private var handle: Long) : AutoCloseable {

    val isReady: Boolean
        get() = handle > 0L

    /** 就地处理 [samples]，不足一帧的部分会被忽略，返回末帧语音概率。 */
    fun process(samples: FloatArray): Float {
        if (handle <= 0L || samples.isEmpty()) return -1f
        return nativeProcess(handle, samples, samples.size)
    }

    override fun close() {
        if (handle > 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        // 必须先加载库，后面才能调 native 方法
        init {
            System.loadLibrary("mp3lame_jni")
        }

        /** 48kHz，每帧 480 采样 = 10ms。 */
        const val SAMPLE_RATE = 48_000

        val FRAME_SIZE: Int = nativeFrameSize()

        fun create(): RnnoiseBridge = RnnoiseBridge(nativeCreate())

        @JvmStatic
        private external fun nativeCreate(): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeProcess(handle: Long, samples: FloatArray, length: Int): Float

        @JvmStatic
        private external fun nativeFrameSize(): Int
    }
}
