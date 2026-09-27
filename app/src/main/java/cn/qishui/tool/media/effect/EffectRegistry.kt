package cn.qishui.tool.media.effect

/**
 * 一个效果 = 参数描述 + 纯运算。UI 靠 [params] 自动生成滑杆，
 * 执行靠 [apply]，两边都不需要再为单个功能写代码。
 */
data class EffectDefinition(
    val id: String,
    val label: String,
    val params: List<EffectParam> = emptyList(),
    val multiTrack: Boolean = false,
    val apply: (List<PcmBuffer>, Map<String, Float>) -> PcmBuffer,
) {
    fun defaults(): Map<String, Float> = params.associate { it.id to it.default }
}

object EffectRegistry {

    private val percent = EffectParam("mix", "混合", 0f, 1f, 0.3f)
    private val delay = EffectParam("delayMs", "延迟", 20f, 1500f, 250f, step = 1f, unit = "ms")

    private val all: List<EffectDefinition> = listOf(
        EffectDefinition("reverse", "音频倒放") { buffers, _ ->
            PcmEffects.reverse(buffers.first())
        },
        EffectDefinition("invert", "反转相位") { buffers, _ ->
            PcmEffects.invertPhase(buffers.first())
        },
        EffectDefinition(
            "echo",
            "回声效果",
            params = listOf(delay, EffectParam("feedback", "反馈", 0f, 0.9f, 0.45f), percent),
        ) { buffers, values ->
            PcmEffects.echo(
                buffers.first(),
                values.getFloat("delayMs", 250f),
                values.getFloat("feedback", 0.45f),
                values.getFloat("mix", 0.3f),
            )
        },
        EffectDefinition(
            "choir",
            "合唱效果",
            params = listOf(EffectParam("spreadMs", "展宽", 5f, 80f, 25f, step = 1f, unit = "ms"), percent),
        ) { buffers, values ->
            PcmEffects.choir(
                buffers.first(),
                values.getFloat("spreadMs", 25f),
                values.getFloat("mix", 0.3f),
            )
        },
        EffectDefinition(
            "reverb",
            "混响",
            params = listOf(EffectParam("roomSize", "空间", 0f, 1f, 0.5f), percent),
        ) { buffers, values ->
            PcmEffects.reverb(
                buffers.first(),
                values.getFloat("roomSize", 0.5f),
                values.getFloat("mix", 0.3f),
            )
        },
        EffectDefinition(
            "equalizer",
            "均衡器",
            params = listOf(
                EffectParam("lowDb", "低频", -12f, 12f, 0f, step = 0.5f, unit = "dB"),
                EffectParam("midDb", "中频", -12f, 12f, 0f, step = 0.5f, unit = "dB"),
                EffectParam("highDb", "高频", -12f, 12f, 0f, step = 0.5f, unit = "dB"),
            ),
        ) { buffers, values ->
            PcmEffects.equalizer(
                buffers.first(),
                values.getFloat("lowDb", 0f),
                values.getFloat("midDb", 0f),
                values.getFloat("highDb", 0f),
            )
        },
        EffectDefinition(
            "stereo_split",
            "立体声分离",
            params = listOf(EffectParam("keep", "保留", 0f, 2f, 1f, step = 1f)),
        ) { buffers, values ->
            PcmEffects.stereoSplit(buffers.first(), values.getFloat("keep", 1f).toInt())
        },
        EffectDefinition(
            "stereo_widen",
            "立体声环绕",
            params = listOf(EffectParam("width", "宽度", 0f, 1f, 0.5f)),
        ) { buffers, values ->
            PcmEffects.stereoWiden(buffers.first(), values.getFloat("width", 0.5f))
        },
        EffectDefinition(
            "stereo_mix",
            "立体声合成",
            params = listOf(EffectParam("pan", "声像", -1f, 1f, 0f)),
        ) { buffers, values ->
            PcmEffects.stereoMix(buffers.first(), values.getFloat("pan", 0f))
        },
        EffectDefinition(
            "silence_trim",
            "去除头尾",
            params = listOf(
                EffectParam("thresholdDb", "静音阈值", -60f, 0f, -45f, step = 1f, unit = "dB"),
                EffectParam("keepMs", "保留静音", 0f, 2000f, 200f, step = 10f, unit = "ms"),
            ),
        ) { buffers, values ->
            PcmEffects.trimSilence(
                buffers.first(),
                values.getFloat("thresholdDb", -45f),
                values.getFloat("keepMs", 200f),
            )
        },
        EffectDefinition(
            "radio_fx",
            "收音机音效",
            params = listOf(EffectParam("amount", "强度", 0f, 1f, 0.5f)),
        ) { buffers, values ->
            PcmEffects.radioFx(buffers.first(), values.getFloat("amount", 0.5f))
        },
        EffectDefinition(
            "repair",
            "音频修复",
            params = listOf(EffectParam("strength", "力度", 0f, 1f, 0.6f)),
        ) { buffers, values ->
            PcmEffects.repair(buffers.first(), values.getFloat("strength", 0.6f))
        },
        EffectDefinition(
            "convert",
            "格式转换",
            params = listOf(
                EffectParam("sampleRate", "采样率", 44_100f, 48_000f, 44_100f, step = 100f, unit = "Hz"),
                EffectParam("mono", "单声道", 0f, 1f, 0f, step = 1f),
            ),
        ) { buffers, values ->
            val rate = values.getFloat("sampleRate", 44_100f).toInt()
            val converted = buffers.first().let { if (rate == it.sampleRateHz) it else it.resampled(rate) }
            if (values.getFloat("mono", 0f) >= 1f) converted.toMono() else converted
        },
        EffectDefinition(
            "mix",
            "混音",
            multiTrack = true,
            params = listOf(EffectParam("gainDb", "增益", -24f, 12f, 0f, step = 0.5f, unit = "dB")),
        ) { buffers, values ->
            mixBuffers(buffers, values.getFloat("gainDb", 0f))
        },
    )

    private val byId: Map<String, EffectDefinition> = all.associateBy { it.id }

    fun find(id: String): EffectDefinition? = byId[id]

    fun all(): List<EffectDefinition> = all

    private fun Map<String, Float>.getFloat(key: String, fallback: Float): Float = this[key] ?: fallback

    private fun mixBuffers(buffers: List<PcmBuffer>, gainDb: Float): PcmBuffer {
        require(buffers.isNotEmpty()) { "混音至少需要一首歌" }
        val gain = Math.pow(10.0, (gainDb / 20.0)).toFloat()
        val scaled = buffers.map { buffer ->
            if (gain == 1f) return@map buffer
            val out = ShortArray(buffer.samples.size)
            for (index in out.indices) {
                out[index] = (buffer.samples[index] * gain).toInt()
                    .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
            }
            PcmBuffer(buffer.sampleRateHz, buffer.channels, out)
        }
        val merged = scaled.reduce { acc, next -> acc.mixedWith(next) }
        return PcmBuffer.normalize(merged)
    }
}
