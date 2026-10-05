package cn.music.audioworkshop.domain.model

/**
 * 淡入淡出曲线。
 *
 * [LINEAR] 幅度线性上升，渐变过程中听感音量偏低。
 * [EQUAL_POWER] 等功率（sin）曲线，噪声和音乐素材的渐变更自然。
 */
enum class FadeCurve {
    LINEAR,
    EQUAL_POWER,
    ;

    /** 归一化进度 0..1 -> 增益系数 0..1。 */
    fun shape(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> t
            EQUAL_POWER -> kotlin.math.sin(t * HALF_PI)
        }
    }

    private companion object {
        const val HALF_PI = (Math.PI / 2.0).toFloat()
    }
}
