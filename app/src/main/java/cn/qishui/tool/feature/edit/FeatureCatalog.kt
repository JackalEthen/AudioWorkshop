package cn.qishui.tool.feature.edit

import androidx.annotation.DrawableRes
import cn.qishui.tool.R
import cn.qishui.tool.domain.model.EditOperation

/**
 * 功能总览目录。UI 先落地，具体算法按 A → B 档逐个接入。
 * [operation] 为 null 表示还没有对应的编辑工作台，点击后只提示未接入。
 */
enum class FeatureGroup(val label: String) {
    CUT("剪辑功能"),
    COMMON("常规功能"),
    EFFECT("音效处理"),
    VIDEO("视频处理"),
}

data class FeatureEntry(
    val id: String,
    val label: String,
    val group: FeatureGroup,
    @DrawableRes val icon: Int,
    val operation: EditOperation? = null,
    val effectId: String? = null,
    val videoTool: String? = null,
    val tool: String? = null,
) {
    val isReady: Boolean
        get() = operation != null || effectId != null || videoTool != null || tool != null
}

/** 一张卡片塞 4 个，剩余的单独成卡，和参考图一致。 */
const val FEATURES_PER_CARD = 4

val FeatureCatalog: List<FeatureEntry> = listOf(
    // 剪辑功能
    entry("trim", "剪切", FeatureGroup.CUT, R.drawable.ic_scissors, EditOperation.TRIM),
    entry("join", "合成", FeatureGroup.CUT, R.drawable.ic_combine, EditOperation.JOIN),
    entry("convert", "格式转换", FeatureGroup.CUT, R.drawable.ic_repeat, effectId = "convert"),
    entry("mix", "混音", FeatureGroup.CUT, R.drawable.ic_blend, effectId = "mix"),
    entry("speed_pitch", "变速变调", FeatureGroup.CUT, R.drawable.ic_gauge, effectId = "speed_pitch"),
    entry("segment_pitch", "分段变调", FeatureGroup.CUT, R.drawable.ic_sliders_vertical, effectId = "segment_pitch"),
    entry("segment_gain", "分段修改音量", FeatureGroup.CUT, R.drawable.ic_layers, effectId = "segment_gain"),
    entry("fade", "淡入淡出", FeatureGroup.CUT, R.drawable.ic_audio_lines, EditOperation.FADE_IN),
    entry("gain", "修改音量", FeatureGroup.CUT, R.drawable.ic_volume_2, EditOperation.GAIN),
    entry("silence_trim", "去除头尾", FeatureGroup.CUT, R.drawable.ic_signal, effectId = "silence_trim"),
    entry("split", "音频分割", FeatureGroup.CUT, R.drawable.ic_split, EditOperation.SPLIT),

    // 常规功能


    entry("lrc", "Lrc歌词编辑", FeatureGroup.COMMON, R.drawable.ic_captions, EditOperation.LYRIC_OFFSET),
    entry("metadata", "修改音乐信息", FeatureGroup.COMMON, R.drawable.ic_type, tool = "metadata"),
    entry("loudness", "响度标准化", FeatureGroup.COMMON, R.drawable.ic_gauge, effectId = "loudness"),
    entry("opus", "Opus解码", FeatureGroup.COMMON, R.drawable.ic_file_audio, effectId = "convert"),

    // 音效处理
    entry("equalizer", "均衡器", FeatureGroup.EFFECT, R.drawable.ic_equal_approximately, effectId = "equalizer"),
    entry("stereo_widen", "立体声环绕", FeatureGroup.EFFECT, R.drawable.ic_speaker, effectId = "stereo_widen"),
    entry("stereo_split", "立体声分离", FeatureGroup.EFFECT, R.drawable.ic_mic_vocal, effectId = "stereo_split"),
    entry("stereo_mix", "立体声合成", FeatureGroup.EFFECT, R.drawable.ic_copy, effectId = "stereo_mix"),
    entry("denoise", "降噪", FeatureGroup.EFFECT, R.drawable.ic_zap, effectId = "denoise"),
    entry("repair", "音频修复", FeatureGroup.EFFECT, R.drawable.ic_wrench, effectId = "repair"),
    entry("echo", "回声效果", FeatureGroup.EFFECT, R.drawable.ic_radio, effectId = "echo"),
    entry("choir", "合唱效果", FeatureGroup.EFFECT, R.drawable.ic_users, effectId = "choir"),
    entry("reverse", "音频倒放", FeatureGroup.EFFECT, R.drawable.ic_flip_horizontal_2, effectId = "reverse"),
    entry("invert", "反转相位", FeatureGroup.EFFECT, R.drawable.ic_flip_horizontal, effectId = "invert"),
    entry("radio_fx", "收音机音效", FeatureGroup.EFFECT, R.drawable.ic_radio_tower, effectId = "radio_fx"),
    entry("reverb", "混响", FeatureGroup.EFFECT, R.drawable.ic_sparkles, effectId = "reverb"),

    // 视频处理
    entry("video_audio", "视频提取音频", FeatureGroup.VIDEO, R.drawable.ic_film, videoTool = "extract_audio"),
    entry("video_trim", "视频裁剪", FeatureGroup.VIDEO, R.drawable.ic_square_play, videoTool = "trim"),
    entry("video_join", "视频拼接", FeatureGroup.VIDEO, R.drawable.ic_list_video, videoTool = "join"),
    entry("video_speed", "视频变速", FeatureGroup.VIDEO, R.drawable.ic_gauge, videoTool = "speed"),





)

private fun entry(
    id: String,
    label: String,
    group: FeatureGroup,
    @DrawableRes icon: Int,
    operation: EditOperation? = null,
    effectId: String? = null,
    videoTool: String? = null,
    tool: String? = null,
) = FeatureEntry(
    id = id,
    label = label,
    group = group,
    icon = icon,
    operation = operation,
    effectId = effectId,
    videoTool = videoTool,
    tool = tool,
)
