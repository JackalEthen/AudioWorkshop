package cn.music.audioworkshop.domain.media

import cn.music.audioworkshop.domain.model.ChoirPreset
import cn.music.audioworkshop.domain.model.DenoiseMode
import cn.music.audioworkshop.domain.model.EchoPreset
import cn.music.audioworkshop.domain.model.FadeCurve
import cn.music.audioworkshop.domain.model.JoinTransition

data class ExportSegment(
    val sourceStartUs: Long,
    val sourceEndUs: Long,
)

data class ExportSource(
    val id: String,
    val localPath: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val lyrics: String?,
    /** 逐行打点后的歌词覆盖层，优先于 lyrics。 */
    val lyricsOverride: String? = null,
    val segments: List<ExportSegment>,
)

data class ExportJob(
    val jobId: String,
    val editProjectId: String,
    val outputTempPath: String,
    val sources: List<ExportSource>,
    val gainDb: Float,
    /** 防炸音：过增益时用软限制而不是硬削波，避免波峰被砍平产生爆音。 */
    val preventClipping: Boolean = false,
    val format: ExportFormat = ExportFormat.MP3,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val fadeCurve: FadeCurve = FadeCurve.LINEAR,
    val lyricOffsetMs: Long,
    val joinTransition: JoinTransition = JoinTransition.NORMAL,
    val transitionMs: Long = 0L,
    val normalizeSources: Boolean = false,
    val trailingSilenceMs: Long = 0L,
    /**
     * 逐项插入的空白，毫秒。与 [sources] 一一对应，缺项按 0 处理。
     *
     * 用户要「在具体时间点插入空白」——就是这个：改第 N 项的值，
     * 等于在第 N 项结束的那个时间点上开了个口子。空列表表示不插。
     */
    val perSourceGapMs: List<Long> = emptyList(),
    /** 变速倍率，1.0 = 原速。
     *
     * 大于 1 变快、小于 1 变慢。**只改时长不改音高**，靠时间轴拉伸实现。
     * 变速后时长 = 原时长 / speed，所以时长校验必须跟着算，否则导出会被判「时长对不上」。
     */
    val speed: Float = 1f,
    /** 变调半音数，0 = 原调。正数升调、负数降调。**只改音高不改时长**。 */
    val semitones: Float = 0f,
    /**
     * 8 段均衡增益，分贝。顺序对应
     * [cn.music.audioworkshop.media.effect.PcmEffects.EQ_BAND_LABELS]。
     *
     * 空列表或全 0 表示不处理 —— 全 0 也直接跳过，省掉整条 biquad 链。
     */
    val eqGainsDb: List<Float> = emptyList(),
    /** 降噪方式。默认 [DenoiseMode.NONE] 表示不降噪。 */
    val denoiseMode: DenoiseMode = DenoiseMode.NONE,
    /** [DenoiseMode.GENERAL] 的起始频率，Hz。低于它的部分被压掉。 */
    val denoiseLowHz: Int = DenoiseMode.DEFAULT_LOW_HZ,
    /** [DenoiseMode.GENERAL] 的结束频率，Hz。高于它的部分被压掉。 */
    val denoiseHighHz: Int = DenoiseMode.DEFAULT_HIGH_HZ,
    /** 降噪强度 0~1。1 = 效果最强。 */
    val denoiseStrength: Float = 1f,
    /** 混响湿声比例 0~1。0 = 完全干声，等于不混响。 */
    val reverbMix: Float = 0f,
    /** 混响空间大小 0~1。决定早期反射的延迟长度。 */
    val reverbRoomSize: Float = 0.5f,
    /** 混响衰减 0~1。决定尾音拖多长。 */
    val reverbDecay: Float = 0.6f,
    /** 混响预延迟，毫秒。声音进房间到听到回声之间的空档。 */
    val reverbPredelayMs: Float = 0f,
    /** 混响高频阻尼 0~1。大 = 高频衰减更快，听感更闷更真实。 */
    val reverbDamping: Float = 0.5f,
    /** 回声预设。null = 不加回声。 */
    val echoPreset: EchoPreset? = null,
    /** 合唱预设。null = 不加合唱。 */
    val choirPreset: ChoirPreset? = null,
    /**
     * 音频修复强度 0~1。0 = 不修。
     *
     * 处理爆破音、咔嗒声、削波峰值和轻微连续噪声。强度越高，
     * 判定为瑕疵的阈值越宽松 —— 修得更多，但也更容易伤到干净的段落。
     */
    val repairStrength: Float = 0f,
    /**
     * 只要这一个声道，0 = 左，1 = 右。null = 保留原始声道数。
     *
     * 立体声分离用：拆成两个单声道文件。输入本来就是单声道时，
     * 两个输出都是同一份原声（符合直觉），不做任何加工。
     */
    val extractChannel: Int? = null,
    /** 立体声环绕：走半圈的时间（秒）。null = 不环绕。 */
    val orbitHalfCircleSec: Float? = null,
    /** 立体声环绕的幅度（半径，角度）。 */
    val orbitDegrees: Float = 0f,
    /** 目标响度（LUFS）。null = 不做响度标准化。 */
    val targetLufs: Float? = null,
    /** 目标码率 kbps，0 = 让编码器自己选（MP3 走 VBR）。无损格式忽略这个值。 */
    val bitrateKbps: Int = 0,
    /** 目标采样率 Hz，0 = 跟随源。 */
    val sampleRateHz: Int = 0,
    /** 0 = 跟随源，1 = 单声道，2 = 立体声。 */
    val channelMode: Int = 0,
)


