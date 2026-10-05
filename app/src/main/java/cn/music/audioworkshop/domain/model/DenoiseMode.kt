package cn.music.audioworkshop.domain.model

/**
 * 降噪方式。
 *
 * [NONE] 不处理。
 *
 * [GENERAL] 按频段压制：把 [DenoiseLowHz][DEFAULT_LOW_HZ] 以下和
 * [DEFAULT_HIGH_HZ] 以上的成分压掉，中间的人声/主体保留。适合环境噪声比较宽的录音，
 * 比如马路、风扇、空调这类持续低频底噪。
 *
 * [SPEECH] RNNoise 语音降噪：靠模型判断什么算人声、什么算噪声，只压后者。
 * 适合人声录音和采访 —— 这类场景噪声和人声在频谱上混得很近，
 * 按频段切会把辅音和齿音一起削掉，而语音模型不会。
 * 因此这一模式不暴露频率参数。
 */
enum class DenoiseMode(val label: String, val caption: String) {
    NONE("不降噪", "保持原样"),
    GENERAL("通用降噪", "压制低频底噪和高频嘶声，保留中间频段"),
    SPEECH("录音降噪", "针对人声录音和采访，只压噪声不动人声"),
    ;

    companion object {
        /**
         * 通用降噪默认起始频率 80Hz。
         *
         * 80Hz 以下几乎只有隆隆声和震动，手机录音的握持噪声、风扇、
         * 空调外机都在这里。人声的基频低到 85Hz（男声），压到 80Hz
         * 以下不会碰到说话声。
         */
        const val DEFAULT_LOW_HZ = 80

        /**
         * 通用降噪默认结束频率 8kHz。
         *
         * 8kHz 以上是齿音和嘶声区，但人声的辅音（s、sh、f）也在这一带，
         * 所以不能压太低 —— 压到 8kHz 能压掉持续的「嘶」底噪，
         * 又留着齿音的清晰度。
         */
        const val DEFAULT_HIGH_HZ = 8_000

        /** 频率参数的可用范围 Hz。低于 20 高于 20k 都对音频没有意义。 */
        const val MIN_FREQ_HZ = 20
        const val MAX_FREQ_HZ = 20_000
    }
}