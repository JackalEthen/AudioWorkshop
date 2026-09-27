package cn.qishui.tool.media.effect

/**
 * EBU R128 响度桥（libebur128，MIT）。峰值归一化不等于响度标准化，
 * 导出时按 LUFS 校准才能保证不同歌之间音量一致。
 */
class LoudnessBridge {

    /** 整体响度（LUFS），测不出时返回 null。 */
    fun measureLufs(pcm: ShortArray, frames: Int, channels: Int, sampleRateHz: Int): Float? {
        if (frames <= 0 || pcm.size < frames * channels) return null
        val value = nativeMeasureLufs(pcm, frames, channels, sampleRateHz)
        return if (value <= -999f) null else value
    }

    /** 就地按目标 LUFS 增益，返回实际施加的 dB。 */
    fun normalizeTo(
        pcm: ShortArray,
        frames: Int,
        channels: Int,
        sampleRateHz: Int,
        targetLufs: Float,
    ): Float {
        if (frames <= 0 || pcm.size < frames * channels) return 0f
        return nativeNormalize(pcm, frames, channels, sampleRateHz, targetLufs)
    }

    companion object {
        /** 主流流媒体的目标响度，Spotify/YouTube 都在 -14 LUFS 附近。 */
        const val TARGET_LUFS = -14.0f

        @JvmStatic
        private external fun nativeMeasureLufs(
            pcm: ShortArray,
            frames: Int,
            channels: Int,
            sampleRateHz: Int,
        ): Float

        @JvmStatic
        private external fun nativeNormalize(
            pcm: ShortArray,
            frames: Int,
            channels: Int,
            sampleRateHz: Int,
            targetLufs: Float,
        ): Float
    }
}
