package cn.music.audioworkshop.domain.media

/**
 * 导出容器格式。
 *
 * [WAV] / [FLAC] 走 PCM 直写，不经过有损编码，也没有 ID3 —— 封面和歌词会丢。
 * [MP3] 走 LAME CBR，标签完整。
 *
 * AAC 与 M4A 曾经在这里，但两者都依赖 MediaCodec 的 AAC 编码器：
 * 那台设备上 `MediaCodec.createEncoderByType` 会永久阻塞（Codec2 组件查询挂死），
 * 表现为点导出没反应。已从目标格式里移除，需要时再接软件编码器。
 */
enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    val label: String,
    /** 无损格式没有「比特率」这个概念，界面上要置灰。 */
    val lossless: Boolean = false,
) {
    MP3("mp3", "audio/mpeg", "mp3"),
    WAV("wav", "audio/wav", "wav", lossless = true),
    FLAC("flac", "audio/flac", "flac", lossless = true),
    ;

    val supportsBitrate: Boolean
        get() = !lossless

    /**
     * 有没有 ID3 可写。WAV / FLAC 是裸流，塞标签会破坏文件结构。
     * FLAC 的 Vorbis 注释本可以写，但那是另一套容器逻辑，目前没做。
     */
    val supportsTags: Boolean
        get() = this == MP3

    /**
     * 本格式真正支持的采样率（Hz），空集表示「不限制」。
     *
     * 界面上只能从这个列表里选，编码器启动前再校验一次。
     *
     * 为什么每个格式不一样：
     * - MP3 走 LAME，它的合法采样率只有 8000/11025/12000/16000/22050/24000/32000/44100/48000，
     *   且 8000/12000 在部分 LAME 版本上会退回 16000，稳妥起见只放开 16000 及以上。
     * - WAV / FLAC 是 PCM 直写，任意采样率都能写，不做限制。
     */
    val supportedSampleRates: List<Int>
        get() = when (this) {
            MP3 -> listOf(16_000, 22_050, 24_000, 32_000, 44_100, 48_000)
            WAV, FLAC -> ALL_SAMPLE_RATES
        }

    /**
     * 本格式支持的比特率（kbps），空列表表示「不适用」（无损格式）。
     *
     * 界面只显示这里列出的值，避开「给无损格式选码率」和「给 MP3 选 8192」这类
     * 编码器直接拒绝、但界面又拦不住导致导出失败的组合。
     */
    val supportedBitrates: List<Int>
        get() = when (this) {
            WAV, FLAC -> emptyList()
            MP3 -> listOf(64, 96, 128, 160, 192, 224, 256, 320)
        }

    /**
     * 校验一组导出参数，非法时给出能看懂的中文原因。
     * 编码器创建前必须过这一关，别让用户等导出失败才发现选错了。
     */
    fun validate(sampleRateHz: Int, bitrateKbps: Int, channels: Int): String? = when {
        sampleRateHz > 0 && sampleRateHz !in supportedSampleRates ->
            "${label.uppercase()} 不支持 $sampleRateHz Hz，可选：${supportedSampleRates.joinToString(" / ")}"

        bitrateKbps > 0 && bitrateKbps !in supportedBitrates ->
            "${label.uppercase()} 不支持 $bitrateKbps kbps，可选：${supportedBitrates.joinToString(" / ")}"

        channels !in 1..2 -> "只支持单声道或双声道，当前 $channels"
        else -> null
    }

    companion object {
        /** 不限制采样率时用这个全集，作为下拉的可选项来源。 */
        val ALL_SAMPLE_RATES = listOf(
            8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000, 96_000,
        )

        fun fromName(name: String?): ExportFormat =
            entries.firstOrNull { it.name == name } ?: MP3
    }
}
