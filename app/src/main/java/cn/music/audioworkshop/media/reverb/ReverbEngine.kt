package cn.music.audioworkshop.media.reverb

import cn.music.audioworkshop.domain.player.SoundEffectPreset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.ln

/**
 * 实时房间混响引擎。
 *
 * 照 lx-music-desktop 的思路建模：**干路 / 环境路并联，各自一个增益，相加后限幅**。
 * 区别在 lx 的环境路是「一个 IR 卷积」，这里是**按声学参数实时合成** ——
 * 素材不落地、没有授权问题，每个空间的参数可控。
 *
 * 环境路拆成两截，这是关键：
 *
 * ```
 * 输入 → 早期反射库 ──────────────────────┐
 *      → 输入扩散 → FDN 晚期混响 ─────────┴→ 湿声 → sendGain ┐
 * 直达声 ──────────────────────────────────→ mainGain ────┴→ 相加 → 限幅
 * ```
 *
 * **为什么必须有早期反射。** 耳朵判断「这是个大厅还是卫生间」靠的是
 * 前 50–100ms 那一串离散反射的图案，不是靠尾音长度。之前用 Freeverb
 * （8 梳状 + 4 全通）只有尾音、没有早期反射，湿声再长也是一片
 * 没有空间感的噪声 —— 这就是「听着平」的原因。
 *
 * **为什么晚期要用 FDN 而不是并联梳状。** 并联梳状是周期性的，能听出
 * 每 1116 个样本重复一次的共鸣感。FDN 用互质的延迟线加 Householder
 * 反馈矩阵，能量在延迟线之间来回绕，几圈之后就是密集的扩散场 ——
 * 这才是真实房间晚期混响该有的样子。
 *
 * **输入扩散不能省。** FDN 前面不加扩散器，直接激励延迟线会激起模态
 * 音（金属味）。三级短全通先把输入打散，再进 FDN。
 */
internal class ReverbEngine(sampleRate: Int) {

    // ---- 早期反射 ----

    /** 单个环形历史，所有抽头从里面读。比 12 条独立延迟线省内存也省缓存压力。 */
    private val erHistory = FloatArray(erHistorySize(sampleRate))
    private var erIndex = 0
    private val erDelay = IntArray(ER_TAPS)
    private val erPanL = FloatArray(ER_TAPS)
    private val erPanR = FloatArray(ER_TAPS)
    private val erGain = FloatArray(ER_TAPS)

    // ---- 输入扩散 ----

    private val diffusers = Array(DIFFUSION_STAGES) {
        AllPassFilter(diffusionLengthSamples(it, sampleRate))
    }

    // ---- FDN 晚期混响 ----

    // 缓冲按最大房间尺度一次分配，之后只改有效长度。
    private val lines = Array(FDN_LINES) {
        DampedDelayLine(fdnMaxLengthSamples(FDN_LINES - 1, sampleRate))
    }
    private val damped = FloatArray(FDN_LINES)

    /** 当前房间尺度下的平均环路时长，反馈增益靠它算。 */
    private var fdnMeanLengthSeconds = 0f

    // ---- 其它 ----

    private val sampleRate = sampleRate

    var outLeft = 0f
        private set
    var outRight = 0f
        private set

    var wet = 0f
    private var dry = 1f
    private var stereoWidth = 0f

    /** 听筒窄带：把 300–3400Hz 以外滤掉。 */
    private val bandPass = BiquadBandPass()
    private var useBandPass = false

    /** 软削波量，给电话一点沙哑感。 */
    private var drive = 0f

    /** 声像旋转：当前弧度。 */
    private var rotation = 0f
    private var rotating = false

    /** 每秒转多少弧度。12 秒一圈，比 lx 默认慢一倍 —— 转太快会晕。 */
    private var rotationSpeed = 0f

    init {
        bandPass.setBandPass(TELEPHONE_CENTER_HZ, TELEPHONE_Q, sampleRate)
        applyPreset(SoundEffectPreset.NONE)
    }

