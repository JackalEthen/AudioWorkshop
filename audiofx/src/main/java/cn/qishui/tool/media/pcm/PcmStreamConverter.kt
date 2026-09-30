package cn.qishui.tool.media.pcm

import kotlin.math.floor

/**
 * 流式重采样 + 声道转换。
 *
 * 导出链路是纯追加的（不能 seek、不能回看整段），所以重采样不能走
 * [cn.qishui.tool.media.effect.PcmBuffer.resampled] 那种整段算法。
 * 这里用线性插值：左右邻居都拿到才吐一个目标帧，否则先攒着。
 *
 * 只在目标格式和源不一致时才会用到，两边一致时调用方直接跳过。
 */
class PcmStreamConverter(
    sourceRate: Int,
    private val sourceChannels: Int,
    targetRate: Int,
    private val targetChannels: Int,
) {
    private val ratio: Double = targetRate.toDouble() / sourceRate.toDouble()

    init {
        require(sourceRate > 0 && targetRate > 0) { "采样率无效" }
        require(sourceChannels in 1..2 && targetChannels in 1..2) { "只支持单声道和立体声" }
    }

    /** 已缓存的源帧，front 对应绝对下标 [baseIndex]。 */
    private val queue = ArrayDeque<IntArray>()
    private var baseIndex = 0L

    /** 下一个目标帧在源时间轴上的位置，单位是源帧。 */
    private var nextOutput = 0.0

    private var out = EMPTY
    private var outSize = 0

    /** 喂一块源 PCM，返回转换后的块；没有可输出时返回空数组。 */
    fun convert(chunk: ShortArray, count: Int = chunk.size): ShortArray {
        val frames = count / sourceChannels
        if (frames <= 0) return take()
        for (frame in 0 until frames) {
            queue.addLast(mixFrame(chunk, frame * sourceChannels))
        }
        drain(final = false)
        return take()
    }

    /**
     * 冲刷尾部。最后一个源帧没有右邻居，[drain] 的 final 分支会按左样本原样吐出，
     * 所以这里不需要额外补一帧 —— 补了就会多出一帧。
     */
    fun flush(): ShortArray {
        drain(final = true)
        return take()
    }

    private fun mixFrame(chunk: ShortArray, base: Int): IntArray {
        val out = IntArray(targetChannels)
        for (channel in 0 until targetChannels) {
            out[channel] = when {
                sourceChannels == 2 && targetChannels == 1 -> (chunk[base].toInt() + chunk[base + 1]) / 2
                sourceChannels == 1 && targetChannels == 2 -> chunk[base].toInt()
                else -> chunk[base + channel].toInt()
            }
        }
        return out
    }

    private fun drain(final: Boolean) {
        while (true) {
            val index = floor(nextOutput).toLong()
            val offset = (index - baseIndex).toInt()
            val hasRight = offset + 1 < queue.size
            if (!hasRight && !final) return
            if (offset >= queue.size) return
            if (hasRight) {
                val t = (nextOutput - index).coerceIn(0.0, 1.0)
                val left = queue[offset]
                val right = queue[offset + 1]
                for (channel in 0 until targetChannels) {
                    val a = left[channel]
                    emit(a + (right[channel] - a) * t)
                }
                nextOutput += 1.0 / ratio
            } else {
                // 尾部：右邻居缺失，按左样本原样输出
                for (channel in 0 until targetChannels) emit(queue[offset][channel].toDouble())
                nextOutput = (index + 1).toDouble()
            }
            // 已经用完的源帧一次性弹掉。下采样时一步会跨好几个源帧，
            // 只弹一个的话队列排不出去，等于没重采样
            var drop = (index - baseIndex).toInt()
            while (drop > 0) {
                queue.removeFirst()
                baseIndex++
                drop--
            }
        }
    }

    private fun emit(value: Double) {
        if (outSize == out.size) {
            out = out.copyOf(if (out.isEmpty()) targetChannels * 256 else out.size * 2)
        }
        out[outSize++] = value.toInt().coerceIn(SHORT_MIN, SHORT_MAX).toShort()
    }

    private fun take(): ShortArray {
        if (outSize == 0) return EMPTY
        val result = if (outSize == out.size) out else out.copyOf(outSize)
        outSize = 0
        return result
    }

    private companion object {
        const val SHORT_MIN = -32_768
        const val SHORT_MAX = 32_767
        val EMPTY = ShortArray(0)
    }
}
