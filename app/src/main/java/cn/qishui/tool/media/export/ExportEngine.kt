package cn.qishui.tool.media.export

import android.media.MediaMetadataRetriever
import cn.qishui.tool.data.media.AudioFileProbe
import cn.qishui.tool.data.media.LyricsCodec
import cn.qishui.tool.domain.lyrics.LyricsParser
import cn.qishui.tool.domain.edit.LyricTimelineMapper
import cn.qishui.tool.domain.media.ExportFormat
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
import cn.qishui.tool.media.effect.PcmBuffer
import cn.qishui.tool.media.pcm.DecodedAudioFormat
import cn.qishui.tool.media.pcm.PcmChunkReader
import cn.qishui.tool.media.pcm.PcmEditPipeline
import cn.qishui.tool.media.pcm.PcmStreamConverter
import cn.qishui.tool.media.pcm.PcmStreamWriter
import java.io.File
import kotlin.math.abs

class ExportEngine(
    private val probe: AudioFileProbe,
    private val reader: PcmChunkReader,
    private val lyricParser: LyricsParser,
) {

    fun execute(
        job: ExportJob,
        shouldCancel: () -> Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        var stage = ExportStage.PREPARING
        val plan: ExportPlan
        val partFile = ExportPaths.partOf(job.outputTempPath)
        val tagFile = ExportPaths.tagOf(job.outputTempPath)
        val normalizedFiles = mutableListOf<File>()
        try {
            partFile.delete()
            tagFile.delete()
            job.sources.forEach { source ->
                if (!File(source.localPath).isFile) throw ExportFailureException("源文件不存在: ${source.id}")
            }
            // 格式化音乐：采样率不一致时先把每条源重采样到最高采样率
            val prepared = normalizeSourceRates(job, normalizedFiles)
            plan = ExportPlanner.plan(prepared)
            val format = reader.probeFormat(File(prepared.sources.first().localPath))
            report(job, stage, 0f, onProgress)
            if (!prepared.format.supportsTags) {
                // 无损格式没有 ID3 可写，标签整段跳过
                encodeRaw(prepared, plan, format, partFile, stage, shouldCancel, onProgress)
                val durationMs = validateWav(prepared, plan, partFile)
                val wavOutput = File(prepared.outputTempPath)
                partFile.delete()
                if (!partFile.renameTo(wavOutput)) throw ExportFailureException("导出文件发布失败")
                report(prepared, ExportStage.VALIDATING, 1f, onProgress)
                return ExportResult.Completed(
                    jobId = prepared.jobId,
                    outputPath = wavOutput.absolutePath,
                    durationMs = durationMs,
                    sizeBytes = wavOutput.length(),
                )
            }
            encodeRaw(prepared, plan, format, partFile, stage, shouldCancel, onProgress)

            stage = ExportStage.TAGGING
            report(prepared, stage, 0f, onProgress)
            val tagged = writeTags(prepared, plan, partFile, tagFile)

            stage = ExportStage.VALIDATING
            report(prepared, stage, 0f, onProgress)
            val durationMs = validate(prepared, plan, tagged)

            val outputFile = File(prepared.outputTempPath)
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
            normalizedFiles.forEach { it.delete() }
        }
    }

    /**
     * 格式化音乐：把采样率不一致的源重采样到最高采样率，写成临时 WAV 再进编码链路。
     * 声道数不统一时不动它 —— 编码器是一次性按固定声道数初始化的，
     * 混声道要另做一次下混/上混决策，这里交回原有的报错更安全。
     */
    private fun normalizeSourceRates(job: ExportJob, created: MutableList<File>): ExportJob {
        if (!job.normalizeSources || job.sources.size < 2) return job
        val formats = job.sources.map { reader.probeFormat(File(it.localPath)) }
        if (formats.map { it.sampleRateHz }.distinct().size <= 1) return job
        val channels = formats.map { it.channels }.distinct()
        if (channels.size > 1) {
            throw ExportFailureException("所选歌曲声道数不一致，格式化音乐只统一采样率")
        }
        val targetRate = formats.maxOf { it.sampleRateHz }
        val sources = job.sources.mapIndexed { index, source ->
            if (formats[index].sampleRateHz == targetRate) return@mapIndexed source
            val target = ExportPaths.normalizedOf(job.outputTempPath, index)
            target.delete()
            val decoded = PcmBuffer.read(reader.fileSource(File(source.localPath)))
            decoded.resampled(targetRate).writeWav(target)
            created += target
            source.copy(localPath = target.absolutePath)
        }
        return job.copy(sources = sources)
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
        val target = targetFormat(job, format)
        // 只有 MP3 复用调用方给的流；其他格式编码器自己开 partFile，
        // 两边同时开会把文件截断两次
        if (job.format == ExportFormat.MP3) {
            partFile.outputStream().buffered().use { output ->
                runEncode(job, plan, format, target, output, partFile, stage, shouldCancel, onProgress)
            }
        } else {
            runEncode(job, plan, format, target, null, partFile, stage, shouldCancel, onProgress)
        }
    }

    private fun runEncode(
        job: ExportJob,
        plan: ExportPlan,
        format: DecodedAudioFormat,
        target: DecodedAudioFormat,
        sharedOutput: java.io.OutputStream?,
        partFile: File,
        stage: ExportStage,
        shouldCancel: () -> Boolean,
        onProgress: (ExportProgress) -> Unit,
    ) {
        val encoder = createTargetEncoder(
            format = job.format,
            file = partFile,
            sampleRateHz = target.sampleRateHz,
            channels = target.channels,
            bitrateKbps = job.bitrateKbps,
            output = sharedOutput,
        )
        try {
            for ((index, step) in plan.steps.withIndex()) {
                throwIfCancelled(shouldCancel)
                val file = File(step.source.localPath)
                val stepFormat = reader.probeFormat(file)
                if (stepFormat != format) {
                    throw ExportFailureException("拼接源格式不一致: ${step.source.id} $stepFormat != $format")
                }
                val pipeline = PcmEditPipeline(step.segments, job.gainDb, step.fadeInUs, step.fadeOutUs, step.fadeCurve)
                val converter = converterFor(job, format, target)
                var reportedFraction = -1f
                pipeline.process(reader.fileSource(file)) { block ->
                    throwIfCancelled(shouldCancel)
                    val converted = converter?.convert(block) ?: block
                    if (converted.isNotEmpty()) encoder.write(converted)
                    val fraction = ((index + consumedFraction(pipeline.outputTimeUs, step)) / plan.steps.size)
                        .toFloat()
                    if (fraction - reportedFraction >= PROGRESS_STEP || fraction >= 1f) {
                        reportedFraction = fraction
                        report(job, stage, fraction, onProgress)
                    }
                }
                converter?.flush()?.let { if (it.isNotEmpty()) encoder.write(it) }
                if (step.gapAfterUs > 0L) {
                    throwIfCancelled(shouldCancel)
                    writeSilence(encoder, step.gapAfterUs, target)
                }
            }
            if (plan.trailingSilenceUs > 0L) {
                throwIfCancelled(shouldCancel)
                writeSilence(encoder, plan.trailingSilenceUs, target)
            }
            encoder.finish()
        } finally {
            encoder.close()
        }
    }


    /** 目标格式：用户指定了采样率/声道就照办，否则跟随源。 */
    private fun targetFormat(job: ExportJob, source: DecodedAudioFormat): DecodedAudioFormat =
        DecodedAudioFormat(
            sampleRateHz = job.sampleRateHz.takeIf { it > 0 } ?: source.sampleRateHz,
            channels = when (job.channelMode) {
                1 -> 1
                2 -> 2
                else -> source.channels
            },
        )

    /** 目标和源一致时不做任何转换，省掉一遍插值。 */
    private fun converterFor(
        job: ExportJob,
        source: DecodedAudioFormat,
        target: DecodedAudioFormat,
    ): PcmStreamConverter? = if (source == target) {
        null
    } else {
        PcmStreamConverter(
            sourceRate = source.sampleRateHz,
            sourceChannels = source.channels,
            targetRate = target.sampleRateHz,
            targetChannels = target.channels,
        )
    }

    /** 接缝/末尾的静音：分块喂给编码器，别一次分配几秒的 ShortArray。 */
    private fun writeSilence(
        encoder: AudioTargetEncoder,
        durationUs: Long,
        format: DecodedAudioFormat,
    ) {
        val totalFrames = (durationUs * format.sampleRateHz) / 1_000_000L
        val chunk = ShortArray(SILENCE_BLOCK_FRAMES * format.channels)
        var written = 0L
        while (written < totalFrames) {
            val frames = minOf(SILENCE_BLOCK_FRAMES.toLong(), totalFrames - written).toInt()
            encoder.write(chunk, frames * format.channels)
            written += frames
        }
    }

    /** WAV 没有标签可校验，只确认能解析且时长对得上。 */
    private fun validateWav(job: ExportJob, plan: ExportPlan, file: File): Long {
        val probed = probe.probeFile(file)
        val durationMs = probed.durationMs ?: 0L
        if (probed.format == null || durationMs <= 0L) throw ExportFailureException("导出的 WAV 无法解析")
        val toleranceMs = maxOf(DURATION_TOLERANCE_MS, plan.durationMs / 10L)
        if (abs(durationMs - plan.durationMs) > toleranceMs) {
            throw ExportFailureException("时长校验失败: 实际 ${durationMs}ms, 预期 ${plan.durationMs}ms")
        }
        return durationMs
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
            val raw = step.source.lyricsOverride ?: step.source.lyrics
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

    private companion object {
        const val PROGRESS_STEP = 0.01f
        const val DURATION_TOLERANCE_MS = 1_500L
        const val SILENCE_BLOCK_FRAMES = 2_048
        const val CODE_CANCELLED = "CANCELLED"
        const val CODE_FAILED = "FAILED"
    }
}