    fun applyPreset(preset: SoundEffectPreset) {
        // ---- 早期反射图案 ----
        // 反射到达时间由「尺度」决定：第一跳是直达声后的第一个墙面反射，
        // 之后每跳的间隔随距离几何增长（球面扩散是 d² 关系）。
        // 增益按房间自身的 -60dB/RT60 衰减律走，所以湿声和尾音是连着的，
        // 不会出现「反射还在响但尾巴已经没了」的割裂。
        val baseDelayMs = maxOf(1f, preset.preDelayMs)
        val sizeScale = 1f + preset.roomSize * 6f
        var timeMs = baseDelayMs
        var spacing = sizeScale * 1.6f
        for (i in 0 until ER_TAPS) {
            timeMs += spacing
            spacing *= ER_SPACING_GROWTH
            val t = timeMs / 1000f
            // 0.001^(t/rt60)：跟 CombFilter 里那条衰减律同一个来源
            val decay = if (preset.rt60Seconds > 0f) {
                exp(LOG_001 * t / preset.rt60Seconds)
            } else {
                0f
            }
            // 越靠后的反射越弱（球面扩散），再乘一条固定的滚降免得糊成一片
            erDelay[i] = (t * sampleRate).toInt().coerceIn(1, erHistory.size - 1)
            erGain[i] = decay * exp(ln(ER_TAP_ROLLOFF) * i)
            // 左右交替并逐渐散开：立体声宽度从这里来，不是把两声道混一起
            val spread = (i.toFloat() / ER_TAPS) * preset.stereoWidth
            if (i % 2 == 0) {
                erPanL[i] = 0.5f + spread
                erPanR[i] = 0.5f - spread
            } else {
                erPanL[i] = 0.5f - spread
                erPanR[i] = 0.5f + spread
            }
        }

        // ---- FDN 反馈增益 ----
        // 延迟线长度跟着房间尺度走：房间越大，同样 rt60 需要更密的绕圈。
        // 增益必须用**实际长度**算 —— 用标称值算会让衰减和声明的 RT-60 差出几倍。
        var totalLength = 0f
        for (i in lines.indices) {
            val samples = fdnLengthSamples(i, preset.roomSize, sampleRate)
            lines[i].setLength(samples)
            totalLength += samples.toFloat() / sampleRate
        }
        fdnMeanLengthSeconds = totalLength / FDN_LINES

        // 能量每绕一圈衰减 fdnGain。要求 rt60 秒后到 -60dB，
        // 所以 fdnGain = 0.001^(平均环路时长/rt60)。
        fdnGain = if (preset.rt60Seconds > 0f) {
            exp(LOG_001 * fdnMeanLengthSeconds / preset.rt60Seconds)
                .coerceIn(0f, FDN_GAIN_CEILING)
        } else {
            0f
        }

        wet = preset.wet
        dry = 1f
        stereoWidth = preset.stereoWidth
        useBandPass = preset.telephoneBand
        drive = preset.drive
        rotating = preset.rotating
        rotationSpeed = TAU / ROTATION_PERIOD_SECONDS
        lines.forEach { it.setDamping(preset.damping) }
    }

    fun clear() {
        erHistory.fill(0f)
        erIndex = 0
        diffusers.forEach { it.clear() }
        lines.forEach { it.clear() }
        bandPass.clear()
        outLeft = 0f
        outRight = 0f
    }

