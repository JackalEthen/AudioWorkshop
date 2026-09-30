package cn.qishui.tool.media.reverb

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import cn.qishui.tool.domain.player.SoundEffectPreset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 实时空间音效（房间混响 / 电话窄带）。
 *
 * 结构是经典的 Freeverb：8 个梳状滤波 + 4 个全通，梳状滤波器组的
 * 延迟长度本身就是早期反射模式，参数由 [SoundEffectPreset] 给。
 * Freeverb 本身是 MIT，这里是自己按房间声学重新给参数 + 加了电话带通。
 *
 * 契约（Media3 1.5 的 AudioProcessor）：
 * **本处理器必须常驻链路**，音效开关只能在 `queueInput` 内部做。
 *
 * 为什么不能靠 `configure()` 返回 `NOT_SET` 来「关掉」：Media3 的
 * `DefaultAudioSink.setupAudioProcessors()` 只在 sink 配置时跑一次，
 * 那时返回 `NOT_SET` 就等于被链路**永久排除**。而 1.5.1 的
 * `DefaultAudioSink` 没有任何运行时重配置音频处理链的公开 API ——
 * 之后改预设只是改了个字段，链路根本不知道，音效永远不生效。
 *
 * 所以格式兼容就永远接下，preset 为「无」时逐字节原样输出。
 *
 * 安全第一：只要不是 16bit 立体交织 PCM，直接 NOT_SET 整条旁路。
 * 音效出问题宁可没音效，也不能让播放挂掉。
 */
class RoomReverbProcessor : AudioProcessor {

    private var preset: SoundEffectPreset = SoundEffectPreset.NONE
    private var engine: ReverbEngine? = null
    private var bytesPerFrame = 4
    private var output: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var ended = false

    /** configure 时记下的采样率。切预设时用它现场建 engine，不必等 sink 重新配置。 */
    private var sampleRate = 0

    /** configure 已接受这个格式。false 时才允许返回 NOT_SET。 */
    private var formatAccepted = false

    /** 切预设。切到「无」时清掉延迟线，不留尾巴。 */
    fun setPreset(next: SoundEffectPreset) {
        if (next == preset) return
        preset = next
        if (!next.isEnabled) {
            engine?.clear()
            engine = null
            return
        }
        val existing = engine
        if (existing != null) {
            // 换空间时保留原来的尾音自然衰减，比硬切干净
            existing.applyPreset(next)
            return
        }
        // configure 时预设是关的，engine 就没建。现在开了要**现场补一个**，
        // 否则运行中切音效毫无反应 —— 采样率已经在 configure 里记下了。
        if (formatAccepted && sampleRate > 0) {
            engine = ReverbEngine(sampleRate).also { it.applyPreset(next) }
        }
    }

    override fun configure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.channelCount == 2 &&
            inputAudioFormat.sampleRate > 0
        if (!supported) {
            // 真的不兼容才旁路：这是输出设备格式决定的，不会中途变
            reset()
            return AudioProcessor.AudioFormat.NOT_SET
        }

