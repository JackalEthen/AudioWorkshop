package cn.qishui.tool.media.pcm

import android.content.Context
import cn.qishui.tool.domain.model.EditTimeSegment
import cn.qishui.tool.domain.model.FadeCurve
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把编辑后的结果渲染成一个临时 WAV，供页面直接试听。
 *
 * 走 UI 进程而不是 EncoderService：预览不需要 MP3 编码，也不想和真导出抢并发。
 */
class EditPreviewRenderer(
    private val context: Context,
    private val pcmChunkReader: PcmChunkReader,
) {

    suspend fun render(
        sourcePath: String,
        segments: List<EditTimeSegment>,
        gainDb: Float,
        fadeInUs: Long,
        fadeOutUs: Long,
        fadeCurve: FadeCurve,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val source = File(sourcePath)
            require(source.isFile) { "源文件不存在: $sourcePath" }
            require(segments.isNotEmpty()) { "当前参数没有产生任何区间" }

            val directory = File(context.cacheDir, "edit-preview").apply { mkdirs() }
            directory.listFiles()?.forEach { it.delete() }
            val target = File(directory, "preview.wav")

            val format = pcmChunkReader.probeFormat(source)
            val writer = PcmStreamWriter(target, format.sampleRateHz, format.channels)
            try {
                val pipeline = PcmEditPipeline(segments, gainDb, fadeInUs, fadeOutUs, fadeCurve)
                pipeline.process(pcmChunkReader.fileSource(source)) { block -> writer.write(block) }
            } finally {
                writer.close()
            }
            target
        }
    }

    /**
     * 拼接预览：按 [plan] 把多条源、接缝空白和末尾空白一起写成一个 WAV。
     * 和导出走同一份 [cn.qishui.tool.media.export.ExportPlanner]，所以听到的和导出的对得上。
     */
    suspend fun renderPlan(
        plan: cn.qishui.tool.media.export.ExportPlan,
        gainDb: Float,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(plan.steps.isNotEmpty()) { "当前参数没有产生任何区间" }
            val directory = File(context.cacheDir, "edit-preview").apply { mkdirs() }
            directory.listFiles()?.forEach { it.delete() }
            val target = File(directory, "preview.wav")

            val first = File(plan.steps.first().source.localPath)
            require(first.isFile) { "源文件不存在: ${first.name}" }
            val format = pcmChunkReader.probeFormat(first)
            val writer = PcmStreamWriter(target, format.sampleRateHz, format.channels)
            try {
                for (step in plan.steps) {
                    val file = File(step.source.localPath)
                    require(file.isFile) { "源文件不存在: ${file.name}" }
                    val pipeline = PcmEditPipeline(
                        step.segments,
                        gainDb,
                        step.fadeInUs,
                        step.fadeOutUs,
                        step.fadeCurve,
                    )
                    pipeline.process(pcmChunkReader.fileSource(file)) { block -> writer.write(block) }
                    writer.writeSilenceUs(step.gapAfterUs, format.sampleRateHz, format.channels)
                }
                writer.writeSilenceUs(plan.trailingSilenceUs, format.sampleRateHz, format.channels)
            } finally {
                writer.close()
            }
            target
        }
    }
}
