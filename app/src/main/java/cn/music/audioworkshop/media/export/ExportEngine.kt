package cn.music.audioworkshop.media.export

import android.media.MediaMetadataRetriever
import cn.music.audioworkshop.data.media.AudioFileProbe
import cn.music.audioworkshop.data.media.LyricsCodec
import cn.music.audioworkshop.domain.lyrics.LyricsParser
import cn.music.audioworkshop.domain.edit.LyricTimelineMapper
import cn.music.audioworkshop.domain.media.ExportFormat
import cn.music.audioworkshop.domain.media.ExportJob
import cn.music.audioworkshop.domain.media.ExportPaths
import cn.music.audioworkshop.domain.media.ExportProgress
import cn.music.audioworkshop.domain.media.ExportResult
import cn.music.audioworkshop.domain.media.ExportStage
import cn.music.audioworkshop.domain.model.DenoiseMode
import cn.music.audioworkshop.domain.model.EditMode
import cn.music.audioworkshop.domain.model.EditTimeRange
import cn.music.audioworkshop.domain.model.LyricLine
import cn.music.audioworkshop.domain.model.LyricWord
import cn.music.audioworkshop.domain.model.LyricsTrack
import cn.music.audioworkshop.domain.model.TrackMetadata
import cn.music.audioworkshop.media.metadata.Id3v2Codec
import cn.music.audioworkshop.media.effect.PcmBuffer
import cn.music.audioworkshop.media.effect.PcmEffects
import cn.music.audioworkshop.media.effect.RnnoiseBridge
import cn.music.audioworkshop.media.pcm.DecodedAudioFormat
import cn.music.audioworkshop.media.pcm.PcmChunkReader
import cn.music.audioworkshop.media.pcm.PcmEditPipeline
import cn.music.audioworkshop.media.pcm.PcmStreamConverter
import cn.music.audioworkshop.media.pcm.PcmStreamWriter
import cn.music.audioworkshop.media.pcm.joinShortArrays
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
                    // 不能先 delete 再 rename：File.renameTo 对不存在的源恒返回 false，
                    // 那样无损格式的导出 100% 失败在最后一步。
                    wavOutput.delete()
                    if (!partFile.renameTo(wavOutput)) throw ExportFailureException("导出文件发布失败")
                    patchFlacHeader(prepared, plan, wavOutput)
                    report(prepared, ExportStage.VALIDATING, 1f, onProgress)
                    android.util.Log.d(
                        "QishuiExport",
                        "raw done format=${prepared.format} planMs=${plan.durationMs} " +
                            "probedMs=$durationMs bytes=${wavOutput.length()}",
                    )
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
            // 删的是 partFile，改名的是 tagged —— 别搞反
            partFile.delete()
            outputFile.delete()
            if (!tagged.renameTo(outputFile)) throw ExportFailureException("导出文件发布失败")
            android.util.Log.d(
                "QishuiExport",
                "tagged done format=${prepared.format} planMs=${plan.durationMs} " +
                    "probedMs=$durationMs bytes=${outputFile.length()}",
            )
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

    /**
     * 按 [job] 施加均衡与降噪。
     *
     * 顺序：先降噪再均衡。反过来的话均衡把噪声一起放大了，
     * 降噪拿到的就是被抬过的噪声，效果打折。
     *
     * 均衡和降噪各自全 0 / 不启用时直接返回原 buffer ——
     * biquad 链和 RNNoise 都要逐样本跑，没开就不该付这个时间。
     */
    private fun applyEffects(buffer: PcmBuffer, job: ExportJob): PcmBuffer {
        // 声道提取排最前：先砍成单声道，后面所有效果都只处理这一路。
        // 输入本身是单声道时 extractChannel 会原样复制一份，两个声道输出一致。
        val extracted = job.extractChannel?.let { PcmEffects.extractChannel(buffer, it) } ?: buffer
        var out = extracted
        // 修复排最前：它修的是被削波污染的样本本身。
        // 放到后面的话，前面任何增益/混响都先把瑕疵放大了一遍，
        // 修的时候等于在处理已经劣化的信号。
        if (job.repairStrength > 0.001f) {
            out = PcmEffects.repair(out, job.repairStrength.coerceIn(0f, 1f))
        }
        if (job.denoiseMode != DenoiseMode.NONE) {
            out = when (job.denoiseMode) {
                DenoiseMode.SPEECH -> {
                    // RNNoise：只压噪声不动人声，所以不碰频率参数
                    val bridge = RnnoiseBridge.create()
                    try {
                        PcmEffects.denoise(out, bridge, job.denoiseStrength)
                    } finally {
                        runCatching { bridge.close() }
                    }
                }
                // GENERAL：按频段压制，强度直接当 bandLimit 的 amount
                DenoiseMode.GENERAL -> PcmEffects.bandLimit(
                    buffer = out,
                    lowHz = job.denoiseLowHz.toFloat(),
                    highHz = job.denoiseHighHz.toFloat(),
                    amount = job.denoiseStrength.coerceIn(0f, 1f),
                )
                DenoiseMode.NONE -> out
            }
        }
        if (job.eqGainsDb.any { kotlin.math.abs(it) > 0.01f }) {
            out = PcmEffects.equalizer(out, job.eqGainsDb.toFloatArray())
        }
        // 混响放最后：干湿混合，前面处理过的信号才是它的输入
        if (job.reverbMix > 0.001f) {
            out = PcmEffects.reverb(
                buffer = out,
                roomSize = job.reverbRoomSize,
                mix = job.reverbMix,
                decay = job.reverbDecay,
                predelayMs = job.reverbPredelayMs,
                damping = job.reverbDamping,
            )
        }
        // 回声和合唱都是「复制出多份再叠加」，所以排在混响之后：
        // 先把空间的底子铺好，回声再在这个空间里反射，听感才对。
        job.echoPreset?.let { preset ->
            out = PcmEffects.echo(
                buffer = out,
                delayMs = preset.delayMs,
                feedback = preset.feedback,
                mix = preset.mix,
                kind = preset.kind,
            )
        }
        job.choirPreset?.let { preset ->
            out = PcmEffects.choir(
                buffer = out,
                spreadMs = preset.spreadMs,
                mix = preset.mix,
                kind = preset.kind,
            )
        }
        // 环绕放最后：前面几个效果已经把信号混好了，环绕决定的是成品落在哪。
        // 响度标准化放绝对最后：前面所有效果都会改变整体响度，
        // 中途标准化会被后续效果抵消。
        if (job.targetLufs != null) {
            out = PcmEffects.normalizeLoudness(out, job.targetLufs)
        }
        if (job.orbitHalfCircleSec != null) {
            out = PcmEffects.stereoOrbit(out, job.orbitHalfCircleSec, job.orbitDegrees)
        }
        return out
    }

    /** 这一步是否要走整段效果处理。挂在 step 上但只看 job 参数，几个功能共用同一个出口。 */
