package cn.music.audioworkshop.media.effect

import cn.music.audioworkshop.media.pcm.PcmFrameSource
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 整段 PCM 缓冲。效果类运算都需要看到完整波形，所以这里一次性读完，
 * 统一用 ShortArray 存，比 Float 省一半内存。
 */
class PcmBuffer(
    val sampleRateHz: Int,
    val channels: Int,
    val samples: ShortArray,
) {
    val frames: Int
        get() = if (channels > 0) samples.size / channels else 0

    val durationUs: Long
        get() = if (sampleRateHz <= 0) 0L else (frames.toLong() * MICROS_PER_SECOND) / sampleRateHz

    fun slice(startFrame: Int, endFrame: Int): PcmBuffer {
        val from = startFrame.coerceIn(0, frames)
        val to = endFrame.coerceIn(from, frames)
        return PcmBuffer(sampleRateHz, channels, samples.copyOfRange(from * channels, to * channels))
    }

    fun resampled(targetRate: Int): PcmBuffer {
        require(targetRate > 0) { "目标采样率无效: $targetRate" }
        if (targetRate == sampleRateHz) return this
        val ratio = targetRate.toDouble() / sampleRateHz.toDouble()
        val targetFrames = max(1, (frames * ratio).roundToInt())
        val out = ShortArray(targetFrames * channels)
        for (frame in 0 until targetFrames) {
            val sourcePosition = frame / ratio
            val index = sourcePosition.toInt().coerceIn(0, max(0, frames - 1))
            val fraction = (sourcePosition - index).toFloat().coerceIn(0f, 1f)
            val next = (index + 1).coerceAtMost(max(0, frames - 1))
            val base = frame * channels
            for (channel in 0 until channels) {
                val a = samples[index * channels + channel].toFloat()
                val b = samples[next * channels + channel].toFloat()
                out[base + channel] = (a + (b - a) * fraction).toInt().coerceIn(SHORT_MIN, SHORT_MAX).toShort()
            }
        }
        return PcmBuffer(targetRate, channels, out)
    }

    /** 多轨混合：按最长轨补零，逐样本相加后限幅。 */
    fun mixedWith(other: PcmBuffer): PcmBuffer {
        require(other.channels == channels) { "声道数不一致: ${other.channels} != $channels" }
        val rate = max(sampleRateHz, other.sampleRateHz)
        val a = if (sampleRateHz == rate) this else resampled(rate)
        val b = if (other.sampleRateHz == rate) other else other.resampled(rate)
        val targetFrames = max(a.frames, b.frames)
        val out = ShortArray(targetFrames * channels)
        val aSamples = a.samples
        val bSamples = b.samples
        for (index in out.indices) {
            val left = if (index < aSamples.size) aSamples[index].toInt() else 0
            val right = if (index < bSamples.size) bSamples[index].toInt() else 0
            out[index] = (left + right).coerceIn(SHORT_MIN, SHORT_MAX).toShort()
        }
        return PcmBuffer(rate, channels, out)
    }

    fun toMono(): PcmBuffer {
        if (channels <= 1) return this
        val out = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) sum += samples[frame * channels + channel].toInt()
            out[frame] = (sum / channels).coerceIn(SHORT_MIN, SHORT_MAX).toShort()
        }
        return PcmBuffer(sampleRateHz, 1, out)
    }

    fun writeWav(target: File) {
        val dataBytes = samples.size * 2
        RandomAccessFile(target, "rw").use { file ->
            file.setLength(0)
            file.writeBytes("RIFF")
            file.writeIntLe(36 + dataBytes)
            file.writeBytes("WAVEfmt ")
            file.writeIntLe(16)
            file.writeShortLe(1)
            file.writeShortLe(channels)
            file.writeIntLe(sampleRateHz)
            file.writeIntLe(sampleRateHz * channels * 2)
            file.writeShortLe(channels * 2)
            file.writeShortLe(16)
            file.writeBytes("data")
            file.writeIntLe(dataBytes)
            val bytes = ByteArray(dataBytes)
            var offset = 0
            for (sample in samples) {
                val value = sample.toInt()
                bytes[offset++] = (value and 0xFF).toByte()
                bytes[offset++] = ((value shr 8) and 0xFF).toByte()
            }
            file.write(bytes)
        }
    }

    companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val SHORT_MIN = -32768
        const val SHORT_MAX = 32767

        fun read(source: PcmFrameSource): PcmBuffer {
            val collected = ArrayList<ShortArray>(64)
            var total = 0
            var sampleRate = 0
            var channels = 0
            source.read { chunk ->
                if (chunk.channels <= 0 || chunk.sampleRateHz <= 0 || chunk.samples.isEmpty()) return@read
                if (channels == 0) {
                    channels = chunk.channels
                    sampleRate = chunk.sampleRateHz
                } else if (chunk.channels != channels) {
                    return@read
                }
                collected.add(chunk.samples)
                total += chunk.samples.size
            }
            require(channels > 0 && sampleRate > 0 && total > 0) { "音频没有可用的 PCM 数据" }
            val merged = ShortArray(total)
            var offset = 0
            collected.forEach { block ->
                block.copyInto(merged, offset)
                offset += block.size
            }
            return PcmBuffer(sampleRate, channels, merged)
        }

        fun read(file: File, source: PcmFrameSource): PcmBuffer = read(source)

        fun of(samples: ShortArray, sampleRateHz: Int = 44_100, channels: Int = 2): PcmBuffer =
            PcmBuffer(sampleRateHz, channels, samples)

        /** 峰值归一化，混音和音效叠加后防止削波。 */
        fun peakOf(buffer: PcmBuffer): Int {
            var peak = 0
            for (sample in buffer.samples) {
                val value = abs(sample.toInt())
                if (value > peak) peak = value
            }
            return peak
        }

        fun normalize(buffer: PcmBuffer, targetPeak: Int = 30_000): PcmBuffer {
            val peak = peakOf(buffer)
            if (peak <= targetPeak || peak == 0) return buffer
            val gain = targetPeak.toFloat() / peak
            val out = ShortArray(buffer.samples.size)
            for (index in out.indices) {
                out[index] = (buffer.samples[index] * gain).toInt().coerceIn(SHORT_MIN, SHORT_MAX).toShort()
            }
            return PcmBuffer(buffer.sampleRateHz, buffer.channels, out)
        }
    }
}

private fun RandomAccessFile.writeIntLe(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
    write((value shr 16) and 0xFF)
    write((value shr 24) and 0xFF)
}

private fun RandomAccessFile.writeShortLe(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
}
