package cn.music.audioworkshop.domain.model

data class WaveformPeaks(
    val startUs: Long,
    val endUs: Long,
    val min: FloatArray,
    val max: FloatArray,
)
