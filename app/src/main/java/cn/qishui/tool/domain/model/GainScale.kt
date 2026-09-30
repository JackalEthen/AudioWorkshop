package cn.qishui.tool.domain.model

import kotlin.math.log10
import kotlin.math.pow

/**
 * 修改音量的显示单位。内部只存 dB，这里只管换算。
 */
enum class GainScale {
    DECIBEL,
    PERCENT,
    ;

    fun toDisplay(gainDb: Float): Float = when (this) {
        DECIBEL -> gainDb
        PERCENT -> 10f.pow(gainDb / 20f) * 100f
    }

    /** 输入框/步进给的是当前显示单位的值，换回 dB。 */
    fun toGainDb(display: Float, fallbackDb: Float = 0f): Float = when (this) {
        DECIBEL -> display
        PERCENT -> if (display <= 0f) fallbackDb else 20f * log10(display / 100f)
    }

    fun unitLabel(): String = when (this) {
        DECIBEL -> "dB"
        PERCENT -> "%"
    }
}
