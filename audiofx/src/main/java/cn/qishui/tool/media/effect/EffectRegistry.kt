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
    val needsRnnoise: Boolean = false,
    /** 内置出厂预设，卡标题会显示当前预设名。用户自建方案不落库。 */
    val preset: EffectPresetCatalog = EffectPresetCatalog.EMPTY,
    /**
     * 多输出效果（立体声分离产出左右两个文件）。默认 null = 单输出，
     * 加这个字段就是为了不动其他 19 个效果的单输出链路。
     */
    val applyMulti: ((List<PcmBuffer>, Map<String, Float>, RnnoiseBridge?) -> List<PcmBuffer>)? = null,
    /** 参数是整数档位时用双列步进块渲染，而不是滑杆。 */
    val stepperTiles: Boolean = false,
    /** 左右声道各一个独立音轨槽（立体声合成），UI 渲染成两个选歌卡。 */
    val leftRightSlots: Boolean = false,
    /**
     * 这一页要不要显示「格式」下拉行。
     * 格式是导出属性，但对倒放、混响这类效果来说没有意义，露出来只是噪音。
     * 只有功能本身就关心产物格式的才打开。
     */
    val showFormatRow: Boolean = false,
    /**
     * 分段处理（分段变调、分段修改音量）。每段带自己的参数，
     * null = 不分段，UI 就不会渲染片段列表。
     */
    val applySegmented: ((PcmBuffer, List<SegmentRange>) -> PcmBuffer)? = null,
    val apply: (List<PcmBuffer>, Map<String, Float>, RnnoiseBridge?) -> PcmBuffer,
) {
    fun defaults(): Map<String, Float> = params.associate { it.id to it.default }

    val isMultiOutput: Boolean
        get() = applyMulti != null
}

data class EffectPreset(
    val name: String,
    val values: Map<String, Float>,
)

data class EffectPresetCatalog(
    val presets: List<EffectPreset> = emptyList(),
) {
    fun valuesOf(name: String): Map<String, Float>? =
        presets.firstOrNull { it.name == name }?.values

    companion object {
        val EMPTY = EffectPresetCatalog()
    }
}

/** 所有效果都能限定生效区间，用它覆盖"分段变调""分段修改音量"这类需求。 */
val RANGE_PARAMS = listOf(
    EffectParam("startSec", "起始秒", 0f, 600f, 0f, step = 0.5f, unit = "s"),
    EffectParam("endSec", "结束秒", 0f, 600f, 0f, step = 0.5f, unit = "s"),
)

object EffectRegistry {

    private val percent = EffectParam("mix", "混合", 0f, 1f, 0.3f)
    private val delay = EffectParam("delayMs", "延迟", 20f, 1500f, 250f, step = 1f, unit = "ms")

    /** 8 段均衡的出厂曲线，顺序同 PcmEffects.EQ_BAND_LABELS。 */
    private val EQ_PRESETS = EffectPresetCatalog(
        listOf(
            EffectPreset("默认", FloatArray(8) { 0f }.toEqMap()),
            EffectPreset("流行", floatArrayOf(3f, 2f, 0f, -1f, -1f, 1f, 3f, 4f).toEqMap()),
            EffectPreset("摇滚", floatArrayOf(5f, 4f, 2f, 0f, -1f, 1f, 4f, 5f).toEqMap()),
            EffectPreset("人声", floatArrayOf(-2f, -1f, 2f, 4f, 4f, 2f, 0f, -1f).toEqMap()),
            EffectPreset("低语", floatArrayOf(6f, 5f, 3f, 1f, 0f, -1f, -2f, -3f).toEqMap()),
        ),
    )

    private fun FloatArray.toEqMap(): Map<String, Float> =
        withIndex().associate { (index, value) -> "band$index" to value }

    /** 收音机四种模式：频响越窄越像老式喇叭，noise 是额外加的底噪。 */
    private data class RadioMode(val amount: Float, val lowHz: Float, val highHz: Float, val noise: Float)

    private val RADIO_MODES = listOf(
        RadioMode(amount = 0.45f, lowHz = 300f, highHz = 3_400f, noise = 0f),
        RadioMode(amount = 0.65f, lowHz = 420f, highHz = 2_800f, noise = 0.012f),
        RadioMode(amount = 0.9f, lowHz = 500f, highHz = 2_400f, noise = 0.03f),
        RadioMode(amount = 0.25f, lowHz = 250f, highHz = 5_000f, noise = 0f),
    )