        bytesPerFrame = inputAudioFormat.bytesPerFrame
        sampleRate = inputAudioFormat.sampleRate
        formatAccepted = true
        engine = if (preset.isEnabled) {
            ReverbEngine(sampleRate).also { it.applyPreset(preset) }
        } else {
            null
        }
        output = ByteBuffer.allocateDirect(OUTPUT_CAPACITY_BYTES)
            .order(ByteOrder.nativeOrder())
        ended = false
        // 与 preset 无关：一定要留在链路里，否则运行中切不动
        return inputAudioFormat
    }

    /** 已接下格式就一直活跃 —— 旁路时逐字节复制，链路行为一致。 */
    override fun isActive(): Boolean = formatAccepted

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (!formatAccepted || bytes < bytesPerFrame) {
            // 没在处理就把输入吃掉，别让链路卡住
            inputBuffer.position(inputBuffer.limit())
            return
        }
        if (output.capacity() < bytes) {
            output = ByteBuffer.allocateDirect(bytes + bytes / 2)
                .order(ByteOrder.nativeOrder())
        }
        output.clear()

        val current = engine
        if (current == null) {
            // 旁路：原样搬运。绝不能「吃掉输入却不产出输出」，那会丢音频变成静音。
            output.put(inputBuffer)
            inputBuffer.position(inputBuffer.limit())
            output.flip()
            return
        }

        // 输入契约保证是 direct + native order，可以直接按下标取
        var i = inputBuffer.position()
        val last = inputBuffer.limit() - bytesPerFrame
        while (i <= last) {
            val left = inputBuffer.getShort(i) * PcmScale
            val right = inputBuffer.getShort(i + 2) * PcmScale
            current.processStereo(left, right)
            output.putShort(toPcm(current.outLeft))
            output.putShort(toPcm(current.outRight))
            i += bytesPerFrame
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun queueEndOfStream() {
        ended = true
    }

    override fun getOutput(): ByteBuffer = output

    override fun isEnded(): Boolean = ended

    override fun flush() {
        output.clear()
        ended = false
        // 跳转 / 换流之后旧尾音留着是错的，一起清掉
        engine?.clear()
    }

    override fun reset() {
        output = AudioProcessor.EMPTY_BUFFER
        engine = null
        ended = false
        formatAccepted = false
        sampleRate = 0
    }

    private companion object {
        /** 16bit 有符号归一到 -1..1。 */
        const val PcmScale = 1f / 32768f

        /** 正常播放缓冲 4–16KB，够用且不浪费。 */
        const val OUTPUT_CAPACITY_BYTES = 16 * 1024

        /**
         * 软限幅。超过 0.7 之后用 tanh 收，避免数字削波那种「咔咔」的方波失真。
         */
        fun toPcm(value: Float): Short {
            val limit = 0.7f
            val a = abs(value)
            val shaped = if (a <= limit) {
                value
            } else {
                val over = (a - limit) / (1f - limit)
                val curved = limit + (1f - limit) * (over / (1f + over))
                if (value < 0f) -curved else curved
            }
            val s = (shaped * 32767f).toInt()
            return s.coerceIn(-32768, 32767).toShort()
        }
    }
}

/** 全通滤波器，打散梳状的梳状感。 */
internal class AllPassFilter(size: Int) {
    private val buffer = FloatArray(size)
    private var index = 0

    fun process(input: Float): Float {
        val output = buffer[index]
        buffer[index] = input + output * 0.5f
        if (++index >= buffer.size) index = 0
        return output - input
    }

    fun clear() {
        buffer.fill(0f)
        index = 0
    }
}

/** 纯延迟线，给湿声做预延迟。 */
private class DelayLine(size: Int) {
    private val buffer = FloatArray(size)
    private var index = 0
    private var preDelay = 0

    /** 预延迟样本数，上限由缓冲区大小决定。 */
    fun setPreDelay(samples: Int) {
        preDelay = samples.coerceIn(0, buffer.size - 1)
    }

    fun process(input: Float): Float {
        // 读指针滞后写指针 preDelay 个位置
        val readIndex = (index - preDelay + buffer.size) % buffer.size
        buffer[index] = input
        if (++index >= buffer.size) index = 0
        return buffer[readIndex]
    }

    fun clear() {
        buffer.fill(0f)
        index = 0
    }
}

/** RBJ 带通，用来切出电话听筒的 300–3400Hz 窄带。 */
internal class BiquadBandPass {
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun setBandPass(centerHz: Float, q: Float, sampleRate: Int) {
        val w0 = 2.0 * Math.PI * centerHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (alpha / a0).toFloat()
        b1 = 0f
        b2 = (-alpha / a0).toFloat()
        a1 = (-2.0 * cos(w0) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun process(input: Float): Float {
        val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = output
        return output
    }

    fun clear() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
    }
}
