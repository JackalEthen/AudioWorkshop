package cn.music.audioworkshop.media.pcm

object PcmFormatRules {

    val SUPPORTED_SAMPLE_RATES = setOf(44_100, 48_000)
    val SUPPORTED_CHANNELS = setOf(1, 2)

    fun isSupported(sampleRateHz: Int, channels: Int): Boolean =
        sampleRateHz in SUPPORTED_SAMPLE_RATES && channels in SUPPORTED_CHANNELS

    fun requireSupported(sampleRateHz: Int, channels: Int, mime: String? = null): DecodedAudioFormat {
        if (sampleRateHz !in SUPPORTED_SAMPLE_RATES) {
            throw UnsupportedPcmFormatException("仅支持 44100/48000 采样率, 实际 $sampleRateHz (${mime.orEmpty()})")
        }
        if (channels !in SUPPORTED_CHANNELS) {
            throw UnsupportedPcmFormatException("仅支持单声道或立体声, 实际 $channels (${mime.orEmpty()})")
        }
        return DecodedAudioFormat(sampleRateHz = sampleRateHz, channels = channels)
    }
}