    fun processStereo(leftIn: Float, rightIn: Float) {
        var l = leftIn
        var r = rightIn

        if (useBandPass) {
            // 同一个滤波器实例处理两声道，声像才不会往一边跑
            l = bandPass.process(l)
            r = bandPass.process(r)
        }
        if (drive > 0f) {
            // 软削波：给听筒一点沙哑的谐波，不是硬削（那只会「咔咔」）
            val boost = 1f + drive * 3f
            l = l * boost / (1f + abs(l) * boost)
            r = r * boost / (1f + abs(r) * boost)
        }

        // 激励取 mid。左右声道各激励一次会互相抵消（相位相反的成分尤其明显），
        // 混响只跟声源位置有关，不需要保留原始的立体声相位关系。
        val excitation = (l + r) * 0.5f

        // ---- 早期反射 ----
        erHistory[erIndex] = excitation
        if (++erIndex >= erHistory.size) erIndex = 0
        var erOut = 0f
        val size = erHistory.size
        for (i in 0 until ER_TAPS) {
            val s = erHistory[(erIndex - erDelay[i] + size) % size]
            erOut += s * erGain[i]
        }

        // ---- 输入扩散 → FDN ----
        var diffused = excitation
        for (i in diffusers.indices) diffused = diffusers[i].process(diffused)

        // 每条线读出来先过阻尼低通，sum 只累加**阻尼后**的值。
        // 之前原始值和阻尼值各加了一遍，sum 翻倍，反馈里就多出一倍能量 ——
        // 表现是混响不但不衰减反而一路涨上去。
        var sum = 0f
        for (i in lines.indices) {
            damped[i] = lines[i].damp(lines[i].read())
            sum += damped[i]
        }

        // Householder 反馈矩阵：(H·x)_i = 2·mean − x_i。
        // 正交、单位行和，天然稳定，不会像并联梳状那样在高增益下自激。
        val twiceMean = 2f * sum / FDN_LINES
        for (i in lines.indices) {
            lines[i].write(diffused + fdnGain * (twiceMean - damped[i]))
        }

        // 取不同线做左右，混响本身就是散开的
        val lateL = (damped[0] + damped[2] * 0.7f) * LATE_GAIN
        val lateR = (damped[1] + damped[3] * 0.7f) * LATE_GAIN

        // ---- lx 的干湿并联 ----
        val wetL = erOut + lateL
        val wetR = erOut + lateR
        var mixL = l * dry + wetL * wet
        var mixR = r * dry + wetR * wet

        // ---- 声像旋转（磁性立体声） ----
        // 在 (L, R) 平面上做二维旋转：把整个声场当成一个向量转，
        // 听起来就像声音在四周绕。这是 lx 里 PannerNode 的廉价等价物 ——
        // 真的 3D 定位要 ITD/ILD 双耳模型，手机上做不了也没必要。
        // 只转一半幅度，否则会晕。
        if (rotating) {
            rotation += rotationSpeed / sampleRate
            if (rotation >= TAU) rotation -= TAU
            val half = 0.5f * rotation
            val cos = cos(half)
            val sin = sin(half)
            val rotL = mixL * cos - mixR * sin
            val rotR = mixL * sin + mixR * cos
            mixL = rotL
            mixR = rotR
        }

        // mid/side 展宽，width=1 原样，width=0 单声道
        val mid = (mixL + mixR) * 0.5f
        val side = (mixL - mixR) * 0.5f * stereoWidth
        outLeft = mid + side
        outRight = mid - side
    }

    private var fdnGain = 0f

