package cn.music.audioworkshop.media.pcm

import android.content.Context
import cn.music.audioworkshop.domain.model.EditTimeSegment
import cn.music.audioworkshop.domain.model.ChoirPreset
import cn.music.audioworkshop.domain.model.DenoiseMode
import cn.music.audioworkshop.domain.model.EchoPreset
import cn.music.audioworkshop.domain.model.FadeCurve
import cn.music.audioworkshop.media.effect.PcmBuffer
import cn.music.audioworkshop.media.effect.LoudnessBridge
import cn.music.audioworkshop.media.effect.PcmEffects
import cn.music.audioworkshop.media.effect.RnnoiseBridge
import cn.music.audioworkshop.media.export.needsSpeedPitch
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

    /**
     * 解码后的原始 PCM 缓存，按路径 + 文件大小 + 修改时间认。
     *
     * 预览要反复重渲染（每拖一次滑杆一次），而解码走 MediaCodec，
     * 一首四分钟的歌要几百毫秒到一秒，变速的那部分 WSOLA 反而更快。
     * 缓存住解码结果后，改参数只需要重跑一次拉伸，预览从「一秒多」降到「几十毫秒」。
     *
     * 只留一份并限大小：整首 44.1kHz 立体声大约 42MB，
     * 多留几份容易在低端机上 OOM，而一份就够覆盖「同一首歌反复调参数」这个场景。
     */
    private var decodedCache: Decoded? = null

    private data class Decoded(
        val path: String,
        val lengthBytes: Long,
        val lastModifiedMs: Long,
        val sampleRateHz: Int,
        val channels: Int,
val samples: ShortArray,
    ) {
        val frames: Int
            get() = if (channels > 0) samples.size / channels else 0
    }

    private fun decoded(file: File): Decoded {
        val key = Triple(file.absolutePath, file.length(), file.lastModified())
        decodedCache?.let { if (Triple(it.path, it.lengthBytes, it.lastModifiedMs) == key) return it }
        val format = pcmChunkReader.probeFormat(file)
        val buffer = PcmBuffer.read(pcmChunkReader.fileSource(file))
        // 超过上限就不留：缓存的意义是「省掉下一次解码」，
        // 留不下等于白读一次内存还占着，得不偿失
        val decoded = Decoded(
            path = file.absolutePath,
            lengthBytes = file.length(),
            lastModifiedMs = file.lastModified(),
            sampleRateHz = buffer.sampleRateHz,
            channels = buffer.channels,
            samples = buffer.samples,
        )
        decodedCache = decoded.takeIf { buffer.samples.size <= MAX_CACHE_SAMPLES }
        return decoded
    }

    /** 换一个文件就把缓存丢掉，避免它一直占着几十 MB。 */
    fun invalidateCache() {
        decodedCache = null
    }

    private companion object {
        /** 约 5 分钟 44.1kHz 立体声，再大的歌就不缓存了。 */
        const val MAX_CACHE_SAMPLES = 26_000_000
    }

    /**
     * 测一下素材当前的整体响度，给 UI 显示「现在多少 LUFS」。
     *
     * 走 native 链路，所以只能在真机上跑 —— JVM 单元测试里是 null。
     */
    suspend fun measureLufs(sourcePath: String): Float? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(sourcePath)
            if (!file.isFile) return@runCatching null
            val decoded = PcmBuffer.read(pcmChunkReader.fileSource(file))
            LoudnessBridge().measureLufs(decoded.samples, decoded.frames, decoded.channels, decoded.sampleRateHz)
        }.getOrNull()
    }

    /**
     * 把两个文件合成一个立体声 WAV：左文件进左声道，右文件进右声道。
     *
     * 每个输入都只取第 0 路 —— 输入是立体声就取左声道，是单声道就用原样。
     * 两路时长不同取较长的，短的那路补静音（[PcmEffects.stereoCompose] 的行为）。
     *
     * 导出也复用这个产物：先合成立体声 WAV，再拿它当唯一 source 走导出流程。
     * 引擎的多 source 是**混音**不是左右分配，指望它拼立体声会得到一个单声道。
     */
    suspend fun renderStereoPair(leftPath: String, rightPath: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val leftFile = File(leftPath)
                val rightFile = File(rightPath)
                require(leftFile.isFile) { "左声道文件不存在: $leftPath" }
                require(rightFile.isFile) { "右声道文件不存在: $rightPath" }

                val left = PcmBuffer.read(pcmChunkReader.fileSource(leftFile))
                val right = PcmBuffer.read(pcmChunkReader.fileSource(rightFile))
                val composed = PcmEffects.stereoCompose(
                    PcmEffects.extractChannel(left, 0),
                    PcmEffects.extractChannel(right, 0),
                )

                val target = previewTarget("stereo-pair.wav")
                composed.writeWav(target)
                target
            }
        }

    // 每次给一个新名字：同名覆盖会让还在播放的旧文件被删掉，
    // 播放器的 fd 立刻失效（表现为「调完参数就没声了」）。
    // 旧产物由调用方在 loadFile 之后自己删。
    private fun previewTarget(name: String): File {
        val directory = File(context.cacheDir, "edit-preview").apply { mkdirs() }
        return File(directory, "${System.nanoTime()}-$name")
    }

    suspend fun render(
        sourcePath: String,
        segments: List<EditTimeSegment>,
        gainDb: Float,
        fadeInUs: Long,
        fadeOutUs: Long,
        fadeCurve: FadeCurve,
preventClipping: Boolean = false,
        speed: Float = 1f,
        semitones: Float = 0f,
        /** 8 段均衡增益，分贝。全 0 或空表示不处理。 */
        eqGainsDb: List<Float> = emptyList(),
        denoiseMode: DenoiseMode = DenoiseMode.NONE,
        denoiseLowHz: Int = DenoiseMode.DEFAULT_LOW_HZ,
        denoiseHighHz: Int = DenoiseMode.DEFAULT_HIGH_HZ,
        denoiseStrength: Float = 1f,
        reverbMix: Float = 0f,
        reverbRoomSize: Float = 0.5f,
        reverbDecay: Float = 0.6f,
        reverbPredelayMs: Float = 0f,
        reverbDamping: Float = 0.5f,
        echoPreset: EchoPreset? = null,
        choirPreset: ChoirPreset? = null,
        /** 音频修复强度 0~1。0 = 不修。 */
        repairStrength: Float = 0f,
        /** 只要这一个声道，0 = 左，1 = 右。null = 保留原始声道数。 */
        extractChannel: Int? = null,
        /** 立体声环绕：走半圈的时间（秒）。null = 不环绕。 */
        orbitHalfCircleSec: Float? = null,
        /** 立体声环绕的幅度（半径，角度）。 */
        orbitDegrees: Float = 0f,
        /** 目标响度（LUFS）。null = 不做响度标准化。 */
        targetLufs: Float? = null,
        /** 产物文件名。同一个渲染器连续出多个产物时必须给不同的名字，否则互相覆盖。 */
        outputName: String = "preview.wav",
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val source = File(sourcePath)
            require(source.isFile) { "源文件不存在: $sourcePath" }
            require(segments.isNotEmpty()) { "当前参数没有产生任何区间" }

            val target = previewTarget(outputName)

            val format = pcmChunkReader.probeFormat(source)
            var writer = PcmStreamWriter(target, format.sampleRateHz, format.channels)
            try {
                val pipeline = PcmEditPipeline(
                    segments,
                    gainDb,
                    fadeInUs,
                    fadeOutUs,
                    fadeCurve,
                    preventClipping,
                )
// 先过 pipeline 再变速，和 ExportEngine 保持同一顺序：
                // 反过来的话淡入淡出的斜坡会被拉伸变形，等于设了个假淡入。
                val collected = mutableListOf<ShortArray>()
                // 均衡和降噪都要连续波形，只能整段处理；它们和变速共用这个分支。
                // 条件是「要不要走整段」，具体做哪些效果是另一件事。
                val wholeFile = speed.needsSpeedPitch(semitones) ||
                    eqGainsDb.any { kotlin.math.abs(it) > 0.01f } ||
                    denoiseMode != DenoiseMode.NONE ||
                    reverbMix > 0.001f ||
                    echoPreset != null ||
                    choirPreset != null ||
                    repairStrength > 0.001f ||
                    extractChannel != null
                if (wholeFile) {
                    // 要变速或加效果：直接吃缓存的解码结果，省掉一次 MediaCodec 解码
                    val decoded = decoded(source)
                    pipeline.consume(
                        PcmChunk(
                            presentationTimeUs = 0L,
                            sampleRateHz = decoded.sampleRateHz,
                            channels = decoded.channels,
                            samples = decoded.samples,
                        ),
                        segments,
                        gainDb,
                        fadeInUs,
                        fadeOutUs,
                        fadeCurve,
                        preventClipping,
                    ) { block -> collected += block }
                    var buffer = PcmBuffer(
                        sampleRateHz = decoded.sampleRateHz,
                        channels = decoded.channels,
                        samples = joinShortArrays(collected),
                    )
                    // 顺序与 ExportEngine.applyEffects 逐项对齐：声道提取 → 修复 → 降噪 → 均衡
                    // → 混响 → 回声 → 合唱 → 响度标准化 → 环绕。变速最后跑。
                    // 改这里必须同步改那边，否则预览和导出的成品不是一回事。
                    if (extractChannel != null) {
                        buffer = PcmEffects.extractChannel(buffer, extractChannel)
                    }
                    if (repairStrength > 0.001f) {
                        buffer = PcmEffects.repair(buffer, repairStrength.coerceIn(0f, 1f))
                    }
                    if (denoiseMode != DenoiseMode.NONE) {
                        buffer = when (denoiseMode) {
                            DenoiseMode.SPEECH -> RnnoiseBridge.create().let { bridge ->
                                try {
                                    PcmEffects.denoise(buffer, bridge, denoiseStrength)
                                } finally {
                                    runCatching { bridge.close() }
                                }
                            }
                            DenoiseMode.GENERAL -> PcmEffects.bandLimit(
                                buffer = buffer,
                                lowHz = denoiseLowHz.toFloat(),
                                highHz = denoiseHighHz.toFloat(),
                                amount = denoiseStrength.coerceIn(0f, 1f),
                            )
                            DenoiseMode.NONE -> buffer
                        }
                    }
                    if (eqGainsDb.any { kotlin.math.abs(it) > 0.01f }) {
                        buffer = PcmEffects.equalizer(buffer, eqGainsDb.toFloatArray())
                    }
                    if (reverbMix > 0.001f) {
                        buffer = PcmEffects.reverb(
                            buffer = buffer,
                            roomSize = reverbRoomSize,
                            mix = reverbMix,
                            decay = reverbDecay,
                            predelayMs = reverbPredelayMs,
                            damping = reverbDamping,
                        )
                    }
                    echoPreset?.let { preset ->
                        buffer = PcmEffects.echo(
                            buffer = buffer,
                            delayMs = preset.delayMs,
                            feedback = preset.feedback,
                            mix = preset.mix,
                            kind = preset.kind,
                        )
                    }
                    choirPreset?.let { preset ->
                        buffer = PcmEffects.choir(
                            buffer = buffer,
                            spreadMs = preset.spreadMs,
                            mix = preset.mix,
                            kind = preset.kind,
                        )
                    }
                    // 响度标准化绝对最后：前面所有效果都会改变整体响度。
                    if (targetLufs != null) {
                        buffer = PcmEffects.normalizeLoudness(buffer, targetLufs)
                    }
                    if (orbitHalfCircleSec != null) {
                        buffer = PcmEffects.stereoOrbit(buffer, orbitHalfCircleSec, orbitDegrees)
                    }
                    // 效果可能改采样率（降噪内部走 48k），写出器要跟着新建
                    if (buffer.sampleRateHz != format.sampleRateHz || buffer.channels != format.channels) {
                        writer.close()
                        writer = PcmStreamWriter(target, buffer.sampleRateHz, buffer.channels)
                    }
                    if (speed.needsSpeedPitch(semitones)) {
                        buffer = PcmEffects.speedPitch(buffer, speed, semitones)
                    }
                    writer.write(buffer.samples)
                } else {
                    pipeline.process(pcmChunkReader.fileSource(source)) { block -> collected += block }
                    collected.forEach { writer.write(it) }
                }
            } finally {
                writer.close()
            }
            target
        }
    }

    /**
     * 拼接预览：按 [plan] 把多条源、接缝空白和末尾空白一起写成一个 WAV。
     * 和导出走同一份 [cn.music.audioworkshop.media.export.ExportPlanner]，所以听到的和导出的对得上。
     */
    suspend fun renderPlan(
        plan: cn.music.audioworkshop.media.export.ExportPlan,
        gainDb: Float,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(plan.steps.isNotEmpty()) { "当前参数没有产生任何区间" }
            val target = previewTarget("preview.wav")

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
                    val collected = mutableListOf<ShortArray>()
                    pipeline.process(pcmChunkReader.fileSource(file)) { block -> collected += block }
                    if (step.needsSpeedPitch) {
                        val buffer = PcmBuffer(
                            sampleRateHz = format.sampleRateHz,
                            channels = format.channels,
                            samples = joinShortArrays(collected),
                        )
                        writer.write(PcmEffects.speedPitch(buffer, step.speed, step.semitones).samples)
                    } else {
                        collected.forEach { writer.write(it) }
                    }
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
