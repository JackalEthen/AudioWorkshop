package cn.qishui.tool.media.effect

/**
 * Signalsmith Stretch 桥（MIT）。变速 + 变调一次完成，音质比自研 WSOLA 好一个档次。
 */
class StretchBridge private constructor(private var ok: Boolean) {

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
        /** 最低 0.25x，输出最长是输入的 4 倍，再留一点余量。 */
        fun maxOutputSamples(frames: Int, channels: Int): Int = frames * 4 * channels + 8192

        fun create(): StretchBridge = StretchBridge(true)

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
