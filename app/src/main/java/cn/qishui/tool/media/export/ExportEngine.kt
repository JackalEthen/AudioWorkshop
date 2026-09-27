package cn.qishui.tool.media.export

import android.media.MediaMetadataRetriever
import cn.qishui.tool.data.media.AudioFileProbe
import cn.qishui.tool.data.media.QsmusicLyricParser
import cn.qishui.tool.domain.edit.LyricTimelineMapper
import cn.qishui.tool.domain.media.ExportJob
import cn.qishui.tool.domain.media.ExportPaths
import cn.qishui.tool.domain.media.ExportProgress
import cn.qishui.tool.domain.media.ExportResult
import cn.qishui.tool.domain.media.ExportStage
import cn.qishui.tool.domain.model.EditMode
import cn.qishui.tool.domain.model.EditTimeRange
import cn.qishui.tool.domain.model.LyricLine
import cn.qishui.tool.domain.model.LyricWord
import cn.qishui.tool.domain.model.LyricsTrack
import cn.qishui.tool.domain.model.TrackMetadata
import cn.qishui.tool.media.metadata.Id3v2Codec
import cn.qishui.tool.media.pcm.DecodedAudioFormat
import cn.qishui.tool.media.pcm.PcmChunkReader
import cn.qishui.tool.media.pcm.PcmEditPipeline
import java.io.File
import kotlin.math.abs

