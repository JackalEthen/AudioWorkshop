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
    NORMAL,
    FADE,
    STABLE,
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
    val joinedTrackIds: List<String>,
    val joinTransition: JoinTransition = JoinTransition.NORMAL,
    val transitionMs: Long = 0L,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
