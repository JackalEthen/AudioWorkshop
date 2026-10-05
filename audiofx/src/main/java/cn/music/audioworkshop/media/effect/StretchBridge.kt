package cn.music.audioworkshop.media.effect

/**
 * Signalsmith Stretch 桥（MIT）。变速 + 变调一次完成，音质比自研 WSOLA 好一个档次。
 */
class StretchBridge private constructor(private var ok: Boolean) {

    init {
        // Signalsmith 和 LAME、RNNoise 编在同一个 so 里，所以加载的是同一个库名。
        // 之前这里没有 loadLibrary，nativeProcess 第一次被调用时才会抛
        // UnsatisfiedLinkError —— 而它被 PcmEffects 的 runCatching 一路吞掉，
        // 表现就是「变速变调静默不生效」。
        runCatching { System.loadLibrary(NATIVE_LIB) }
            .onFailure { error ->
                android.util.Log.e("QishuiSpeedPitch", "加载 $NATIVE_LIB 失败", error)
            }
    }

    /**
     * 变速 + 变调。[output] 由调用方按最大长度预分配，返回实际写入的帧数，失败 -1。
     */
    fun process(
        pcm: ShortArray,
        frames: Int,
        channels: Int,
        sampleRateHz: Int,
        speed: Float,
        semitones: Float,
        output: ShortArray,
    ): Int {
        if (!ok || frames <= 0 || pcm.size < frames * channels) return -1
        return nativeProcess(pcm, frames, channels, sampleRateHz, speed, semitones, output)
    }

    companion object {
        /**
         * 最低 0.25x，输出最长是输入的 4 倍。
         *
         * 变速变调不改变总输出帧数倍率之外的额外开销，所以按实际最坏情况
         * （0.25x 且 +12 半音需要额外对齐帧）留一点余量即可。
         * 曾经按 4 倍整曲分配，一首三分钟的歌就是 100MB+ 的 ShortArray，
         * 光分配就够慢，拖慢每一次预览。
         */
        fun maxOutputSamples(frames: Int, channels: Int): Int = frames * 4 * channels + 8192

        fun create(): StretchBridge = StretchBridge(true)

        /** 与 CMake 里的 add_library 名一致。 */
        private const val NATIVE_LIB = "mp3lame_jni"

        @JvmStatic
        private external fun nativeProcess(
            pcm: ShortArray,
            frames: Int,
            channels: Int,
            sampleRateHz: Int,
            speed: Float,
            semitones: Float,
            output: ShortArray,
        ): Int
    }
}
