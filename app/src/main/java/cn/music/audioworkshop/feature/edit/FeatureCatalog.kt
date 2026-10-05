package cn.music.audioworkshop.feature.edit

import androidx.annotation.DrawableRes
import cn.music.audioworkshop.R
import cn.music.audioworkshop.domain.model.EditOperation

/**
 * 功能总览目录。UI 先落地，具体算法按 A → B 档逐个接入。
 * [operation] 为 null 表示还没有对应的编辑工作台，点击后只提示未接入。
 */
enum class FeatureGroup(val label: String) {
    CUT("剪辑功能"),
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
    entry("trim", "剪切", FeatureGroup.CUT, R.drawable.ic_scissors, tool = "trim"),
    entry("join", "合成", FeatureGroup.CUT, R.drawable.ic_combine, tool = "join"),
    entry("convert", "格式转换", FeatureGroup.CUT, R.drawable.ic_repeat, tool = "convert"),
    entry("speed_pitch", "变速变调", FeatureGroup.CUT, R.drawable.ic_gauge, tool = "speed_pitch"),
    entry("fade", "淡入淡出", FeatureGroup.CUT, R.drawable.ic_audio_lines, tool = "fade"),
    entry("gain", "修改音量", FeatureGroup.CUT, R.drawable.ic_volume_2, tool = "volume"),
    

    // 常规功能


    entry("lrc", "Lrc歌词编辑", FeatureGroup.CUT, R.drawable.ic_captions, tool = "lrc"),
    entry("metadata", "修改音乐信息", FeatureGroup.CUT, R.drawable.ic_type, tool = "metadata"),
    entry("loudness", "响度标准化", FeatureGroup.EFFECT, R.drawable.ic_gauge, tool = "loudness"),

    // 音效处理
    entry("equalizer", "均衡器", FeatureGroup.EFFECT, R.drawable.ic_equal_approximately, tool = "equalizer"),
    entry("stereo_orbit", "立体声环绕", FeatureGroup.EFFECT, R.drawable.ic_speaker, tool = "stereo_orbit"),
    entry("stereo_split", "立体声分离", FeatureGroup.EFFECT, R.drawable.ic_mic_vocal, tool = "stereo_split"),
    entry("stereo_compose", "立体声合成", FeatureGroup.EFFECT, R.drawable.ic_copy, tool = "stereo_compose"),
    entry("denoise", "降噪", FeatureGroup.EFFECT, R.drawable.ic_zap, tool = "denoise"),
    entry("repair", "音频修复", FeatureGroup.EFFECT, R.drawable.ic_wrench, tool = "repair"),
    entry("echo", "回声效果", FeatureGroup.EFFECT, R.drawable.ic_radio, tool = "echo"),
    entry("choir", "合唱效果", FeatureGroup.EFFECT, R.drawable.ic_users, tool = "choir"),
    entry("reverb", "混响", FeatureGroup.EFFECT, R.drawable.ic_sparkles, tool = "reverb"),

    // 视频处理
    entry("video_audio", "视频提取音频", FeatureGroup.VIDEO, R.drawable.ic_film, tool = "video_audio"),





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





