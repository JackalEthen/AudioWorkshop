package cn.qishui.tool.domain.player

/**
 * 播放音效预设：给正在播的音频加一个空间感。
 *
 * 每个预设描述一个物理空间的声学条件，核心参数是 [rt60Seconds] ——
 * 混响衰减到 -60dB 所需的时间（就是房间声学里的 RT-60）。
 *
 * 增益不是手调的，是从这个时间**算**出来的，见
 * [cn.qishui.tool.media.reverb.ReverbEngine] 里 Soundpipe `sp_comb` 的公式。
 *
 * lx-music-desktop 的音效也是这几类空间（电影院/大厅/餐厅/电话），
 * 但它用卷积混响，参数藏在一堆 IR wav 二进制素材里，授权也没写明；
 * 卫生间它根本没有。这里改成按数字实时合成，素材不落地。
 */
enum class SoundEffectPreset(
    val displayName: String,
    val description: String,
    /**
     * 混响衰减 60dB 所需秒数。
     *
     * 0 表示关闭。0.5 上下是小瓷砖房间，1.5 上下是音乐厅。
     */
    val rt60Seconds: Float,
    /** 高频阻尼 0..1，越大越闷（空气吸收 + 厚地毯软座吸收）。 */
    val damping: Float,
    /** 湿声比例 0..1。 */
    val wet: Float,
    /** 预延迟毫秒。直达声到第一个反射的间隔，小空间几乎为 0。 */
    val preDelayMs: Float,
    /** 立体声展宽 0..1，小空间很窄，场馆最宽。 */
    val stereoWidth: Float,
    /** 是否走电话窄带。 */
    val telephoneBand: Boolean = false,
    /** 软削波量 0..1，给电话一点沙哑感。 */
    val drive: Float = 0f,
) {
    NONE("无", "原声", 0f, 0f, 0f, 0f, 0f),

    /** 听筒 300–3400Hz 窄带 + 沙哑感，混响压到几乎听不见。 */
    TELEPHONE("电话", "听筒窄带，沙哑", 0.15f, 0.10f, 0.08f, 0f, 0f, telephoneBand = true, drive = 0.35f),

    /** 瓷砖全反射，RT60 约 0.5s，高频几乎不衰减。lx 没有这个。 */
    BATHROOM("卫生间", "瓷砖小空间，混响短促", 0.50f, 0.15f, 0.30f, 8f, 0.20f),
    /** 满座 + 餐具 + 桌布，吸收很多，RT60 约 0.7s。对应 lx 的 medium-room。 */
    RESTAURANT("餐厅", "嘈杂中等混响", 0.70f, 0.45f, 0.28f, 14f, 0.55f),

    /** 音乐厅硬质反射面，RT60 约 1.8s，所以明亮。对应 lx 的 bright-hall。 */
    HALL("大厅", "宽敞明亮，尾音长", 1.80f, 0.25f, 0.32f, 28f, 0.80f),

    /** 厚地毯 + 软座吸掉高频，RT60 约 1.1s 但很闷。对应 lx 的 cinema-diningroom。 */
    CINEMA("电影院", "厚地毯座椅，很闷", 1.10f, 0.70f, 0.30f, 25f, 0.85f),
    ;

    val isEnabled: Boolean get() = rt60Seconds > 0f

    companion object {
        val selectable: List<SoundEffectPreset> = entries.toList()
    }
}
