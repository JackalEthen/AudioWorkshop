package cn.qishui.tool.media.effect

import android.content.Context
import cn.qishui.tool.media.pcm.PcmChunkReader
import java.io.File

/**
 * 效果执行：解码成整段 PCM → 套用效果 → 写成无损 WAV 中间件。
 * 中间件再交给现有导出链路，所以只会有一次有损编码。
 */
class EffectProcessor(
    private val context: Context,
    private val reader: PcmChunkReader,
) {

    fun process(
        effectId: String,
        sources: List<File>,
        values: Map<String, Float>,
        segments: List<SegmentRange> = emptyList(),
        onProgress: (Float) -> Unit = {},
    ): Result<File> {
        val definition = EffectRegistry.find(effectId)
            ?: return Result.failure(IllegalArgumentException("未知效果: $effectId"))
        if (sources.isEmpty()) return Result.failure(IllegalArgumentException("请先选择歌曲"))
        if (definition.multiTrack && sources.size < 2) {
            return Result.failure(IllegalArgumentException("「${definition.label}」至少需要两首歌"))
        }
        // RNNoise 状态机有上下文，进程内复用一份，省掉每次重新初始化
        val rnnoise = if (definition.needsRnnoise) sharedRnnoise() else null
        if (definition.needsRnnoise && rnnoise?.isReady != true) {
            return Result.failure(IllegalStateException("降噪模型初始化失败"))
        }
        return runCatching {
            val buffers = ArrayList<PcmBuffer>(sources.size)
            sources.forEachIndexed { index, file ->
                require(file.isFile && file.length() > 0L) { "源文件不可用: ${file.name}" }
                onProgress(index.toFloat() / (sources.size + 1))
                buffers += PcmBuffer.read(reader.fileSource(file))
            }
            val source = buffers.first()
            if (source.channels > 2) throw IllegalStateException("暂不支持多声道源（${source.channels} 声道）")
            if (source.sampleRateHz !in SUPPORTED_RATES) {
                throw IllegalStateException("采样率 ${source.sampleRateHz}Hz 不支持，请先用「格式转换」转成 44100/48000")
            }
            buffers.forEachIndexed { index, buffer ->
                if (buffer.channels != source.channels && !definition.leftRightSlots) {
                    throw IllegalStateException("所选歌曲声道数不一致，无法混合")
                }
                if (index > 0 && !definition.multiTrack) {
                    throw IllegalStateException("「${definition.label}」一次只能处理一首歌")
                }
            }

            // 立体声合成：左槽进左声道、右槽进右声道，两首各自独立
            if (definition.leftRightSlots) {
                if (buffers[1].sampleRateHz != source.sampleRateHz) {
                    throw IllegalStateException("左右两首歌采样率不同，请先统一转成 44100 或 48000")
                }
                onProgress(0.9f)
                val composed = PcmEffects.stereoCompose(buffers[0], buffers[1])
                onProgress(0.95f)
                val target = outputFile(definition.id)
                if (target.exists()) target.delete()
                composed.writeWav(target)
                onProgress(1f)
                return@runCatching target
            }

            onProgress(sources.size.toFloat() / (sources.size + 1))
            // 多轨效果（混音、合成）要拿到全部 buffer，所以先各自裁区间再一起交给效果
            val startUs = (values["startSec"] ?: 0f).toLong() * MICROS_PER_SECOND
            val endUs = (values["endSec"] ?: 0f).toLong() * MICROS_PER_SECOND
            val regions = buffers.map { buffer ->
                PcmEffects.applyRange(buffer = buffer, startUs = startUs, endUs = endUs) { it }
            }
            val processed = definition.applySegmented?.invoke(regions.first(), segments)
                ?: definition.apply(regions, values, rnnoise)
            onProgress(0.95f)

            val target = outputFile(definition.id)
            if (target.exists()) target.delete()
            processed.writeWav(target)
            onProgress(1f)
            target
        }
    }

    /**
     * 多输出效果（立体声分离）。返回按顺序排好的 WAV 列表，
     * 调用方负责试听和落盘。区间参数对多输出不生效。
     */
    fun processMulti(
        effectId: String,
        sources: List<File>,
        values: Map<String, Float>,
        onProgress: (Float) -> Unit = {},
    ): Result<List<File>> {
        val definition = EffectRegistry.find(effectId)
            ?: return Result.failure(IllegalArgumentException("未知效果: $effectId"))
        val applyMulti = definition.applyMulti
            ?: return Result.failure(IllegalStateException("「${definition.label}」不是多输出效果"))
        if (sources.isEmpty()) return Result.failure(IllegalArgumentException("请先选择歌曲"))
        val file = sources.first()
        require(file.isFile && file.length() > 0L) { "源文件不可用: ${file.name}" }
        return runCatching {
            val source = PcmBuffer.read(reader.fileSource(file))
            if (source.channels > 2) throw IllegalStateException("暂不支持多声道源（${source.channels} 声道）")
            if (source.sampleRateHz !in SUPPORTED_RATES) {
                throw IllegalStateException("采样率 ${source.sampleRateHz}Hz 不支持，请先用「格式转换」转成 44100/48000")
            }
            onProgress(0.8f)
            val outputs = applyMulti(listOf(source), values, null)
            onProgress(0.95f)
            val files = outputs.mapIndexed { index, buffer ->
                outputFile("${definition.id}-$index").also { target ->
                    if (target.exists()) target.delete()
                    buffer.writeWav(target)
                }
            }
            onProgress(1f)
            files
        }
    }

    private fun sharedRnnoise(): RnnoiseBridge? = runCatching {
        synchronized(this) {
            cachedRnnoise ?: RnnoiseBridge.create().also { cachedRnnoise = it }
        }
    }.getOrNull()

    /** 上一次处理的产物，用完删掉，别把缓存塞满。 */
    fun clearOutput() {
        outputDirectory().listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun outputFile(effectId: String): File =
        File(outputDirectory(), "$effectId-${System.currentTimeMillis()}.wav")

    private fun outputDirectory(): File =
        File(context.cacheDir, "effect-output").apply { if (!exists()) mkdirs() }

    private var cachedRnnoise: RnnoiseBridge? = null

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        val SUPPORTED_RATES = setOf(44_100, 48_000)
    }
}