    /** 降噪的保留频段预设：起始频率以下和结束频率以上都会被滤掉。 */
    private val DENOISE_BAND_PRESETS = EffectPresetCatalog(
        listOf(
            EffectPreset("全频", denoiseBand(20f, 20_000f)),
            EffectPreset("人声", denoiseBand(200f, 3_000f)),
            EffectPreset("语音", denoiseBand(150f, 3_500f)),
            EffectPreset("电话", denoiseBand(300f, 3_400f)),
            EffectPreset("宽频", denoiseBand(80f, 8_000f)),
        ),
    )

    private fun denoiseBand(low: Float, high: Float): Map<String, Float> =
        mapOf("lowHz" to low, "highHz" to high)

    /** 格式转换可选的采样率和比特率，顺序就是界面下拉的顺序。 */
    val CONVERT_RATES = listOf(8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)
    val CONVERT_BITRATES = listOf(64, 96, 128, 160, 192, 224, 256, 320)

    /** 音频修复三档强度：轻度 / 标准 / 强力。 */
    private val REPAIR_STRENGTH = floatArrayOf(0.35f, 0.6f, 0.9f)

    private val all: List<EffectDefinition> = listOf(
        EffectDefinition("reverse", "音频倒放") { buffers, _, _ ->
            PcmEffects.reverse(buffers.first())
        },
        EffectDefinition(
            "invert",
            "反转相位",
            params = listOf(
                EffectParam(
                    id = "channel",
                    label = "反转声道",
                    min = 0f,
                    max = 2f,
                    default = 0f,
                    step = 1f,
                    choices = listOf("全声道", "左声道", "右声道"),
                ),
            ),
        ) { buffers, values, _ ->
            PcmEffects.invertPhase(buffers.first(), values.getFloat("channel", 0f).toInt())
        },
        EffectDefinition(
            "echo",
            "回声效果",
            params = listOf(
                EffectParam(
                    id = "kind",
                    label = "回声类型",
                    min = 0f,
                    max = 1f,
                    default = 0f,
                    step = 1f,
                    choices = listOf("山谷回响", "两倍叠加"),
                    caption = "选择要应用的回声效果",
                ),
                delay,
                EffectParam("feedback", "反馈", 0f, 0.9f, 0.45f),
                percent,
            ) + RANGE_PARAMS,
        ) { buffers, values, _ ->
            PcmEffects.echo(
                buffers.first(),
                values.getFloat("delayMs", 250f),
                values.getFloat("feedback", 0.45f),
                values.getFloat("mix", 0.3f),
                values.getFloat("kind", 0f).toInt(),
            )
        },
        EffectDefinition(
            "choir",
            "合唱效果",
            params = listOf(
                EffectParam(
                    id = "kind",
                    label = "合唱类型",
                    min = 0f,
                    max = 2f,
                    default = 0f,
                    step = 1f,
                    choices = listOf("两人合唱", "三人合唱", "多人合唱"),
                    caption = "选择要模拟的合唱人数",
                ),
                EffectParam("spreadMs", "展宽", 5f, 80f, 25f, step = 1f, unit = "ms"),
                percent,
            ) + RANGE_PARAMS,
        ) { buffers, values, _ ->
            PcmEffects.choir(
                buffers.first(),
                values.getFloat("spreadMs", 25f),
                values.getFloat("mix", 0.3f),
                values.getFloat("kind", 0f).toInt(),
            )
        },
        EffectDefinition(
            "reverb",
            "混响",
            params = listOf(
                percent,
                EffectParam("roomSize", "空间大小", 0f, 1f, 0.5f),
                EffectParam("decay", "衰减时间", 0f, 1f, 0.6f),
                EffectParam("predelayMs", "预延迟", 0f, 200f, 0f, step = 5f, unit = "ms"),
                EffectParam("damping", "高频阻尼", 0f, 1f, 0.5f),
            ) + RANGE_PARAMS,
        ) { buffers, values, _ ->
            PcmEffects.reverb(
                buffers.first(),
                values.getFloat("roomSize", 0.5f),
                values.getFloat("mix", 0.3f),
                values.getFloat("decay", 0.6f),
                values.getFloat("predelayMs", 0f),
                values.getFloat("damping", 0.5f),
            )
        },
        EffectDefinition(
            "equalizer",
            "均衡器",
            // 8 段，标签和参考示例一致
            params = List(PcmEffects.EQ_BAND_LABELS.size) { index ->
                EffectParam("band$index", PcmEffects.EQ_BAND_LABELS[index], -12f, 12f, 0f, step = 0.5f, unit = "dB")
            } + RANGE_PARAMS,
            preset = EQ_PRESETS,
        ) { buffers, values, _ ->
            val gains = FloatArray(PcmEffects.EQ_BAND_LABELS.size) { index ->
                values.getFloat("band$index", 0f)
            }
            PcmEffects.equalizer(buffers.first(), gains)
        },
        EffectDefinition(
            "stereo_split",
            "立体声分离",
            params = RANGE_PARAMS,
            // 一次产出左右两个单声道文件，参考示例就是两行独立试听
            applyMulti = { buffers, _, _ -> PcmEffects.splitChannels(buffers.first()) },
        ) { buffers, _, _ ->
            PcmEffects.stereoSplit(buffers.first(), 1)
        },
        EffectDefinition(
            "stereo_widen",
            "立体声环绕",
            params = listOf(
                EffectParam(
                    id = "halfCircleSec",
                    label = "半圆环绕时间",
                    min = 1f,
                    max = 60f,
                    default = 5f,
                    step = 1f,
                    unit = "s",
                ),
                EffectParam(
                    id = "degrees",
                    label = "环绕幅度",
                    min = 1f,
                    max = 9f,
                    default = 5f,
                    step = 1f,
                    unit = "度",
                ),
            ) + RANGE_PARAMS,
            // 环绕幅度是 1-9 的整数档位，滑杆不合适，走双列步进块
            stepperTiles = true,
        ) { buffers, values, _ ->
            PcmEffects.stereoOrbit(
                buffers.first(),
                values.getFloat("halfCircleSec", 5f),
                values.getFloat("degrees", 5f),
            )
        },
        EffectDefinition(
            "stereo_mix",
            "立体声合成",
            // 左边一个音轨、右边一个音轨，各自独立选歌
            multiTrack = true,
            leftRightSlots = true,
        ) { buffers, _, _ ->
            val left = buffers.first()
            PcmEffects.stereoCompose(left, buffers.getOrNull(1) ?: left)
        },
        EffectDefinition(
            "silence_trim",
            "去除头尾",
            params = listOf(
                EffectParam("headMs", "去除头部", 0f, 600_000f, 3000f, step = 100f, unit = "ms"),
                EffectParam("tailMs", "去除尾部", 0f, 600_000f, 3000f, step = 100f, unit = "ms"),
            ),
        ) { buffers, values, _ ->
            PcmEffects.trimEnds(
                buffers.first(),
                values.getFloat("headMs", 3000f),
                values.getFloat("tailMs", 3000f),
            )
        },
        EffectDefinition(
            "radio_fx",
            "收音机音效",
            showFormatRow = true,
            params = listOf(
                EffectParam(
                    id = "mode",
                    label = "模式",
                    min = 0f,
                    max = 3f,
                    default = 0f,
                    step = 1f,
                    choices = listOf("稳定", "复古", "破音", "清亮"),
                ),
            ) + RANGE_PARAMS,
        ) { buffers, values, _ ->
            val mode = RADIO_MODES[values.getFloat("mode", 0f).toInt().coerceIn(0, 3)]
            PcmEffects.radioFx(buffers.first(), mode.amount, mode.lowHz, mode.highHz, mode.noise)
        },
        EffectDefinition(
            "repair",
            "音频修复",
            params = listOf(
                EffectParam(
                    id = "mode",
                    label = "修复强度",
                    min = 0f,
                    max = 2f,
                    default = 1f,
                    step = 1f,
                    choices = listOf("轻度", "标准", "强力"),
                    caption = "根据音频受损程度选择处理强度",
                ),
            ) + RANGE_PARAMS,
        ) { buffers, values, _ ->
            PcmEffects.repair(buffers.first(), REPAIR_STRENGTH[values.getFloat("mode", 1f).toInt().coerceIn(0, 2)])
        },
        EffectDefinition(
            "convert",
            "格式转换",
            showFormatRow = true,
            // 重采样和编码都发生在导出链路里（流式编码器），所以这里不做任何处理，
            // 参数只是把用户的选择传给 ExportJob。
            params = listOf(
                EffectParam(
                    id = "sampleRate",
                    label = "采样率",
                    min = 0f,
                    max = 8f,
                    default = 0f,
                    step = 1f,
                    choices = listOf("跟随源") + CONVERT_RATES.map { it.toString() },
                    asRow = true,
                ),
                EffectParam(
                    id = "bitrate",
                    label = "比特率",
                    min = 0f,
                    max = 7f,
                    default = 6f,
                    step = 1f,
                    choices = listOf("自动") + CONVERT_BITRATES.map { "$it" },
                    asRow = true,
                ),
                EffectParam(
                    id = "channels",
                    label = "声道",
                    min = 0f,
                    max = 2f,
                    default = 0f,
                    step = 1f,
                    choices = listOf("不变", "单声道", "立体声"),
                    asRow = true,
                ),
            ),
        ) { buffers, _, _ -> buffers.first() },
        EffectDefinition(
            "mix",
            "混音",
            multiTrack = true,
            params = listOf(
                EffectParam("crossfadeMs", "淡入淡出时间", 0f, 20_000f, 2000f, step = 100f, unit = "ms"),
                EffectParam("normalizeRates", "格式化音乐", 0f, 1f, 1f, step = 1f),
            ),
        ) { buffers, values, _ ->
            PcmEffects.concatWithCrossfade(
                buffers,
                values.getFloat("crossfadeMs", 2000f),
                values.getFloat("normalizeRates", 1f) >= 0.5f,
            )
        },
        EffectDefinition(
            "denoise",
            "降噪",
            showFormatRow = true,
            needsRnnoise = true,
            preset = DENOISE_BAND_PRESETS,
            stepperTiles = true,
            params = listOf(
                EffectParam(
                    id = "type",
                    label = "降噪类型",
                    min = 0f,
                    max = 1f,
                    default = 0f,
                    step = 1f,
                    choices = listOf("通用", "录音降噪"),
                    caption = "根据音频来源选择更合适的降噪方式。",
                ),
                EffectParam(
                    id = "mode",
                    label = "模式",
                    min = 0f,
                    max = 1f,
                    default = 0f,
                    step = 1f,
                    choices = listOf("稳定", "正常"),
                    asRow = true,
                ),
                EffectParam("lowHz", "起始频率", 20f, 2_000f, 200f, step = 10f, unit = "Hz"),
                EffectParam("highHz", "结束频率", 1_000f, 20_000f, 3_000f, step = 100f, unit = "Hz"),
            ) + RANGE_PARAMS,
        ) { buffers, values, rnnoise ->
            val low = values.getFloat("lowHz", 200f)
            val high = values.getFloat("highHz", 3_000f)
            // 模式只影响强度：稳定轻一点，正常按标准力度
            val amount = if (values.getFloat("mode", 0f) >= 0.5f) 1f else 0.55f
            if (values.getFloat("type", 0f) >= 0.5f) {
                val bridge = rnnoise ?: throw IllegalStateException("降噪模型未就绪")
                PcmEffects.denoise(buffers.first(), bridge, amount)
            } else {
                PcmEffects.bandLimit(buffers.first(), low, high, amount)
            }
        },
        EffectDefinition(
            "speed_pitch",
            "变速变调",
            params = listOf(
                // 参考示例用百分比表示音调：100% 是原调，一个半音约 ±5.9%
                EffectParam("pitchPct", "音调设置", 50f, 200f, 100f, step = 0.5f, unit = "%"),
                EffectParam("speedPct", "速度设置", 25f, 400f, 100f, step = 0.5f, unit = "%"),
            ) + RANGE_PARAMS,
        ) { buffers, values, _ ->
            val pitchPct = values.getFloat("pitchPct", 100f).coerceIn(50f, 200f)
            PcmEffects.speedPitch(
                buffers.first(),
                speed = values.getFloat("speedPct", 100f).coerceIn(25f, 400f) / 100f,
                semitones = PcmEffects.pitchPercentToSemitones(pitchPct),
            )
        },
        EffectDefinition(
            "segment_pitch",
            "分段变调",
            applySegmented = { buffer, segments ->
                PcmEffects.applySegmentRanges(buffer, segments) { slice, semitones ->
                    PcmEffects.speedPitch(slice, 1f, semitones)
                }
            },
        ) { buffers, _, _ -> buffers.first() },
        EffectDefinition(
            "segment_gain",
            "分段修改音量",
            applySegmented = { buffer, segments ->
                PcmEffects.applySegmentRanges(buffer, segments) { slice, gainDb ->
                    val gain = Math.pow(10.0, (gainDb / 20.0)).toFloat()
                    if (gain == 1f) {
                        slice
                    } else {
                        val scaled = ShortArray(slice.samples.size)
                        for (index in scaled.indices) {
                            scaled[index] = (slice.samples[index] * gain).toInt()
                                .coerceIn(PcmBuffer.SHORT_MIN, PcmBuffer.SHORT_MAX).toShort()
                        }
                        PcmBuffer(slice.sampleRateHz, slice.channels, scaled)
                    }
                }
            },
        ) { buffers, _, _ -> buffers.first() },
        EffectDefinition(
            "loudness",
            "响度标准化",
            params = listOf(
                EffectParam("targetLufs", "目标响度", -24f, -9f, -14f, step = 0.5f, unit = "LUFS"),
            ),
        ) { buffers, values, _ ->
            PcmEffects.normalizeLoudness(buffers.first(), values.getFloat("targetLufs", -14f))
        },
    )

    private val byId: Map<String, EffectDefinition> = all.associateBy { it.id }

    fun find(id: String): EffectDefinition? = byId[id]

    fun all(): List<EffectDefinition> = all

    private fun Map<String, Float>.getFloat(key: String, fallback: Float): Float = this[key] ?: fallback
}