    private companion object {
        /** ln(0.001)，即 -60dB。衰减律的唯一来源。 */
        const val LOG_001 = -6.9078f

        /** 早期反射抽头数。12 个够描述出房间的尺度感，再多只是糊。 */
        const val ER_TAPS = 12

        /** 抽头间��逐渐拉大，模拟几何扩散（声音走得越远间隔越长）。 */
        const val ER_SPACING_GROWTH = 1.18f

        /** 每个抽头相对上一个的固定滚降，防止后面几个叠起来变成一坨。 */
        const val ER_TAP_ROLLOFF = 0.82f

        /** FDN 延迟线数量。4 条是 Householder 的甜点，再多只是白烧 CPU。 */
        const val FDN_LINES = 4

        /** 四条线长度的互质比例，防止绕圈周期重合。 */
        val FDN_LENGTH_RATIOS = floatArrayOf(1.00f, 1.53f, 2.17f, 2.89f)

        /**
         * FDN 反馈增益上限。
         *
         * Householder 矩阵虽然是稳定的，但延迟线太短 + 增益接近 1 时
         * 仍会积累出可闻的驻波。0.96 给足尾音又不至于自激。
         */
        const val FDN_GAIN_CEILING = 0.96f

        /** 晚期输出总增益。4 条线求和会放大，留归一余量。 */
        const val LATE_GAIN = 0.5f

        /** 听筒中心频率与 Q。300–3400Hz 窄带的标准取值。 */
        const val TELEPHONE_CENTER_HZ = 1200f
        const val TELEPHONE_Q = 0.7f

        /** 声场转一圈要多久（秒）。12 秒，慢到不晕。 */
        const val ROTATION_PERIOD_SECONDS = 12f

        const val TAU = 6.2831855f

        /** 输入扩散级数。3 级是够用又不闷的下限。 */
        const val DIFFUSION_STAGES = 3


        /**
         * 早期反射历史长度：最后一个抽头最多落在 400ms 之后。
         * 一个环形缓冲区装下全部抽头，比每抽头一条延迟线省得多。
         */
        fun erHistorySize(sampleRate: Int): Int = (sampleRate * 0.4f).toInt().coerceAtLeast(64)

        /**
         * 第 i 条 FDN 延迟线的长度（秒）。
         *
         * 基准长度随房间尺度增长，四条线取**互质比例**（1 / 1.53 / 2.17 / 2.89，
         * 大致对应 1、3/2、7/3、11/4）：互质保证任何两条线的绕圈周期都对不上，
         * 能量不会周期性重合 —— 那正是并联梳状「听得出来」的原因。
         */
        fun fdnLengthSeconds(index: Int, roomSize: Float): Float {
            val base = 0.010f + roomSize * 0.032f
            val ratio = FDN_LENGTH_RATIOS[index % FDN_LENGTH_RATIOS.size]
            return base * ratio
        }

        fun fdnLengthSamples(index: Int, roomSize: Float, sampleRate: Int): Int =
            (fdnLengthSeconds(index, roomSize) * sampleRate).toInt().coerceIn(32, sampleRate)

        /** 缓冲按最大尺度分配。 */
        fun fdnMaxLengthSamples(index: Int, sampleRate: Int): Int =
            (fdnLengthSeconds(index, 1f) * sampleRate).toInt().coerceIn(32, sampleRate)

        /** 输入扩散各级长度，同样取互质。 */
        fun diffusionLengthSamples(stage: Int, sampleRate: Int): Int {
            val primes = intArrayOf(1, 5, 13)
            val samples = (primes[stage] * sampleRate / 1000f * 3.1f).toInt()
            return samples.coerceIn(8, sampleRate)
        }
    }
}

/**
 * 带阻尼低通的延迟线，FDN 的一条腿。
 *
 * 阻尼写在反馈回路里，所以它影响的是**每一次绕圈的衰减量**，
 * 而不是把整个尾音一刀切低通掉 —— 这是「高频先死、低频留尾」的做法。
 *
 * 缓冲按最大长度一次分配好，之后只改读指针偏移。
 * 音频线程里分配内存会打断实时性，切预设时重新分配是危险的。
 */
private class DampedDelayLine(maxSize: Int) {
    private val buffer = FloatArray(maxSize)
    private var index = 0

    /** 当前有效长度（样本数），随房间尺度变。 */
    private var length = maxSize

    private var store = 0f

    /** 一阶低通系数，越大越闷。单位直流增益。 */
    private var coefficient = 0.3f

    fun setLength(samples: Int) {
        length = samples.coerceIn(1, buffer.size)
    }

    fun setDamping(damping: Float) {
        // 阻尼 0 → 系数 1（几乎不滤），阻尼 1 → 系数 0（高频全没）
        coefficient = 1f - damping.coerceIn(0f, 0.98f)
    }

    fun read(): Float = buffer[(index - length + buffer.size) % buffer.size]

    fun damp(input: Float): Float {
        store += (input - store) * coefficient
        return store
    }

    fun write(value: Float) {
        buffer[index] = value
        if (++index >= buffer.size) index = 0
    }

    fun clear() {
        buffer.fill(0f)
        index = 0
        store = 0f
    }
}