private fun ExportSourceStep.needsAudioEffects(job: ExportJob): Boolean =
    job.extractChannel != null ||
        job.orbitHalfCircleSec != null ||
        job.targetLufs != null ||
        job.repairStrength > 0.001f ||
        job.denoiseMode != DenoiseMode.NONE ||
        job.eqGainsDb.any { kotlin.math.abs(it) > 0.01f } ||
        job.reverbMix > 0.001f ||
        job.echoPreset != null ||
        job.choirPreset != null

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
                val pipeline = PcmEditPipeline(
                    step.segments,
                    job.gainDb,
                    step.fadeInUs,
                    step.fadeOutUs,
                    step.fadeCurve,
                    job.preventClipping,
                )
                val converter = converterFor(job, format, target)
                var reportedFraction = -1f
                var framesFed = 0L
                val source = reader.fileSource(file)
                if (step.needsSpeedPitch) {
                    // 变速变调是整段处理：Signalsmith 需要连续的完整波形，没法流式喂进去。
                    // 顺序很重要 —— 必须是「切段/增益/淡入淡出 之后」再变速，
                    // 反过来的话淡入淡出的斜坡会被拉伸变形，等于设了个假淡入。
                    val collected = mutableListOf<ShortArray>()
                    pipeline.process(source) { block -> collected += block }
                    val decoded = reader.probeFormat(file)
                    var buffer = PcmBuffer(
                        sampleRateHz = decoded.sampleRateHz,
                        channels = decoded.channels,
                        samples = joinShortArrays(collected),
                    )
                    buffer = applyEffects(buffer, job)
                    buffer = PcmEffects.speedPitch(buffer, step.speed, step.semitones)
                    val stretched = buffer.samples
                    val converted = converter?.convert(stretched) ?: stretched
                    if (converted.isNotEmpty()) {
                        encoder.write(converted)
                        framesFed += converted.size / target.channels
                    }
                    converter?.flush()?.let { if (it.isNotEmpty()) encoder.write(it) }
                    // 变速时 pipeline.outputTimeUs 停在原始时间轴上，
                    // 所以进度用已喂帧数折算，否则会一直停在 0 然后直接跳到 1。
                    val totalFrames = maxOf(1L, step.outputDurationUs * target.sampleRateHz / 1_000_000L)
                    val done = (framesFed.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
                    report(job, stage, (index + done) / plan.steps.size, onProgress)
                } else if (step.needsAudioEffects(job)) {
                    // 均衡 / 降噪也是整段处理（biquad 链和 RNNoise 都要连续波形），
                    // 但不改时长，所以导出时长和进度照旧按原始时间轴算。
                    val collected = mutableListOf<ShortArray>()
                    pipeline.process(source) { block -> collected += block }
                    val decoded = reader.probeFormat(file)
                    val buffer = applyEffects(
                        PcmBuffer(
                            sampleRateHz = decoded.sampleRateHz,
                            channels = decoded.channels,
                            samples = joinShortArrays(collected),
                        ),
                        job,
                    )
                    val converter2 = converterFor(job, format, target)
                    // 效果可能重采样（比如降噪内部转 48k 再转回来），所以要重新决定转换器。
                    // 分块喂回去而不是攒成一个大数组：ShortArray 没有 chunked，
                    // 而且一份四分钟的歌本来就是几十 MB。
                    val effectBlock = 8_192 * maxOf(1, buffer.channels)
                    var at = 0
                    while (at < buffer.samples.size) {
                        throwIfCancelled(shouldCancel)
                        val end = minOf(at + effectBlock, buffer.samples.size)
                        val block = buffer.samples.copyOfRange(at, end)
                        val converted = converter2?.convert(block) ?: block
                        if (converted.isNotEmpty()) {
                            encoder.write(converted)
                            framesFed += converted.size / maxOf(1, target.channels)
                        }
                        at = end
                    }
                    converter2?.flush()?.let { if (it.isNotEmpty()) encoder.write(it) }
                    val fraction = ((index + consumedFraction(pipeline.outputTimeUs, step)) / plan.steps.size)
                        .toFloat()
                    if (fraction - reportedFraction >= PROGRESS_STEP || fraction >= 1f) {
                        reportedFraction = fraction
                        report(job, stage, fraction, onProgress)
                    }
                } else {
                    // 不变速就直接流式喂编码器：攒进 List 再拼起来会多分配一份整曲大小的数组，
                    // 一首四分钟的歌就是几十 MB 的纯浪费
                    pipeline.process(source) { block ->
                        throwIfCancelled(shouldCancel)
                        val converted = converter?.convert(block) ?: block
                        if (converted.isNotEmpty()) {
                            encoder.write(converted)
                            framesFed += converted.size / target.channels
                        }
                        val fraction = ((index + consumedFraction(pipeline.outputTimeUs, step)) / plan.steps.size)
                            .toFloat()
                        if (fraction - reportedFraction >= PROGRESS_STEP || fraction >= 1f) {
                            reportedFraction = fraction
                            report(job, stage, fraction, onProgress)
                        }
                    }
                }
                // ponytail: 导出时长对不上时先看这三个数 ——
                // segmentEndUs 是声明要导出多久，framesFed 是实际喂进编码器的帧数，
                // partBytes 是落盘大小。三者不一致就知道是哪一环截断的。
                android.util.Log.d(
                    "QishuiExport",
                    "step=$index segs=${step.segments.size} " +
                        "srcEndUs=${step.segments.lastOrNull()?.sourceEndUs} " +
                        "outEndUs=${step.segments.lastOrNull()?.outputEndUs} " +
                        "fed=${framesFed}f fedUs=${framesFed * 1_000_000L / target.sampleRateHz} " +
                        "conv=${converter != null} fadeIn=${step.fadeInUs} fadeOut=${step.fadeOutUs}",
                )
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


    /**
     * 回填 FLAC 的 STREAMINFO.totalSamples。
     *
     * `c2.android.flac.encoder` 是流式编码器，结束时不会回填总样本数
     * （实测导出文件里 totalSamples = 0）。播放器读不出时长，
     * 表现为「能播但总时长 0、进度条拖不动」。
     *
     * 编码器做不到是因为它只顺序写；这里在文件落盘后再打开补上固定偏移。
     *
     * 采样帧数从流水线实测的时长算，不扫文件数帧 —— 扫描会被音频数据里的
     * 字节误判成同步码。
     */
    private fun patchFlacHeader(job: ExportJob, plan: ExportPlan, file: File) {
        if (job.format != ExportFormat.FLAC) return
        // ---- 诊断：回填前后的真实字节，证明改的是不是最终被发布的那个文件 ----
        android.util.Log.i(
            "QishuiDiag",
            "flac patch file=${file.absolutePath} exists=${file.isFile} " +
                "len=${file.length()} before=${FlacHeader.readTotalSamples(file)}",
        )
        if (!FlacHeader.looksLikeFlac(file)) {
            android.util.Log.w("QishuiExport", "patch skipped: not a flac stream, file=$file")
            return
        }
        val target = targetFormat(job, reader.probeFormat(File(job.sources.first().localPath)))
        val totalFrames = plan.durationMs * target.sampleRateHz / 1000L
        val ok = FlacHeader.writeTotalSamples(file, totalFrames)
        android.util.Log.i(
            "QishuiDiag",
            "flac patch done ok=$ok totalFrames=$totalFrames rate=${target.sampleRateHz} " +
                "after=${FlacHeader.readTotalSamples(file)} len=${file.length()}",
        )
    }

    /**
     * 目标格式：用户指定了采样率/声道就照办，否则跟随源。
     */
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

/**
 * 无损/无标签格式（WAV、FLAC）没有 ID3 可写，只确认产物是完整的。
 *
 * FLAC 不走 MediaExtractor 校验：设备自带的 extractor 认不出 MediaCodec 产出的
 * FLAC 流（真机实测报 `probedFormat=raw`、时长 0），但文件头是合法 FLAC
 * （`fLaC` + STREAMINFO）。让导出成败取决于设备容器解析能力不合理，
 * 所以 FLAC 只校验魔数和体积，时长直接用流水线实测的帧数。
 */
private fun validateWav(job: ExportJob, plan: ExportPlan, file: File): Long {
        if (job.format == ExportFormat.FLAC) {
            val magic = runCatching {
                file.inputStream().use { input ->
                    val buffer = ByteArray(4)
                    if (input.read(buffer) != 4) return@use null
                    buffer.joinToString(" ") { "%02X".format(it) }
                }
            }.getOrNull()
            if (file.length() <= 44L || magic != FLAC_MAGIC_HEX) {
                throw ExportFailureException("导出的 FLAC 文件不完整")
            }
            return plan.durationMs
        }
        val probed = probe.probeFile(file)
        val durationMs = probed.durationMs ?: 0L
        if (probed.format == null || durationMs <= 0L) {
            throw ExportFailureException("导出的 ${job.format.label} 无法解析")
        }
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
        // 歌词链路的每一环都可能把内容吞掉：override 没传进来、解析器不认、
        // 映射后被过滤空。这里留一条日志，导出没歌词时能立刻看出断在哪。
        android.util.Log.i(
            "QishuiLrc",
            "buildLyrics job=${job.editProjectId} sources=${job.sources.size} " +
                "override=${job.sources.map { it.lyricsOverride?.length ?: -1 }} " +
                "raw=${job.sources.map { it.lyrics?.length ?: -1 }} " +
                "parsedLines=${lines.size}",
        )
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

        /** FLAC 魔数 "fLaC" 的字节序列。 */
        const val FLAC_MAGIC_HEX = "66 4C 61 43"
        const val CODE_CANCELLED = "CANCELLED"
        const val CODE_FAILED = "FAILED"
    }
}






