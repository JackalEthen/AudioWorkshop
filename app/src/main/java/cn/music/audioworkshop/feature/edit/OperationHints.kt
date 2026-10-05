package cn.music.audioworkshop.feature.edit

import cn.music.audioworkshop.domain.model.EditOperation

/** 时间轴类功能的温馨提示，按我们实际实现写。 */
fun operationHint(operation: EditOperation): String = when (operation) {
    EditOperation.TRIM ->
        "剪切会保留你选中的时间段，没选中的部分直接丢弃。" +
            "「保留选中部分」和「去除选中部分」是两种相反的解释方式，结果互为补集。" +
            "时间可以点波形定位，也可以直接输入毫秒数。"

    EditOperation.JOIN ->
        "拼接会把选中的多首歌按顺序首尾相接。" +
            "所有歌曲的采样率和声道数必须一致，不一致会直接报错而不是悄悄转码。" +
            "顺序按「已选择歌曲」里的排列，可以点选切换。"

    EditOperation.FADE_IN ->
        "淡入是让开头音量从 0 线性升到正常水平，常用在开头或段落衔接处。" +
            "淡入时长超过音频长度会按整首处理。"

    EditOperation.FADE_OUT ->
        "淡出是让结尾音量从正常水平线性降到 0。" +
            "时长是从结尾往前算的，所以它不会影响前面的内容。"

    EditOperation.GAIN ->
        "音量增益直接乘在波形上，0dB 是原始音量。" +
            "正数放大、负数衰减，建议先小幅调整试听再决定，" +
            "超过 0dB 容易削波失真。"

    EditOperation.LYRIC_OFFSET ->
        "歌词校正只改歌词的时间轴，不动音频本身。" +
            "正值表示歌词整体往后延，负值表示提前。" +
            "时间线会按你保留的区间一起映射，所以剪切后歌词依然对得上。"
}
