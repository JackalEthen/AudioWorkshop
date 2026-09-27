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
        onProgress: (Float) -> Unit = {},
    ): Result<File> {
        val definition = EffectRegistry.find(effectId)
            ?: return Result.failure(IllegalArgumentException("未知效果: $effectId"))
        if (sources.isEmpty()) return Result.failure(IllegalArgumentException("请先选择歌曲"))
        if (definition.multiTrack && sources.size < 2) {
            return Result.failure(IllegalArgumentException("「${definition.label}」至少需要两首歌"))
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
                if (buffer.channels != source.channels) {
                    throw IllegalStateException("所选歌曲声道数不一致，无法混合")
                }
                if (index > 0 && !definition.multiTrack) {
                    throw IllegalStateException("「${definition.label}」一次只能处理一首歌")
                }
            }

            onProgress(sources.size.toFloat() / (sources.size + 1))
            val processed = definition.apply(buffers, values)
            onProgress(0.95f)

            val target = outputFile(definition.id)
            if (target.exists()) target.delete()
            processed.writeWav(target)
            onProgress(1f)
            target
        }
    }

    /** 上一次处理的产物，用完删掉，别把缓存塞满。 */
    fun clearOutput() {
        outputDirectory().listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun outputFile(effectId: String): File =
        File(outputDirectory(), "$effectId-${System.currentTimeMillis()}.wav")

    private fun outputDirectory(): File =
        File(context.cacheDir, "effect-output").apply { if (!exists()) mkdirs() }

    private companion object {
        val SUPPORTED_RATES = setOf(44_100, 48_000)
    }
}
