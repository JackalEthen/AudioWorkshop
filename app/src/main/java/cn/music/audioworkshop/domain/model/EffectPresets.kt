package cn.music.audioworkshop.domain.model

/**
 * 回声预设。
 *
 * 三种覆盖三个听感差异最大的维度，不是同一参数的三个档位：
 * - [ROOM] 单次回声，延迟短，只有一层，像在空房间喊一声
 * - [DOUBLE] 两次回声，是回声效果的「本体」，能听出明显的「回、回」
 * - [VALLEY] 山谷那种一高一低的多重回声，是三个里最花的
 *
 * 每个预设自带延迟、反馈和湿声比例，用户不需要调参数。
 */
enum class EchoPreset(
    val label: String,
    val caption: String,
    val delayMs: Float,
    val feedback: Float,
    val mix: Float,
    /** 传给 [cn.music.audioworkshop.media.effect.PcmEffects.echo] 的 kind。 */
    val kind: Int,
) {
ROOM("房间回声", "单次回声，延迟短", 220f, 0.42f, 0.5f, 1),
    DOUBLE("双重回声", "两次回声，能听出明显的「回、回」，回声效果的本体", 360f, 0.62f, 0.58f, 1),
    VALLEY("山谷回声", "一高一低的多重回声", 520f, 0.76f, 0.62f, 0),
    ;

    val isActive: Boolean
        get() = true
}

/**
 * 合唱预设：把一个声部铺成多个人同时在唱。
 *
 * 人数决定声部数量，铺得越宽越像大编制，但也越容易糊。
 */
enum class ChoirPreset(
    val label: String,
    val caption: String,
    /** 每个声部之间的延迟，毫秒。铺得越宽人声定位越散。 */
    val spreadMs: Float,
    val mix: Float,
    /** 传给 [cn.music.audioworkshop.media.effect.PcmEffects.choir] 的 kind。 */
    val kind: Int,
) {
    DUET("两人", "一前一后两个声部，像对唱，最自然", 20f, 0.62f, 0),
    TRIO("三人", "三个声部，中间声部加厚，适合合唱段", 30f, 0.7f, 1),
    CHOIR("合唱团", "六个声部铺开，声音最厚最有气势，也最容易糊", 48f, 0.78f, 2),
    ;

    /** 声部数量。听感上「几个人在唱」。 */
    val voiceCount: Int
        get() = when (kind) {
            0 -> 2
            2 -> 6
            else -> 3
        }
}