class ExportEngine(
    private val probe: AudioFileProbe,
    private val reader: PcmChunkReader,
    private val lyricParser: QsmusicLyricParser,
) {

    fun execute(
        job: ExportJob,
        shouldCancel: () -> Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        var stage = ExportStage.PREPARING
        val plan = ExportPlanner.plan(job)
        val partFile = ExportPaths.partOf(job.outputTempPath)
        val tagFile = ExportPaths.tagOf(job.outputTempPath)
        try {
            partFile.delete()
            tagFile.delete()
            job.sources.forEach { source ->
                if (!File(source.localPath).isFile) throw ExportFailureException("源文件不存在: ${source.id}")
            }
            val format = reader.probeFormat(File(job.sources.first().localPath))
            report(job, stage, 0f, onProgress)
            encodeRaw(job, plan, format, partFile, stage, shouldCancel, onProgress)

            stage = ExportStage.TAGGING
            report(job, stage, 0f, onProgress)
            val tagged = writeTags(job, plan, partFile, tagFile)

            stage = ExportStage.VALIDATING
            report(job, stage, 0f, onProgress)
            val durationMs = validate(job, plan, tagged)

            val outputFile = File(job.outputTempPath)
            partFile.delete()
            if (!tagged.renameTo(outputFile)) throw ExportFailureException("导出文件发布失败")
            report(job, stage, 1f, onProgress)
            return ExportResult.Completed(
                jobId = job.jobId,
                outputPath = outputFile.absolutePath,
                durationMs = durationMs,
                sizeBytes = outputFile.length(),
            )
        } catch (cancelled: ExportCancelledException) {
            return ExportResult.Failed(job.jobId, stage, CODE_CANCELLED, "导出已取消")
        } catch (error: Throwable) {
            return ExportResult.Failed(job.jobId, stage, CODE_FAILED, error.message ?: error.javaClass.simpleName)
        } finally {
            partFile.delete()
            tagFile.delete()
        }
    }

    private fun encodeRaw(
        job: ExportJob,
        plan: ExportPlan,
        format: DecodedAudioFormat,
        partFile: File,
        stage: ExportStage,
        shouldCancel: () -> Boolean,
        onProgress: (ExportProgress) -> Unit,
    ) {
        val bridge = LameNativeBridge()
        try {
            bridge.init(format.sampleRateHz, format.channels, VBR_QUALITY)
            partFile.outputStream().buffered().use { output ->
                for ((index, step) in plan.steps.withIndex()) {
                    throwIfCancelled(shouldCancel)
                    val file = File(step.source.localPath)
                    val stepFormat = reader.probeFormat(file)
                    if (stepFormat != format) {
                        throw ExportFailureException("拼接源格式不一致: ${step.source.id} $stepFormat != $format")
                    }
                    val pipeline = PcmEditPipeline(step.segments, job.gainDb, step.fadeInUs, step.fadeOutUs)
                    var reportedFraction = -1f
                    pipeline.process(reader.fileSource(file)) { block ->
                        throwIfCancelled(shouldCancel)
                        val encoded = bridge.encode(block, block.size / format.channels)
                        if (encoded.isNotEmpty()) output.write(encoded)
                        val fraction = ((index + consumedFraction(pipeline.outputTimeUs, step)) / plan.steps.size)
                            .toFloat()
                        if (fraction - reportedFraction >= PROGRESS_STEP || fraction >= 1f) {
                            reportedFraction = fraction
                            report(job, stage, fraction, onProgress)
                        }
                    }
                }
                val tail = bridge.flush()
                if (tail.isNotEmpty()) output.write(tail)
            }
        } finally {
            bridge.close()
        }
    }

    private fun writeTags(job: ExportJob, plan: ExportPlan, partFile: File, tagFile: File): File {
        val lead = job.sources.first()
        val artwork = embeddedArtwork(File(lead.localPath))
        val metadata = TrackMetadata(
            title = lead.title,
            artist = lead.artist,
            album = lead.album,
            year = null,
            artworkBytes = artwork?.first,
            artworkMimeType = artwork?.second,
        )
        Id3v2Codec.write(partFile, tagFile, metadata, buildLyrics(job, plan))
        if (!tagFile.isFile || tagFile.length() <= partFile.length()) throw ExportFailureException("标签写入失败")
        return tagFile
    }

    private fun buildLyrics(job: ExportJob, plan: ExportPlan): LyricsTrack {
        val lines = mutableListOf<LyricLine>()
        plan.steps.forEach { step ->
            val raw = step.source.lyrics
            val sourceDurationUs = step.segments.maxOfOrNull { it.sourceEndUs } ?: return@forEach
            if (raw.isNullOrBlank()) return@forEach
            val mapped = LyricTimelineMapper.map(
                lyrics = lyricParser.parse(raw),
                durationUs = sourceDurationUs,
                selections = step.segments.map { EditTimeRange(it.sourceStartUs, it.sourceEndUs) },
                mode = EditMode.KEEP_SELECTED,
                lyricOffsetMs = job.lyricOffsetMs,
            )
            lines += mapped.lines.map { line -> line.shiftedBy(step.outputStartUs) }
        }
        return LyricsTrack(lines = lines, offsetMs = job.lyricOffsetMs)
    }

    private fun validate(job: ExportJob, plan: ExportPlan, tagged: File): Long {
        val lead = job.sources.first()
        val tags = Id3v2Codec.read(tagged)
        lead.title?.trim()?.takeIf(String::isNotEmpty)?.let { expected ->
            if (tags.title != expected) throw ExportFailureException("标题校验失败: ${tags.title}")
        }
        lead.artist?.trim()?.takeIf(String::isNotEmpty)?.let { expected ->
            if (tags.artist != expected) throw ExportFailureException("艺术家校验失败: ${tags.artist}")
        }
        val probed = probe.probeFile(tagged)
        val durationMs = probed.durationMs ?: 0L
        if (probed.format == null || durationMs <= 0L) throw ExportFailureException("导出的 MP3 无法解析")
        val toleranceMs = maxOf(DURATION_TOLERANCE_MS, plan.durationMs / 10L)
        if (abs(durationMs - plan.durationMs) > toleranceMs) {
            throw ExportFailureException("时长校验失败: 实际 ${durationMs}ms, 预期 ${plan.durationMs}ms")
        }
        return durationMs
    }

    private fun embeddedArtwork(file: File): Pair<ByteArray, String>? {
        val retriever = MediaMetadataRetriever()
        return try {
            java.io.FileInputStream(file).use { stream -> retriever.setDataSource(stream.fd) }
            val bytes = retriever.embeddedPicture?.takeIf(ByteArray::isNotEmpty) ?: return null
            bytes to mimeTypeOf(bytes)
        } catch (error: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun LyricLine.shiftedBy(offsetUs: Long): LyricLine {
        if (offsetUs == 0L) return this
        return LyricLine(
            text = text,
            startUs = startUs + offsetUs,
            endUs = endUs + offsetUs,
            words = words.map { word -> LyricWord(word.text, word.startUs + offsetUs, word.endUs + offsetUs) },
        )
    }

    private fun report(job: ExportJob, stage: ExportStage, fraction: Float, onProgress: (ExportProgress) -> Unit) {
        onProgress(ExportProgress(jobId = job.jobId, stage = stage, fraction = fraction.coerceIn(0f, 1f)))
    }

    private fun throwIfCancelled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) throw ExportCancelledException()
    }

    private fun consumedFraction(outputTimeUs: Long, step: ExportSourceStep): Double =
        (outputTimeUs.toDouble() / step.outputDurationUs.coerceAtLeast(1L)).coerceIn(0.0, 1.0)

    private fun mimeTypeOf(bytes: ByteArray): String {
        val png = bytes.size > 3 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        return if (png) "image/png" else "image/jpeg"
    }

    private class ExportCancelledException : RuntimeException()

    private class ExportFailureException(message: String) : RuntimeException(message)

    private companion object {
        const val VBR_QUALITY = 4
        const val PROGRESS_STEP = 0.01f
        const val DURATION_TOLERANCE_MS = 1_500L
        const val CODE_CANCELLED = "CANCELLED"
        const val CODE_FAILED = "FAILED"
    }
}
