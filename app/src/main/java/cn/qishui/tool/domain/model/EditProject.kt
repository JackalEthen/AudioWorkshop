package cn.qishui.tool.domain.model

data class EditTimeRange(
    val startUs: Long,
    val endUs: Long,
)

data class EditTimeSegment(
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val outputStartUs: Long,
    val outputEndUs: Long,
)

enum class JoinTransition {
    /** 直接首尾硬接。 */
    NORMAL,

    /** 前一首尾部淡出 + 后一首头部淡入，线性曲线。 */
    FADE,

    /** 同 FADE，但用等功率曲线，两首音量起伏更小。 */
    STABLE,

    /** 不裁任何原样本，接缝处插入静音。 */
    PRESERVE,
    ;

    /** 是否走淡出/淡入斜坡对接。 */
    val ramps: Boolean
        get() = this == FADE || this == STABLE

    val curve: FadeCurve
        get() = if (this == STABLE) FadeCurve.EQUAL_POWER else FadeCurve.LINEAR
}

enum class EditMode {
    KEEP_SELECTED,
    REMOVE_SELECTED,
}

enum class EditOperation {
    TRIM,
    SPLIT,
    JOIN,
    FADE_IN,
    FADE_OUT,
    GAIN,
    LYRIC_OFFSET,
}

data class EditProject(
    val id: String,
    val sourceTrackId: String,
    val type: EditOperation,
    val segments: List<EditTimeRange>,
    val gainDb: Float?,
    val fadeInMs: Long?,
    val fadeOutMs: Long?,
    val lyricOffsetMs: Long?,
    /** 逐行打点后的 LRC 原文。null = 没改歌词，导出用源曲自带的。 */
    val lyricsOverride: String? = null,
    val joinedTrackIds: List<String>,
    val joinTransition: JoinTransition = JoinTransition.NORMAL,
    val transitionMs: Long = 0L,
    /** 拼接前把所有源重采样到同一采样率。 */
    val normalizeSources: Boolean = false,
    /** 拼接完成后在末尾追加的空白毫秒数。 */
    val trailingSilenceMs: Long = 0L,
    val fadeCurve: FadeCurve = FadeCurve.LINEAR,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
