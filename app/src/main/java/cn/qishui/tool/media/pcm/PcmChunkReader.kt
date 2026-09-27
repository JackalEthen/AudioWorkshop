package cn.qishui.tool.media.pcm

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class UnsupportedPcmFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DecodedAudioFormat(
    val sampleRateHz: Int,
    val channels: Int,
)

data class PcmChunk(
    val presentationTimeUs: Long,
    val sampleRateHz: Int,
    val channels: Int,
    val samples: ShortArray,
) {
    val samplesPerChannel: Int
        get() = if (channels > 0) samples.size / channels else 0
}

fun interface PcmFrameSource {
    fun read(onChunk: (PcmChunk) -> Unit): DecodedAudioFormat
}

class PcmChunkReader(private val context: Context) {

    fun fileSource(file: File): PcmFrameSource =
        PcmFrameSource { sink -> read(file, sink) }

    fun uriSource(uri: Uri): PcmFrameSource =
        PcmFrameSource { sink -> read(uri, sink) }

    fun probeFormat(file: File): DecodedAudioFormat {
        val extractor = MediaExtractor()
        try {
            FileInputStream(file).use { stream -> extractor.setDataSource(stream.fd) }
            val trackIndex = selectAudioTrack(extractor)
            val trackFormat = extractor.getTrackFormat(trackIndex)
            return PcmFormatRules.requireSupported(
                trackFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0),
                trackFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 0),
                trackFormat.getString(MediaFormat.KEY_MIME),
            )
        } catch (error: IllegalArgumentException) {
            throw UnsupportedPcmFormatException("无法读取音频轨道: ${file.name}", error)
        } finally {
            extractor.release()
        }
    }

    fun read(file: File, onChunk: (PcmChunk) -> Unit): DecodedAudioFormat {
        if (!file.isFile || file.length() == 0L) {
            throw UnsupportedPcmFormatException("音频文件不存在或为空：${file.name}")
        }
        return try {
            // 传 fd 而不是路径：中文文件名在 native 层会 Failed to instantiate extractor
            FileInputStream(file).use { stream ->
                readInternal({ it.setDataSource(stream.fd) }, onChunk)
            }
        } catch (error: UnsupportedPcmFormatException) {
            throw error
        } catch (error: Exception) {
            // ponytail: 把底层 extractor 报错翻译成能定位问题的信息，并留下日志
            android.util.Log.e("QishuiWaveform", "波形解码失败: ${file.absolutePath} (${file.length()} bytes)", error)
            throw UnsupportedPcmFormatException(
                "无法解析音频（${file.name}，${file.length() / 1024} KB）：${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
    }

    fun read(uri: Uri, onChunk: (PcmChunk) -> Unit): DecodedAudioFormat =
        readInternal({ it.setDataSource(context, uri, null) }, onChunk)

    private fun readInternal(configure: (MediaExtractor) -> Unit, onChunk: (PcmChunk) -> Unit): DecodedAudioFormat {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            configure(extractor)
            val trackIndex = selectAudioTrack(extractor)
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.equals("audio/flac", ignoreCase = true) && Build.VERSION.SDK_INT < 27) {
                throw UnsupportedPcmFormatException("API 26 不支持 FLAC 解码")
            }
            val format = PcmFormatRules.requireSupported(
                sampleRateHz = trackFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0),
                channels = trackFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 0),
                mime = mime,
            )
            extractor.selectTrack(trackIndex)
            codec = createDecoder(mime, trackFormat)
            drain(codec, extractor, format, onChunk)
            return format
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return index
        }
        throw UnsupportedPcmFormatException("未找到音频轨道")
    }

    private fun createDecoder(mime: String, trackFormat: MediaFormat): MediaCodec {
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (error: Exception) {
            throw UnsupportedPcmFormatException("无法创建解码器: $mime", error)
        }
        try {
            codec.configure(trackFormat, null, null, 0)
            codec.start()
        } catch (error: Exception) {
            runCatching { codec.release() }
            throw UnsupportedPcmFormatException("无法解码音频: $mime", error)
        }
        return codec
    }

    private fun drain(
        codec: MediaCodec,
        extractor: MediaExtractor,
        format: DecodedAudioFormat,
        onChunk: (PcmChunk) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputFinished = false
        var outputFinished = false
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var planes = 1
        var emptyPolls = 0
        while (!outputFinished) {
            if (!inputFinished) {
                val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    emptyPolls = 0
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    val size = if (inputBuffer == null) -1 else extractor.readSampleData(inputBuffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputFinished = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    encoding = outputFormat.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    planes = outputFormat.intOrDefault(KEY_PLANES, 1)
                    emptyPolls = 0
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputFinished && ++emptyPolls > MAX_EMPTY_POLLS) break
                }
                else -> {
                    if (outputIndex >= 0) {
                        emptyPolls = 0
                        if (info.size > 0) {
                            val samples = codec.getOutputBuffer(outputIndex)
                                ?.let { readPcm(it, info, encoding, planes, format.channels) }
                                ?: ShortArray(0)
                            if (samples.isNotEmpty()) {
                                onChunk(
                                    PcmChunk(
                                        presentationTimeUs = info.presentationTimeUs,
                                        sampleRateHz = format.sampleRateHz,
                                        channels = format.channels,
                                        samples = samples,
                                    )
                                )
                            }
                        }
                        outputFinished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
    }

    private fun readPcm(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        encoding: Int,
        planes: Int,
        channels: Int,
    ): ShortArray {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val total = info.size / 4
            val floats = FloatArray(total)
            if (total > 0) buffer.asFloatBuffer().get(floats, 0, total)
            return interleaveFloat(floats, planes, channels)
        }
        val shorts = ShortArray(info.size / 2)
        if (shorts.isNotEmpty()) buffer.asShortBuffer().get(shorts)
        return shorts
    }

    private fun interleaveFloat(floats: FloatArray, planes: Int, channels: Int): ShortArray {
        if (planes <= 1 || channels <= 0) {
            return ShortArray(floats.size) { floats[it].toPcmShort() }
        }
        val frames = floats.size / (planes * channels)
        val output = ShortArray(frames * channels)
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                output[frame * channels + channel] = floats[channel * frames + frame].toPcmShort()
            }
        }
        return output
    }

    private fun Float.toPcmShort(): Short =
        (this * SHORT_FULL_SCALE).coerceIn(SHORT_MIN, SHORT_MAX).roundToInt().toShort()

    private fun MediaFormat.intOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val MAX_EMPTY_POLLS = 200
        const val KEY_PLANES = "planes"
        const val SHORT_FULL_SCALE = 32767f
        const val SHORT_MAX = 32767f
        const val SHORT_MIN = -32768f
    }
}
